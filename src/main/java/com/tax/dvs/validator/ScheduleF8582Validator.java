package com.tax.dvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Validator for Schedule F (Form 1040) and Form 8582 (Passive Activity Loss Limitations)
 * 
 * IRS Rules:
 * - Schedule F Line 34 (Net Profit/Loss) = Line 9 (Gross Income) - Line 33 (Total Expenses)
 * - Passive activity losses (MateriallyParticipatedInd = false) must be reported on Form 8582
 * - Form 8582 should match the calculated loss amount from Schedule F
 * - When losses are passive, Schedule F Line 34 may show $0 while Form 8582 shows the actual loss
 * - Business names must match between Schedule F and Form 8582
 */
@Component
public class ScheduleF8582Validator {

    private static final String SCHEDULE_F_PATH = "/IRS1040ScheduleF";
    private static final String FORM_8582_PATH = "/IRS8582";
    private static final String FARM_EXPENSES_GRP = "FarmExpensesGrp";
    private static final String FARM_INCOME_GRP = "FarmIncomeCashMethodGrp";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /**
     * Validates the entire tax return payload for Schedule F and Form 8582 consistency
     */
    public ValidationResult validate(JsonNode payload) {
        ValidationResult result = ValidationResult.builder()
                .valid(true)
                .errors(new ArrayList<>())
                .warnings(new ArrayList<>())
                .build();

        try {
            // Extract forms from payload
            JsonNode body = payload.get("body");
            if (body == null || !body.has("forms")) {
                result.getErrors().add("Missing 'body.forms' in payload");
                result.setValid(false);
                return result;
            }

            JsonNode forms = body.get("forms");
            List<ScheduleFData> scheduleFList = extractScheduleFData(forms);
            Form8582Data form8582Data = extractForm8582Data(forms);

            // Validate each Schedule F
            for (ScheduleFData scheduleF : scheduleFList) {
                validateScheduleF(scheduleF, form8582Data, result);
            }

            // Validate Form 8582 against Schedule F data
            validateForm8582(scheduleFList, form8582Data, result);

            // Set final validation status
            result.setValid(result.getErrors().isEmpty());

        } catch (Exception e) {
            result.getErrors().add("Validation exception: " + e.getMessage());
            result.setValid(false);
        }

        return result;
    }

    /**
     * Extracts all Schedule F data from the forms array
     */
    private List<ScheduleFData> extractScheduleFData(JsonNode forms) {
        List<ScheduleFData> scheduleFList = new ArrayList<>();

        for (JsonNode form : forms) {
            String formNum = getTextValue(form, "formNum");
            if ("IRS1040ScheduleF".equals(formNum)) {
                ScheduleFData data = ScheduleFData.builder().build();
                JsonNode lineItems = form.get("lineItems");

                if (lineItems != null) {
                    for (JsonNode lineItem : lineItems) {
                        String lineName = getTextValue(lineItem, "lineNameTxt");

                        // Extract business name
                        if (lineName.contains("FarmProprietorName/BusinessNameLine1Txt")) {
                            data.setBusinessName(getTextValue(lineItem, "perReturnValueTxt"));
                        }
                        // Extract EIN
                        else if (lineName.endsWith("/EIN")) {
                            data.setEin(getTextValue(lineItem, "perReturnValueTxt"));
                        }
                        // Extract principal product
                        else if (lineName.endsWith("/PrincipalProductDesc")) {
                            data.setPrincipalProduct(getTextValue(lineItem, "perReturnValueTxt"));
                        }
                        // Extract material participation
                        else if (lineName.endsWith("/MateriallyParticipatedInd")) {
                            String value = getTextValue(lineItem, "perReturnValueTxt");
                            data.setMateriallyParticipated("true".equalsIgnoreCase(value));
                        }
                        // Extract gross income (Line 9)
                        else if (lineName.contains(FARM_INCOME_GRP + "/GrossIncomeAmt")) {
                            data.setGrossIncome(getBigDecimalValue(lineItem, "perReturnValueTxt"));
                        }
                        // Extract total expenses (Line 33)
                        else if (lineName.contains(FARM_EXPENSES_GRP + "/TotalExpensesAmt")) {
                            data.setTotalExpenses(getBigDecimalValue(lineItem, "perReturnValueTxt"));
                        }
                        // Extract net profit/loss (Line 34)
                        else if (lineName.contains(FARM_EXPENSES_GRP + "/NetFarmProfitLossAmt")) {
                            data.setNetProfitLoss(getBigDecimalValue(lineItem, "perReturnValueTxt"));
                        }
                    }
                }

                scheduleFList.add(data);
            }
        }

        return scheduleFList;
    }

