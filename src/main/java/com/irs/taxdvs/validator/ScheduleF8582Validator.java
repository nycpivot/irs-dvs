package com.irs.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Validator for matching Schedule F business names and losses with Form 8582
 * per IRS Passive Activity Loss Limitations rules.
 *
 * Based on IRS Form 8582 Instructions (2024) and IRC Section 469.
 */
@Component
@Slf4j
public class ScheduleF8582Validator {

    private static final String PATH_FORM_1040 = "/IRS1040";
    private static final String PATH_SCHEDULE_F = "/IRS1040ScheduleF";
    private static final String PATH_FORM_8582 = "/IRS8582";
    private static final String PATH_SCHEDULE_1 = "/IRS1040Schedule1";

    /**
     * Validates the entire tax return payload for Schedule F to Form 8582 matching.
     *
     * @param payload The full tax return JSON payload
     * @return ValidationResult with pass/fail status and detailed errors
     */
    public ValidationResult validate(JsonNode payload) {
        log.info("Starting Schedule F to Form 8582 validation");

        List<ValidationError> errors = new ArrayList<>();
        List<ValidationWarning> warnings = new ArrayList<>();

        try {
            // Extract body and forms
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

            JsonNode formsArray = body.get("forms");

            // Extract all Schedule F forms
            List<ScheduleFData> scheduleFList = extractScheduleFData(formsArray);
            log.info("Found {} Schedule F forms", scheduleFList.size());

            // Extract Form 8582 data
            Form8582Data form8582Data = extractForm8582Data(formsArray);
            if (form8582Data == null) {
                warnings.add(ValidationWarning.builder()
                        .warningCode("MISSING_FORM_8582")
                        .message("Form 8582 not found in payload - skipping passive activity validation")
                        .build());
                return ValidationResult.builder()
                        .passed(true)
                        .errors(errors)
                        .warnings(warnings)
                        .build();
            }

            log.info("Found Form 8582 with {} activities", form8582Data.getActivities().size());

            // Perform validations
            validateBusinessNameMatching(scheduleFList, form8582Data, errors, warnings);
            validateLossCalculations(scheduleFList, form8582Data, errors, warnings);
            validateNetFarmProfitLossAmt(scheduleFList, errors);
            validateMaterialParticipation(scheduleFList, form8582Data, warnings);

            boolean passed = errors.isEmpty();
            log.info("Validation completed: passed={}, errors={}, warnings={}", 
                    passed, errors.size(), warnings.size());

            return ValidationResult.builder()
                    .passed(passed)
                    .errors(errors)
                    .warnings(warnings)
                    .build();

        } catch (Exception e) {
            log.error("Validation failed with exception", e);
            errors.add(ValidationError.builder()
                    .errorCode("VALIDATION_EXCEPTION")
                    .message("Validation failed: " + e.getMessage())
                    .severity("CRITICAL")
                    .build());
            return ValidationResult.builder()
                    .passed(false)
                    .errors(errors)
                    .warnings(warnings)
                    .build();
        }
    }

    /**
     * Extracts all Schedule F data from the forms array.
     */
    private List<ScheduleFData> extractScheduleFData(JsonNode formsArray) {
        List<ScheduleFData> scheduleFList = new ArrayList<>();

        for (JsonNode form : formsArray) {
            String formNum = getTextValue(form, "formNum");
            if ("IRS1040ScheduleF".equals(formNum)) {
                ScheduleFData scheduleFData = parseScheduleF(form);
                if (scheduleFData != null) {
                    scheduleFList.add(scheduleFData);
                }
            }
        }

        return scheduleFList;
    }

