package com.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgusConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * Validator for Schedule F (Form 1040) and Form 8582 integration.
 * 
 * This validator implements the following rules:
 * 1. Match business names between Schedule F and Form 8582
 * 2. Validate net farm profit/loss calculations (Line 9 - Line 33)
 * 3. Verify passive losses are correctly reported on Form 8582
 * 4. Ensure passive activity losses show $0 on Schedule F Line 34
 * 5. Validate active participation flags and loss treatment
 */
@Service
public class ScheduleFForm8582Validator {

    private static final String SCHEDULE_F_PATH = "/IRS1040ScheduleF";
    private static final String FORM_8582_PATH = "/IRS8582";
    private static final String SCHEDULE_1_PATH = "/IRS1040Schedule1";

    /**
     * Validates the entire tax return payload for Schedule F and Form 8582 consistency.
     * 
     * @param payload The complete tax return JSON payload
     * @return ValidationResult containing all validation errors and warnings
     */
    public ValidationResult validate(JsonNode payload) {
        ValidationResult result = new ValidationResult();

        JsonNode body = payload.get("body");
        if (body == null || !body.has("forms")) {
            result.addError("Missing 'body/forms' in payload");
            return result;
        }

        JsonNode forms = body.get("forms");
        
        // Extract all Schedule F forms
        List<JsonNode> scheduleFForms = extractFormsByName(forms, "IRS1040ScheduleF");
        
        // Extract Form 8582
        JsonNode form8582 = extractFormByName(forms, "IRS8582");
        
        // Extract Schedule 1 for net farm profit/loss
        JsonNode schedule1 = extractFormByName(forms, "IRS1040Schedule1");

        if (scheduleFForms.isEmpty()) {
            result.addWarning("No Schedule F forms found in payload");
            return result;
        }

        // Parse all Schedule F activities
        List<ScheduleFActivity> activities = new ArrayList<>();
        for (JsonNode scheduleF : scheduleFForms) {
            ScheduleFActivity activity = parseScheduleFActivity(scheduleF);
            if (activity != null) {
                activities.add(activity);
            }
        }

        // Parse Form 8582 passive losses
        Map<String, BigDecimal> form8582Losses = new HashMap<>();
        if (form8582 != null) {
            form8582Losses = parseForm8582Losses(form8582);
        }

        // Get total net farm profit/loss from Schedule 1
        BigDecimal schedule1NetFarmProfitLoss = getSchedule1NetFarmProfitLoss(schedule1);

        // Validate each activity
        for (ScheduleFActivity activity : activities) {
            validateActivity(activity, form8582Losses, result);
        }

        // Validate total net farm profit/loss
        validateTotalNetFarmProfitLoss(activities, schedule1NetFarmProfitLoss, result);

        // Validate Form 8582 completeness
        validateForm8582Completeness(activities, form8582Losses, result);

        return result;
    }

    /**
     * Parses a Schedule F form into a ScheduleFActivity object.
     */
    private ScheduleFActivity parseScheduleFActivity(JsonNode scheduleF) {
        JsonNode lineItems = scheduleF.get("lineItems");
        if (lineItems == null || !lineItems.isArray()) {
            return null;
        }

        String businessName = null;
        String ein = null;
        String principalProduct = null;
        Boolean materiallyParticipated = null;
        BigDecimal grossIncome = BigDecimal.ZERO;
        BigDecimal totalExpenses = BigDecimal.ZERO;
        BigDecimal netProfitLoss = BigDecimal.ZERO;

        for (JsonNode lineItem : lineItems) {
            String lineName = getTextValue(lineItem, "lineNameTxt");
            
            if (lineName == null) continue;

            switch (lineName) {
                case "/IRS1040ScheduleF/FarmProprietorName":
                    businessName = extractBusinessName(lineItem);
                    break;
                case "/IRS1040ScheduleF/EIN":
                    ein = getTextValue(lineItem, "perReturnValueTxt");
                    break;
                case "/IRS1040ScheduleF/PrincipalProductDesc":
                    principalProduct = getTextValue(lineItem, "perReturnValueTxt");
                    break;
                case "/IRS1040ScheduleF/MateriallyParticipatedInd":
                    materiallyParticipated = getBooleanValue(lineItem, "perReturnValueTxt");
                    break;
                case "/IRS1040ScheduleF/FarmIncomeCashMethodGrp":
                    grossIncome = extractGrossIncome(lineItem);
                    break;
                case "/IRS1040ScheduleF/FarmExpensesGrp":
                    totalExpenses = extractTotalExpenses(lineItem);
                    netProfitLoss = extractNetProfitLoss(lineItem);
                    break;
            }
        }

        return ScheduleFActivity.builder()
                .businessName(businessName)
                .ein(ein)
                .principalProduct(principalProduct)
                .materiallyParticipated(materiallyParticipated != null ? materiallyParticipated : false)
                .grossIncome(grossIncome)
                .totalExpenses(totalExpenses)
                .netProfitLossReported(netProfitLoss)
                .build();
    }

