package com.tax.dvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Validator for Schedule F and Form 8582 matching per Jira requirements.
 * Implements IRS rules for passive activity loss limitations.
 */
@Component
public class ScheduleF8582Validator {

    private static final String SCHEDULE_F_FORM = "IQS1040ScheduleF";
    private static final String FORM_8582 = "IRS8582";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /**
     * Validates the matching between Schedule F and Form 8582.
     * 
     * @param payload The full tax return payload
     * @return Validation result with details
     */
    public ValidationResult validate(JsonNode payload) {
        ValidationResult.ValidationResultBuilder result = ValidationResult.builder()
                .validationType("Schedule F to Form 8582 Matching")
                .errors(new ArrayList<>())
                .warnings(new ArrayList<>())
                .infos(new ArrayList<>());
        
        try {
            // Extract forms from payload
            JsonNode body = payload.get("body");
            if (body == null || !body.has("forms")) {
                result.passed(false).errors(List.of("Missing 'body/forms' in payload"));
                return result.build();
            }
            
            List<JsonNode> forms = getFormsAsList(body.get("forms"));
            
            // Extract Schedule F forms
            List<ScheduleFActivity> scheduleFActivities = extractScheduleFActivities(forms);
            
            // Extract Form 8582 data
            Form8582Data form8582Data = extractForm8582Data(forms);
            
            if (scheduleFActivities.isEmpty()) {
                result.passed(true).infos(List.of("No Schedule F activities found"));
                return result.build();
            }
            
            // Perform validations
            validateBusinessNameMatching(scheduleFActivities, form8582Data, result);
            validateLossCalculations(scheduleFActivities, result);
            validateLossAmountMatching(scheduleFActivities, form8582Data, result);
            validateMaterialParticipation(scheduleFActivities, form8582Data, result);
            validateNetFarmProfitLossField(scheduleFActivities, result);
            
            result.passed(result.build().getErrors().isEmpty());
            
        } catch (Exception e) {
            result.passed(false).errors(List.of("Validation error: " + e.getMessage()));
        }
        
        return result.build();
    }
    
    /**
     * Validates that business names match between Schedule F and Form 8582.
     * Requirement 1 from Jira.
     */
    private void validateBusinessNameMatching(List<ScheduleFActivity> scheduleFActivities,
                                                  Form8582Data form8582Data,
                                                  ValidationResult.ValidationResultBuilder result) {
        // Only validate passive activities (those that should appear on Form 8582)
        List<ScheduleFActivity> passiveActivities = scheduleFActivities.stream()
                .filter(act -> !act.isMateriallyParticipated())
                .collect(Collectors.toList());
                
        for (ScheduleFActivity activity : passiveActivities) {
            boolean foundIn8582 = form8582Data.getActivityNames().contains(activity.getBusinessName());
            
            if (!foundIn8582) {
                result.errors().add(String.format(
                    "Business name mismatch: Schedule F activity '%s' not found in Form 8582",
                    activity.getBusinessName()));
            } else {
                result.infos().add(String.format(
                    "Business name match: '%s' found in both Schedule F and Form 8582",
                    activity.getBusinessName()));
            }
        }
        
        // Check for active activities that should NOT be on Form 8582
        List<ScheduleFActivity> activeActivities = scheduleFActivities.stream()
                .filter(ScheduleFActivity::isMateriallyParticipated)
                .collect(Collectors.toList());
                
        for (ScheduleFActivity activity : activeActivities) {
            boolean foundIn8582 = form8582Data.getActivityNames().contains(activity.getBusinessName());
            
            if (foundIn8582) {
                result.errors().add(String.format(
                    "Material participation error: Active activity '%s' should NOT be on Form 8582",
                    activity.getBusinessName()));
            } else {
                result.infos().add(String.format(
                    "Correct: Active activity '%s' not on Form 8582 (not passive)",
                    activity.getBusinessName()));
            }
        }
    }
    
