package com.irs.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Validator for Schedule F matching with Form 8582 (Passive Activity Loss Limitations)
 * 
 * Business Rules:
 * 1. Business names must match between Schedule F and Form 8582
 * 2. For passive activities (no material participation):
 *    a. Schedule F Line 34 (NetFarmProfitLossAmt) should show $0
 *    b. Actual loss = Line 9 (GrossIncomeAmt) - Line 33 (TotalExpensesAmt)
 *    c. Actual loss must be tracked on Form 8582
 * 3. For active activities (material participation):
 *    a. Schedule F Line 34 shows actual net profit/loss
 *    b. NOT included on Form 8582 (not passive)
 * 
 * IRS References: Form 8582 Instructions, Section 469 Passive Activity Rules
 */
@Component
public class ScheduleF8582Validator {

    private static final String SCHEDULE_F_FORM = "IQS1040ScheduleF";
    private static final String FORM_8582 = "IRS8582";
    private static final String FORM_8995 = "IRS8995";

    /**
     * Validates Schedule F matching with Form 8582
     * 
     * @param payload The full tax return JSON payload
     * @return ValidationResult containing pass/fail and detailed messages
     */
    public ValidationResult validate(JsonNode payload) {
        ValidationResult result = ValidationResult.builder()
                .passed(true)
                .messages(new ArrayList<>())
                .errors(new ArrayList<>())
                .warnings(new ArrayList<>())
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
            
            // Parse Schedule F forms
            List<ScheduleFData> scheduleFList = parseScheduleFForms(forms);
            result.getMessages().add("Found " + scheduleFList.size() + " Schedule F forms");

            // Parse Form 8582
            Form8582Data form8582 = parseForm8582(forms);
            if (form8582 != null) {
                result.getMessages().add("Found Form 8582 with " + form8582.getActivities().size() + " passive activities");
            }

            // Validate each Schedule F
            for (ScheduleFData scheduleF : scheduleFList) {
                validateScheduleF(scheduleF, form8582, result);
            }

            // Validate Form 8582 activities are all accounted for
            if (form8582 != null) {
                validateForm8582Activities(scheduleFList, form8582, result);
            }

            // Final summary
            if (result.getErrors().isEmpty()) {
                result.getMessages().add("VALIDATION PASSED: All Schedule F to Form 8582 matching rules satisfied");
            } else {
                result.setPassed(false);
                result.getMessages().add("VALIDATION FAILED: " + result.getErrors().size() + " error(s) found");
            }

        } catch (Exception e) {
            result.setPassed(false);
            result.getErrors().add("Validation exception: " + e.getMessage());
        }

