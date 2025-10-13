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
 * Validator for Schedule F and Form 8582 matching and loss calculation.
 * 
 * Business Requirements:
 * 1. Match business names between Schedule F and Form 8582
 * 2. Validate loss amounts calculation: Line 9 (Gross Income) - Line 33 (Total Expenses)
 * 3. Verify Schedule F with no material participation shows $0 net profit/loss
 * 4. Confirm Form 8582 captures correct loss amounts for passive activities
 * 
 * IRS Rules (Form 8582 Instructions):
 * - Passive activities include trade/business without material participation
 * - Rental activities are generally passive regardless of participation
 * - Losses from passive activities must be tracked on Form 8582
 * - Material participation excludes activity from passive loss limitations
 */
@Component
public class ScheduleF8582Validator {

    private static final String SCHEMULE_F_PATH = "/IRS1040ScheduleF";
    private static final String FORM_8582_PATH = "/IRS8582";
    private static final String BUSINESS_NAME_PATH = "/IRS1040ScheduleF/FarmProprietorName/BusinessNameLine1Txt";
    private static final String PRINCIPAL_PRODUCT_PATH = "/IRS1040ScheduleF/PrincipalProductDesc";
    private static final String MATERIAL_PARTICIPATION_PATH = "/IRS1040ScheduleF/MateriallyParticipatedInd";
    private static final String GROSS_INCOME_PATH = "/IRS1040ScheduleF/FarmIncomeCashMethodGrp/GrossIncomeAmt";
    private static final String TOTAL_EXPENSES_PATH = "/IRS1040ScheduleF/FarmExpensesGrp/TotalExpensesAmt";
    private static final String NET_FARM_PROFIT_LOSS_PATH = "/IRS1040ScheduleF/FarmExpensesGrp/NetFarmProfitLossAmt";
    private static final String FORM_8582_ACTIVITY_NAME_PATH = "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/NonParticipateActivityNm";
    private static final String FORM_8582_CURRENT_YEAR_LOSS_PATH = "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/CurrentYearNetLossAmt";
    private static final String FORM_8582_OVERALL_LOSS_PATH = "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/OverallLossAmt";

    /**
     * Validates the entire tax return payload for Schedule F and Form 8582 consistency.
     * 
     * @param payload The complete tax return JSON payload
     * @return ValidationResult containing pass/fail status and detailed findings
     */
    public ValidationResult validate(JsonNode payload) {
        ValidationResult.ValidationResultBuilder resultBuilder = ValidationResult.builder();
        List<ValidationError> errors = new ArrayList<>();
        List<ValidationWarning> warnings = new ArrayList<>();
        List<ActivityMatchResult> matchResults = new ArrayList<>();

        try {
            // Extract forms array
            JsonNode body = payload.get("body");
            if (body == null || !body.has("forms")) {
                errors.add(ValidationError.builder()
                        .errorCode("MISSING_BODY")
                        .errorMessage("Payload missing 'body' or 'forms' section")
                        .severity("CRITICAL")
                        .build());
                return resultBuilder.passed(false).errors(errors).build();
            }

            JsonNode forms = body.get("forms");

            // Extract all Schedule F forms
            List<ScheduleFData> scheduleFForms = extractScheduleFForms(forms);
            
            // Extract Form 8582 data
            Form8582Data form8582Data = extractForm8582Data(forms);

            if (scheduleFForms.isEmpty()) {
                warnings.add(ValidationWarning.builder()
                        .warningCode("NO_SCHEDULE_F")
                        .warningMessage("No Schedule F forms found in payload")
                        .build());
            }

            if (form8582Data == null || form8582Data.getActivities().isEmpty()) {
                warnings.add(ValidationWarning.builder()
                        .warningCode("NO_FORM_8582")
                        .warningMessage("No Form 8582 passive activities found in payload")
                        .build());
            }

            // Validate each Schedule F
            for (ScheduleFData scheduleF : scheduleFForms) {
                // Validation 1 & 2: Loss calculation and passive activity check
                validateLossCalculation(scheduleF, errors, warnings);

                // Validation 3: Match with Form 8582 if passive activity
                if (!scheduleF.isMateriallyParticipated() && form8582Data != null) {
                    ActivityMatchResult matchResult = matchWithForm8582(scheduleF, form8582Data);
                    matchResults.add(matchResult);

                    if (!matchResult.isNameMatched()) {
                        errors.add(ValidationError.builder()
                                .errorCode("NAME_MISMATCH")
                                .errorMessage(String.format(
                                        "Schedule F activity '%s' not found in Form 8582 passive activities",
                                        scheduleF.getPrincipalProduct()))
                                .severity("HIGH")
                                .formNumber("IRS1040ScheduleF")
                                .sequenceNumber(scheduleF.getSequenceNumber())
                                .build());
                    }

                    if (!matchResult.isLossAmountMatched()) {
                        errors.add(ValidationError.builder()
                                .errorCode("LOSS_AMOUNT_MISMATCH")
                                .errorMessage(String.format(
                                        "Loss amount mismatch for '%s': Calculated=$%s, Form 8582=$%s",
                                        scheduleF.getPrincipalProduct(),
                                        matchResult.getCalculatedLoss(),
                                        matchResult.getForm8582Loss()))
                                .severity("HIGH")
                                .formNumber("IRS1040ScheduleF")
                                .sequenceNumber(scheduleF.getSequenceNumber())
                                .build());
                    }
                }
            }

            // Check for material participation activities in Form 8582
            if (form8582Data != null) {
                validateNoMaterialParticipationInForm8582(scheduleFForms, form8582Data, warnings);
            }

            boolean passed = errors.isEmpty();

            return resultBuilder
                    .passed(passed)
                    .errors(errors)
                    .warnings(warnings)
                    .matchResults(matchResults)
                    .summary(generateSummary(passed, errors.size(), warnings.size(), matchResults.size()))
                    .build();

        } catch (Exception e) {
            errors.add(ValidationError.builder()
                    .errorCode("VALIDATION_EXCEPTION")
                    .errorMessage("Validation failed: " + e.getMessage())
                    .severity("CRITICAL")
                    .build());
            return resultBuilder.passed(false).errors(errors).build();
        }
    }

