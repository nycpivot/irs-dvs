package com.tax.dvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Validator for Schedule F and Form 8582 matching and validation.
 * 
 * Business Requirements:
 * 1. Match business names between Schedule F and IRS8582
 * 2. Match total business income/loss between Schedule F and IRS8582
 * 3. When Schedule F shows $0 net loss, verify IRS8582 shows correct loss
 * 4. Calculate loss: Line 9 (GrossIncomeAmt) - Line 33 (TotalExpensesAmt) = Loss
 * 5. Validate material participation flag affects passive loss treatment
 *
 * IRS Rules (Form 8582 Instructions):
 * - Passive activities include rental activities and trade/business without material participation
 * - When net loss occurs without material participation, loss is passive
 * - Schedule F shows $0 for passive losses; actual loss appears on Form 8582
 * - Passive losses can only offset passive income
 * - Material participation makes activity non-passive
 */
@Component
public class ScheduleF8582Validator {

    private static final String SCHEDULE_F_FORM = "IRS1040ScheduleF";
    private static final String FORM_8582 = "IQS8582";
    private static final String PRINCIPAL_PRODUCT_PATH = "/IRS1040ScheduleF/PrincipalProductDesc";
    private static final String BUSINESS_NAME_PATH = "/IRS1040ScheduleF/FarmProprietorName/BusinessNameLine1Txt";
    private static final String GROSS_INCOME_PATH = "/IRS1040ScheduleF/FarmIncomeCashMethodGrp/GrossIncomeAmt";
    private static final String TOTAL_EXPENSES_PATH = "/IRS1040ScheduleF/FarmExpensesGrp/TotalExpensesAmt";
    private static final String NET_FARM_PROFIT_LOSS_PATH = "/IRS1040ScheduleF/FarmExpensesGrp/NetFarmProfitLossAmt";
    private static final String MATERIAL_PARTICIPATION_PATH = "/IRS1040ScheduleF/MateriallyParticipatedInd";
    private static final String FORM_8582_ACTIVITY_NAME_PATH = "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/NonParticipateActivityNm";
    private static final String FORM_8582_CURRENT_LOSS_PATH = "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/CurrentYearNetLossAmt";
    private static final String FORM_8582_OVERALL_LOSS_PATH = "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/OverallLossAmt";