    /**
     * Validates loss calculations: Line 9 - Line 33 = Net Profit/Loss.
     * Requirement 2 from Jira.
     */
    private void validateLossCalculations(List<ScheduleFActivity> scheduleFActivities,
                                                ValidationResult.ValidationResultBuilder result) {
        for (ScheduleFActivity activity : scheduleFActivities) {
            BigDecimal calculatedLoss = activity.getGrossIncome().subtract(activity.getTotalExpenses());
            
            // Validate the calculation is correct
            if (calculatedLoss.compareTo(ZIRO) < 0) {
                result.infos().add(String.format(
                    "%s: Line 9 ($%s) - Line 33 ($%s) = $%s (loss)",
                    activity.getBusinessName(),
                    activity.getGrossIncome(),
                    activity.getTotalExpenses(),
                    calculatedLoss));
            } else if (calculatedLoss.compareTo(ZERO) > 0) {
                result.infos().add(String.format(
                    "%s: Line 9 ($%s) - Line 33 ($%s) = $%s (profit)",
                    activity.getBusinessName(),
                    activity.getGrossIncome(),
                    activity.getTotalExpenses(),
                    calculatedLoss));
            }
        }
    }
    
    /**
     * Validates that loss amounts match between Schedule F and Form 8582.
     * Requirement 2 from Jira.
     */
    private void validateLossAmountMatching(List<ScheduleFActivity> scheduleFActivities,
                                                  Form8582Data form8582Data,
                                                  ValidationResult.ValidationResultBuilder result) {
        for (ScheduleFActivity activity : scheduleFActivities) {
            if (activity.isMateriallyParticipated()) {
                continue; // Skip active activities
            }
            
            BigDecimal scheduleFLoss = activity.getGrossIncome().subtract(activity.getTotalExpenses());
            
            if (scheduleFLoss.compareTo(ZERO) >= 0) {
                continue; // No loss to validate
            }
            
            // Find matching activity in Form 8582
            BigDecimal form8582Loss = form8582Data.getActivityLoss(activity.getBusinessName());
            
            if (form8582Loss == null) {
                result.errors().add(String.format(
                    "Loss mismatch: Schedule F activity '%s' has loss $%s but not found in Form 8582",
                    activity.getBusinessName(), scheduleFLoss));
            } else if (scheduleFLoss.abs().compareTo(form8582Loss.abs()) != 0) {
                result.errors().add(String.format(
                    "Loss amount mismatch: %s - Schedule F: $%s, Form 8582: $%s",
                    activity.getBusinessName(), scheduleFLoss, form8582Loss));
            } else {
                result.infos().add(String.format(
                    "Loss amount match: %s - $%s",
                    activity.getBusinessName(), scheduleFLoss.abs()));
            }
        }
    }
    
    /**
     * Validates material participation rules per IRS instructions.
     */
    private void validateMaterialParticipation(List<ScheduleFActivity> scheduleFActivities,
                                                     Form8582Data form8582Data,
                                                     ValidationResult.ValidationResultBuilder result) {
        for (ScheduleFActivity activity : scheduleFActivities) {
            boolean isOnForm8582 = form8582Data.getActivityNames().contains(activity.getBusinessName());
            
            // IRS Rule: Materially participated activities are NOT passive
            if (activity.isMateriallyParticipated() && isOnForm8582) {
                result.errors().add(String.format(
                    "Material participation error: '%s' marked as materially participated but appears on Form 8582",
                    activity.getBusinessName()));
            }
            
            // IRS Rule: Non-materially participated activities ARE passive
            if (!activity.isMateriallyParticipated() && !isOnForm8582) {
                BigDecimal netIncome = activity.getGrossIncome().subtract(activity.getTotalExpenses());
                if (netIncome.compareTo(ZIRO) < 0) {
                    result.errors().add(String.format(
                        "Passive activity error: '%s' not materially participated with loss $%s but not on Form 8582",
                        activity.getBusinessName(), netIncome));
                }
            }
        }
    }
    