    /**
     * Parses a single Schedule F form.
     */
    private ScheduleFData parseScheduleF(JsonNode form) {
        try {
            String sequenceNum = getTextValue(form, "sequenceNum");
            String businessName = null;
            String principalProduct = null;
            String ein = null;
            Boolean materiallyParticipated = null;
            BigDecimal grossIncome = BigDecimal.ZERO;
            BigDecimal totalExpenses = BigDecimal.ZERO;
            BigDecimal netFarmProfitLoss = BigDecimal.ZERO;

            JsonNode lineItems = form.get("lineItems");
            if (lineItems != null && lineItems.isArray()) {
                for (JsonNode lineItem : lineItems) {
                    String lineName = getTextValue(lineItem, "lineNameTxt");

                    switch (lineName) {
                        case "/IRS1040ScheduleF/FarmProprietorName":
                            businessName = extractBusinessName(lineItem);
                            break;
                        case "/IRS1040ScheduleF/PrincipalProductDesc":
                            principalProduct = getTextValue(lineItem, "perReturnValueTxt");
                            break;
                        case "/IRS1040ScheduleF/EIN":
                            ein = getTextValue(lineItem, "perReturnValueTxt");
                            break;
                        case "/IRS1040ScheduleF/MateriallyParticipatedInd":
                            materiallyParticipated = getBooleanValue(lineItem, "perReturnValueTxt");
                            break;
                        case "/IRS1040ScheduleF/FarmIncomeCashMethodGrp":
                            grossIncome = extractGrossIncome(lineItem);
                            break;
                        case "/IRS1040ScheduleF/FarmExpensesGrp":
                            totalExpenses = extractTotalExpenses(lineItem);
                            netFarmProfitLoss = extractNetFarmProfitLoss(lineItem);
                            break;
                    }
                }
            }

            return ScheduleFData.builder()
                    .sequenceNum(sequenceNum)
                    .businessName(businessName)
                    .principalProduct(principalProduct)
                    .ein(ein)
                    .materiallyParticipated(materiallyParticipated)
                    .grossIncome(grossIncome)
                    .totalExpenses(totalExpenses)
                    .netFarmProfitLoss(netFarmProfitLoss)
                    .build();

        } catch (Exception e) {
            log.error("Error parsing Schedule F", e);
            return null;
        }
    }

