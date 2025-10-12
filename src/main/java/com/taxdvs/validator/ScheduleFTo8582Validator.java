package com.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Validator for Schedule F to Form 8582 matching and consistency checks.
 * Implements IRS rules for passive activity loss reporting.
 *
 * Key Validations:
 * 1. Match business names between Schedule F and Form 8582
 * 2. Verify loss calculation: Line 9 (Gross Income) - Line 33 (Total Expenses)
 * 3. Confirm loss amounts match between forms
 * 4. Validate material participation logic (material farms should NOT be on 8582)
 * 5. Check that Schedule F Line 34 shows $0 for passive losses
 */
@Component
@Slf4j
public class ScheduleFTo8582Validator {

    private static final String SCHEDULE_F_PATH = "/IRS1040ScheduleF";
    private static final String FORM_8582_PATH = "/IRS8582";
    private static final String BUSINESS_NAME_PATH = "/FarmProprietorName/BusinessNameLine1Txt";
    private static final String PRINCIPAL_PRODUCT_PATH = "/PrincipalProductDesc";
    private static final String GROSS_INCOME_PATH = "/FarmIncomeCashMethodGrp/GrossIncomeAmt";
    private static final String TOTAL_EXPENSES_PATH = "/FarmExpensesGrp/TotalExpensesAmt";
    private static final String NET_PROFIT_LOSS_PATH = "/FarmExpensesGrp/NetFarmProfitLossAmt";
    private static final String MATERIAL_PARTICIPATION_PATH = "/MateriallyParticipatedInd";
    private static final String WORKSHEET_PASSIVE_GRP = "/ParentWrkshtPassiveGrp/WrkshtPassiveGrp";
    private static final String ACTIVITY_NAME_PATH = "/NonParticipateActivityNm";
    private static final String CURRENT_YEAR_LOSS_PATH = "/CurrentYearNetLossAmt";

    /**
     * Validates the entire tax return payload for Schedule F to Form 8582 consistency.
     *
     * @param payload The full tax return JSON payload
     * @return ValidationResult with pass/fail status and detailed findings
     */
    public ValidationResult validate(JsonNode payload) {
        log.info("Starting Schedule F to Form 8582 validation");
        
        List<ValidationError> errors = new ArrayList<>();
        List<ValidationWarning> warnings = new ArrayList<>();
        
        // Extract forms from payload
        JsonNode body = payload.get("body");
        if (body == null || !body.has("forms")) {
            errors.add(ValidationError.builder()
                    .errorCode("MISSING_BODY")
                    .message("Payload missing 'body' or 'forms' section")
                    .severity("CRITICAL")
                    .build());
            return ValidationResult.builder()
                    .passed(false)
                    .errors(errors)
                    .warnings(warnings)
                    .build();
        }
        
        List<JsonNode> forms = getFormsAsList(body.get("forms"));
        
        // Extract Schedule F and Form 8582
        List<ScheduleFData> scheduleFList = extractScheduleFData(forms);
        Form8582Data form8582Data = extractForm8582Data(forms);
        
        if (scheduleFList.isEmpty()) {
            warnings.add(ValidationWarning.builder()
                    .warningCode("NO_SCHEDULE_F")
                    .message("No Schedule F forms found in payload")
                    .build());
        }
        
        // Perform validations
        validateLossCalculations(scheduleFList, errors, warnings);
        validatePassiveActivityMatching(scheduleFList, form8582Data, errors, warnings);
        validateMaterialParticipation(scheduleFList, form8582Data, errors, warnings);
        validatePassiveLossReporting(scheduleFList, errors, warnings);
        
        boolean passed = errors.isEmpty();
        log.info("Validation completed: {}, Errors: {}, Warnings: {}", 
                passed ? "PASSED" : "FAILED", errors.size(), warnings.size());
        
        return ValidationResult.builder()
                .passed(passed)
                .errors(errors)
                .warnings(warnings)
                .scheduleFCount(scheduleFList.size())
                .form8582Present(form8582Data != null)
                .build();
    }