    /**
     * Validates the entire tax return payload for Schedule F and Form 8582 matching.
     * 
     * @param payload The full tax return JSON payload
     * @return ValidationResult containing pass/fail status and detailed errors
     */
    public ValidationResult validate(JsonNode payload) {
        List<ValidationError> errors = new ArrayList<>();
        
        try {
            // Extract forms from payload
            JsonNode body = payload.get("body");
            if (body == null || !body.has("forms")) {
                errors.add(ValidationError.builder()
                        .errorCode("PAYLOAD_INVALID")
                        .errorMessage("Payload missing 'body.forms' node")
                        .severity("CRITICAL")
                        .build());
                return ValidationResult.builder()
                        .passed(false)
                        .errors(errors)
                        .build();
            }
            
            JsonNode forms = body.get("forms");
            
            // Extract Schedule F forms
            List<ScheduleFData> scheduleFForms = extractScheduleFForms(forms);
            
            // Extract Form 8582 data
            Form8582Data form8582Data = extractForm8582Data(forms);
            
            if (scheduleFForms.isEmpty()) {
                errors.add(ValidationError.builder()
                        .errorCode("SCH_F_NOT_FOUND")
                        .errorMessage("No Schedule F forms found in payload")
                        .severity("WARNING")
                        .build());
            }
            
            // Perform validations
            for (ScheduleFData scheduleF : scheduleFForms) {
                // Validation 1 & 2: Calculate actual loss from Schedule F
                BigDecimal calculatedLoss = calculateLoss(scheduleF);
                
                // Validation 3: Check if material participation affects passive treatment
                boolean isPassive = !scheduleF.isMateriallyParticipated();
                
                // Validation 4: If passive loss, verify Schedule F shows $0
                if (isPassive && calculatedLoss.compareTo(BigDecimal.ZERO) < 0) {
                    if (scheduleF.getNetFarmProfitLossAmt().compareTo(BigDecimal.ZERO) != 0) {
                        errors.add(ValidationError.builder()
                                .errorCode("SCH_F_PASSIVE_LOSS_NOT_ZERO")
                                .errorMessage(String.format(
                                        "Schedule F '%s': Passive loss should show $0 on NetFarmProfitLossAmt, but shows %s",
                                        scheduleF.getBusinessName(),
                                        scheduleF.getNetFarmProfitLossAmt()))
                                .severity("ERROR")
                                .formNum(SCHEDULE_F_FORM)
                                .fieldPath(NET_FARM_PROFIT_LOSS_PATH)
                                .actualValue(scheduleF.getNetFarmProfitLossAmt().toString())
                                .expectedValue("0")
                                .build());
                    }
                    
                    // Validation 5: Match with Form 8582
                    if (form8582Data != null) {
                        validateForm8582Matching(scheduleF, calculatedLoss, form8582Data, errors);
                    } else {
                        errors.add(ValidationError.builder()
                                .errorCode("FORM_8582_NOT_FOUND")
                                .errorMessage(String.format(
                                        "Schedule F '%s' has passive loss but Form 8582 not found in payload",
                                        scheduleF.getBusinessName()))
                                .severity("ERROR")
                                .formNum(SCHEDULE_F_FORM)
                                .build());
                    }
                } else if (!isPassive && calculatedLoss.compareTo(BigDecimal.ZERO) < 0) {
                    // Non-passive loss should appear on Schedule F
                    if (scheduleF.getNetFarmProfitLossAmt().compareTo(calculatedLoss) != 0) {
                        errors.add(ValidationError.builder()
                                .errorCode("SCH_F_NON_PASSIVE_LOSS_MISMATCH")
                                .errorMessage(String.format(
                                        "Schedule F '%s': Non-passive loss mismatch. Calculated: %s, Reported: %s",
                                        scheduleF.getBusinessName(),
                                        calculatedLoss,
                                        scheduleF.getNetFarmProfitLossAmt()))
                                .severity("ERROR")
                                .formNum(SCHEDULE_F_FORM)
                                .fieldPath(NET_FARM_PROFIT_LOSS_PATH)
                                .actualValue(scheduleF.getNetFarmProfitLossAmt().toString())
                                .expectedValue(calculatedLoss.toString())
                                .build());
                    }
                }
            }
            
            boolean passed = errors.stream()
                    .noneMatch(e -> "ERROR".equals(e.getSeverity()) || "CRITICAL".equals(e.getSeverity()));
            
            return ValidationResult.builder()
                    .passed(passed)
                    .errors(errors)
                    .totalScheduleFForms(scheduleFForms.size())
                    .build();
                    
        } catch (Exception e) {
            errors.add(ValidationError.builder()
                    .errorCode("VALIDATION_ERROR")
                    .errorMessage("Unexpected error during validation: " + e.getMessage())
                    .severity("CRITICAL")
                    .build());
            return ValidationResult.builder()
                    .passed(false)
                    .errors(errors)
                    .build();
        }
    }

    /**
     * Calculates the actual loss from Schedule F.
     * Formula: Line 9 (GrossIncomeAmt) - Line 33 (TotalExpensesAmt)
     */
    private BigDecimal calculateLoss(ScheduleFData scheduleF) {
        return scheduleF.getGrossIncomeAmt().subtract(scheduleF.getTotalExpensesAmt());
    }