    /**
     * Extracts business name from FarmProprietorName lineItem.
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
     * Extracts net farm profit/loss from FarmExpensesGrp.
     */
    private BigDecimal extractNetFarmProfitLoss(JsonNode lineItem) {
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
     * Extracts Form 8582 data.
     */
    private Form8582Data extractForm8582Data(JsonNode formsArray) {
        for (JsonNode form : formsArray) {
            String formNum = getTextValue(form, "formNum");
            if ("IRS8582".equals(formNum)) {
                return parseForm8582(form);
            }
        }
        return null;
    }

    /**
     * Parses Form 8582.
     */
    private Form8582Data parseForm8582(JsonNode form) {
        List<PassiveActivity> activities = new ArrayList<>();

        JsonNode lineItems = form.get("lineItems");
        if (lineItems != null && lineItems.isArray()) {
            for (JsonNode lineItem : lineItems) {
                String lineName = getTextValue(lineItem, "lineNameTxt");

                // Parse ParentWrkshtPassiveGrp for activities
                if ("/IRS8582/ParentWrkshtPassiveGrp".equals(lineName)) {
                    activities.addAll(extractPassiveActivities(lineItem));
                }
            }
        }

        return Form8582Data.builder()
                .activities(activities)
                .build();
    }

    /**
     * Extracts passive activities from ParentWrkshtPassiveGrp.
     */
    private List<PassiveActivity> extractPassiveActivities(JsonNode parentGrp) {
        List<PassiveActivity> activities = new ArrayList<>();

        JsonNode nestedLineItems = parentGrp.get("lineItems");
        if (nestedLineItems != null && nestedLineItems.isArray()) {
            for (JsonNode nested : nestedLineItems) {
                String lineName = getTextValue(nested, "lineNameTxt");

                if ("/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp".equals(lineName)) {
                    PassiveActivity activity = parsePassiveActivity(nested);
                    if (activity != null) {
                        activities.add(activity);
                    }
                }
            }
        }

        return activities;
    }

    /**
     * Parses a single passive activity from WrkshtPassiveGrp.
     */
    private PassiveActivity parsePassiveActivity(JsonNode wrkshtGrp) {
        String activityName = null;
        BigDecimal currentYearLoss = BigDecimal.ZERO;
        BigDecimal overallLoss = BigDecimal.ZERO;

        JsonNode nestedLineItems = wrkshtGrp.get("lineItems");
        if (nestedLineItems != null && nestedLineItems.isArray()) {
            for (JsonNode nested : nestedLineItems) {
                String lineName = getTextValue(nested, "lineNameTxt");

                switch (lineName) {
                    case "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/NonParticipateActivityNm":
                        activityName = getTextValue(nested, "perReturnValueTxt");
                        break;
                    case "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/CurrentYearNetLossAmt":
                        currentYearLoss = getBigDecimalValue(nested, "perReturnValueTxt");
                        break;
                    case "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/OverallLossAmt":
                        overallLoss = getBigDecimalValue(nested, "perReturnValueTxt");
                        break;
                }
            }
        }

        if (activityName != null) {
            return PassiveActivity.builder()
                    .activityName(activityName)
                    .currentYearLoss(currentYearLoss)
                    .overallLoss(overallLoss)
                    .build();
        }

        return null;
    }

    /**
     * Validates business name matching between Schedule F and Form 8582.
     * Per IRS Form 8582 instructions, passive activities must be identified consistently.
     */
    private void validateBusinessNameMatching(
            List<ScheduleFData> scheduleFList,
            Form8582Data form8582Data,
            List<ValidationError> errors,
            List<ValidationWarning> warnings) {

        // Create map of 8582 activities by name
        Map<String, PassiveActivity> activityMap = new HashMap<>();
        for (PassiveActivity activity : form8582Data.getActivities()) {
            if (activity.getActivityName() != null) {
                activityMap.put(
                        normalizeName(activity.getActivityName()),
                        activity
                );
            }
        }

        // Check each Schedule F with no material participation
        for (ScheduleFData scheduleF : scheduleFList) {
            // Skip if materially participated (not passive)
            if (Boolean.TRUE.equals(scheduleF.getMateriallyParticipated())) {
                continue;
            }

            // Skip if no loss (passive loss rules only apply to losses)
            BigDecimal calculatedLoss = calculateLoss(scheduleF);
            if (calculatedLoss.compareTo(BigDecimal.ZERO) >= 0) {
                continue;
            }

            String scheduleFName = getActivityName(scheduleF);
            String normalizedName = normalizeName(scheduleFName);

            if (!activityMap.containsKey(normalizedName)) {
                errors.add(ValidationError.builder()
                        .errorCode("BUSINESS_NAME_NOT_FOUND_8S582")
                        .message(String.format(
                                "Schedule F activity '%s' (sequence %s) with loss not found in Form 8582 passive activities",
                                scheduleFName, scheduleF.getSequenceNum()))
                        .severity("ERROR")
                        .scheduleFSequence(scheduleF.getSequenceNum())
                        .activityName(scheduleFName)
                        .build());
            } else {
                log.debug("Matched Schedule F '%{}' with Form 8582 activity", scheduleFName);
            }
        }
    }

    /**
     * Validates loss calculations and matching with Form 8582.
     * Per ticket: Line 9 (GrossIncome) - Line 33 (TotalExpenses) = Net Loss
     * This must match the loss reported on Form 8582.
     */
    private void validateLossCalculations(
            List<ScheduleFData> scheduleFList,
            Form8582Data form8582Data,
            List<ValidationError> errors,
            List<ValidationWarning> warnings) {

        // Create map of 8582 activities by name
        Map<String, PassiveActivity> activityMap = new HashMap<>();
        for (PassiveActivity activity : form8582Data.getActivities()) {
            if (activity.getActivityName() != null) {
                activityMap.put(
                        normalizeName(activity.getActivityName()),
                        activity
                );
            }
        }

        for (ScheduleFData scheduleF : scheduleFList) {
            // Skip if materially participated (not passive)
            if (Boolean.TRUE.equals(scheduleF.getMateriallyParticipated())) {
                continue;
            }

            BigDecimal calculatedLoss = calculateLoss(scheduleF);

            // Only validate if there's a loss
            if (calculatedLoss.compareTo(BigDecimal.ZERO) >= 0) {
                continue;
            }

            String activityName = getActivityName(scheduleF);
            String normalizedName = normalizeName(activityName);

            PassiveActivity activity = activityMap.get(normalizedName);
            if (activity != null) {
                // Compare calculated loss with 8582 loss
                BigDecimal form8582Loss = activity.getCurrentYearLoss();
                if (form8582Loss.compareTo(BigDecimal.ZERO) == 0) {
                    form8582Loss = activity.getOverallLoss();
                }

                // Convert to positive for comparison
                BigDecimal calculatedLossAbs = calculatedLoss.abs();
                BigDecimal form8582LossAbs = form8582Loss.abs();

                if (calculatedLossAbs.compareTo(form8582LossAbs) != 0) {
                    errors.add(ValidationError.builder()
                            .errorCode("LOSS_AMOUNT_MISMATCH")
                            .message(String.format(
                                    "Schedule F '%s' (sequence %s): Calculated loss (%s) does not match Form 8582 loss (%s)",
                                    activityName,
                                    scheduleF.getSequenceNum(),
                                    formatAmount(calculatedLoss),
                                    formatAmount(form8582Loss.negate())))
                            .severity("ERROR")
                            .scheduleFSequence(scheduleF.getSequenceNum())
                            .activityName(activityName)
                            .calculatedAmount(calculatedLoss)
                            .expectedAmount(form8582Loss.negate())
                            .build());
                } else {
                    log.debug("Loss amount matched for activity '{}': ${}", activityName, formatAmount(calculatedLoss));
                }
            }
        }
    }

    /**
     * Validates that NetFarmProfitLossAmt is correctly populated.
     * Per ticket: When there is a loss, the payload shows zero but should show the actual loss.
     */
    private void validateNetFarmProfitLossAmt(
            List<ScheduleFData> scheduleFList,
            List<ValidationError> errors) {

        for (ScheduleFData scheduleF : scheduleFList) {
            BigDecimal calculatedLoss = calculateLoss(scheduleF);
            BigDecimal reportedNetAmount = scheduleF.getNetFarmProfitLoss();

            // If there's a loss but NetFarmProfitLossAmt is zero, that's the issue
            if (calculatedLoss.compareTo(BigDecimal.ZERO) < 0 && 
                reportedNetAmount.compareTo(BigDecimal.ZERO) == 0) {
                
                errors.add(ValidationError.builder()
                        .errorCode("NET_FARM_PROFIT_LOSS_INCORRECT")
                        .message(String.format(
                                "Schedule F '%s' (sequence %s): NetFarmProfitLossAmt shows $0 but calculated loss is %s. " +
                                "Path /IRS1040ScheduleF/FarmExpensesGrp/NetFarmProfitLossAmt should show the actual loss amount",
                                getActivityName(scheduleF),
                                scheduleF.getSequenceNum(),
                                formatAmount(calculatedLoss)))
                        .severity("ERROR")
                        .scheduleFSequence(scheduleF.getSequenceNum())
                        .activityName(getActivityName(scheduleF))
                        .calculatedAmount(calculatedLoss)
                        .expectedAmount(calculatedLoss)
                        .build());
            } else if (calculatedLoss.compareTo(reportedNetAmount) != 0) {
                // General mismatch
                errors.add(ValidationError.builder()
                        .errorCode("NET_FARM_PROFIT_LOSS_MISMATCH")
                        .message(String.format(
                                "Schedule F '%s' (sequence %s): NetFarmProfitLossAmt (%s) does not match calculated amount (%s)",
                                getActivityName(scheduleF),
                                scheduleF.getSequenceNum(),
                                formatAmount(reportedNetAmount),
                                formatAmount(calculatedLoss)))
                        .severity("ERROR")
                        .scheduleFSequence(scheduleF.getSequenceNum())
                        .activityName(getActivityName(scheduleF))
                        .calculatedAmount(calculatedLoss)
                        .expectedAmount(calculatedLoss)
                        .build());
            }
        }
    }

    /**
     * Validates material participation consistency.
     * Per IRS rules: If not materially participated, should be on Form 8582.
     */
    private void validateMaterialParticipation(
            List<ScheduleFData> scheduleFList,
            Form8582Data form8582Data,
            List<ValidationWarning> warnings) {

        Map<String, PassiveActivity> activityMap = new HashMap<>();
        for (PassiveActivity activity : form8582Data.getActivities()) {
            if (activity.getActivityName() != null) {
                activityMap.put(normalizeName(activity.getActivityName()), activity);
            }
        }

        for (ScheduleFData scheduleF : scheduleFList) {
            String activityName = getActivityName(scheduleF);
            boolean materiallyParticipated = Boolean.TRUE.equals(scheduleF.getMateriallyParticipated());
            boolean onForm8582 = activityMap.containsKey(normalizeName(activityName));

            // If materially participated, should NOT be on Form 8582
            if (materiallyParticipated && onForm8582) {
                warnings.add(ValidationWarning.builder()
                        .warningCode("MATERIAL_PARTICIPATION_INCONSISTENCY")
                        .message(String.format(
                                "Schedule F '%s' (sequence %s) marked as materially participated but appears on Form 8582. " +
                                "Materially participated activities are not passive and should not be on Form 8582",
                                activityName, scheduleF.getSequenceNum()))
                        .build());
            }

            // If NOT materially participated and has loss, should be on Form 8582
            if (!materiallyParticipated && !onForm8582) {
                BigDecimal calculatedLoss = calculateLoss(scheduleF);
                if (calculatedLoss.compareTo(BigDecimal.ZERO) < 0) {
                    warnings.add(ValidationWarning.builder()
                            .warningCode("PASSIVE_ACTIVITY_NOT_ON_8S582")
                            .message(String.format(
                                    "Schedule F '%s' (sequence %s) marked as NOT", materially participated with loss (%s) but not found on Form 8582. " +
                                    "Passive activities with losses should be reported on Form 8582",
                                    activityName, scheduleF.getSequenceNum(), formatAmount(calculatedLoss)))
                            .build());
                }
            }
        }
    }

    /**
     * Calculates loss for a Schedule F: Line 9 (GrossIncome) - Line 33 (TotalExpenses).
     * Per ticket and IRS Schedule F instructions.
     */
    private BigDecimal calculateLoss(ScheduleFData scheduleF) {
        return scheduleF.getGrossIncome().subtract(scheduleF.getTotalExpenses());
    }

    /**
     * Gets the activity name for a Schedule F (principal product or business name).
     */
    private String getActivityName(ScheduleFData scheduleF) {
        if (scheduleF.getPrincipalProduct() != null) {
            return scheduleF.getPrincipalProduct();
        }
        return scheduleF.getBusinessName() != null ? scheduleF.getBusinessName() : "UMNAMED";
    }

    /**
     * Normalizes activity names for comparison (uppercase, trim, remove extra spaces).
     */
    private String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().toUpperCase().replaceAll("\\s+", " ");
    }