        return result;
    }

    /**
     * Parses all Schedule F forms from the payload
     */
    private List<ScheduleFData> parseScheduleFForms(JsonNode forms) {
        List<ScheduleFData> scheduleFList = new ArrayList<>();

        for (JsonNode form : forms) {
            String formNum = form.get("formNum").asText();
            if (SCHEDULE_F_FORM.equals(formNum)) {
                ScheduleFData scheduleF = parseScheduleFData(form);
                scheduleFList.add(scheduleF);
            }
        }

        return scheduleFList;
    }

    /**
     * Parses a single Schedule F form
     */
    private ScheduleFData parseScheduleFData(JsonNode form) {
        ScheduleFData data = new ScheduleFData();
        data.setSequenceNum(form.get("sequenceNum").asText());

        JsonNode lineItems = form.get("lineItems");
        for (JsonNode lineItem : lineItems) {
            String lineName = lineItem.get("lineNameTxt").asText();

            // Business Name
            if (lineName.contains("FarmProprietorName/BusinessNameLine1Txt")) {
                data.setBusinessName(lineItem.get("perReturnValueTxt").asText());
            }
            // Principal Product
            else if (lineName.contains("PrincipalProductDesc")) {
                data.setPrincipalProduct(lineItem.get("perReturnValueTxt").asText());
            }
            // Material Participation
            else if (lineName.contains("MateriallyParticipatedInd")) {
                data.setMateriallyParticipated("true".equalsIgnoreCase(lineItem.get("perReturnValueTxt").asText()));
            }
            // Farm Income Group
            else if (lineName.contains("FarmIncomeCashMethodGrp")) {
                parseFarmIncomeGroup(lineItem, data);
            }
            // Farm Expenses Group
            else if (lineName.contains("FarmExpensesGrp")) {
                parseFarmExpensesGroup(lineItem, data);
            }
        }

        return data;
    }

    /**
     * Parses Farm Income Group (Line 9)
     */
    private void parseFarmIncomeGroup(JsonNode group, ScheduleFData data) {
        if (group.has("lineItems")) {
            for (JsonNode item : group.get("lineItems")) {
                String lineName = item.get("lineNameTxt").asText();
                if (lineName.contains("GrossIncomeAmt")) {
                    data.setGrossIncome(parseBigDecimal(item.get("perReturnValueTxt")));
                }
            }
        }
    }

    /**
     * Parses Farm Expenses Group (Line 33 and 34)
     */
    private void parseFarmExpensesGroup(JsonNode group, ScheduleFData data) {
        if (group.has("lineItems")) {
            for (JsonNode item : group.get("lineItems")) {
                String lineName = item.get("lineNameTxt").asText();
                if (lineName.contains("TotalExpensesAmt")) {
                    data.setTotalExpenses(parseBigDecimal(item.get("perReturnValueTxt")));
                } else if (lineName.contains("NetFarmProfitLossAmt")) {
                    data.setNetProfitLoss(parseBigDecimal(item.get("perReturnValueTxt")));
                }
            }
        }
    }

    /**
     * Parses Form 8582
     */
    private Form8582Data parseForm8582(JsonNode forms) {
        for (JsonNode form : forms) {
            String formNum = form.get("formNum").asText();
            if (FORM_8582.equals(formNum)) {
                return parseForm8582Data(form);
            }
        }
        return null;
    }

    /**
     * Parses Form 8582 data
     */
    private Form8582Data parseForm8582Data(JsonNode form) {
        Form8582Data data = new Form8582Data();
        data.setActivities(new ArrayList<>());

        JsonNode lineItems = form.get("lineItems");
        for (JsonNode lineItem : lineItems) {
            String lineName = lineItem.get("lineNameTxt").asText();

            // Parse ParentWrkshtPassiveGrp
            if (lineName.contains("ParentWrkshtPassiveGrp")) {
                parsePassiveActivities(lineItem, data);
            }
        }

        return data;
    }

    /**
     * Parses passive activities from Form 8582
     */
    private void parsePassiveActivities(JsonNode group, Form8582Data data) {
        if (group.has("lineItems")) {
            for (JsonNode item : group.get("lineItems")) {
                String lineName = item.get("lineNameTxt").asText();

                if (lineName.contains("WrkshtPassiveGrp") && item.has("lineItems")) {
                    PassiveActivity activity = new PassiveActivity();

                    for (JsonNode actItem : item.get("lineItems")) {
                        String actLine = actItem.get("lineNameTxt").asText();

                        if (actLine.contains("NonParticipateActivityNm")) {
                            activity.setActivityName(actItem.get("perReturnValueTxt").asText());
                        } else if (actLine.contains("CurrentYearNetLossAmt")) {
                            activity.setCurrentYearLoss(parseBigDecimal(actItem.get("perReturnValueTxt")));
                        } else if (actLine.contains("OverallLossAmt")) {
                            activity.setOverallLoss(parseBigDecimal(actItem.get("perReturnValueTxt")));
                        }
                    }

                    if (activity.getActivityName() != null) {
                        data.getActivities().add(activity);
                    }
                }
            }
        }
    }

    /**
     * Validates a single Schedule F against Form 8582
     */
    private void validateScheduleF(ScheduleFData scheduleF, Form8582Data form8582, ValidationResult result) {
        String businessName = scheduleF.getBusinessName();
        String product = scheduleF.getPrincipalProduct();
        boolean isMaterial = scheduleF.isMateriallyParticipated();

        // Calculate expected loss (Line 9 - Line 33)
        BigDecimal grossIncome = scheduleF.getGrossIncome();
        BigDecimal totalExpenses = scheduleF.getTotalExpenses();
        BigDecimal calculatedLoss = grossIncome.subtract(totalExpenses);
        BigDecimal reportedNet = scheduleF.getNetProfitLoss();

        result.getMessages().add("\nValidating Schedule F - " + product);
        result.getMessages().add("  Business: " + businessName);
        result.getMessages().add("  Material Participation: " + isMaterial);
        result.getMessages().add("  Gross Income (Line 9): $" + grossIncome);
        result.getMessages().add("  Total Expenses (Line 33): $" + totalExpenses);
        result.getMessages().add("  Calculated Net: $" + calculatedLoss);
        result.getMessages().add("  Reported Net (Line 34): $" + reportedNet);

        if (isMaterial) {
            // Rule: Material participation = NOT passive, should NOT be on Form 8582
            result.getMessages().add("  ✅ Material participation - not passive, should NOT be on Form 8582");

            // Verify Line 34 shows actual net
            if (reportedNet.compareTo(calculatedLoss) != 0) {
                result.getErrors().add("ERROR: " + product + " - Line 34 should show $" + calculatedLoss + " but shows $" + reportedNet);
            } else {
                result.getMessages().add("  ✅ Line 34 correctly shows actual net profit/loss");
            }

            // Verify NOT in Form 8582
            if (form8582 != null && isInForm8582(product, form8582)) {
                result.getErrors().add("ERROR: " + product + " has material participation but is included in Form 8582");
            } else {
                result.getMessages().add("  ✅ Correctly excluded from Form 8582");
            }

        } else {
            // Rule: No material participation = PASSIVE, must be on Form 8582
            result.getMessages().add("  ✅ No material participation - passive activity");

            // Rule: If loss, Line 34 should show $0
            if (calculatedLoss.compareTo(BigDecimal.ZERO) < 0) {
                result.getMessages().add("  ✅ Calculated loss: $" + calculatedLoss);

                if (reportedNet.compareTo(BigDecimal.ZERO) != 0) {
                    result.getErrors().add("ERROR: " + product + " - Passive loss should show $0 on Line 34 but shows $" + reportedNet);
                } else {
                    result.getMessages().add("  ✅ Line 34 correctly shows $0 for passive loss");
                }

                // Verify in Form 8582
                if (form8582 == null) {
                    result.getErrors().add("ERROR: " + product + " has passive loss but Form 8582 is missing");
                } else {
                    PassiveActivity activity = findInForm8582(product, form8582);
                    if (activity == null) {
                        result.getErrors().add("ERROR: " + product + " not found in Form 8582");
                    } else {
                        result.getMessages().add("  ✅ Found in Form 8582");

                        // Verify business name match
                        if (!product.equalsIgnoreCase(activity.getActivityName())) {
                            result.getWarnings().add("WARNING: " + product + " - Name mismatch with Form 8582: " + activity.getActivityName());
                        } else {
                            result.getMessages().add("   ✅ Business name matches");
                        }

                        // Verify loss amount match
                        BigDecimal expectedLoss = calculatedLoss.abs();
                        BigDecimal form8582Loss = activity.getOverallLoss();

                        result.getMessages().add("  Form 8582 loss: $" + form8582Loss);

                        if (expectedLoss.compareTo(form8582Loss) != 0) {
                            result.getErrors().add("ERROR: " + product + " - Loss mismatch. Expected: $" + expectedLoss + ", Form 8582: $" + form8582Loss);
                        } else {
                            result.getMessages().add("  ✅ Loss amount matches Form 8582");
                        }
                    }
                }
            } else {
                // No loss - should show actual net
                result.getMessages().add("  ✅ No loss - Line 34 should show actual net");
                if (reportedNet.compareTo(calculatedLoss) != 0) {
                    result.getErrors().add("ERROR: " + product + " - Line 34 should show $" + calculatedLoss + " but shows $" + reportedNet);
                }
            }
        }
    }

    /**
     * Validates that all Form 8582 activities have corresponding Schedule F
     */
    private void validateForm8582Activities(List<ScheduleFData> scheduleFList, Form8582Data form8582, ValidationResult result) {
        result.getMessages().add("\nValidating Form 8582 activities have matching Schedule F...");

        for (PassiveActivity activity : form8582.getActivities()) {
            String activityName = activity.getActivityName();
            boolean found = false;

            for (ScheduleFData scheduleF : scheduleFList) {
                if (activityName.equalsIgnoreCase(scheduleF.getPrincipalProduct())) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                result.getErrors().add("ERROR: Form 8582 activity '" + activityName + "' not found in any Schedule F");
            } else {
                result.getMessages().add(" ✅ " + activityName + " matches Schedule F");
            }
        }
    }

    /**
     * Checks if an activity is in Form 8582
     */
    private boolean isInForm8582(String productName, Form8582Data form8582) {
        return findInForm8582(productName, form8582) != null;
    }

    /**
     * Finds an activity in Form 8582
     */
    private PassiveActivity findInForm8582(String productName, Form8582Data form8582) {
        for (PassiveActivity activity : form8582.getActivities()) {
            if (productName.equalsIgnoreCase(activity.getActivityName())) {
                return activity;
            }
        }
        return null;
    }

    /**
     * Parses BigDecimal from JsonNode
     */
    private BigDecimal parseBigDecimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return BigDecimal.ZERO;
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    // Data Classes

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ValidationResult {
        private boolean passed;
        private List<String> messages;
        private List<String> errors;
        private List<String> warnings;
    }

    @Data
    public static class ScheduleFData {
        private String sequenceNum;
        private String businessName;
        private String principalProduct;
        private boolean materiallyParticipated;
        private BigDecimal grossIncome = BigDecimal.ZERO;
        private BigDecimal totalExpenses = BigDecimal.ZERO;
        private BigDecimal netProfitLoss = BigDecimal.ZERO;
    }

    @Data
    public static class Form8582Data {
        private List<PassiveActivity> activities;
    }

    @Data
    public static class PassiveActivity {
        private String activityName;
        private BigDecimal currentYearLoss = BigDecimal.ZERO;
        private BigDecimal overallLoss = BigDecimal.ZERO;
    }
}