    /**
     * Validates matching between Schedule F and Form 8582.
     */
    private void validateForm8582Matching(ScheduleFData scheduleF, 
                                             BigDecimal calculatedLoss,
                                             Form8582Data form8582Data,
                                             List<ValidationError> errors) {
        // Find matching activity in Form 8582
        Form8582Activity matchingActivity = form8582Data.getActivities().stream()
                .filter(a -> normalizeBusinessName(a.getActivityName())
                        .equalsIgnoreCase(normalizeBusinessName(scheduleF.getBusinessName())))
                .findFirst()
                .orElse(null);
        
        if (matchingActivity == null) {
            errors.add(ValidationError.builder()
                    .errorCode("BUSINESS_NAME_NOT_FOUND_8582")
                    .errorMessage(String.format(
                            "Schedule F business '%s' not found in Form 8582 passive activities",
                            scheduleF.getBusinessName()))
                    .severity("ERROR")
                    .formNum(SCHEDULE_F_FORM)
                    .build());
            return;
        }
        
        // Validate business name match
        if (!normalizeBusinessName(matchingActivity.getActivityName())
                .equalsIgnoreCase(normalizeBusinessName(scheduleF.getBusinessName()))) {
            errors.add(ValidationError.builder()
                    .errorCode("BUSINESS_NAME_MISMATCH")
                    .errorMessage(String.format(
                            "Business name mismatch. Schedule F: '%s', Form 8582: '%s'",
                            scheduleF.getBusinessName(),
                            matchingActivity.getActivityName()))
                    .severity("WARNING")
                    .formNum(SCHEDULE_F_FORM)
                    .build());
        }
        
        // Validate loss amount matching
        BigDecimal form8582Loss = matchingActivity.getOverallLossAmt();
        BigDecimal expectedLoss = calculatedLoss.abs(); // Form 8582 shows losses as positive
        
        if (form8582Loss.compareTo(expectedLoss) != 0) {
            errors.add(ValidationError.builder()
                    .errorCode("LOSS_AMOUNT_MISMATCH")
                    .errorMessage(String.format(
                            "Loss amount mismatch for '%s'. Calculated from Schedule F: %s, Form 8582: %s",
                            scheduleF.getBusinessName(),
                            expectedLoss,
                            form8582Loss))
                    .severity("ERROR")
                    .formNum(FORM_8582)
                    .fieldPath(FORM_8582_OVERALL_LOSS_PATH)
                    .actualValue(form8582Loss.toString())
                    .expectedValue(expectedLoss.toString())
                    .build());
        }
    }

    /**
     * Normalizes business names for comparison.
     */
    private String normalizeBusinessName(String name) {
        if (name == null) return "";
        return name.trim().toUpperCase().replaceAll("\\s+", " ");
    }

    /**
     * Extracts all Schedule F forms from the payload.
     */
    private List<ScheduleFData> extractScheduleFForms(JsonNode forms) {
        List<ScheduleFData> result = new ArrayList<>();
        
        for (JsonNode form : forms) {
            String formNum = getTextValue(form, "formNum");
            if (SCHEDULE_F_FORM.equals(formNum)) {
                result.add(extractScheduleFData(form));
            }
        }
        
        return result;
    }

    /**
     * Extracts data from a single Schedule F form.
     */
    private ScheduleFData extractScheduleFData(JsonNode form) {
        String businessName = findLineItemValue(form, BUSINESS_NAME_PATH);
        if (businessName == null || businessName.isBlank()) {
            businessName = findLineItemValue(form, PRINCIPAL_PRODUCT_PATH);
        }
        
        return ScheduleFData.builder()
                .businessName(businessName)
                .grossIncomeAmt(parseBigDecimal(findLineItemValue(form, GROSS_INCOME_PATH)))
                .totalExpensesAmt(parseBigDecimal(findLineItemValue(form, TOTAL_EXPENSES_PATH)))
                .netFarmProfitLossAmt(parseBigDecimal(findLineItemValue(form, NET_FARM_PROFIT_LOSS_PATH)))
                .materiallyParticipated(parseBoolean(findLineItemValue(form, MATERIAL_PARTICIPATION_PATH)))
                .build();
    }

    /**
     * Extracts Form 8582 data from the payload.
     */
    private Form8582Data extractForm8582Data(JsonNode forms) {
        for (JsonNode form : forms) {
            String formNum = getTextValue(form, "formNum");
            if (FORM_8582.equals(formNum)) {
                return extractForm8582DataFromForm(form);
            }
        }
        return null;
    }

