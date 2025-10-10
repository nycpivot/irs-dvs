package com.tax.dvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Validator for Schedule F to Form 8582 matching and validation.
 * Implements business rules for passive activity loss reporting.
 *
 * Based on IRS Rules:
 * - Schedule F Line 34 (NetFarmProfitLossAmt) must equal Line 9 (GrossIncome) - Line 33 (TotalExpenses)
 * - Passive activities (MateriallyParticipatedInd = false) must be reported on Form 8582
 * - Business names must match between Schedule F and Form 8582
 * - Loss amounts must match between calculated values and Form 8582
 */
@Component
public class ScheduleFTo8582Validator {

    private static final String SCHEDULE_F_FORM = "IQS1040ScheduleF";
    private static final String FORM_8582 = "IRS8582";
    private static final String FORM_1040 = "IRS1040";
    
    /**
     * Validates the entire tax return payload for Schedule F to Form 8582 consistency.
     *
     * @param payload The full tax return JSON payload
     * @return ValidationResult containing all validation errors and warnings
     */
    public ValidationResult validate(JsonNode payload) {
        ValidationResult result = new ValidationResult();
        
        if (payload == null || !payload.has("body")) {
            result.addError("Invalid payload structure: missing 'body' node");
            return result;
        }
        
        JsonNode body = payload.get("body");
        if (!body.has("forms")) {
            result.addError("Invalid payload structure: missing 'forms' node");
            return result;
        }
        
        // Extract all Schedule F forms
        List<ScheduleFData> scheduleFForms = extractScheduleFForms(body.get("forms"));
        
        // Extract Form 8582 data
        Form8582Data form8582Data = extractForm8582Data(body.get("forms"));
        
        // Validate each Schedule F
        for (ScheduleFData scheduleF : scheduleFForms) {
            validateScheduleF(scheduleF, result);
        }
        
        // Validate Schedule F to Form 8582 matching
        if (form8582Data != null) {
            validateScheduleFTo8582Matching(scheduleFForms, form8582Data, result);
        }
        
        return result;
    }
    
