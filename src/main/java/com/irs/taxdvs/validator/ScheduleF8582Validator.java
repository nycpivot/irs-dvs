package com.irs.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Validator for Schedule F to Form 8582 matching per IRS rules.
 * <p>
 * This validator implements the following rules:
 * 1. Match business names between Schedule F and Form 8582
 * 2. Calculate losses as: Line 9 (Gross Income) - Line 33 (Total Expenses)
 * 3. Verify calculated losses match Form 8582 Current Year Net Loss
 * 4. Validate passive activity treatment for non-material participation
 * 5. Verify NetFarmProfitLossAmt shows $0 for passive losses
 */
@Slf4j
@Component
public class ScheduleF8582Validator {

    private static final String SCH_F_PATH = "/IRS1040ScheduleF";
    private static final String FORM_8582_PATH = "/IRS8582";
    private static final String BUSINESS_NAME_PATH = "/FarmProprietorName/BusinessNameLine1Txt";
    private static final String PRINCIPAL_PRODUCT_PATH = "/PrincipalProductDesc";
    private static final String GROSS_INCOME_PATH = "/FarmIncomeCashMethodGrp/GrossIncomeAmt";
    private static final String TOTAL_EXPENSES_PATH = "/FarmExpensesGrp/TotalExpensesAmt";
    private static final String NET_PROFIT_LOSS_PATH = "/FarmExpensesGrp/NetFarmProfitLossAmt";
    private static final String MATERIAL_PARTICIPATION_PATH = "/MateriallyParticipatedInd";
    private static final String PASSIVE_WRKSHT_PATH = "/ParentWrkshtPassiveGrp/WrkshtPassiveGrp";
    private static final String ACTIVITY_NAME_PATH = "/NonParticipateActivityNm";
    private static final String CURRENT_YEAR_LOSS_PATH = "/CurrentYearNetLossAmt";

    /**
     * Validates the entire tax return payload for Schedule F to Form 8582 matching.
     *
     * @param payload The full IRS JSON tax return payload
     * @return ValidationResult containing pass/fail status and detailed findings
     */
    public ValidationResult validate(JsonNode payload) {
        log.info("Starting Schedule F to Form 8582 validation");

        ValidationResult.ValidationResultBuilder resultBuilder = ValidationResult.builder()
                .passed(true)
                .validationDetails(new ArrayList<>());

        try {
            // Extract forms from payload
            JsonNode body = payload.get("body");
            if (body == null || !body.has("forms")) {
                return resultBuilder
                        .passed(false)
                        .errorMessage("Invalid payload structure: missing body or forms")
                        .build();
            }

            JsonNode forms = body.get("forms");

            // Extract all Schedule F forms
            List<JsonNode> scheduleFForms = extractFormsByName(forms, "IRS1040ScheduleF");
            log.info("Found {} Schedule F forms", scheduleFForms.size());

            // Extract Form 8582
            Optional<JsonNode> form8582 = extractFormByName(forms, "IRS8582");
            if (form8582.isEmpty()) {
                log.warn("No Form 8582 found in payload");
                // If no 8582, verify all Schedule Fs have material participation
                return validateNoForm8582Scenario(scheduleFForms, resultBuilder);
            }

            // Extract passive activities from Form 8582
            List<PassiveActivity> passiveActivities = extractPassiveActivities(form8582.get());
            log.info("Found {} passive activities on Form 8582", passiveActivities.size());

            // Validate each Schedule F
            for (JsonNode scheduleF : scheduleFForms) {
                validateScheduleF(scheduleF, passiveActivities, resultBuilder);
            }

        } catch (Exception e) {
            log.error("Validation error", e);
            resultBuilder.passed(false).errorMessage("Validation error: " + e.getMessage());
        }

        ValidationResult result = resultBuilder.build();
        log.info("Validation completed: passed={}", result.isPassed());
        return result;
    }

