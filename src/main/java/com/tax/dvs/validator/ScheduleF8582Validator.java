package com.tax.dvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ScheduleF8582Validator {

    private static final double TOLERANCE = 0.01;

    public ValidationResult validate(String jsonPayload) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonPayload);

            ValidationResult result = new ValidationResult();
            result.setResult("PASS");

            JsonNode body = root.get("body");
            if (body == null || !body.has("forms")) {
                result.setResult("FAIL");
                result.addReason("Missing /body/forms node");
                return result;
            }

            ArrayNode forms = (ArrayNode) body.get("forms");

            // Extract Schedule F forms
            List<ScheduleFData> scheduleFList = extractScheduleFData(forms);

            // Extract Form 8582 data
            List<Form8582Data> form8582List = extractForm8582Data(forms);

            // Validate each Schedule F
            for (ScheduleFData scheduleF : scheduleFList) {
                // Skip if materially participated or no loss
                if (scheduleF.isMateriallyParticipated()) {
                    continue;
                }

                double calculatedLoss = scheduleF.getLine9() - scheduleF.getLine33();

                if (calculatedLoss >= 0) {
                    // No loss, skip
                    continue;
                }

                // This is a passive loss - must be on Form 8582
                Form8582Data matching8582 = findMatching8582(form8582List, scheduleF.getBusinessName());

                ValidationDetail detail = new ValidationDetail();
                detail.setFarm(scheduleF.getBusinessName());
                detail.setLine9(scheduleF.getLine9());
                detail.setLine33(scheduleF.getLine33());
                detail.setCalculatedLoss(Math.abs(calculatedLoss));

                if (matching8582 == null) {
                    result.setResult("FAIL");
                    result.addReason("Schedule F business '" + scheduleF.getBusinessName() + 
                        "' with passive loss not found on Form 8582");
                    detail.setForm8582Loss(0.0);
                    detail.setMatch(false);
                } else {
                    detail.setForm8582Loss(matching8582.getCurrentYearNetLossAmt());

                    // Compare absolute values
                    double diff = Math.abs(Math.abs(calculatedLoss) - matching8582.getCurrentYearNetLossAmt());
                    
                    if (diff > TOLERANCE) {
                        result.setResult("FAIL");
                        result.addReason("Loss amount mismatch for '" + scheduleF.getBusinessName() + 
                            "': calculated=" + Math.abs(calculatedLoss) + ", Form 8582=" + 
                            matching8582.getCurrentYearNetLossAmt());
                        detail.setMatch(false);
                    } else {
                        detail.setMatch(true);
                    }

                    // Validate Line 34 is zero when loss is suspended
                    if (Math.abs(scheduleF.getLine34()) > TOLERANCE) {
                        result.setResult("FAIL");
                        result.addReason("Schedule F Line 34 (NetFarmProfitLossAmt) for '" + 
                            scheduleF.getBusinessName() + "' should be $0 when loss is suspended, but is " +
                            scheduleF.getLine34());
                        detail.setMatch(false);
                    }
                }

                result.addDetail(detail);
            }

            return result;

        } catch (Exception e) {
            ValidationResult result = new ValidationResult();
            result.setResult("FAIL");
            result.addReason("Error processing payload: " + e.getMessage());
            return result;
        }
    }

    private List<ScheduleFData> extractScheduleFData(ArrayNode forms) {
        List<ScheduleFData> result = new ArrayList<>();

        for (JsonNode form : forms) {
            if (!form.has("formNum") || !"IRS1040ScheduleF".equals(form.get("formNum").asText())) {
                continue;
            }

            ScheduleFData data = new ScheduleFData();

            ArrayNode lineItems = (ArrayNode) form.get("lineItems");
            if (lineItems == null) continue;

            for (JsonNode lineItem : lineItems) {
                String lineName = lineItem.has("lineNameTxt") ? lineItem.get("lineNameTxt").asText() : "";

                if (lineName.contains("FarmProprietorName")) {
                    ArrayNode nameItems = (ArrayNode) lineItem.get("lineItems");
                    if (nameItems != null && nameItems.size() > 0) {
                        JsonNode firstName = nameItems.get(0);
                        if (firstName.has("perReturnValueTxt")) {
                            data.setBusinessName(firstName.get("perReturnValueTxt").asText());
                        }
                    }
                } else if (lineName.contains("PrincipalProductDesc")) {
                    if (lineItem.has("perReturnValueTxt")) {
                        data.setBusinessName(lineItem.get("perReturnValueTxt").asText());
                    }
                } else if (lineName.contains("MateriallyParticipatedInd")) {
                    if (lineItem.has("perReturnValueTxt")) {
                        String value = lineItem.get("perReturnValueTxt").asText();
                        data.setMateriallyParticipated("true".equalsIgnoreCase(value) || "X".equals(value));
                    }
                } else if (lineName.contains("FarmIncomeCashMethodGrp")) {
                    ArrayNode incomeItems = (ArrayNode) lineItem.get("lineItems");
                    if (incomeItems != null) {
                        for (JsonNode incomeItem : incomeItems) {
                            String incomeLine = incomeItem.has("lineNameTxt") ? incomeItem.get("lineNameTxt").asText() : "";
                            if (incomeLine.contains("GrossIncomeAmt") && incomeItem.has("perReturnValueTxt")) {
                                data.setLine9(Double.parseDouble(incomeItem.get("perReturnValueTxt").asText()));
                            }
                        }
                    }
                } else if (lineName.contains("FarmExpensesGrp")) {
                    ArrayNode expenseItems = (ArrayNode) lineItem.get("lineItems");
                    if (expenseItems != null) {
                        for (JsonNode expenseItem : expenseItems) {
                            String expenseLine = expenseItem.has("lineNameTxt") ? expenseItem.get("lineNameTxt").asText() : "";
                            if (expenseLine.contains("TotalExpensesAmt") && expenseItem.has("perReturnValueTxt")) {
                                data.setLine33(Double.parseDouble(expenseItem.get("perReturnValueTxt").asText()));
                            } else if (expenseLine.contains("NetFarmProfitLossAmt") && expenseItem.has("perReturnValueTxt")) {
                                data.setLine34(Double.parseDouble(expenseItem.get("perReturnValueTxt").asText()));
                            }
                        }
                    }
                }
            }

            if (data.getBusinessName() != null) {
                result.add(data);
            }
        }

        return result;
    }

    private List<Form8582Data> extractForm8582Data(ArrayNode forms) {
        List<Form8582Data> result = new ArrayList<>();

        for (JsonNode form : forms) {
            if (!form.has("formNum") || !"IRS8582".equals(form.get("formNum").asText())) {
                continue;
            }

            ArrayNode lineItems = (ArrayNode) form.get("lineItems");
            if (lineItems == null) continue;

            for (JsonNode lineItem : lineItems) {
                String lineName = lineItem.has("lineNameTxt") ? lineItem.get("lineNameTxt").asText() : "";

                if (lineName.contains("ParentWrkshtPassiveGrp")) {
                    ArrayNode parentItems = (ArrayNode) lineItem.get("lineItems");
                    if (parentItems == null) continue;

                    for (JsonNode parentItem : parentItems) {
                        String parentLine = parentItem.has("lineNameTxt") ? parentItem.get("lineNameTxt").asText() : "";

                        if (parentLine.contains("WrkshtPassiveGrp") && !parentLine.contains("Parent")) {
                            ArrayNode wrkshtItems = (ArrayNode) parentItem.get("lineItems");
                            if (wrkshtItems == null) continue;

                            Form8582Data data = new Form8582Data();

                            for (JsonNode wrkshtItem : wrkshtItems) {
                                String wrkshtLine = wrkshtItem.has("lineNameTxt") ? wrkshtItem.get("lineNameTxt").asText() : "";

                                if (wrkshtLine.contains("NonParticipateActivityNm") && wrkshtItem.has("perReturnValueTxt")) {
                                    data.setActivityName(wrkshtItem.get("perReturnValueTxt").asText());
                                } else if (wrkshtLine.contains("CurrentYearNetLossAmt") && wrkshtItem.has("perReturnValueTxt")) {
                                    data.setCurrentYearNetLossAmt(Double.parseDouble(wrkshtItem.get("perReturnValueTxt").asText()));
                                }
                            }

                            if (data.getActivityName() != null) {
                                result.add(data);
                            }
                        }
                    }
                }
            }
        }

        return result;
    }

    private Form8582Data findMatching8582(List<Form8582Data> form8582List, String businessName) {
        for (Form8582Data data : form8582List) {
            if (data.getActivityName() != null && data.getActivityName().equalsIgnoreCase(businessName)) {
                return data;
            }
        }
        return null;
    }

    // Inner classes
    public static class ValidationResult {
        private String result;
        private List<String> reasons = new ArrayList<>();
        private List<ValidationDetail> details = new ArrayList<>();

        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
        public List<String> getReasons() { return reasons; }
        public void addReason(String reason) { this.reasons.add(reason); }
        public List<ValidationDetail> getDetails() { return details; }
        public void addDetail(ValidationDetail detail) { this.details.add(detail); }
    }

    public static class ValidationDetail {
        private String farm;
        private double line9;
        private double line33;
        private double calculatedLoss;
        private double form8582Loss;
        private boolean match;

        public String getFarm() { return farm; }
        public void setFarm(String farm) { this.farm = farm; }
        public double getLine9() { return line9; }
        public void setLine9(double line9) { this.line9 = line9; }
        public double getLine33() { return line33; }
        public void setLine33(double line33) { this.line33 = line33; }
        public double getCalculatedLoss() { return calculatedLoss; }
        public void setCalculatedLoss(double calculatedLoss) { this.calculatedLoss = calculatedLoss; }
        public double getForm8582Loss() { return form8582Loss; }
        public void setForm8582Loss(double form8582Loss) { this.form8582Loss = form8582Loss; }
        public boolean isMatch() { return match; }
        public void setMatch(boolean match) { this.match = match; }
    }

    private static class ScheduleFData {
        private String businessName;
        private double line9;
        private double line33;
        private double line34;
        private boolean materiallyParticipated;

        public String getBusinessName() { return businessName; }
        public void setBusinessName(String businessName) { this.businessName = businessName; }
        public double getLine9() { return line9; }
        public void setLine9(double line9) { this.line9 = line9; }
        public double getLine33() { return line33; }
        public void setLine33(double line33) { this.line33 = line33; }
        public double getLine34() { return line34; }
        public void setLine34(double line34) { this.line34 = line34; }
        public boolean isMateriallyParticipated() { return materiallyParticipated; }
        public void setMateriallyParticipated(boolean materiallyParticipated) { this.materiallyParticipated = materiallyParticipated; }
    }

    private static class Form8582Data {
        private String activityName;
        private double currentYearNetLossAmt;

        public String getActivityName() { return activityName; }
        public void setActivityName(String activityName) { this.activityName = activityName; }
        public double getCurrentYearNetLossAmt() { return currentYearNetLossAmt; }
        public void setCurrentYearNetLossAmt(double currentYearNetLossAmt) { this.currentYearNetLossAmt = currentYearNetLossAmt; }
    }
}
