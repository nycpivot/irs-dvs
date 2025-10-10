package com.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Component
@Slf4j
public class ScheduleF8582Validator {

    private static final String SCHEDULE_F_PATH = "/IRS1040ScheduleF";
    private static final String FORM_8582_PATH = "/IRS8582";
    private static final String FARM_EXPENSES_GRP = "/FarmExpensesGrp";
    private static final String FARM_INCOME_GRP = "/FarmIncomeCashMethodGrp";

    /**
     * Validates Schedule F and Form 8582 matching per IRS rules.
     *
     * Requirements:
     * 1. Business names must match between Schedule F and Form 8582
     * 2. For losses: Line 9 (Gross Income) - Line 33 (Total Expenses) = Net Loss
     * 3. Schedule F NetFarmProfitLossAmt must match calculated amount
     * 4. Form 8582 loss amounts must match Schedule F losses for passive activities
     * 5. Active participation farms should NOT appear on Form 8582
     */
    public ValidationResult validate(JsonNode payload) {
        log.info("Starting Schedule F / Form 8582 validation");

        ValidationResult result = ValidationResult.builder()
                .passed(true)
                .errors(new ArrayList<>())
                .warnings(new ArrayList<>())
                .info(new ArrayList<>())
                .build();

        try {
            JsonNode body = payload.get("body");
            if (body == null || !body.has("forms")) {
                result.getErrors().add("Missing body/forms in payload");
                result.setPassed(false);
                return result;
            }

            // Extract Schedule F forms
            List<ScheduleFData> scheduleFForms = extractScheduleFData(body);
            log.info("Found {} Schedule F forms", scheduleFForms.size());

            // Extract Form 8582 data
            Form8582Data form8582Data = extractForm8582Data(body);

            // Validate each Schedule F
            for (ScheduleFData scheduleF : scheduleFForms) {
                validateScheduleF(scheduleF, form8582Data, result);
            }

            // Validate Form 8582 entries match Schedule F
            validateForm8582Matching(scheduleFForms, form8582Data, result);

        } catch (Exception e) {
            log.error("Validation error", e);
            result.getErrors().add("Validation exception: " + e.getMessage());
            result.setPassed(false);
        }

        return result;
    }

    private List<ScheduleFData> extractScheduleFData(JsonNode body) {
        List<ScheduleFData> scheduleFForms = new ArrayList<>();
        JsonNode forms = body.get("forms");

        if (forms != null && forms.isArray()) {
            for (JsonNode form : forms) {
                if ("IRS1040ScheduleF".equals(getTextValue(form, "formNum"))) {
                    ScheduleFData scheduleF = parseScheduleF(form);
                    scheduleFForms.add(scheduleF);
                }
            }
        }

        return scheduleFForms;
    }

    private ScheduleFData parseScheduleF(JsonNode form) {
        ScheduleFData data = new ScheduleFData();
        data.setSequenceNum(getTextValue(form, "sequenceNum"));

        JsonNode lineItems = form.get("lineItems");
        if (lineItems != null && lineItems.isArray()) {
            for (JsonNode item : lineItems) {
                String lineName = getTextValue(item, "lineNameTxt");

                // Business Name
                if (lineName.contains("FarmProprietorName/BusinessNameLine1Txt")) {
                    data.setBusinessName(getTextValue(item, "perReturnValueTxt"));
                }
                // Principal Product
                else if (lineName.contains("PrincipalProductDesc")) {
                    data.setPrincipalProduct(getTextValue(item, "perReturnValueTxt"));
                }
                // EIN
                else if (lineName.contains("/EIN")) {
                    data.setEin(getTextValue(item, "perReturnValueTxt"));
                }
                // Material Participation
                else if (lineName.contains("MateriallyParticipatedInd")) {
                    data.setMateriallyParticipated(Boolean.parseBoolean(getTextValue(item, "perReturnValueTxt")));
                }
                // Gross Income (Line 9)
                else if (lineName.contains(FARM_INCOME_GRP + "/GrossIncomeAmt")) {
                    data.setGrossIncome(getBigDecimalValue(item, "perReturnValueTxt"));
                }
                // Total Expenses (Line 33)
                else if (lineName.contains(FARM_EXPENSES_GRP + "/TotalExpensesAmt")) {
                    data.setTotalExpenses(getBigDecimalValue(item, "perReturnValueTxt"));
                }
                // Net Farm Profit/Loss (Line 34)
                else if (lineName.contains(FARM_EXPENSES_GRP + "/NetFarmProfitLossAmt")) {
                    data.setNetFarmProfitLoss(getBigDecimalValue(item, "perReturnValueTxt"));
                }
            }
        }

        return data;
    }