    /**
     * Extracts activity data from Form 8582.
     */
    private Form8582Data extractForm8582DataFromForm(JsonNode form) {
        List<Form8582Activity> activities = new ArrayList<>();
        
        // Extract activities from ParentWrkshtPassiveGrp/WrkshtPassiveGrp
        JsonNode lineItems = form.get("lineItems");
        if (lineItems != null) {
            for (JsonNode lineItem : lineItems) {
                String lineName = getTextValue(lineItem, "lineNameTxt");
                
                if ("/IRS8582/ParentWrkshtPassiveGrp".equals(lineName)) {
                    JsonNode nestedItems = lineItem.get("lineItems");
                    if (nestedItems != null) {
                        for (JsonNode nested : nestedItems) {
                            String nestedName = getTextValue(nested, "lineNameTxt");
                            if ("/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp".equals(nestedName)) {
                                activities.add(extract8582Activity(nested));
                            }
                        }
                    }
                }
            }
        }
        
        return Form8582Data.builder()
                .activities(activities)
                .build();
    }

    /**
     * Extracts a single activity from Form 8582.
     */
    private Form8582Activity extract8582Activity(JsonNode activityNode) {
        String activityName = null;
        BigDecimal currentYearLoss = BigDecimal.ZERO;
        BigDecimal overallLoss = BigDecimal.ZERO;
        
        JsonNode lineItems = activityNode.get("lineItems");
        if (lineItems != null) {
            for (JsonNode item : lineItems) {
                String lineName = getTextValue(item, "lineNameTxt");
                String value = getTextValue(item, "perReturnValueTxt");
                
                if (FORM_8582_ACTIVITY_NAME_PATH.equals(lineName)) {
                    activityName = value;
                } else if (FORM_8582_CURRENT_LOSS_PATH.equals(lineName)) {
                    currentYearLoss = parseBigDecimal(value);
                } else if (FORM_8582_OVERALL_LOSS_PATH.equals(lineName)) {
                    overallLoss = parseBigDecimal(value);
                }
            }
        }
        
        return Form8582Activity.builder()
                .activityName(activityName)
                .currentYearLossAmt(currentYearLoss)
                .overallLossAmt(overallLoss)
                .build();
    }

    /**
     * Finds a line item value by path.
     */
    private String findLineItemValue(JsonNode form, String path) {
        JsonNode lineItems = form.get("lineItems");
        if (lineItems == null) return null;
        
        for (JsonNode item : lineItems) {
            String lineName = getTextValue(item, "lineNameTxt");
            if (path.equals(lineName)) {
                String value = getTextValue(item, "perReturnValueTxt");
                if (value != null) return value;
            }
            
            // Check nested lineItems
            JsonNode nested = item.get("lineItems");
            if (nested != null) {
                String nestedResult = findLineItemValueInNested(nested, path);
                if (nestedResult != null) return nestedResult;
            }
        }
        
        return null;
    }

    private String findLineItemValueInNested(JsonNode nestedItems, String path) {
        for (JsonNode item : nestedItems) {
            String lineName = getTextValue(item, "lineNameTxt");
            if (path.equals(lineName)) {
                return getTextValue(item, "perReturnValueTxt");
            }
            
            JsonNode nested = item.get("lineItems");
            if (nested != null) {
                String result = findLineItemValueInNested(nested, path);
                if (result != null) return result;
            }
        }
        return null;
    }

    private String getTextValue(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field != null && !field.isNull() ? field.asText() : null;
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value.replaceAll("[,]", ""));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private boolean parseBoolean(String value) {
        if (value == null) return false;
        return "true".equalsIgnoreCase(value.trim());
    }

    // Data classes
    @Data
    @Builder
    public static class ScheduleFData {
        private String businessName;
        private BigDecimal grossIncomeAmt;
        private BigDecimal totalExpensesAmt;
        private BigDecimal netFarmProfitLossAmt;
        private boolean materiallyParticipated;
    }

    @Data
    @Builder
    public static class Form8582Data {
        private List<Form8582Activity> activities;
    }

    @Data
    @Builder
    public static class Form8582Activity {
        private String activityName;
        private BigDecimal currentYearLossAmt;
        private BigDecimal overallLossAmt;
    }

    @Data
    @Builder
    public static class ValidationResult {
        private boolean passed;
        private List<ValidationError> errors;
        private Integer totalScheduleFForms;
    }

    @Data
    @Builder
    public static class ValidationError {
        private String errorCode;
        private String errorMessage;
        private String severity; // CRITICAL, ERROR, WARNING
        private String formNum;
        private String fieldPath;
        private String actualValue;
        private String expectedValue;
    }
}