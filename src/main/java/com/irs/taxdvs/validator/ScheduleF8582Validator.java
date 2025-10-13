package com.irs.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Component
public class ScheduleF8582Validator {

    /**
     * Validates that Schedule F activities correctly match with Form 8582
     * based on material participation status and loss amounts.
     *
     * @param payload The full tax return JSON payload
     * @return ValidationResult containing pass/fail and errors
     */
    public ValidationResult validateScheduleFToForm8582(JsonNode payload) {
        ValidationResult result = ValidationResult.builder()
                .passed(true)
                .errors(new ArrayList<>())
                .build();

        try {
            // Extract forms from payload
            JsonNode body = payload.get("body");
            if (body == null || !body.has("forms")) {
                result.getErrors().add("Missing 'body.forms' in payload");
                result.setPassed(false);
                return result;
            }

            JsonNode forms = body.get("forms");

            // Parse Schedule F activities
            List<ScheduleFActivity> scheduleFActivities = parseScheduleFActivities(forms);

            // Parse Form 8582 activities
            List<Form8582Activity> form8582Activities = parseForm8582Activities(forms);

            // Validate material participation rules
            validateMaterialParticipationRules(scheduleFActivities, form8582Activities, result);

            // Validate business name matching
            validateBusinessNameMatching(scheduleFActivities, form8582Activities, result);

            // Validate loss amount calculations
            validateLossAmounts(scheduleFActivities, form8582Activities, result);

            // Validate NetFarSPrUd¬ÙLossAmt for losses
            validateNetFarmProfitLossAmt(scheduleFActivities, form8582Activities, result);

        } catch (Exception e) {
            result.getErrors().add("Validation exception: " + e.getMessage());
            result.setPassed(false);
        }

        return result;
    }

    /**
     * Parses all Schedule F activities from the forms array
     */
    private List<ScheduleFActivity> parseScheduleFActivities(JsonNode forms) {
        List<ScheduleFActivity> activities = new ArrayList<>();

        for (JsonNode form : forms) {
            String formNum = getTextValue(form, "formNum");
            if ("IRS1040ScheduleF".equals(formNum)) {
                ScheduleFActivity activity = parseSingleScheduleF(form);
                if (activity != null) {
                    activities.add(activity);
                }
            }
        }

        return activities;
    }

    /**
     * Parses a single Schedule F form
     */
    private ScheduleFActivity parseSingleScheduleF(JsonNode form) {
        String businessName = null;
        String principalProduct = null;
        BigDecimal grossIncome = BigDecimal.ZERO;
        BigDecimal totalExpenses = BigDecimal.ZERO;
        BigDecimal netProfitLoss = BigDecimal.ZERO;
        boolean materiallyParticipated = false;

        JsonNode lineItems = form.get("lineItems");
        if (lineItems != null) {
            for (JsonNode lineItem : lineItems) {
                String lineName = getTextValue(lineItem, "lineNameTxt");

                // Business name
                if (lineName.contains("PrincipalProductDesc")) {
                    principalProduct = getTextValue(lineItem, "perReturnValueTxt");
                }

                // Material participation
                if (lineName.contains("MateriallyParticipatedInd")) {
                    String value = getTextValue(lineItem, "perReturnValueTxt");
                    materiallyParticipated = "true".equalsIgnoreCase(value);
                }

                // Gross income (Line 9)
                if (lineName.contains("FarmIncomeCashMethodGrp/GrossIncomeAmt")) {
                    grossIncome = getBigDecimalValue(lineItem, "perReturnValueTxt");
                }

                // Total expenses (Line 33)
                if (lineName.contains("FarmExpensesGrp/TotalExpensesAmt")) {
                    totalExpenses = getBigDecimalValue(lineItem, "perReturnValueTxt");
                }

                // Net profit/loss (Line 34)
                if (lineName.contains("FarmExpensesGrp/NetFarSPofitLossAmt")) {
                    netProfitLoss = getBigDecimalValue(lineItem, "perReturnValueTxt");
                }
            }
        }

        // Extract business name from nested structure
        businessName = extractBusinessName(lineItems);

        if (businessName == null && principalProduct != null) {
            businessName = principalProduct;
        }

        return ScheduleFActivity.builder()
                .businessName(businessName)
                .principalProduct(principalProduct)
                .grossIncome(grossIncome)
                .totalExpenses(totalExpenses)
                .netProfitLoss(netProfitLoss)
                .materiallyParticipated(materiallyParticipated)
                .build();
    }