    /**
     * Extracts Form 8582 data from the forms array
     */
    private Form8582Data extractForm8582Data(JsonNode forms) {
        Form8582Data data = Form8582Data.builder()
                .activities(new ArrayList<>())
                .build();

        for (JsonNode form : forms) {
            String formNum = getTextValue(form, "formNum");
            if ("IRS8582".equals(formNum)) {
                JsonNode lineItems = form.get("lineItems");

                if (lineItems != null) {
                    for (JsonNode lineItem : lineItems) {
                        String lineName = getTextValue(lineItem, "lineNameTxt");

                        // Extract passive activity data
                        if (lineName.contains("ParentWrkshtPassiveGrp/WrkshtPassiveGrp")) {
                            JsonNode subItems = lineItem.get("lineItems");
                            if (subItems != null) {
                                PassiveActivity activity = PassiveActivity.builder().build();
                                for (JsonNode subItem : subItems) {
                                    String subLineName = getTextValue(subItem, "lineNameTxt");
                                    if (subLineName.endsWith("/NonParticipateActivityNm")) {
                                        activity.setActivityName(getTextValue(subItem, "perReturnValueTxt"));
                                    } else if (subLineName.endsWith("/CurrentYearNetLossAmt")) {
                                        activity.setCurrentYearLoss(getBigDecimalValue(subItem, "perReturnValueTxt"));
                                    } else if (subLineName.endsWith("/OverallLossAmt")) {
                                        activity.setOverallLoss(getBigDecimalValue(subItem, "perReturnValueTxt"));
                                    }
                                }
                                if (activity.getActivityName() != null) {
                                    data.getActivities().add(activity);
                                }
                            }
                        }
                    }
                }
                break;
            }
        }

        return data;
    }

    /**
     * Validates a single Schedule F against IRS rules and Form 8582
     */
    private void validateScheduleF(ScheduleFData scheduleF, Form8582Data form8582Data, ValidationResult result) {
        String businessId = scheduleF.getBusinessName() != null ? scheduleF.getBusinessName() : scheduleF.getEin();

        // Rule 1: Calculate expected net profit/loss (Line 9 - Line 33)
        BigDecimal calculatedNet = scheduleF.getGrossIncome().subtract(scheduleF.getTotalExpenses());

        // Rule 2: If passive activity with loss, Schedule F Line 34 may show $0
        if (!scheduleF.isMateriallyParticipated() && calculatedNet.compareTo(ZERO) < 0) {
            // Passive loss - should be on Form 8582
            Optional<PassiveActivity> matchingActivity = findMatchingActivity(businessId, form8582Data);

            if (matchingActivity.isPresent()) {
                BigDecimal form8582Loss = matchingActivity.get().getCurrentYearLoss();
                BigDecimal expectedLoss = calculatedNet.abs();

                // Compare calculated loss with Form 8582 loss
                if (form8582Loss != null && form8582Loss.compareTo(expectedLoss) != 0) {
                    result.getErrors().add(String.format(
                            "Loss mismatch for '%s': Schedule F calculated loss (%s) != Form 8582 loss (%s)",
                            businessId, expectedLoss, form8582Loss
                    ));
                }

                // Warning if Schedule F Line 34 is not $0
                if (scheduleF.getNetProfitLoss().compareTo(ZERO) != 0) {
                    result.getWarnings().add(String.format(
                            "Passive loss for '%s': Schedule F Line 34 shows %s but should typically be $0 (with loss on Form 8582)",
                            businessId, scheduleF.getNetProfitLoss()
                    ));
                }
            } else {
                // Passive loss but not on Form 8582
                result.getErrors().add(String.format(
                        "Passive loss for '%s' (%s) not found on Form 8582",
                        businessId, calculatedNet
                ));
            }
        } else {
            // Non-passive or profit - Schedule F Line 34 should match calculation
            if (scheduleF.getNetProfitLoss().compareTo(calculatedNet) != 0) {
                result.getErrors().add(String.format(
                        "Net profit/loss mismatch for '%s': Schedule F Line 34 (%s) != Line 9 (%s) - Line 33 (%s) = %s",
                        businessId, scheduleF.getNetProfitLoss(), scheduleF.getGrossIncome(),
                        scheduleF.getTotalExpenses(), calculatedNet
                ));
            }
        }
    }