    /**
     * Validates loss calculations: Line 9 (Gross Income) - Line 33 (Total Expenses) = Net Loss
     */
    private void validateLossCalculations(List<ScheduleFData> scheduleFList, 
                                              List<ValidationError> errors,
                                              List<ValidationWarning> warnings) {
        log.debug("Validating loss calculations for {} Schedule F forms", scheduleFList.size());
        
        for (ScheduleFData scheduleF : scheduleFList) {
            BigDecimal grossIncome = scheduleF.getGrossIncome();
            BigDecimal totalExpenses = scheduleF.getTotalExpenses();
            BigDecimal reportedNet = scheduleF.getNetProfitLoss();
            
            // Calculate: Line 9 - Line 33
            BigDecimal calculatedNet = grossIncome.subtract(totalExpenses);
            
            // If it's a loss and not materially participated, Schedule F Line 34 should show $0
            if (calculatedNet.compareTo(BigDecimal.ZERO) < 0 && !scheduleF.isMateriallyParticipated()) {
                if (reportedNet.compareTo(BigDecimal.ZERO) != 0) {
                    errors.add(ValidationError.builder()
                            .errorCode("INVALID_PASSIVE_LOSS_REPORTING")
                            .message(String.format(
                                "Schedule F '%s': Passive loss should show $0 on Line 34, but shows %s",
                                scheduleF.getBusinessName(), reportedNet))
                            .field("NetFarmProfitLossAmt")
                            .expectedValue("0")
                            .actualValue(reportedNet.toPlainString())
                            .severity("HIGH")
                            .build());
                }
            } else {
                // For profits or material participation, verify calculation
                if (calculatedNet.compareTo(reportedNet) != 0) {
                    errors.add(ValidationError.builder()
                            .errorCode("NET_PROFIT_LOSS_MISMATCH")
                            .message(String.format(
                                "Schedule F '%s': Calculated net (%s) does not match reported (%s)",
                                scheduleF.getBusinessName(), calculatedNet, reportedNet))
                            .field("NetFarmProfitLossAmt")
                            .expectedValue(calculatedNet.toPlainString())
                            .actualValue(reportedNet.toPlainString())
                            .severity("HIGH")
                            .build());
                }
            }
        }
    }

    /**
     * Validates matching between Schedule F passive losses and Form 8582.
     */
    private void validatePassiveActivityMatching(List<ScheduleFData> scheduleFList,
                                                      Form8582Data form8582Data,
                                                      List<ValidationError> errors,
                                                      List<ValidationWarning> warnings) {
        if (form8582Data == null) {
            warnings.add(ValidationWarning.builder()
                    .warningCode("NO_FORM_8582")
                    .message("Form 8582 not found in payload")
                    .build());
            return;
        }
        
        log.debug("Validating passive activity matching");
        
        // Get passive farms (those without material participation)
        List<ScheduleFData> passiveFarms = scheduleFList.stream()
                .filter(f -> !f.isMateriallyParticipated())
                .collect(Collectors.toList());
                
        // Match each passive farm with Form 8582
        for (ScheduleFData passiveFarm : passiveFarms) {
            BigDecimal grossIncome = passiveFarm.getGrossIncome();
            BigDecimal totalExpenses = passiveFarm.getTotalExpenses();
            BigDecimal calculatedLoss = grossIncome.subtract(totalExpenses);
            
            // Only check if it's a loss
            if (calculatedLoss.compareTo(BigDecimal.ZERO) >= 0) {
                continue;
            }
            
            // Find matching activity on Form 8582
            Optional<PassiveActivity> matchingActivity = form8582Data.getPassiveActivities().stream()
                    .filter(a -> matchesBusinessName(passiveFarm.getBusinessName(), 
                                                      passiveFarm.getPrincipalProduct(), 
                                                      a.getActivityName()))
                    .findFirst();
                    
            if (matchingActivity.isPresent()) {
                PassiveActivity activity = matchingActivity.get();
                BigDecimal form8582Loss = activity.getCurrentYearLoss().abs(); // Convert to positive
                BigDecimal expectedLoss = calculatedLoss.abs();
                
                if (form8582Loss.compareTo(expectedLoss) != 0) {
                    errors.add(ValidationError.builder()
                            .errorCode("LOSS_AMOUNT_MISMATCH")
                            .message(String.format(
                                "Loss amount mismatch for '%s': Schedule F calculated (%s) vs Form 8582 (%s)",
                                passiveFarm.getBusinessName(), expectedLoss, form8582Loss))
                            .field("CurrentYearNetLossAmt")
                            .expectedValue(expectedLoss.toPlainString())
                            .actualValue(form8582Loss.toPlainString())
                            .severity("HIGH")
                            .build());
                }
            } else {
                errors.add(ValidationError.builder()
                        .errorCode("MISSING_ON_FORM_8582")
                        .message(String.format(
                            "Passive farm '%s' with loss (%s) not found on Form 8582",
                            passiveFarm.getBusinessName(), calculatedLoss))
                        .field("NonParticipateActivityNm")
                        .severity("HIGH")
                        .build());
            }
        }
    }