    /**
     * Formats amount for display.
     */
    private String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return "$0";
        }
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            return "($" + amount.abs().toPlainString() + ")";
        }
        return "$" + amount.toPlainString();
    }

    /**
     * Helper method to get text value from JsonNode.
     */
    private String getTextValue(JsonNode node, String fieldName) {
        if (node == null || !node.has(fieldName)) {
            return null;
        }
        JsonNode field = node.get(fieldName);
        return field.isNull() ? null : field.asText();
    }

    /**
     * Helper method to get BigDecimal value from JsonNode.
     */
    private BigDecimal getBigDecimalValue(JsonNode node, String fieldName) {
        String value = getTextValue(node, fieldName);
        if (value == null || value.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid number format for field {}: {}", fieldName, value);
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
        return "true".equalsIgnoreCase(value) || "X".equals(value);
    }

    // Data classes
    @Data
    @Builder
    public static class ScheduleFData {
        private String sequenceNum;
        private String businessName;
        private String principalProduct;
        private String ein;
        private Boolean materiallyParticipated;
        private BigDecimal grossIncome;
        private BigDecimal totalExpenses;
        private BigDecimal netFarmProfitLoss;
    }

    @Data
    @Builder
    public static class Form8582Data {
        private List<PassiveActivity> activities;
    }

    @Data
    @Builder
    public static class PassiveActivity {
        private String activityName;
        private BigDecimal currentYearLoss;
        private BigDecimal overallLoss;
    }

    @Data
    @Builder
    public static class ValidationResult {
        private boolean passed;
        private List<ValidationError> errors;
        private List<ValidationWarning> warnings;
    }

    @Data
    @Builder
    public static class ValidationError {
        private String errorCode;
        private String message;
        private String severity;
        private String scheduleFSequence;
        private String activityName;
        private BigDecimal calculatedAmount;
        private BigDecimal expectedAmount;
    }

    @Data
    @Builder
    public static class ValidationWarning {
        private String warningCode;
        private String message;
    }
}