    /**
     * Validates Form 8582 against Schedule F data
     */
    private void validateForm8582(List<ScheduleFData> scheduleFList, Form8582Data form8582Data, ValidationResult result) {
        // Check if all passive losses are on Form 8582
        for (ScheduleFData scheduleF : scheduleFList) {
            if (!scheduleF.isMateriallyParticipated()) {
                BigDecimal calculatedNet = scheduleF.getGrossIncome().subtract(scheduleF.getTotalExpenses());
                if (calculatedNet.compareTo(ZERO) < 0) {
                    String businessId = scheduleF.getBusinessName() != null ? scheduleF.getBusinessName() : scheduleF.getEin();
                    Optional<PassiveActivity> matchingActivity = findMatchingActivity(businessId, form8582Data);
                    if (matchingActivity.isEmpty()) {
                        result.getErrors().add(String.format(
                                "Passive activity '%s' with loss not found on Form 8582",
                                businessId
                        ));
                    }
                }
            }
        }

        // Check if all Form 8582 activities have matching Schedule F
        for (PassiveActivity activity : form8582Data.getActivities()) {
            boolean found = false;
            for (ScheduleFData scheduleF : scheduleFList) {
                if (matchesBusiness(activity.getActivityName(), scheduleF)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                result.getWarnings().add(String.format(
                        "Form 8582 activity '%s' not found in Schedule F forms",
                        activity.getActivityName()
                ));
            }
        }
    }

    /**
     * Finds matching passive activity on Form 8582
     */
    private Optional<PassiveActivity> findMatchingActivity(String businessId, Form8582Data form8582Data) {
        return form8582Data.getActivities().stream()
                .filter(a -> businessId != null && businessId.equalsIgnoreCase(a.getActivityName()))
                .findFirst();
    }

    /**
     * Checks if business name or EIN matches
     */
    private boolean matchesBusiness(String activityName, ScheduleFData scheduleF) {
        if (activityName == null) return false;
        return activityName.equalsIgnoreCase(scheduleF.getBusinessName()) ||
               activityName.equalsIgnoreCase(scheduleF.getPrincipalProduct()) ||
               activityName.equalsIgnoreCase(scheduleF.getEin());
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
        if (value == null || value.isBlank()) return ZERO;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return ZERO;
        }
    }

    // Data models

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScheduleFData {
        private String businessName;
        private String ein;
        private String principalProduct;
        private boolean materiallyParticipated;
        private BigDecimal grossIncome = ZERO;
        private BigDecimal totalExpenses = ZERO;
        private BigDecimal netProfitLoss = ZERO;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Form8582Data {
        private List<PassiveActivity> activities;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PassiveActivity {
        private String activityName;
        private BigDecimal currentYearLoss;
        private BigDecimal overallLoss;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationResult {
        private boolean valid;
        private List<String> errors;
        private List<String> warnings;
    }
}
