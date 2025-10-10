package com.tax.dvs.validator;

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
     * Validates Schedule F against Form 8582 for passive activity losses
     * 
     * IRS Rules:
     * - Passive activities (non-material participation) must be reported on Form 8582
     * - Losses from passive activities are limited and may be carried forward
     * - Material participation activities are NOT subject to passive loss limitations
     * - Net farm profit/loss = Gross Income - Total Expenses
     */
    public ValidationResult validate(JsonNode taxReturn) {
        ValidationResult result = new ValidationResult();
        List<ValidationError> errors = new ArrayList<>();
        List<ValidationWarning> warnings = new ArrayList<>();

        try {
            // Extract Schedule F forms
            List<ScheduleFData> scheduleFs = extractScheduleFData(taxReturn);
            
            // Extract Form 8582 data
            Form8582Data form8582 = extractForm8582Data(taxReturn);

            // Validate each Schedule F
            for (ScheduleFData schF : scheduleFs) {
                // Rule 1: Calculate actual net profit/loss
                BigDecimal calculatedNet = schF.grossIncome.subtract(schF.totalExpenses);
                schF.setCalculatedNetProfitLoss(calculatedNet);

                // Rule 2: Check if passive activity (no-material participation)
                if (!schF.materiallyParticipated) {
                    // Rule 3: Passive activities with losses must be on Form 8582
                    if (calculatedNet.compareTo(BigDecimal.ZERO) < 0) {
                        // Find matching activity on Form 8582
                        Optional<PassiveActivity> matchingActivity = findMatchingActivity(form8582, schF);

                        if (matchingActivity.isPresent()) {
                            PassiveActivity activity = matchingActivity.get();
                            
                            // Rule 4: Validate loss amount matches
                            BigDecimal absCalculatedLoss = calculatedNet.abs();
                            if (absCalculatedLoss.compareTo(activity.currentYearLoss) != 0) {
                                errors.add(ValidationError.builder()
                                        .errorCode("SCHF-8582-001")
                                        .severity("ERROR")
                                        .fieldPath("/IRS1040ScheduleF/FarmExpensesGrp/NetFarmProfitLossAmt")
                                        .message(String.format(
                                                "Schedule F loss mismatch for '%s': Calculated loss $%s does not match Form 8582 loss $%s",
                                                schF.businessName, absCalculatedLoss, activity.currentYearLoss))
                                        .expectedValue(activity.currentYearLoss.toPlainString())
                                        .actualValue(absCalculatedLoss.toPlainString())
                                        .businessName(schF.businessName)
                                        .ein(schF.ein)
                                        .build());
                            }

                            // Rule 5: Validate NetFarmProfitLossAmt shows zero for passive losses
                            if (schF.netFarmProfitLoss.compareTo(BigDecimal.ZERO) == 0 && calculatedNet.compareTo(BigDecimal.ZERO) < 0) {
                                warnings.add(ValidationWarning.builder()
                                        .warningCode("SCHF-8582-W001")
                                        .fieldPath("/IRS1040ScheduleF/FarmExpensesGrp/NetFarmProfitLossAmt")
                                        .message(String.format(
                                                "Schedule F for '%s' shows $0 for NetFarmProfitLossAmt, but calculated loss is $%s. " +
                                                "Passive losses are limited and reported on Form 8582.",
                                                schF.businessName, absCalculatedLoss))
                                        .businessName(schF.businessName)
                                        .ein(schF.ein)
                                        .build());
                            }
                        } else {
                            // Rule 6: Passive activity with loss not found on Form 8582
                            errors.add(ValidationError.builder()
                                    .errorCode("SCHF-8582-002")
                                    .severity("ERROR")
                                    .fieldPath("/IRS8582/ParentWrkshtPassiveGrp")
                                    .message(String.format(
                                            "Passive activity '%s' with loss $%s not found on Form 8582. " +
                                            "Passive losses must be reported on Form 8582.",
                                            schF.businessName, absCalculatedLoss))
                                    .expectedValue("Activity on Form 8582")
                                    .actualValue("Not found")
                                    .businessName(schF.businessName)
                                    .ein(schF.ein)
                                    .build());
                        }
                    } else if (calculatedNet.compareTo(BigDecimal.ZERO) >= 0) {
                        // Rule 7: Passive activity with income should not be on Form 8582 loss section
                        Optional<PassiveActivity> matchingActivity = findMatchingActivity(form8582, schF);
                        if (matchingActivity.isPresent() && matchingActivity.get().currentYearLoss.compareTo(BigDecimal.ZERO) > 0) {
                            warnings.add(ValidationWarning.builder()
                                    .warningCode("SCHF-8582-W002")
                                    .fieldPath("/IRS8582/ParentWrkshtPassiveGrp")
                                    .message(String.format(
                                            "Passive activity '%s' has income $%s but is listed on Form 8582 with loss $%s.",
                                            schF.businessName, calculatedNet, matchingActivity.get().currentYearLoss))
                                    .businessName(schF.businessName)
                                    .ein(schF.ein)
                                    .build());
                        }
                    }
                } else {
                    // Rule 8: Material participation activities should NOT be on Form 8582
                    Optional<PassiveActivity> matchingActivity = findMatchingActivity(form8582, schF);
                    if (matchingActivity.isPresent()) {
                        errors.add(ValidationError.builder()
                                .errorCode("SCHE-8582-003")
                                .severity("ERROR")
                                .fieldPath("/IRS8582/ParentWrkshtPassiveGrp")
                                .message(String.format(
                                        "Activity '%s' has material participation but is listed on Form 8582. " +
                                        "Material participation activities are not subject to passive loss limitations.",
                                        schF.businessName))
                                .expectedValue("Not on Form 8582")
                                .actualValue("On Form 8582")
                                .businessName(schF.businessName)
                                .ein(schF.ein)
                                .build());
                    }
                }
            }

            // Rule 9: Validate Form 8582 totals
            validateForm8582Totals(form8582, scheduleFs, errors);

            result.setValid(errors.isEmpty());
            result.setErrors(errors);
            result.setWarnings(warnings);
            result.setScheduleFData(scheduleFs);
            result.setForm8582Data(form8582);

        } catch (Exception e) {
            errors.add(ValidationError.builder()
                    .errorCode("SCHE-8582-999")
                    .severity("FATAL")
                    .fieldPath("N/A")
                    .message("Validation error: " + e.getMessage())
                    .build());
            result.setValid(false);
            result.setErrors(errors);
        }

        return result;
    }

    private List<ScheduleFData> extractScheduleFData(JsonNode taxReturn) {
        List<ScheduleFData> scheduleFs = new ArrayList<>();
        JsonNode body = taxReturn.get("body");
        if (body == null) return scheduleFs;

        JsonNode forms = body.get("forms");
        if (forms == null || !forms.isArray()) return scheduleFs;

        for (JsonNode form : forms) {
            String formNum = getTextValue(form, "formNum");
            if ("IRS1040ScheduleF".equals(formNum)) {
                ScheduleFData schF = extractSingleScheduleF(form);
                if (schF != null) {
                    scheduleFs.add(schF);
                }
            }
        }

        return scheduleFs;
    }

    private ScheduleFData extractSingleScheduleF(JsonNode form) {
        ScheduleFData schF = new ScheduleFData();
        JsonNode lineItems = form.get("lineItems");
        if (lineItems == null || !lineItems.isArray()) return null;

        for (JsonNode item : lineItems) {
            String lineName = getTextValue(item, "lineNameTxt");
            String value = getTextValue(item, "perReturnValueTxt");

            switch (lineName) {
                case "/IRS1040ScheduleF/FarmProprietorName":
                    JsonNode nestedItems = item.get("lineItems");
                    if (nestedItems != null && nestedItems.isArray()) {
                        for (JsonNode nested : nestedItems) {
                            if ("/IRS1040ScheduleF/FarmProprietorName/BusinessNameLine1Txt".equals(getTextValue(nested, "lineNameTxt"))) {
                                schF.businessName = getTextValue(nested, "perReturnValueTxt");
                            }
                        }
                    }
                    break;
                case "/IRS1040ScheduleF/EIN":
                    schF.ein = value;
                    break;
                case "/IRS1040ScheduleF/PrincipalProductDesc":
                    schF.principalProduct = value;
                    break;
                case "/IRS1040ScheduleF/MateriallyParticipatedInd":
                    schF.materiallyParticipated = "true".equalsIgnoreCase(value);
                    break;
                case "/IRS1040ScheduleF/FarmIncomeCashMethodGrp":
                    JsonNode incomeGrp = item.get("lineItems");
                    if (incomeGrp != null && incomeGrp.isArray()) {
                        for (JsonNode incomeItem : incomeGrp) {
                            if ("/IRS1040ScheduleF/FarmIncomeCashMethodGrp/GrossIncomeAmt".equals(getTextValue(incomeItem, "lineNameTxt"))) {
                                schF.grossIncome = parseBigDecimal(getTextValue(incomeItem, "perReturnValueTxt"));
                            }
                        }
                    }
                    break;
                case "/IRS1040ScheduleF/FarmExpensesGrp":
                    JsonNode expenseGrp = item.get("lineItems");
                    if (expenseGrp != null && expenseGrp.isArray()) {
                        for (JsonNode expenseItem : expenseGrp) {
                            String expLine = getTextValue(expenseItem, "lineNameTxt");
                            if ("/IRS1040ScheduleF/FarmExpensesGrp/TotalExpensesAmt".equals(expLine)) {
                                schF.totalExpenses = parseBigDecimal(getTextValue(expenseItem, "perReturnValueTxt"));
                            } else if ("/IRS1040ScheduleF/FarmExpensesGrp/NetFarmProfitLossAmt".equals(exLine)) {
                                schF.netFarmProfitLoss = parseBigDecimal(getTextValue(expenseItem, "perReturnValueTxt"));
                            }
                        }
                    }
                    break;
            }
        }

        return schF;
    }

    private Form8582Data extractForm8582Data(JsonNode taxReturn) {
        Form8582Data form8582 = new Form8582Data();
        JsonNode body = taxReturn.get("body");
        if (body == null) return form8582;

        JsonNode forms = body.get("forms");
        if (forms == null || !forms.isArray()) return form8582;

        for (JsonNode form : forms) {
            String formNum = getTextValue(form, "formNum");
            if ("IRS8582".equals(formNum)) {
                JsonNode lineItems = form.get("lineItems");
                if (lineItems == null || !lineItems.isArray()) continue;

                for (JsonNode item : lineItems) {
                    String lineName = getTextValue(item, "lineNameTxt");
                    String value = getTextValue(item, "perReturnValueTxt");

                    switch (lineName) {
                        case "/IRS8582/OtherActivityLossAmt":
                            form8582.totalOtherActivityLoss = parseBigDecimal(value);
                            break;
                        case "/IRS8582/ParentWrkshtPassiveGrp":
                            extractPassiveActivities(item, form8582);
                            break;
                    }
                }
            }
        }

        return form8582;
    }

    private void extractPassiveActivities(JsonNode parentNode, Form8582Data form8582) {
        JsonNode lineItems = parentNode.get("lineItems");
        if (lineItems == null || !lineItems.isArray()) return;

        for (JsonNode item : lineItems) {
            String lineName = getTextValue(item, "lineNameTxt");
            if ("/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp".equals(lineName)) {
                PassiveActivity activity = extractSinglePassiveActivity(item);
                if (activity != null) {
                    form8582.passiveActivities.add(activity);
                }
            }
        }
    }

    private PasYvKActivity extractSinglePassiveActivity(JsonNode activityNode) {
        PassiveActivity activity = new PassiveActivity();
        JsonNode lineItems = activityNode.get("lineItems");
        if (lineItems == null || !lineItems.isArray()) return null;

        for (JsonNode item : lineItems) {
            String lineName = getTextValue(item, "lineNameTxt");
            String value = getTextValue(item, "perReturnValueTxt");

            switch (lineName) {
                case "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/NonParticipateActivityNm":
                    activity.activityName = value;
                    break;
                case "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/CurrentYearNetLossAmt":
                    activity.currentYearLoss = parseBigDecimal(value);
                    break;
                case "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/OverallLossAmt":
                    activity.overallLoss = parseBigDecimal(value);
                    break;
            }
        }

        return activity.activityName != null ? activity : null;
    }

    private Optional<PassiveActivity> findMatchingActivity(Form8582Data form8582, ScheduleFData schF) {
        return form8582.passiveActivities.stream()
                .filter(a -> a.activityName != null && 
                        a.activityName.trim().equalsIgnoreCase(schF.businessName != null ? schF.businessName.trim() : ""))
                .findFirst();
    }

    private void validateForm8582Totals(Form8582Data form8582, List<ScheduleFData> scheduleFs, List<ValidationError> errors) {
        BigDecimal calculatedTotalLoss = BigDecimal.ZERO;
        
        for (ScheduleFData schF : scheduleFs) {
            if (!schF.materiallyParticipated && schF.calculatedNetPÏfitLoss != null && 
                schF.calculatedNetPÏfitLoss.compareTo(BigDecimal.ZERO) < 0) {
                calculatedTotalLoss = calculatedTotalLoss.add(schF.calculatedNetProfitLoss.abs());
            }
        }

        if (form8582.totalOtherActivityLoss != null && 
            calculatedTotalLoss.compareTo(form8582.totalOtherActivityLoss) != 0) {
            errors.add(ValidationError.builder()
                    .errorCode("SCHF-8582-004")
                    .severity("ERROR")
                    .fieldPath("/IRS8582/OtherActivityLossAmt")
                    .message(String.format(
                            "Form 8582 total other activity loss $%s does not match sum of Schedule F passive losses $%s",
                            form8582.totalOtherActivityLoss, calculatedTotalLoss))
                    .expectedValue(calculatedTotalLoss.toPlainString())
                    .actualValue(form8582.totalOtherActivityLoss.toPlainString())
                    .build());
        }
    }

    private String getTextValue(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field != null && !field.isNull() ? field.asText() : null;
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.replaceAll("[$,]", ""));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ValidationResult {
        private boolean isValid;
        private List<ValidationError> errors;
        private List<ValidationWarning> warnings;
        private List<ScheduleFData> scheduleFData;
        private Form8582Data form8582Data;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ValidationError {
        private String errorCode;
        private String severity;
        private String fieldPath;
        private String message;
        private String expectedValue;
        private String actualValue;
        private String businessName;
        private String ein;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ValidationWarning {
        private String warningCode;
        private String fieldPath;
        private String message;
        private String businessName;
        private String ein;
    }

    @Data
    @NoArgsConstructor
    public static class ScheduleFData {
        private String businessName;
        private String ein;
        private String principalProduct;
        private boolean materiallyParticipated;
        private BigDecimal grossIncome = BigDecimal.ZERO;
        private BigDecimal totalExpenses = BigDecimal.ZERO;
        private BigDecimal netFarmProfitLoss = BigDecimal.ZERO;
        private BigDecimal calculatedNetProfitLoss;
    }

    @Data
    @NoArgsConstructor
    public static class Form8582Data {
        private BigDecimal totalOtherActivityLoss;
        private List<PassiveActivity> passiveActivities = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    public static class PassiveActivity {
        private String activityName;
        private BigDecimal currentYearLoss = BigDecimal.ZERO;
        private BigDecimal overallLoss = BigDecimal.ZERO;
    }
}