    /**
     * Validates material participation logic.
     * Materially participated farms should NOT be on Form 8582.
     */
    private void validateMaterialParticipation(List<ScheduleFData> scheduleFList,
                                                     Form8582Data form8582Data,
                                                     List<ValidationError> errors,
                                                     List<ValidationWarning> warnings) {
        if (form8582Data == null) {
            return;
        }
        
        log.debug("Validating material participation logic");
        
        // Check if any materially participated farms are incorrectly on Form 8582
        List<ScheduleFData> materialFarms = scheduleFList.stream()
                .filter(ScheduleFData::isMateriallyParticipated)
                .collect(Collectors.toList());
                
        for (ScheduleFData materialFarm : materialFarms) {
            boolean foundOnForm8582 = form8582Data.getPassiveActivities().stream()
                    .anyMatch(a -> matchesBusinessName(materialFarm.getBusinessName(), 
                                                       materialFarm.getPrincipalProduct(), 
                                                       a.getActivityName()));
                                                       
            if (foundOnForm8582) {
                errors.add(ValidationError.builder()
                        .errorCode("MATERIAL_FARM_ON_8582")
                        .message(String.format(
                            "Farm '%s' marked as materially participated should NOT be on Form 8582",
                            materialFarm.getBusinessName()))
                        .field("MateriallyParticipatedInd")
                        .severity("HIGH")
                        .build());
            }
        }
    }

    /**
     * Validates that passive losses are reported as $0 on Schedule F Line 34.
     */
    private void validatePassiveLossReporting(List<ScheduleFData> scheduleFList,
                                                    List<ValidationError> errors,
                                                    List<ValidationWarning> warnings) {
        log.debug("Validating passive loss reporting on Schedule F Line 34");
        
        for (ScheduleFData scheduleF : scheduleFList) {
            BigDecimal calculatedNet = scheduleF.getGrossIncome().subtract(scheduleF.getTotalExpenses());
            
            // If it's a loss and not materially participated, Line 34 must be $0
            if (calculatedNet.compareTo(BigDecimal.ZERO) < 0 && !scheduleF.isMateriallyParticipated()) {
                if (scheduleF.getNetProfitLoss().compareTo(BigDecimal.ZERO) != 0) {
                    warnings.add(ValidationWarning.builder()
                            .warningCode("PASSIVE_LOSS_NOT_ZERO")
                            .message(String.format(
                                "Per IRS rules, Schedule F '%s' Line 34 should show $0 for passive losses",
                                scheduleF.getBusinessName()))
                            .build());
                }
            }
        }
    }

    /**
     * Matches business names between Schedule F and Form 8582.
     * Uses both business name and principal product for matching.
     */
    private boolean matchesBusinessName(String businessName, String principalProduct, String activityName) {
        if (activityName == null) {
            return false;
        }
        
        String normalizedActivity = activityName.toUpperCase().trim();
        
        // Match by business name
        if (businessName != null && normalizedActivity.contains(businessName.toUpperCase().trim())) {
            return true;
        }
        
        // Match by principal product
        if (principalProduct != null && normalizedActivity.contains(principalProduct.toUpperCase().trim())) {
            return true;
        }
        
        return false;
    }

    /**
     * Extracts Schedule F data from the forms list.
     */
    private List<ScheduleFData> extractScheduleFData(List<JsonNode> forms) {
        return forms.stream()
                .filter(form -> "IRS1040ScheduleF".equals(getTextValue(form, "formNum")))
                .map(this::parseScheduleF)
                .collect(Collectors.toList());
    }

    /**
     * Extracts Form 8582 data from the forms list.
     */
    private Form8582Data extractForm8582Data(List<JsonNode> forms) {
        return forms.stream()
                .filter(form -> "IQS8582".equals(getTextValue(form, "formNum")))
                .findFirst()
                .map(this::parseForm8582)
                .orElse(null);
    }