    /**
     * Extracts all Schedule F forms from the payload.
     */
    private List<ScheduleFData> extractScheduleFForms(JsonNode forms) {
        List<ScheduleFData> scheduleFForms = new ArrayList<>();

        if (forms.isArray()) {
            for (JsonNode form : forms) {
                String formNum = getTextValue(form, "formNum");
                if ("IRS1040ScheduleF".equals(formNum)) {
                    ScheduleFData scheduleFData = parseScheduleF(form);
                    if (scheduleFData != null) {
                        scheduleFForms.add(scheduleFData);
                    }
                }
            }
        }

        return scheduleFForms;
    }

    /**
     * Parses a single Schedule F form.
     */
    private ScheduleFData parseScheduleF(JsonNode form) {
        String sequenceNumber = getTextValue(form, "sequenceNum");
        String businessName = findLineItemValue(form, BUSINESS_NAME_PATH);
        String principalProduct = findLineItemValue(form, PRINCIPAL_PRODUCT_PATH);
        boolean materiallyParticipated = Boolean.parseBoolean(findLineItemValue(form, MATERIAL_PARTICIPATION_PATH));
        BigDecimal grossIncome = parseAmount(findLineItemValue(form, GROSS_INCOME_PATH));
        BigDecimal totalExpenses = parseAmount(findLineItemValue(form, TOTAL_EXPENSES_PATH));
        BigDecimal netFarmProfitLoss = parseAmount(findLineItemValue(form, NET_FARM_PROFIT_LOSS_PATH));

        return ScheduleFData.builder()
                .sequenceNumber(sequenceNumber)
                .businessName(businessName)
                .principalProduct(principalProduct)
                .materiallyParticipated(materiallyParticipated)
                .grossIncome(grossIncome)
                .totalExpenses(totalExpenses)
                .netFarmProfitLoss(netFarmProfitLoss)
                .build();
    }

    /**
     * Extracts Form 8582 data from the payload.
     */
    private Form8582Data extractForm8582Data(JsonNode forms) {
        if (forms.isArray()) {
            for (JsonNode form : forms) {
                String formNum = getTextValue(form, "formNum");
                if ("IRS8582".equals(formNum)) {
                    return parseForm8582(form);
                }
            }
        }
        return null;
    }