    /**
     * Validates a single Schedule F against Form 8582 passive activities.
     */
    private void validateScheduleF(JsonNode scheduleF, List<PassiveActivity> passiveActivities,
                                         ValidationResult.ValidationResultBuilder resultBuilder) {
        String businessName = extractBusinessName(scheduleF);
        String principalProduct = extractPrincipalProduct(scheduleF);
        boolean materialParticipation = isMaterialParticipation(scheduleF);

        log.debug("Validating Schedule F: {} ({}), material participation: {}",
                businessName, principalProduct, materialParticipation);

        // Extract financial data
        BigDecimal grossIncome = extractAmount(scheduleF, GROSS_INCOME_PATH);
        BigDecimal totalExpenses = extractAmount(scheduleF, TOTAL_EXPENSES_PATH);
        BigDecimal netProfitLoss = extractAmount(scheduleF, NET_PROFIT_LOSS_PATH);

        // Calculate actual profit/loss: Line 9 - Line 33
        BigDecimal calculatedProfitLoss = grossIncome.subtract(totalExpenses);

        ValidationDetail detail = ValidationDetail.builder()
                .businessName(businessName)
                .principalProduct(principalProduct)
                .materialParticipation(materialParticipation)
                .grossIncome(grossIncome)
                .totalExpenses(totalExpenses)
                .calculatedProfitLoss(calculatedProfitLoss)
                .reportedNetProfitLoss(netProfitLoss)
                .build();

        // If not material participation, must be on Form 8582
        if (!materialParticipation) {
            validatePassiveActivity(detail, passiveActivities, resultBuilder);
        } else {
            // Material participation - verify net profit/loss matches calculation
            if (calculatedProfitLoss.compareTo(netProfitLoss) != 0) {
                detail.setPassed(false);
                detail.setValidationMessage(
                        String.format("Material participation activity: NetProfitLossAmt (%s) does not match calculated (%s)",
                                netProfitLoss, calculatedProfitLoss));
                resultBuilder.passed(false);
            } else {
                detail.setPassed(true);
                detail.setValidationMessage("Material participation activity - no Form 8582 matching required");
            }
        }

        resultBuilder.validationDetails.add(detail);
    }

    /**
     * Validates a passive activity against Form 8582.
     */
    private void validatePassiveActivity(ValidationDetail detail,
                                               List<PassiveActivity> passiveActivities,
                                               ValidationResult.ValidationResultBuilder resultBuilder) {
        // Find matching passive activity by name
        Optional<PassiveActivity> matchingActivity = passiveActivities.stream()
                .filter(a -> a.getActivityName().equalsIgnoreCase(detail.getPrincipalProduct()))
                .findFirst();

        if (matchingActivity.isEmpty()) {
            detail.setPassed(false);
            detail.setValidationMessage(
                    String.format("Passive activity '%s' not found on Form 8582", detail.getPrincipalProduct()));
            resultBuilder.passed(false);
            return;
        }

        PassiveActivity activity = matchingActivity.get();
        detail.setForm8582ActivityName(activity.getActivityName());
        detail.setForm8582LossAmount(activity.getCurrentYearLoss());

        // Validate loss calculation matches
        BigDecimal calculatedLoss = detail.getCalculatedProfitLoss();
        BigDecimal form8582Loss = activity.getCurrentYearLoss();

        // For losses, compare absolute values
        if (calculatedLoss.compareTo(BigDecimal.ZERO) < 0) {
            if (calculatedLoss.abs().compareTo(form8582Loss) != 0) {
                detail.setPassed(false);
                detail.setValidationMessage(
                        String.format("Loss mismatch: Calculated (%s) != Form 8582 (%s)",
                                calculatedLoss, form8582Loss.negate()));
                resultBuilder.passed(false);
                return;
            }

            // Validate NetFarmProfitLossAmt shows $0 for passive losses
            if (detail.getReportedNetProfitLoss().compareTo(BigDecimal.ZERO) != 0) {
                detail.setPassed(false);
                detail.setValidationMessage(
                        String.format("Passive loss: NetFarmProfitLossAmt should be $0 but is %s",
                                detail.getReportedNetProfitLoss()));
                resultBuilder.passed(false);
                return;
            }

            detail.setPassed(true);
            detail.setValidationMessage(
                    String.format("Passive loss correctly matched: %s on Form 8582", form8582Loss.negate()));
        } else {
            // Income or break-even - should not be on Form 8582 as a loss
            detail.setPassed(false);
            detail.setValidationMessage(
                    String.format("No-loss activity should not be on Form 8582 as a loss: %s", calculatedLoss));
            resultBuilder.passed(false);
        }
    }

    /**
     * Validates scenario where no Form 8582 exists.
     */
    private ValidationResult validateNoForm8582Scenario(List<JsonNode> scheduleFForms,
                                                            ValidationResult.ValidationResultBuilder resultBuilder) {
        // All Schedule Fs must have material participation or no losses
        for (JsonNode scheduleF : scheduleFForms) {
            String businessName = extractBusinessName(scheduleF);
            boolean materialParticipation = isMaterialParticipation(scheduleF);
            BigDecimal grossIncome = extractAmount(scheduleF, GROSS_INCOME_PATH);
            BigDecimal totalExpenses = extractAmount(scheduleF, TOTAL_EXPENSES_PATH);
            BigDecimal calculatedProfitLoss = grossIncome.subtract(totalExpenses);

            if (!materialParticipation && calculatedProfitLoss.compareTo(BigDecimal.ZERO) < 0) {
                ValidationDetail detail = ValidationDetail.builder()
                        .businessName(businessName)
                        .passed(false)
                        .validationMessage(
                                String.format("Passive loss (%s) requires Form 8582 but none found",
                                        calculatedProfitLoss))
                        .build();
                resultBuilder.validationDetails.add(detail);
                resultBuilder.passed(false);
            }
        }
        return resultBuilder.build();
    }