    /**
     * Parses a Schedule F form node into ScheduleFData.
     */
    private ScheduleFData parseScheduleF(JsonNode form) {
        String businessName = getNestedTextValue(form, "/IRS1040ScheduleF" + BUSINESS_NAME_PATH);
        String principalProduct = getNestedTextValue(form, "/IRS1040ScheduleF" + PRINCIPAL_PRODUCT_PATH);
        BigDecimal grossIncome = getNestedAmount(form, "/IRS1040ScheduleF" + GROSS_INCOME_PATH);
        BigDecimal totalExpenses = getNestedAmount(form, "/IRS1040ScheduleF" + TOTAL_EXPENSES_PATH);
        BigDecimal netProfitLoss = getNestedAmount(form, "/IRS1040ScheduleF" + NET_PROFIT_LOSS_PATH);
        boolean materiallyParticipated = getNestedBoolean(form, "/IRS1040ScheduleF" + MATERIAL_PARTICIPATION_PATH);
        
        return ScheduleFData.builder()
                .businessName(businessName)
                .principalProduct(principalProduct)
                .grossIncome(grossIncome)
                .totalExpenses(totalExpenses)
                .netProfitLoss(netProfitLoss)
                .materiallyParticipated(materiallyParticipated)
                .build();
    }

    /**
     * Parses Form 8582 data.
     */
    private Form8582Data parseForm8582(JsonNode form) {
        List<PassiveActivity> activities = new ArrayList<>();
        
        JsonNode worksheetGrp = findNestedNode(form, "/IRS8582" + WORKSHEET_PASSIVE_GRP);
        if (worksheetGrp != null && worksheetGrp.isArray()) {
            for (JsonNode activityNode : worksheetGrp) {
                String activityName = getTextValue(activityNode, ACTIVITY_NAME_PATH.substring(1));
                BigDecimal currentYearLoss = getAmount(activityNode, CURRENT_YEAR_LOSS_PATH.substring(1));
                
                activities.add(PassiveActivity.builder()
                        .activityName(activityName)
                        .currentYearLoss(currentYearLoss)
                        .build());
            }
        }
        
        return Form8582Data.builder()
                .passiveActivities(activities)
                .build();
    }

    // Helper methods for JSON navigation
    
    private List<JsonNode> getFormsAsList(JsonNode formsNode) {
        if (formsNode == null || !formsNode.isArray()) {
            return Collections.emptyList();
        }
        return StreamSupport.stream(formsNode.spliterator(), false)
                .collect(Collectors.toList());
    }

    private String getTextValue(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field != null && !field.isNull() ? field.asText() : null;
    }

    private String getNestedTextValue(JsonNode node, String path) {
        JsonNode target = findNestedNode(node, path);
        return target != null && !target.isNull() ? target.asText() : null;
    }

    private BigDecimal getAmount(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(field.asText());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal getNestedAmount(JsonNode node, String path) {
        JsonNode target = findNestedNode(node, path);
        if (target == null || target.isNull()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(target.asText());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private boolean getNestedBoolean(JsonNode node, String path) {
        JsonNode target = findNestedNode(node, path);
        if (target == null || target.isNull()) {
            return false;
        }
        if (target.isBoolean()) {
            return target.asBoolean();
        }
        String value = target.asText();
        return "true".equalsIgnoreCase(value) || "X".equals(value);
    }

    private JsonNode findNestedNode(JsonNode node, String path) {
        JsonNode lineItems = node.get("lineItems");
        if (lineItems == null || !lineItems.isArray()) {
            return null;
        }
        
        for (JsonNode item : lineItems) {
            JsonNode lineName = item.get("lineNameTxt");
            if (lineName != null && path.equals(lineName.asText())) {
                JsonNode value = item.get("perReturnValueTxt");
                if (value != null) {
                    return value;
                }
                // Check for nested lineItems
                JsonNode nestedItems = item.get("lineItems");
                if (nestedItems != null) {
                    return nestedItems;
                }
            }
        }
        return null;
    }

    // Data classes
    
    @Data
    @Builder
    public static class ScheduleFData {
        private String businessName;
        private String principalProduct;
        private BigDecimal grossIncome;
        private BigDecimal totalExpenses;
        private BigDecimal netProfitLoss;
        private boolean materiallyParticipated;
    }

    @Data
    @Builder
    public static class Form8582Data {
        private List<PassiveActivity> passiveActivities;
    }

    @Data
    @Builder
    public static class PassiveActivity {
        private String activityName;
        private BigDecimal currentYearLoss;
    }

    @Data
    @Builder
    public static class ValidationResult {
        private boolean passed;
        private List<ValidationError> errors;
        private List<ValidationWarning> warnings;
        private int scheduleFCount;
        private boolean form8582Present;
    }

    @Data
    @Builder
    public static class ValidationError {
        private String errorCode;
        private String message;
        private String field;
        private String expectedValue;
        private String actualValue;
        private String severity;
    }

    @Data
    @Builder
    public static class ValidationWarning {
        private String warningCode;
        private String message;
    }
}