    /**
     * Validates an individual Schedule F for internal consistency.
     */
    private void validateScheduleF(ScheduleFData scheduleF, ValidationResult result) {
        String businessName = scheduleF.getBusinessName();
        
        // Rule 1: Calculate expected net profit/loss
        BigDecimal calculatedNetPL = scheduleF.getGrossIncome().subtract(scheduleF.getTotalExpenses());
        
        // Rule 2: For passive activities with losses, NetFarmProfitLossAmt should be 0
        // because the loss is disallowed and reported on Form 8582
        if (!scheduleF.isMateriallyParticipated() && calculatedNetPL.compareTo(BigDecimal.ZERO) < 0) {
            // Passive activity with loss - should show 0 on Schedule F
            if (scheduleF.getNetFarmProfitLossAmt().compareTo(BigDecimal.ZERO) != 0) {
                result.addError(String.format(
                    "Schedule F '%s': Passive activity with loss must show $0 on Line 34 (NetFarmProfitLossAmt). " +
                    "Calculated loss: %s, Reported: %s. Loss should be reported on Form 8582.",
                    businessName, calculatedNetPL, scheduleF.getNetFarmProfitLossAmt()
                ));
            }
        } else {
            // Active participation or regular income - should match calculation
            if (scheduleF.getNetFarmProfitLossAmt().compareTo(calculatedNetPL"€!= 0) {
                result.addError(String.format(
                    "Schedule F '%s': NetFarmProfitLossAmt (%s) does not match calculated value (%s). " +
                    "Line 34 should equal Line 9 (%s) - Line 33 (%s).",
                    businessName, scheduleF.getNetFarmProfitLossAmt(), calculatedNetPL%Š              YcheduleF.getGrossIncome(), scheduleF.getTotalExpenses()
                ));
            }
        }
        
        // Rule 3: Validate passive activity flag consistency
        if (!scheduleF.isMateriallyParticipated() && calculatedNetPL.compareTo(BigDecimal.ZERO) < 0) {
            result.addWarning(String.format(
                "Schedule F '%s': Passive activity with loss (%s). Must be reported on Form 8582.",
                businessName, calculatedNetPL
            ));
        }
    }
    
    /**
     * Validates matching between Schedule F and Form 8582.
     */
    private void validateScheduleFTo8582Matching(
            List<ScheduleFData> scheduleFForms,
            Form8582Data form8582Data,
            ValidationResult result) {
        
        // Create map of passive activities from Schedule F
        Map<String, ScheduleFData> passiveScheduleFs = scheduleFForms.stream()
                .filter(sf -> !sf.isMateriallyParticipated())
                .collect(Collectors.toMap(
                        ScheduleFData::getBusinessName,
                        sf -> sf,
                        (existing, replacement) -> existing // Handle duplicates
                ));
        
        // Rule 4: Validate business name matching
        for (PassiveActivity activity : form8582Data.getPassiveActivities()) {
            String activityName = activity.getActivityName();
            
            if (!passiveScheduleFs.containsKey(activityName)) {
                result.addError(String.format(
                    "Form 8582: Activity '%s' not found in Schedule F forms or is not marked as passive.",
                    activityName
                ));
                continue;
            }
            
            ScheduleFData matchedScheduleF = passiveScheduleFs.get(activityName);
            
            // Rule 5: Validate loss amount matching
            BigDecimal calculatedLoss = matchedScheduleF.getGrossIncome()
                    .subtract(matchedScheduleF.getTotalExpenses());
            
            // Convert to positive for comparison (losses are positive on Form 8582)
            BigDecimal expectedLossAmount = calculatedLoss.abs();
            BigDecimal reportedLossAmount = activity.getCurrentYearNetLossAmt();
            
            if (expectedLossAmount.compareTo(reportedLossAmount) != 0) {
                result.addError(String.format(
                    "Form 8582: Activity '%s' loss amount (%s) does not match calculated Schedule F loss (%s). " +
                    "Schedule F: Line 9 (%s) - Line 33 (%s) = %s.",
                    activityName, reportedLossAmount, expectedLossAmount,
                    matchedScheduleF.getGrossIncome(), matchedScheduleF.getTotalExpenses(), calculatedLoss
                ));
            }
        }
        
        // Rule 6: Check for passive activities not reported on Form 8582
        Set<String> form8582Activities = form8582Data.getPassiveActivities().stream()
                .map(PassiveActivity::getActivityName)
                .collect(Collectors.toSet());
        
        for (Map.Entry<String, ScheduleFData> entry : passiveScheduleFs.entrySet()) {
            String businessName = entry.getKey();
            ScheduleFData scheduleF = entry.getValue();
            
            BigDecimal calculatedNetPL = scheduleF.getGrossIncome().subtract(scheduleF.getTotalExpenses());
            
            if (calculatedNetPL.compareTo(BigDecimal.ZERO) < 0 && !form8582Activities.contains(businessName)) {
                result.addError(String.format(
                    "Schedule F '%s': Passive activity with loss (%s) not reported on Form 8582.",
                    businessName, calculatedNetPL.abs()
                ));
            }
        }
    }
    
    /**
     * Extracts all Schedule F forms from the payload.
     */
    private List<ScheduleFData> extractScheduleFForms(JsonNode formsNode) {
        List<ScheduleFData> scheduleFForms = new ArrayList<>();
        
        if (!formsNode.isArray()) {
            return scheduleFForms;
        }
        
        for (JsonNode form : formsNode) {
            if (form.has("formNum") && SCHEDULE_F_FORM.equals(form.get("formNum").asText())) {
                scheduleFForms.add(parseScheduleF(form));
            }
        }
        
        return scheduleFForms;
    }
    
    /**
     * Parses a single Schedule F form.
     */
    private ScheduleFData parseScheduleF(JsonNode form) {
        ScheduleFData data = new ScheduleFData();
        
        if (!form.has("lineItems")) {
            return data;
        }
        
        for (JsonNode lineItem : form.get("lineItems")) {
            String lineName = lineItem.has("lineNameTxt") ? lineItem.get("lineNameTxt").asText() : "";
            
            switch (lineName) {
                case "/IRS1040ScheduleF/FarmProprietorName":
                    data.setBusinessName(extractBusinessName(lineItem));
                    break;
                case "/IRS1040ScheduleF/PrincipalProductDesc":
                    data.setPrincipalProduct(lineItem.get("perReturnValueTxt").asText());
                    break;
                case "/IRS1040ScheduleF/MateriallyParticipatedInd":
                    data.setMateriallyParticipated(
                        "true".equalsIgnoreCase(lineItem.get("perReturnValueTxt").asText())
                    );
                    break;
                case "/IRS1040ScheduleF/FarmIncomeCashMethodGrp":
                    parseFarmIncomeGroup(lineItem, data);
                    break;
                case "/IRS1040ScheduleF/FarmExpensesGrp":
                    parseFarmExpensesGroup(lineItem, data);
                    break;
            }
        }
        
        return data;
    }
    
    private String extractBusinessName(JsonNode lineItem) {
        if (lineItem.has("lineItems")) {
            for (JsonNode child : lineItem.get("lineItems")) {
                if (child.has("lineNameTxt") && 
                    child.get("lineNameTxt").asText().contains("BusinessNameLine1Txt")) {
                    return child.get("perReturnValueTxt").asText();
                }
            }
        }
        return "";
    }
    
    private void parseFarmIncomeGroup(JsonNode lineItem, ScheduleFData data) {
        if (lineItem.has("lineItems")) {
            for (JsonNode child : lineItem.get("lineItems")) {
                String childLineName = child.has("lineNameTxt") ? child.get("lineNameTxt").asText() : "";
                if (childLineName.contains("GrossIncomeAmt")) {
                    data.setGrossIncome(new BigDecimal(child.get("perReturnValueTxt").asText()));
                }
            }
        }
    }
    
    private void parseFarmExpensesGroup(JsonNode lineItem, ScheduleFData data) {
        if (lineItem.has("lineItems")) {
            for (JsonNode child : lineItem.get("lineItems")) {
                String childLineName = child.has("lineNameTxt") ? child.get("lineNameTxt").asText() : "";
                if (childLineName.contains("TotalExpensesAmt")) {
                    data.setTotalExpenses(new BigDecimal(child.get("perReturnValueTxt").asText()));
                } else if (childLineName.contains("NetFarmProfitLossAmt")) {
                    data.setNetFarmProfitLossAmt(new BigDecimal(child.get("perReturnValueTxt").asText()));
                }
            }
        }
    }
    
    /**
     * Extracts Form 8582 data from the payload.
     */
    private Form8582Data extractForm8582Data(JsonNode formsNode) {
        if (!formsNode.isArray()) {
            return null;
        }
        
        for (JsonNode form : formsNode) {
            if (form.has("formNum") && FORM_8582.equals(form.get("formNum").asText())) {
                return parseForm8582(form);
            }
        }
        
        return null;
    }
    
    private Form8582Data parseForm8582(JsonNode form) {
        Form8582Data data = new Form8582Data();
        
        if (!form.has("lineItems")) {
            return data;
        }
        
        for (JsonNode lineItem : form.get("lineItems")) {
            String lineName = lineItem.has("lineNameTxt") ? lineItem.get("lineNameTxt").asText() : "";
            
            if (lineName.contains("ParentWrkshtPassiveGrp")) {
                parsePassiveActivities(lineItem, data);
            }
        }
        
        return data;
    }
    
    private void parsePassiveActivities(JsonNode parentNode, Form8582Data data) {
        if (!parentNode.has("lineItems")) {
            return;
        }
        
        for (JsonNode lineItem : parentNode.get("lineItems")) {
            String lineName = lineItem.has("lineNameTxt") ? lineItem.get("lineNameTxt").asText() : "";
            
            if (lineName.contains("WrkshtPassiveGrp") && !lineName.contains("Parent")) {
                PassiveActivity activity = parsePassiveActivity(lineItem);
                if (activity.getActivityName() != null && !activity.getActivityName().isEmpty()) {
                    data.getPassiveActivities().add(activity);
                }
            }
        }
    }
    
    private PassiveActivity parsePassiveActivity(JsonNode node) {
        PassiveActivity activity = new PassiveActivity();
        
        if (node.has("lineItems")) {
            for (JsonNode child : node.get("lineItems")) {
                String childLineName = child.has("lineNameTxt") ? child.get("lineNameTxt").asText() : "";
                
                if (childLineName.contains("NonParticipateActivityNm")) {
                    activity.setActivityName(child.get("perReturnValueTxt").asText());
                } else if (childLineName.contains("CurrentYearNetLossAmt")) {
                    activity.setCurrentYearNetLossAmt(new BigDecimal(child.get("perReturnValueTxt").asText()));
                }
            }
        }
        
        return activity;
    }
    
    // Data classes
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ScheduleFData {
        private String businessName;
        private String principalProduct;
        private boolean materiallyParticipated;
        private BigDecimal grossIncome = BigDecimal.ZERO;
        private BigDecimal totalExpenses = BigDecimal.ZERO;
        private BigDecimal netFarmProfitLossAmt = BigDecimal.ZERO;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Form8582Data {
        private List<PassiveActivity> passiveActivities = new ArrayList<>();
    }
    
    @Data
    @NoArgsConstructor
    @Fuilder
    public static class PassiveActivity {
        private String activityName;
        private BigDecimal currentYearNetLossAmt = BigDecimal.ZERO;
    }
    
    @Data
    @NoArgsConstructor
    @Fuilder
    public static class ValidationResult {
        private List<String> errors = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
        
        public void addError(String error) {
            errors.add(error);
        }
        
        public void addWarning(String warning) {
            warnings.add(warning);
        }
        
        public boolean isValid() {
            return errors.isEmpty();
        }
        
        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
    }
}