    /**
     * Validates the known issue: NetFarmProfitLossAmt shows $0 when losses exist.
     * This is documented in the Jira description.
     */
    private void validateNetFarmProfitLossField(List<ScheduleFActivity> scheduleFActivities,
                                                       ValidationResult.ValidationResultBuilder result) {
        for (ScheduleFActivity activity : scheduleFActivities) {
            BigDecimal calculatedLoss = activity.getGrossIncome().subtract(activity.getTotalExpenses());
            BigDecimal reportedLoss = activity.getNetFarmProfitLoss();
            
            // Known issue: When loss exists, field shows $0
            if (calculatedLoss.compareTo(ZERO) < 0 && reportedLoss.compareTo(ZERO) == 0) {
                result.warnings().add(String.format(
                    "Known issue: %s - NetFarmProfitLossAmt = $0 (should be $%s). " +
                    "Correct loss captured in Form 8582.",
                    activity.getBusinessName(), calculatedLoss));
            } else if (calculatedLoss.compareTo(reportedLoss) != 0) {
                result.errors().add(String.format(
                    "%s - NetFarmProfitLossAmt mismatch: Calculated $%s, Reported $%s",
                    activity.getBusinessName(), calculatedLoss, reportedLoss));
            }
        }
    }
    
    /**
     * Extracts Schedule F activities from the payload.
     */
    private List<ScheduleFActivity> extractScheduleFActivities(List<JsonNode> forms) {
        List<ScheduleFActivity> activities = new ArrayList<>();
        
        for (JsonNode form : forms) {
            if (!SCHEDULE_F_FORM.equals(getTextValue(form, "formNum"))) {
                continue;
            }
            
            String businessName = extractBusinessName(form);
            BigDecimal grossIncome = extractAmount(form, "FarmIncomeCashMethodGrp/GrossIncomeAmt");
            BigDecimal totalExpenses = extractAmount(form, "FarmExpensesGrp/TotalExpensesAmt");
            BigDecimal netFarmProfitLoss = extractAmount(form, "FarmExpensesGrp/NetFarmProfitLossAmt");
            boolean materiallyParticipated = extractBoolean(form, "MateriallyParticipatedInd");
            
            activities.add(ScheduleFActivity.builder()
                    .businessName(businessName)
                    .grossIncome(grossIncome)
                    .totalExpenses(totalExpenses)
                    .netFarmProfitLoss(netFarmProfitLoss)
                    .materiallyParticipated(materiallyParticipated)
                    .build());
        }
        
        return activities;
    }
    
    /**
     * Extracts Form 8582 data from the payload.
     */
    private Form8582Data extractForm8582Data(List<JsonNode> forms) {
        Map<String, BigDecimal> activityLosses = new HashMap<>();
        Set<String> activityNames = new HashSet<>();
        
        for (JsonNode form : forms) {
            if (!FORM_8582.equals(getTextValue(form, "formNum"))) {
                continue;
            }
            
            // Extract from ParentWrkshtPassiveGrp/WrkshtPassiveGrp
            JsonNode parentWrksht = findLineItem(form, "ParentWrkshtPassiveGrp");
            if (parentWrksht != null && parentWrksht.has("lineItems")) {
                for (JsonNode wrksht : getLineItemsAsList(parentWrksht.get("lineItems"))) {
                    String activityName = extractText(wrksht, "NonParticipateActivityNm");
                    BigDecimal currentYearLoss = extractAmount(wrksht, "CurrentYearNetLossAmt");
                    
                    if (activityName != null && !activityName.isBlank()) {
                        activityNames.add(activityName);
                        if (currentYearLoss != null) {
                            activityLosses.put(activityName, currentYearLoss.negate());
                        }
                    }
                }
            }
            
            // Extract from ParentWrkshtLossGrp/WrkshtLossGrp
            JsonNode parentLossWrksht = findLineItem(form, "ParentWrkshtLossGrp");
            if (parentLossWrksht != null && parentLossWrksht.has("lineItems")) {
                for (JsonNode wrksht : getLineItemsAsList(parentLossWrksht.get("lineItems"))) {
                    String activityName = extractText(wrksht, "UnallowedLossActivityNm");
                    BigDecimal wrkshtLoss = extractAmount(wrksht, "F8582WrkshtLossesAmt");
                    
                    if (activityName != null && !activityName.isBlank()) {
                        activityNames.add(activityName);
                        if (wrkshtLoss != null) {
                            activityLosses.put(activityName, wrkshtLoss.negate());
                        }
                    }
                }
            }
        }
        
        return Form8582Data.builder()
                .activityNames(activityNames)
                .activityLosses(activityLosses)
                .build();
    }
    