    /**
     * Parses Form 8582 data.
     */
    private Form8582Data parseForm8582(JsonNode form) {
        List<PassiveActivity> activities = new ArrayList<>();

        JsonNode lineItems = form.get("lineItems");
        if (lineItems != null && lineItems.isArray()) {
            for (JsonNode lineItem : lineItems) {
                String lineName = getTextValue(lineItem, "lineNameTxt");
                
                // Look for ParentWrkshtPassiveGrp
                if ("/IRS8582/ParentWrkshtPassiveGrp".equals(lineName)) {
                    JsonNode nestedLineItems = lineItem.get("lineItems");
                    if (nestedLineItems != null && nestedLineItems.isArray()) {
                        for (JsonNode nestedItem : nestedLineItems) {
                            String nestedName = getTextValue(nestedItem, "lineNameTxt");
                            if ("/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp".equals(nestedName)) {
                                PassiveActivity activity = parsePassiveActivity(nestedItem);
                                if (activity != null) {
                                    activities.add(activity);
                                }
                            }
                        }
                    }
                }
            }
        }

        return Form8582Data.builder().activities(activities).build();
    }

    /**
     * Parses a single passive activity from Form 8582.
     */
    private PassiveActivity parsePassiveActivity(JsonNode activityNode) {
        String activityName = null;
        BigDecimal currentYearLoss = BigDecimal.ZERO;
        BigDecimal overallLoss = BigDecimal.ZERO;

        JsonNode lineItems = activityNode.get("lineItems");
        if (lineItems != null && lineItems.isArray()) {
            for (JsonNode lineItem : lineItems) {
                String lineName = getTextValue(lineItem, "lineNameTxt");
                String value = getTextValue(lineItem, "perReturnValueTxt");

                if (FORM_8582_ACTIVITY_NAME_PATH.equals(lineName)) {
                    activityName = value;
                } else if (FORM_8582_CURRENT_YEAR_LOSS_PATH.equals(lineName)) {
                    currentYearLoss = parseAmount(value);
                } else if (FORM_8582_OVERALL_LOSS_PATH.equals(lineName)) {
                    overallLoss = parseAmount(value);
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
     * Validates loss calculation for a Schedule F.
     * Rule: Line 9 (Gross Income) - Line 33 (Total Expenses) = Net Loss
     * If no material participation and loss, NetFarmProfitLossAmt should be $0
     */
    private void validateLossCalculation(ScheduleFData scheduleF, 
                                                List<ValidationError> errors,
                                                List<ValidationWarning> warnings) {
        BigDecimal calculatedNet = scheduleF.getGrossIncome().subtract(scheduleF.getTotalExpenses());
        BigDecimal reportedNet= scheduleF.getNetFarmProfitLoss();

        // Check if calculated net is a loss
        boolean isLoss = calculatedNetcompareTo(BigDecimal.ZERO) < 0;

        // Validation 1: If no material participation and loss, reported net should be $0
        if (!scheduleF.isMateriallyParticipated() && isLoss) {
            if (reportedNet.compareTo(BigDecimal.ZERO) != 0) {
                errors.add(ValidationError.builder()
                        .errorCode("PASSIVE_LOSS_NOT_ZERO")
                        .errorMessage(String.format(
                                "Schedule F '%s' has no material participation and loss but NetFarmProfitLossAmt = $%s (expected $0)",
                                scheduleF.getPrincipalProduct(),
                                reportedNet))
                        .severity("MEDIUM")
                        .formNumber("IRS1040ScheduleF")
                        .sequenceNumber(scheduleF.getSequenceNumber())
                        .build());
            }
        }

        // Validation 2: Calculation accuracy
        if (calculatedNet.compareTo(reportedNet) != 0 && scheduleF.isMateriallyParticipated()) {
            warnings.add(ValidationWarning.builder()
                    .warningCode("CALCULATION_MISMATCH")
                    .warningMessage(String.format(
                            "Schedule F '%s': Calculated net ($%s) != Reported net ($%s)",
                            scheduleF.getPrincipalProduct(),
                            calculatedNet,
                            reportedNet
              )†             .build());
        }
    }

    /**
     * Matches a Schedule F with Form 8582 passive activities.
     */
    private ActivityMatchResult matchWithForm8582(ScheduleFData scheduleF, Form8582Data form8582Data) {
        String scheduleFName = scheduleF.getPrincipalProduct();
        BigDecimal calculatedLoss = scheduleF.getGrossIncome().subtract(scheduleF.getTotalExpenses());
        
        // Only consider losses (negative amounts)
        if (calculatedLoss.compareTo(BigDecimal.ZERO) >= 0) {
            return ActivityMatchResult.builder()
                    .scheduleFActivity(scheduleFName)
                    .nameMatched(true)
                    .lossAmountMatched(true)
                    .calculatedLoss(calculatedLoss)
                    .form8582Loss(BigDecimal.ZERO)
                    .matchedActivity(null)
                    .notes("No loss to match - activity has profit or breakeven")
                    .build();
        }

        // Convert to positive for comparison with Form 8582
        BigDecimal lossAmount = calculatedLoss.abs();

        // Find matching activity in Form 8582
        Optional<PassiveActivity> matchedActivity = form8582Data.getActivities().stream()
                .filter(a -> scheduleFName.equalsIgnoreCase(a.getActivityName()))
                .findFirst();

        if (matchedActivity.isPresent()) {
            PassiveActivity activity = matchedActivity.get();
            BigDecimal form8582Loss = activity.getOverallLoss();
            
            // Compare amounts (tolerance of $0.01 for rounding)
            boolean amountMatches = lossAmount.subtract(form8582Loss).abs().compareTo(new BigDecimal("0.01")) <= 0;

            return ActivityMatchResult.builder()
                    .scheduleFActivity(scheduleFName)
                    .nameMatched(true)
                    .lossAmountMatched(amountMatches)
                    .calculatedLoss(lossAmount)
                    .form8582Loss(form8582Loss)
                    .matchedActivity(activity.getActivityName())
                    .notes(amountMatches ? "Amounts match" : "Amount discrepancy detected")
                    .build();
        } else {
            return ActivityMatchResult.builder()
                    .scheduleFActivity(scheduleFName)
                    .nameMatched(false)
                    .lossAmountMatched(false)
                    .calculatedLoss(lossAmount)
                    .form8582Loss(BigDecimal.ZERO)
                    .matchedActivity(null)
                    .notes("No matching activity found in Form 8582")
                    .build();
        }
    }

    /**
     * Validates that material participation activities are not in Form 8582.
     */
    private void validateNoMaterialParticipationInForm8582(List<ScheduleFData> scheduleFForms,
                                                                    Form8582Data form8582Data,
                                                                    List<ValidationWarning> warnings) {
        Set<String> materialParticipationActivities = scheduleFForms.stream()
                .filter(ScheduleFData::isMateriallyParticipated)
                .map(ScheduleFData::getPrincipalProduct)
                .collect(Collectors.toSet());

        for (PassiveActivity activity : form8582Data.getActivities()) {
            if (materialParticipationActivities.contains(activity.getActivityName())) {
                warnings.add(ValidationWarning.builder()
                        .warningCode("MATERIAL_PARTICIPATION_IN_8582")
                        .warningMessage(String.format(
                                "Activity '%s' has material participation but appears in Form 8582 passive activities",
                                activity.getActivityName()))
                        .build());
            }
        }
    }

    /**
     * Finds a line item value by path.
     */
    private String findLineItemValue(JsonNode form, String path) {
        JsonNode lineItems = form.get("lineItems");
        if (lineItems != null && lineItems.isArray()) {
            for (JsonNode lineItem : lineItems) {
                String value = findLineItemValueRecursive(lineItem, path);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private String findLineItemValueRecursive(JsonNode node, String path) {
        String lineName = getTextValue(node, "lineNameTxt");
        if (path.equals(lineName)) {
            return getTextValue(node, "perReturnValueTxt");
        }

        JsonNode nestedLineItems = node.get("lineItems");
        if (nestedLineItems != null && nestedLineItems.isArray()) {
            for (JsonNode child : nestedLineItems) {
                String value = findLineItemValueRecursive(child, path);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private String getTextValue(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field != null && !field.isNull() ? field.asText() : null;
    }

    private BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private String generateSummary(boolean passed, int errorCount, int warningCount, int matchCount) {
        return String.format(
                "Validation %s: %d error(s), %d warning(s), %d activity match(s) processed",
                passed ? "PASSED" : "FAILED",
                errorCount,
                warningCount,
                matchCount
        );
    }

    // Data models
    @Data
    @Builder
    public static class ScheduleFData {
        private String sequenceNumber;
        private String businessName;
        private String principalProduct;
        private boolean materiallyParticipated;
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
        private List<ActivityMatchResult> matchResults;
        private String summary;
    }

    @Data
    @Fuilder
    public static class ValidationError {
        private String errorCode;
        private String errorMessage;
        private String severity;
        private String formNumber;
        private String sequenceNumber;
    }

    @Data
    @Builder
    public static class ValidationWarning {
        private String warningCode;
        private String warningMessage;
    }

    @Data
    @Fuilder
    public static class ActivityMatchResult {
        private String scheduleFActivity;
        private boolean nameMatched;
        private boolean lossAmountMatched;
        private BigDecimal calculatedLoss;
        private BigDecimal form8582Loss;
        private String matchedActivity;
        private String notes;
    }
}