    /**
     * Extracts passive activities from Form 8582.
     */
    private List<PassiveActivity> extractPassiveActivities(JsonNode form8582) {
        List<PassiveActivity> activities = new ArrayList<>();

        JsonNode lineItems = form8582.get("lineItems");
        if (lineItems == null || !lineItems.isArray()) {
            return activities;
        }

        for (JsonNode lineItem : lineItems) {
            String lineName = lineItem.path("lineNameTxt").asText("");
            if (lineName.contains(PASSIVE_WRKSHT_PATH)) {
                JsonNode worksheets = lineItem.path("lineItems");
                if (worksheets.isArray()) {
                    for (JsonNode worksheet : worksheets) {
                        PassiveActivity activity = extractPassiveActivityFromWorksheet(worksheet);
                        if (activity != null) {
                            activities.add(activity);
                        }
                    }
                }
            }
        }

        return activities;
    }

    /**
     * Extracts a single passive activity from a worksheet node.
     */
    private PassiveActivity extractPassiveActivityFromWorksheet(JsonNode worksheet) {
        JsonNode lineItems = worksheet.path("lineItems");
        if (!lineItems.isArray()) {
            return null;
        }

        String activityName = null;
        BigDecimal currentYearLoss = BigDecimal.ZERO;

        for (JsonNode lineItem : lineItems) {
            String lineName = lineItem.path("lineNameTxt").asText("");
            if (lineName.endsWith(ACTIVITY_NAME_PATH)) {
                activityName = lineItem.path("perReturnValueTxt").asText("");
            } else if (lineName.endsWith(CURRENT_YEAR_LOSS_PATH)) {
                String value = lineItem.path("perReturnValueTxt").asText("0");
                currentYearLoss = new BigDecimal(value);
            }
        }

        if (activityName != null && !activityName.isBlank()) {
            return PassiveActivity.builder()
                    .activityName(activityName)
                    .currentYearLoss(currentYearLoss)
                    .build();
        }
        return null;
    }

    /**
     * Extracts all forms with a specific form number.
     */
    private List<JsonNode> extractFormsByName(JsonNode forms, String formNum) {
        List<JsonNode> result = new ArrayList<>();
        if (!forms.isArray()) {
            return result;
        }

        for (JsonNode form : forms) {
            if (formNum.equals(form.path("formNum").asText(""))) {
                result.add(form);
            }
        }
        return result;
    }

    /**
     * Extracts a single form by name.
     */
    private Optional<JsonNode> extractFormByName(JsonNode forms, String formNum) {
        if (!forms.isArray()) {
            return Optional.empty();
        }

        for (JsonNode form : forms) {
            if (formNum.equals(form.path("formNum").asText(""))) {
                return Optional.of(form);
            }
        }
        return Optional.empty();
    }

    private String extractBusinessName(JsonNode scheduleF) {
        return extractTextValue(scheduleF, BUSINESS_NAME_PATH);
    }

    private String extractPrincipalProduct(JsonNode scheduleF) {
        return extractTextValue(scheduleF, PRINCIPAL_PRODUCT_PATH);
    }

    private boolean isMaterialParticipation(JsonNode scheduleF) {
        String value = extractTextValue(scheduleF, MATERIAL_PARTICIPATION_PATH);
        return "true".equalsIgnoreCase(value) || "X".equals(value);
    }

    private String extractTextValue(JsonNode node, String path) {
        JsonNode lineItems = node.path("lineItems");
        if (!lineItems.isArray()) {
            return "";
        }

        for (JsonNode lineItem : lineItems) {
            String lineName = lineItem.path("lineNameTxt").asText("");
            if (lineName.endsWith(path)) {
                return lineItem.path("perReturnValueTxt").asText("");
            }
            // Check nested lineItems
            if (lineItem.has("lineItems")) {
                String nestedValue = extractTextValue(lineItem, path);
                if (!nestedValue.isBlank()) {
                    return nestedValue;
                }
            }
        }
        return "";
    }

    private BigDecimal extractAmount(JsonNode node, String path) {
        String value = extractTextValue(node, path);
        if (value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid number format for path {}: {}", path, value);
            return BigDecimal.ZERO;
        }
    }

    /**
     * Represents a passive activity from Form 8582.
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PassiveActivity {
        private String activityName;
        private BigDecimal currentYearLoss;
    }

    /**
     * Represents validation details for a single Schedule F.
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ValidationDetail {
        private String businessName;
        private String principalProduct;
        private boolean materialParticipation;
        private BigDecimal grossIncome;
        private BigDecimal totalExpenses;
        private BigDecimal calculatedProfitLoss;
        private BigDecimal reportedNetProfitLoss;
        private String form8582ActivityName;
        private BigDecimal form8582LossAmount;
        private boolean passed;
        private String validationMessage;
    }

    /**
     * Represents the overall validation result.
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ValidationResult {
        private boolean passed;
        private String errorMessage;
        private List<ValidationDetail> validationDetails;
    }
}