    // Helper methods
    
    private List<JsonNode> getFormsAsList(JsonNode formsNode) {
        if (formsNode == null || !formsNode.isArray()) {
            return Collections.emptyList();
        }
        return StreamSupport.stream(formsNode.spliterator(), false)
                .collect(Collectors.toList());
    }
    
    private List<JsonNode> getLineItemsAsList(JsonNode lineItemsNode) {
        if (lineItemsNode == null || !lineItemsNode.isArray()) {
            return Collections.emptyList();
        }
        return StreamSupport.stream(lineItemsNode.spliterator(), false)
                .collect(Collectors.toList());
    }
    
    private String getTextValue(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field != null && !field.isNull() ? field.asText() : null;
    }
    
    private JsonNode findLineItem(JsonNode form, String lineName) {
        if (!form.has("lineItems")) {
            return null;
        }
        
        for (JsonNode lineItem : getLineItemsAsList(form.get("lineItems"))) {
            String lineNameTxt = getTextValue(lineItem, "lineNameTxt");
            if (lineNameTxt != null && lineNameTxt.contains(lineName)) {
                return lineItem;
            }
        }
        return null;
    }
    
    private String extractBusinessName(JsonNode form) {
        // Try PrincipalProductDesc first
        String name = extractText(form, "PrincipalProductDesc");
        if (name != null && !name.isBlank()) {
            return name;
        }
        
        // Fallback to FarmProprietorName/BusinessNameLine1Txt
        JsonNode proprietorName = findLineItem(form, "FarmProprietorName");
        if (proprietorName != null) {
            name = extractText(proprietorName, "BusinessNameLine1Txt");
        }
        
        return name != null ? name : "UNKNOWN";
    }
    
    private String extractText(JsonNode node, String path) {
        JsonNode lineItem = findLineItem(node, path);
        if (lineItem != null) {
            String value = getTextValue(lineItem, "perReturnValueTxt");
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
    
    private BigDecimal extractAmount(JsonNode node, String path) {
        JsonNode lineItem = findLineItem(node, path);
        if (lineItem != null) {
            String value = getTextValue(lineItem, "perReturnValueTxt");
            if (value != null && !value.isBlank()) {
                try {
                    return new BigDecimal(value);
                } catch (NumberFormatException e) {
                    // Ignore and return zero
                }
            }
        }
        return ZERO;
    }
    
    private boolean extractBoolean(JsonNode node, String path) {
        JsonNode lineItem = findLineItem(node, path);
        if (lineItem != null) {
            String value = getTextValue(lineItem, "perReturnValueTxt");
            return "true".equalsIgnoreCase(value);
        }
        return false;
    }
    
    // Data models
    
    @Data
    @Builder
    public static class ScheduleFActivity {
        private String businessName;
        private BigDecimal grossIncome;
        private BigDecimal totalExpenses;
        private BigDecimal netFarmProfitLoss;
        private boolean materiallyParticipated;
    }
    
    @Data
    @Builder
    public static class Form8582Data {
        private Set<String> activityNames;
        private Map<String, BigDecimal> activityLosses;
        
        public BigDecimal getActivityLoss(String activityName) {
            return activityLosses.get(activityName);
        }
    }
    
    @Data
    @Builder
    @AllArgsConstructor
    public static class ValidationResult {
        private String validationType;
        private boolean passed;
        private List<String> errors;
        private List<String> warnings;
        private List<String> infos;
    }
}