    private Form8582Data extractForm8582Data(JsonNode body) {
        Form8582Data data = new Form8582Data();
        data.setActivities(new ArrayList<>());

        JsonNode forms = body.get("forms");
        if (forms != null && forms.isArray()) {
            for (JsonNode form : forms) {
                if ("IRS8582".equals(getTextValue(form, "formNum"))) {
                    parseForm8582(form, data);
                    break;
                }
            }
        }

        return data;
    }

    private void parseForm8582(JsonNode form, Form8582Data data) {
        JsonNode lineItems = form.get("lineItems");
        if (lineItems != null && lineItems.isArray()) {
            for (JsonNode item : lineItems) {
                String lineName = getTextValue(item, "lineNameTxt");

                // Parse Worksheet Passive Group
                if (lineName.contains("ParentWrkshtPassiveGrp")) {
                    parseWorksheetPassiveGroup(item, data);
                }
            }
        }
    }

    private void parseWorksheetPassiveGroup(JsonNode parentGrp, Form8582Data data) {
        JsonNode lineItems = parentGrp.get("lineItems");
        if (lineItems != null && lineItems.isArray()) {
            for (JsonNode item : lineItems) {
                String lineName = getTextValue(item, "lineNameTxt");

                if (lineName.contains("WrkshtPassiveGrp")) {
                    JsonNode activityItems = item.get("lineItems");
                    if (activityItems != null && activityItems.isArray()) {
                        PassiveActivityData activity = new PassiveActivityData();
                        for (JsonNode actItem : activityItems) {
                            String actLineName = getTextValue(actItem, "lineNameTxt");

                            if (actLineName.contains("NonParticipateActivityNm")) {
                                activity.setActivityName(getTextValue(actItem, "perReturnValueTxt"));
                            } else if (actLineName.contains("CurrentYearNetLossAmt")) {
                                activity.setCurrentYearLoss(getBigDecimalValue(actItem, "perReturnValueTxt"));
                            } else if (actLineName.contains("OverallLossAmt")) {
                                activity.setOverallLoss(getBigDecimalValue(actItem, "perReturnValueTxt"));
                            }
                        }
                        if (activity.getActivityName() != null) {
                            data.getActivities().add(activity);
                        }
                    }
                }
            }
        }
    }

    private void validateScheduleF(ScheduleFData scheduleF, Form8582Data form8582, ValidationResult result) {
        String businessName = scheduleF.getBusinessName();
        log.info("Validating Schedule F: {}", businessName);

        // Calculate expected net profit/loss: Line 9 - Line 33
        BigDecimal calculatedNet = scheduleF.getGrossIncome().subtract(scheduleF.getTotalExpenses());
        BigDecimal reportedNet = scheduleF.getNetFarmProfitLoss();

        log.info("Business: {}, Gross: {}, Expenses: {}, Calculated: {}, Reported: {}",
                businessName, scheduleF.getGrossIncome(), scheduleF.getTotalExpenses(),
                calculatedNet, reportedNet)>

        // Rule 1: NetFarmProfitLossAmt must match calculated amount
        if (calculatedNetcompareTo(reportedNet) != 0) {
            String error = String.format(
                    "[Schedule F - %s] NetFarmProfitLossAmt mismatch. Calculated: %s, Reported: %s. " +
                            "Path: /IRS1040ScheduleF/FarmExpensesGrp/NetFarmProfitLossAmt",
                    businessName, calculatedNet, reportedNet)>
            result.getErrors().add(error);
            result.setPassed(false);
            log.error(error);
        }

        // Rule 2: If loss exists and not materially participated, must be on Form 8582
        if (calculatedNet.compareTo(BigDecimal.ZERO) < 0 && !scheduleF.isMateriallyParticipated()) {
            boolean foundOn8582 = form8582.getActivities().stream()
                    .anyMatch(a -> businessName.equalsIgnoreCase(a.getActivityName()));

            if (!foundOn8582) {
                String error = String.format(
                        "[Schedule F - %s] Passive loss of %s not found on Form 8582. " +
                                "Non-material participation losses must be reported on 8582.",
                        businessName, calculatedNet);
                result.getErrors().add(error);
                result.setPassed(false);
                log.error(error);
            }
        }

        // Rule 3: If materially participated, should NOT be on Form 8582
        if (scheduleF.isMateriallyParticipated()) {
            boolean foundOn8582 = form8582.getActivities().stream()
                    .anyMatch(a -> businessName.equalsIgnoreCase(a.getActivityName()));

            if (foundOn8582) {
                String warning = String.format(
                        "[Schedule F - %s] Material participation activity should NOT be on Form 8582.",
                        businessName);
                result.getWarnings().add(warning);
                log.warn(warning);
            } else {
                result.getInfo().add(String.format(
                        "[Schedule F - %s] Correctly: Material participation activity not on Form 8582.",
                        businessName));
            }
        }
    }