    /**
     * Extracts business name from nested structure.
     */
    private String extractBusinessName(JsonNode lineItem) {
        JsonNode nestedLineItems = lineItem.get("lineItems");
        if (nestedLineItems != null && nestedLineItems.isArray()) {
            for (JsonNode nested : nestedLineItems) {
                String lineName = getTextValue(nested, "lineNameTxt");
                if ("/IRS1040ScheduleF/FarmProprietorName/BusinessNameLine1Txt".equals(lineName)) {
                    return getTextValue(nested, "perReturnValueTxt");
                }
            }
        }
        return null;
    }

    /**
     * Extracts gross income from FarmIncomeCashMethodGrp.
     */
    private BigDecimal extractGrossIncome(JsonNode lineItem) {
        JsonNode nestedLineItems = lineItem.get("lineItems");
        if (nestedLineItems != null && nestedLineItems.isArray()) {
            for (JsonNode nested : nestedLineItems) {
                String lineName = getTextValue(nested, "lineNameTxt");
                if ("/IRS1040ScheduleF/FarmIncomeCashMethodGrp/GrossIncomeAmt".equals(lineName)) {
                    return getBigDecimalValue(nested, "perReturnValueTxt");
                }
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * Extracts total expenses from FarmExpensesGrp.
     */
    private BigDecimal extractTotalExpenses(JsonNode lineItem) {
        JsonNode nestedLineItems = lineItem.get("lineItems");
        if (nestedLineItems != null && nestedLineItems.isArray()) {
            for (JsonNode nested : nestedLineItems) {
                String lineName = getTextValue(nested, "lineNameTxt");
                if ("/IRS1040ScheduleF/FarmExpensesGrp/TotalExpensesAmt".equals(lineName)) {
                    return getBigDecimalValue(nested, "perReturnValueTxt");
                }
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * Extracts net profit/loss from FarmExpensesGrp.
     */
    private BigDecimal extractNetProfitLoss(JsonNode lineItem) {
        JsonNode nestedLineItems = lineItem.get("lineItems");
        if (nestedLineItems != null && nestedLineItems.isArray()) {
            for (JsonNode nested : nestedLineItems) {
                String lineName = getTextValue(nested, "lineNameTxt");
                if ("/IRS1040ScheduleF/FarmExpensesGrp/NetFarmProfitLossAmt".equals(lineName)) {
                    return getBigDecimalValue(nested, "perReturnValueTxt");
                }
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * Parses Form 8582 to extract passive losses by activity name.
     */
    private Map<String, BigDecimal> parseForm8582Losses(JsonNode form8582) {
        Map<String, BigDecimal> losses = new HashMap<>();
        
        JsonNode lineItems = form8582.get("lineItems");
        if (lineItems == null || !lineItems.isArray()) {
            return losses;
        }

        for (JsonNode lineItem : lineItems) {
            String lineName = getTextValue(lineItem, "lineNameTxt");
            
            if ("/IRS8582/ParentWrkshtPassiveGrp".equals(lineName)) {
                extractPassiveLosses(lineItem, losses);
            }
        }

        return losses;
    }

    /**
     * Extracts passive losses from ParentWrkshtPassiveGrp.
     */
    private void extractPassiveLosses(JsonNode parentGrp, Map<String, BigDecimal> losses) {
        JsonNode nestedLineItems = parentGrp.get("lineItems");
        if (nestedLineItems == null || !nestedLineItems.isArray()) {
            return;
        }

        for (JsonNode nested : nestedLineItems) {
            String lineName = getTextValue(nested, "lineNameTxt");
            
            if ("/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp".equals(lineName)) {
                extractSinglePassiveLoss(nested, losses);
            }
        }
    }

    /**
     * Extracts a single passive loss entry.
     */
    private void extractSinglePassiveLoss(JsonNode wrkshtGrp, Map<String, BigDecimal> losses) {
        JsonNode nestedLineItems = wrkshtGrp.get("lineItems");
        if (nestedLineItems == null || !nestedLineItems.isArray()) {
            return;
        }

        String activityName = null;
        BigDecimal lossAmount = null;

        for (JsonNode nested : nestedLineItems) {
            String lineName = getTextValue(nested, "lineNameTxt");
            
            if ("/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/NonParticipateActivityNm".equals(lineName)) {
                activityName = getTextValue(nested, "perReturnValueTxt");
            } else if ("/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/CurrentYearNetLossAmt".equals(lineName)) {
                lossAmount = getBigDecimalValue(nested, "perReturnValueTxt");
            }
        }

        if (activityName != null && lossAmount != null) {
            losses.put(activityName.toUpperCase().trim(), lossAmount);
        }
    }

    /**
     * Gets total net farm profit/loss from Schedule 1.
     */
    private BigDecimal getSchedule1NetFarmProfitLoss(JsonNode schedule1) {
        if (schedule1 == null) {
            return null;
        }

        JsonNode lineItems = schedule1.get("lineItems");
        if (lineItems == null || !lineItems.isArray()) {
            return null;
        }

        for (JsonNode lineItem : lineItems) {
            String lineName = getTextValue(lineItem, "lineNameTxt");
            if ("/IRS1040Schedule1/NetFarmProfitLossAmt".equals(lineName)) {
                return getBigDecimalValue(lineItem, "perReturnValueTxt");
            }
        }

        return null;
    }

    /**
     * Validates a single Schedule F activity.
     */
    private void validateActivity(ScheduleFActivity activity, 
                                      Map<String, BigDecimal> form8582Losses, 
                                      ValidationResult result) {
        // Calculate expected net profit/loss (Line 9 - Line 33)
        BigDecimal calculatedNet = activity.getGrossIncome().subtract(activity.getTotalExpenses());
        activity.setNetProfitLossCalculated(calculatedNet);

        // Rule 1: Validate calculation (Line 9 - Line 33 = Line 34)
        if (calculatedNet.compareTo(activity.getNetProfitLossReported()) != 0) {
            // Check if this is a passive loss scenario
            if (!activity.isMateriallyParticipated() && calculatedNet.compareTo(BigDecimal.ZERO) < 0) {
                // Rule 2: Passive losses should show $0 on Schedule F Line 34
                if (activity.getNetProfitLossReported().compareTo(BigDecimal.ZERO) != 0) {
                    result.addError(String.format(
                        "[%s] Passive loss activity must show $0 on Schedule F Line 34. Reported: %$.2f, Expected: $0.00",
                        activity.getBusinessName(), 
                        activity.getNetProfitLossReported()
                    ));
                }
                
                // Rule 3: Verify loss is reported on Form 8582
                String activityKey = activity.getPrincipalProduct() != null ? 
                    activity.getPrincipalProduct().toUpperCase().trim() : null;
                        
                if (activityKey != null) {
                    BigDecimal form8582Loss = form8582Losses.get(activityKey);
                    
                    if (form8582Loss == null) {
                        result.addError(String.format(
                            "[%s] Passive loss of %$.2f not found on Form 8582",
                            activity.getBusinessName(), 
                            calculatedNet.abs()
                        ));
                    } else if (calculatedNet.abs().compareTo(form8582Loss) != 0) {
                        result.addError(String.format(
                            "[%s] Loss mismatch: Schedule F calculated (%$.2f) != Form 8582 (%$.2f)",
                            activity.getBusinessName(), 
                            calculatedNet.abs(), 
                            form8582Loss
                        ));
                    } else {
                        // Match found
                        activity.setForm8582Loss(form8582Loss);
                    }
                }
            } else {
                // Active participation or profit - should match
                result.addError(String.format(
                    "[%s] Net profit/loss mismatch: Calculated (%$.2f) != Reported (%$.2f)",
                    activity.getBusinessName(), 
                    calculatedNet, 
                    activity.getNetProfitLossReported()
                ));
            }
        }

        // Rule 4: Validate material participation for losses
        if (calculatedNet.compareTo(BigDecimal.ZERO) < 0 && activity.isMateriallyParticipated()) {
            // Active participation with loss - should not be on Form 8582
            String activityKey = activity.getPrincipalProduct() != null ? 
                activity.getPrincipalProduct().toUpperCase().trim() : null;
                
            if (activityKey != null && form8582Losses.containsKey(activityKey)) {
                result.addWarning(String.format(
                    "[%s] Active participation loss should not be on Form 8582 (passive loss form)",
                    activity.getBusinessName()
                ));
            }
        }
    }

    /**
     * Validates that total net farm profit/loss matches Schedule 1.
     */
    private void validateTotalNetFarmProfitLoss(List<ScheduleFActivity> activities,
                                                   BigDecimal schedule1NetFarmProfitLoss,
                                                   ValidationResult result) {
        if (schedule1NetFarmProfitLoss == null) {
            return;
        }

        // Sum up all active participation net profit/losses
        BigDecimal totalCalculated = activities.stream()
            .filter(ScheduleFActivity::isMateriallyParticipated)
            .map(ScheduleFActivity::getNetProfitLossCalculated)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalCalculated.compareTo(schedule1NetFarmProfitLoss) != 0) {
            result.addError(String.format(
                "Total net farm profit/loss mismatch: Schedule F total (%$.2f) != Schedule 1 (%$.2f)",
                totalCalculated, 
                schedule1NetFarmProfitLoss
            ));
        }
    }

    /**
     * Validates that all passive losses are reported on Form 8582.
     */
    private void validateForm8582Completeness(List<ScheduleFActivity> activities,
                                                    Map<String, BigDecimal> form8582Losses,
                                                    ValidationResult result) {
        // Check for passive losses not on Form 8582
        for (ScheduleFActivity activity : activities) {
            if (!activity.isMateriallyParticipated() && 
                activity.getNetProfitLossCalculated().compareTo(BigDecimal.ZERO) < 0 &&
                activity.getForm8582Loss() == null) {
                
                result.addError(String.format(
                    "[%s] Passive loss of %$.2f not found on Form 8582",
                    activity.getBusinessName(), 
                    activity.getNetProfitLossCalculated().abs()
                ));
            }
        }

        // Check for Form 8582 losses not on Schedule F
        Set<String> scheduleFActivities = new HashSet<>();
        for (ScheduleFActivity activity : activities) {
            if (activity.getPrincipalProduct() != null) {
                scheduleFActivities.add(activity.getPrincipalProduct().toUpperCase().trim());
            }
        }

        for (Map.Entry<String, BigDecimal> entry : form8582Losses.entrySet()) {
            if (!scheduleFActivities.contains(entry.getKey())) {
                result.addWarning(String.format(
                    "Form 8582 contains loss for '%s' (%$.2f) but no matching Schedule F found",
                    entry.getKey(), 
                    entry.getValue()
                ));
            }
        }
    }

    /**
     * Extracts all forms with a specific form name.
     */
    private List<JsonNode> extractFormsByName(JsonNode forms, String formName) {
        List<JsonNode> result = new ArrayList<>();
        if (forms == null || !forms.isArray()) {
            return result;
        }

        for (JsonNode form : forms) {
            String formNum = getTextValue(form, "formNum");
            if (formName.equals(formNum)) {
                result.add(form);
            }
        }
        return result;
    }

    /**
     * Extracts a single form by name.
     */
    private JsonNode extractFormByName(JsonNode forms, String formName) {
        List<JsonNode> result = extractFormsByName(forms, formName);
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * Helper method to get text value from JsonNode.
     */
    private String getTextValue(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return (field != null && !field.isNull()) ? field.asText() : null;
    }

    /**
     * Helper method to get BigDecimal value from JsonNode.
     */
    private BigDecimal getBigDecimalValue(JsonNode node, String fieldName) {
        String value = getTextValue(node, fieldName);
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            // Handle negative values and remove commas
            value = value.replace(",", "");
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Helper method to get boolean value from JsonNode.
     */
    private Boolean getBooleanValue(JsonNode node, String fieldName) {
        String value = getTextValue(node, fieldName);
        if (value == null) {
            return null;
        }
        return !false".equalsIgnoreCase(value);
    }

    /**
     * Data class representing a Schedule F activity.
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ScheduleFActivity {
        private String businessName;
        private String ein;
        private String principalProduct;
        private boolean materiallyParticipated;
        private BigDecimal grossIncome;
        private BigDecimal totalExpenses;
        private BigDecimal netProfitLossReported;
        private BigDecimal netProfitLossCalculated;
        private BigDecimal form8582Loss;
    }

    /**
     * Data class representing validation results.
     */
    @Data
    public static class ValidationResult {
        private List<String> errors = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();

        public void addError(String error) {
            errors.add(error);
        }

        public void addWarning(String warning) {
            warnings.add(warning);
        }

        public boolean isValid() {
            return errors.isEmpty();
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
    }
}