    /**
     * Extracts business name from nested BusinessNameLine1Txt
     */
    private String extractBusinessName(JsonNode lineItems) {
        if (lineItems == null) return null;

        for (JsonNode lineItem : lineItems) {
            String lineName = getTextValue(lineItem, "lineNameTxt");
            if (lineName.contains("FarmProprietorName")) {
                JsonNode nestedItems = lineItem.get("lineItems");
                if (nestedItems != null) {
                    for (JsonNode nested : nestedItems) {
                        String nestedLine = getTextValue(nested, "lineNameTxt");
                        if (nestedLine.contains("BusinessNameLine1Txt")) {
                            return getTextValue(nested, "perReturnValueTxt");
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Parses Form 8582 activities from the forms array
     */
    private List<Form8582Activity> parseForm8582Activities(JsonNode forms) {
        List<Form8582Activity> activities = new ArrayList<>();

        for (JsonNode form : forms) {
            String formNum = getTextValue(form, "formNum");
            if ("IRS8582".equals(formNum)) {
                activities.addAll(parseForm8582Worksheets(form));
            }
        }

        return activities;
    }

    /**
     * Parses worksheets from Form 8582 to extract activity details
     */
    private List<Form8582Activity> parseForm8582Worksheets(JsonNode form) {
        List<Form8582Activity> activities = new ArrayList<>();

        JsonNode lineItems = form.get("lineItems");
        if (lineItems == null) return activities;

        for (JsonNode lineItem : lineItems) {
            String lineName = getTextValue(lineItem, "lineNameTxt");

            // Parse ParentWrkshtPassiveGrp for activity details
            if (lineName.contains("ParentWrkshtPassiveGrp")) {
                activities.addAll(parseWorksheetPassiveGroup(lineItem));
            }
        }

        return activities;
    }

    /**
     * Parses worksheet passive group to extract individual activities
     */
    private List<Form8582Activity> parseWorksheetPassiveGroup(JsonNode parentGroup) {
        List<Form8582Activity> activities = new ArrayList<>();

        JsonNode nestedItems = parentGroup.get("lineItems");
        if (nestedItems == null) return activities;

        for (JsonNode nested : nestedItems) {
            String nestedLine = getTextValue(nested, "lineNameTxt");

            if (nestedLine.contains("WrkshtPassiveGrp")) {
                Form8582Activity activity = parseSingleWorksheetActivity(nested);
                if (activity != null) {
                    activities.add(activity);
                }
            }
        }

        return activities;
    }

    /**
     * Parses a single worksheet activity from Form 8582
     */
    private Form8582Activity parseSingleWorksheetActivity(JsonNode worksheetGrp) {
        String activityName = null;
        BigDecimal currentYearLoss = BigDecimal.ZERO;
        BigDecimal overallLoss = BigDecimal.ZERO;

        JsonNode lineItems = worksheetGrp.get("lineItems");
        if (lineItems != null) {
            for (JsonNode lineItem : lineItems) {
                String lineName = getTextValue(lineItem, "lineNameTxt");

                if (lineName.contains("NonParticipateActivityNm")) {
                    activityName = getTextValue(lineItem, "perReturnValueTxt");
                }

                if (lineName.contains("CurrentYearNetLossAmt")) {
                    currentYearLoss = getBigDecimalValue(lineItem, "perReturnValueTxt");
                }

                if (lineName.contains("OverallLossAmt")) {
                    overallLoss = getBigDecimalValue(lineItem, "perReturnValueTxt");
                }
            }
        }

        return Form8582Activity.builder()
                .activityName(activityName)
                .currentYearLoss(currentYearLoss)
                .overallLoss(overallLoss)
                .build();
    }

    /**
     * Validates material participation rules:
     * - Only non-materially participated activities should appear on Form 8582
     * - Materially participated activities should NOT appear on Form 8582
     */
    private void validateMaterialParticipationRules(
            List<ScheduleFActivity> scheduleFActivities,
            List<Form8582Activity> form8582Activities,
            ValidationResult result) {

        // Check for materially participated activities on Form 8582
        for (ScheduleFActivity schedF : scheduleFActivities) {
            if (schedF.isMateriallyParticipated()) {
                // This activity should NOT be on Form 8582
                for (Form8582Activity form8582 : form8582Activities) {
                    if (matchesActivityName(schedF, form8582)) {
                        result.getErrors().add(
                                String.format("Material Participation Rule Violation: Activity '%s' has material participation=true but appears on Form 8582. " +
                                        "Only passive activities (material participation=false) should appear on Form 8582.",
                                schedF.getBusinessName())
                        );
                        result.setPassed(false);
                    }
                }
            }
        }

        // Check for non-materially participated activities with losses not on Form 8582
        for (ScheduleFActivity schedF : scheduleFActivities) {
            if (!schedF.isMateriallyParticipated()) {
                // Calculate loss: Gross Income - Total Expenses
                BigDecimal calculatedLoss = schedF.getGrossIncome().subtract(schedF.getTotalExpenses());

                if (calculatedLoss.compareTo(BigDecimal.ZERO) < 0) {
                    // This is a loss, should be on Form 8582
                    boolean foundOn8582 = false;
                    for (Form8582Activity form8582 : form8582Activities) {
                        if (matchesActivityName(schedF, form8582)) {
                            foundOn8582 = true;
                            break;
                        }
                    }

                    if (!foundOn8582) {
                        result.getErrors().add(
                                String.format("Missing Passive Activity: Schedule F activity '%s' has material participation=false and a loss of $%s, " +
                                        "but does NOT appear on Form 8582. Passive activities with losses must be reported on Form 8582.",
                                schedF.getBusinessName(),
                                calculatedLoss.abs().toPlainString())
                        );
                        result.setPassed(false);
                    }
                }
            }
        }
    }

    /**
     * Validates that business names match between Schedule F and Form 8582
     */
    private void validateBusinessNameMatching(
            List<ScheduleFActivity> scheduleFActivities,
            List<Form8582Activity> form8582Activities,
            ValidationResult result) {

        for (Form8582Activity form8582 : form8582Activities) {
            boolean matchFound = false;

            for (ScheduleFActivity schedF : scheduleFActivities) {
                if (matchesActivityName(schedF, form8582)) {
                    matchFound = true;
                    break;
                }
            }

            if (!matchFound) {
                result.getErrors().add(
                        String.format("Business Name Mismatch: Form 8582 lists activity '%s' but no matching Schedule F activity found.",
                                form8582.getActivityName())
                );
                result.setPassed(false);
            }
        }
    }

    /**
     * Validates loss amounts between Schedule F and Form 8582
     * Based on ticket: Line 9 (Gross Income) - Line 33 (Total Expenses) = Loss
     */
    private void validateLossAmounts(
            List<ScheduleFActivity> scheduleFActivities,
            List<Form8582Activity> form8582Activities,
            ValidationResult result) {

        for (Form8582Activity form8582 : form8582Activities) {
            for (ScheduleFActivity schedF : scheduleFActivities) {
                if (matchesActivityName(schedF, form8582)) {
                    // Calculate loss: Gross Income (Line 9) - Total Expenses (Line 33)
                    BigDecimal calculatedLoss = schedF.getGrossIncome().subtract(schedF.getTotalExpenses());

                    // Form 8582 should show the absolute value of the loss
                    BigDecimal expected8582Loss = calculatedLoss.abs();
                    BigDecimal actual8582Loss = form8582.getOverallLoss();

                    // Compare with tolerance for rounding
                    if (expected8582Loss.compareTo(actual8582Loss) != 0) {
                        result.getErrors().add(
                                String.format("Loss Amount Mismatch: Activity '%s' - Schedule F calculated loss (Line 9 - Line 33) = $%s, " +
                                        "but Form 8582 shows $%s. These amounts must match.",
                                schedF.getBusinessName(),
                                expected8582Loss.toPlainString(),
                                actual8582Loss.toPlainString())
                        );
                        result.setPassed(false);
                    }
                }
            }
        }
    }

    /**
     * Validates that NetFarmProfitLossAmt shows zero for losses
     * as per ticket description: "When there is a loss the payload shows zero"
     */
    private void validateNetFarmProfitLossAmt(
            List<ScheduleFActivity> scheduleFActivities,
            List<Form8582Activity> form8582Activities,
            ValidationResult result) {

        for (ScheduleFActivity schedF : scheduleFActivities) {
            // Calculate actual loss
            BigDecimal calculatedLoss = schedF.getGrossIncome().subtract(schedF.getTotalExpenses());

            // If there's a loss (negative)
            if (calculatedLoss.compareTo(BigDecimal.ZERO) < 0) {
                // Check if NetFarmProfitLossAmt is zero
                if (schedF.getNetProfitLoss().compareTo(BigDecimal.ZERO) != 0) {
                    result.getErrors().add(
                            String.format("NetFarmProfitLossAmt Error: Activity '%s' has a loss (Line 9 - Line 33 = $%s), " +
                                    "but NetFarmProfitLossAmt (Line 34) shows $%s instead of $0.00. " +
                                    "Per IRS rules, when there is a loss, this field should show zero and the correct amount should appear on Form 8582.",
                            schedF.getBusinessName(),
                            calculatedLoss.toPlainString(),
                            schedF.getNetProfitLoss().toPlainString())
                    );
                    result.setPassed(false);
                }
            }
        }
    }

    /**
     * Checks if a Schedule F activity matches a Form 8582 activity by name
     */
    private boolean matchesActivityName(ScheduleFActivity schedF, Form8582Activity form8582) {
        if (schedF.getBusinessName() == null || form8582.getActivityName() == null) {
            return false;
        }

        // Normalize and compare
        String normalizedSchedF = normalizeName(schedF.getBusinessName());
        String normalized8582 = normalizeName(form8582.getActivityName());

        // Also check principal product
        String normalizedProduct = normalizeName(schedF.getPrincipalProduct());

        return normalizedSchedF.equals(normalized8582) || normalizedProduct.equals(normalized8582);
    }

    /**
     * Normalizes a name for comparison (uppercase, trim, remove extra spaces)
     */
    private String normalizeName(String name) {
        if (name == null) return "";
        return name.trim().toUpperCase().replaceAll("\\s+", " ");
    }

    /**
     * Helper method to get text value from JsonNode
     */
    private String getTextValue(JsonNode node, String fieldName) {
        if (node == null || !node.has(fieldName)) return null;
        JsonNode field = node.get(fieldName);
        return field.isNull() ? null : field.asText();
    }

    /**
     * Helper method to get BigDecimal value from JsonNode
     */
    private BigDecimal getBigDecimalValue(JsonNode node, String fieldName) {
        String value = getTextValue(node, fieldName);
        if (value == null || value.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    // Data models
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ScheduleFActivity {
        private String businessName;
        private String principalProduct;
        private BigDecimal grossIncome;
        private BigDecimal totalExpenses;
        private BigDecimal netProfitLoss;
        private boolean materiallyParticipated;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Form8582Activity {
        private String activityName;
        private BigDecimal currentYearLoss;
        private BigDecimal overallLoss;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ValidationResult {
        private boolean passed;
        private List<String> errors;
    }
}