    private void validateForm8582Matching(List<ScheduleFData> scheduleFForms, Form8582Data form8582, ValidationResult result) {
        log.info("Validating Form 8582 matching with Schedule F");

        // Rule 4: Each Form 8582 activity must match a Schedule F
        for (PassiveActivityData activity : form8582.getActivities()) {
            String activityName = activity.getActivityName();
            log.info("Checking Form 8582 activity: {} with loss: {}",
                    activityName, activity.getCurrentYearLoss());

            Optional<ScheduleFData> matchingSchedule = scheduleFForms.stream()
                    .filter(s -> activityName.equalsIgnoreCase(s.getBusinessName()) ||
                                     activityName.equalsIgnoreCase(s.getPrincipalProduct()))
                    .findFirst();

            if (matchingSchedule.isPresent()) {
                ScheduleFData scheduleF = matchingSchedule.get();
                BigDecimal calculatedLoss = scheduleF.getGrossIncome().subtract(scheduleF.getTotalExpenses());
                BigDecimal form8582Loss = activity.getCurrentYearLoss();

                // Compare absolute values (losses are positive on 8582, negative on Sch F)
                BigDecimal expected8582Loss = calculatedLoss.abs();

                if (expected8582Loss.compareTo(form8582Loss) != 0) {
                    String error = String.format(
                            "[Form 8582 - %s] Loss amount mismatch. Schedule F calculated loss: %s, " +
                                    "Form 8582 reported: %s. Path: /IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/CurrentYearNetLossAmt",
                            activityName, calculatedLoss, form8582Loss);
                    result.getErrors().add(error);
                    result.setPassed(false);
                    log.error(error);
                } else {
                    result.getInfo().add(String.format(
                            "[Form 8582 - %s] Loss amount correctly matches: %s",
                            activityName, form8582Loss));
                }
            } else {
                String error = String.format(
                        "[Form 8582 - %s] No matching Schedule F found for this activity.",
                        activityName);
                result.getErrors().add(error);
                result.setPassed(false);
                log.error(error);
            }
        }
    }

    private String getTextValue(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return (field != null && !field.isNull()) ? field.asText() : "";
    }

    private BigDecimal getBigDecimalValue(JsonNode node, String fieldName) {
        String value = getTextValue(node, fieldName);
        if (value == null || value.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid number format: {}", value);
            return BigDecimal.ZERO;
        }
    }

    @Data
    @Builder
    public static class ValidationResult {
        private boolean passed;
        private List<String> errors;
        private List<String> warnings;
        private List<String> info;
    }

    @Data
    public static class ScheduleFData {
        private String sequenceNum;
        private String businessName;
        private String principalProduct;
        private String ein;
        private boolean materiallyParticipated;
        private BigDecimal grossIncome = BigDecimal.ZERO;
        private BigDecimal totalExpenses = BigDecimal.ZERO;
        private BigDecimal netFarmProfitLoss = BigDecimal.ZERO;
    }

    @Data
    public static class Form8582Data {
        private List<PassiveActivityData> activities;
    }

    @Data
    public static class PassiveActivityData {
        private String activityName;
        private BigDecimal currentYearLoss = BigDecimal.ZERO;
        private BigDecimal overallLoss = BigDecimal.ZERO;
    }
}
