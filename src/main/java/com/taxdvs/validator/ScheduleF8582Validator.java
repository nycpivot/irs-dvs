package com.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * Validator for matching Schedule F data with Form 8582 (Passive Activity Loss Limitations)
 * 
 * Business Rules:
 * 1. Match business name between Schedule F and Form 8582
 * 2. Calculate loss as Line 9 (Gross Income) - Line 33 (Total Expenses)
 * 3. Verify calculated loss matches Form 8582 loss amount
 * 4. Handle passive activities (material participation = false)
 * 5. When Schedule F shows loss, NetFarmProfitLossAmt may show $0
 */
@Service
public class ScheduleF8582Validator {

    private static final String SCHEDULE_F_PATH = "/IRS1040ScheduleF";
    private static final String FORM_8582_PATH = "/IRS8582";

    /**
     * Validates the entire payload for Schedule F to Form 8582 matching
     */
    public ValidationResult validate(JsonNode payload) {
        ValidationResult result = new ValidationResult();
        
        try {
            // Extract Schedule F and Form 8582 data
            List<ScheduleFData> scheduleFList = extractScheduleFData(payload);
            Form8582Data form8582Data = extractForm8582Data(payload);
            
            if (scheduleFList.isEmpty()) {
                result.addError("No Schedule F data found in payload");
                return result;
            }
            
            if (form8582Data == null) {
                result.addWarning("No Form 8582 data found in payload");
                return result;
            }
            
            // Validate each Schedule F against Form 8582
            for (ScheduleFData scheduleF : scheduleFList) {
                validateScheduleFAgainstForm8582(scheduleF, form8582Data, result);
            }
            
        } catch (Exception e) {
            result.addError("Validation error: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Extracts all Schedule F data from payload
     */
    private List<ScheduleFData> extractScheduleFData(JsonNode payload) {
        List<ScheduleFData> scheduleFList = new ArrayList<>();
        
        JsonNode body = payload.get("body");
        if (body == null) return scheduleFList;
        
        JsonNode forms = body.get("forms");
        if (forms == null || !forms.isArray()) return scheduleFList;
        
        for (JsonNode form : forms) {
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
     * Parses a single Schedule F form
     */
    private ScheduleFData parseScheduleF(JsonNode form) {
        ScheduleFData data = new ScheduleFData();
        
        JsonNode lineItems = form.get("lineItems");
        if (lineItems == null || !lineItems.isArray()) return null;
        
        for (JsonNode lineItem : lineItems) {
            String lineName = getTextValue(lineItem, "lineNameTxt");
            
            // Business Name
            if (lineName.contains("/FarmProprietorName/BusinessNameLine1Txt")) {
                data.businessName = getTextValue(lineItem, "perReturnValueTxt");
            }
            // Principal Product
            else if (lineName.contains("/PrincipalProductDesc")) {
                data.principalProduct = getTextValue(lineItem, "perReturnValueTxt");
            }
            // EIN
            else if (lineName.endsWith("/EIN")) {
                data.ein = getTextValue(lineItem, "perReturnValueTxt");
            }
            // Material Participation
            else if (lineName.contains("/MateriallyParticipatedInd")) {
                data.materiallyParticipated = "true".equalsIgnoreCase(getTextValue(lineItem, "perReturnValueTxt"));
            }
            // Gross Income (Line 9)
            else if (lineName.contains("/FarmIncomeCashMethodGrp/GrossIncomeAmt")) {
                data.grossIncome = parseBigDecimal(lineItem, "perReturnValueTxt");
            }
            // Total Expenses (Line 33)
            else if (lineName.contains("/FarmExpensesGrp/TotalExpensesAmt")) {
                data.totalExpenses = parseBigDecimal(lineItem, "perReturnValueTxt");
            }
            // Net Farm Profit/Loss (Line 34)
            else if (lineName.contains("/FarmExpensesGrp/NetFarmProfitLossAmt")) {
                data.netFarmProfitLoss = parseBigDecimal(lineItem, "perReturnValueTxt");
            }
        }
        
        // Calculate loss if not explicitly stated
        if (data.grossIncome != null && data.totalExpenses != null) {
            data.calculatedLoss = data.grossIncome.subtract(data.totalExpenses);
        }
        
        return data;
    }

    /**
     * Extracts Form 8582 data from payload
     */
    private Form8582Data extractForm8582Data(JsonNode payload) {
        Form8582Data data = new Form8582Data();
        
        JsonNode body = payload.get("body");
        if (body == null) return null;
        
        JsonNode forms = body.get("forms");
        if (forms == null || !forms.isArray()) return null;
        
        for (JsonNode form : forms) {
            String formNum = getTextValue(form, "formNum");
            
            if ("IRS8582".equals(formNum)) {
                parseForm8582(form, data);
                break;
            }
        }
        
        return data.activities.isEmpty() ? null : data;
    }

    /**
     * Parses Form 8582 data
     */
    private void parseForm8582(JsonNode form, Form8582Data data) {
        JsonNode lineItems = form.get("lineItems");
        if (lineItems == null || !lineItems.isArray()) return;
        
        for (JsonNode lineItem : lineItems) {
            String lineName = getTextValue(lineItem, "lineNameTxt");
            
            // Parse Worksheet Passive Group (Part VII)
            if (lineName.contains("/ParentWrkshtPassiveGrp/WrkshtPassiveGrp")) {
                parsePassiveActivity(lineItem, data);
            }
        }
    }

    /**
     * Parses a passive activity from Form 8582
     */
    private void parsePassiveActivity(JsonNode activityNode, Form8582Data data) {
        PassiveActivity activity = new PassiveActivity();
        
        JsonNode lineItems = activityNode.get("lineItems");
        if (lineItems == null || !lineItems.isArray()) return;
        
        for (JsonNode lineItem : lineItems) {
            String lineName = getTextValue(lineItem, "lineNameTxt");
            
            if (lineName.contains("/NonParticipateActivityNm")) {
                activity.activityName = getTextValue(lineItem, "perReturnValueTxt");
            } else if (lineName.contains("/CurrentYearNetLossAmt")) {
                activity.currentYearLoss = parseBigDecimal(lineItem, "perReturnValueTxt");
            } else if (lineName.contains("/OverallLossAmt")) {
                activity.overallLoss = parseBigDecimal(lineItem, "perReturnValueTxt");
            }
        }
        
        if (activity.activityName != null && !activity.activityName.isEmpty()) {
            data.activities.add(activity);
        }
    }

    /**
     * Validates a Schedule F against Form 8582
     */
    private void validateScheduleFAgainstForm8582(ScheduleFData scheduleF, Form8582Data form8582, ValidationResult result) {
        String context = "Schedule F: " + scheduleF.businessName;
        
        // Rule 1: Only validate if not materially participated (passive activity)
        if (scheduleF.materiallyParticipated) {
            result.addInfo(context + ": Materially participated - not a passive activity, skipping Form 8582 validation");
            return;
        }
        
        // Rule 2: Calculate loss as Line 9 - Line 33
        if (scheduleF.grossIncome == null || scheduleF.totalExpenses == null) {
            result.addError(context + ": Missing gross income or total expenses");
            return;
        }
        
        BigDecimal calculatedLoss = scheduleF.calculatedLoss;
        
        // Rule 3: Find matching activity in Form 8582
        PassiveActivity matchingActivity = findMatchingActivity(scheduleF, form8582.activities);
        
        if (matchingActivity == null) {
            result.addError(context + ": No matching activity found in Form 8582");
            return;
        }
        
        // Rule 4: Verify business name matches
        if (!normalizeName(scheduleF.businessName).equalsIgnoreCase(normalizeName(matchingActivity.activityName))) {
            result.addWarning(context + ": Business name mismatch. Schedule F: " + scheduleF.businessName + ", Form 8582: " + matchingActivity.activityName);
        } else {
            result.addInfo(context + ": Business name matches");
        }
        
        // Rule 5: Verify loss amount matches
        BigDecimal form8582Loss = matchingActivity.overallLoss != null ? matchingActivity.overallLoss : matchingActivity.currentYearLoss;
        
        if (form8582Loss == null) {
            result.addError(context + ": No loss amount found in Form 8582");
            return;
        }
        
        // Compare absolute values (losses are positive in 8582)
        BigDecimal calculatedLossAbs = calculatedLoss.abs();
        BigDecimal form8582LossAbs = form8582Loss.abs();
        
        if (calculatedLossAbs.compareTo(form8582LossAbs) == 0) {
            result.addInfo(context + ": Loss amount matches. Calculated: $" + calculatedLoss + ", Form 8582: $" + form8582Loss);
        } else {
            result.addError(context + ": Loss amount mismatch. Calculated (Line 9 - Line 33): $" + calculatedLoss + ", Form 8582: $" + form8582Loss);
        }
        
        // Rule 6: Warn if NetFarmProfitLossAmt shows zero when there's a loss
        if (scheduleF.netFarmProfitLoss != null && scheduleF.netFarmProfitLoss.compareTo(BigDecimal.ZERO) == 0 && calculatedLoss.compareTo(BigDecimal.ZERO) < 0) {
            result.addWarning(context + ": NetFarmProfitLossAmt shows $0 but calculated loss is $" + calculatedLoss + ". This is expected for passive activities with losses.");
        }
    }

    /**
     * Finds matching activity in Form 8582 based on business name or principal product
     */
    private PassiveActivity findMatchingActivity(ScheduleFData scheduleF, List<PassiveActivity> activities) {
        String normalizedName = normalizeName(scheduleF.businessName);
        String normalizedProduct = normalizeName(scheduleF.principalProduct);
        
        for (PassiveActivity activity : activities) {
            String normalizedActivityName = normalizeName(activity.activityName);
            
            // Exact match on business name
            if (normalizedName.equalsIgnoreCase(normalizedActivityName)) {
                return activity;
            }
            
            // Match on principal product
            if (normalizedProduct != null && normalizedProduct.equalsIgnoreCase(normalizedActivityName)) {
                return activity;
            }
            
            // Partial match
            if (normalizedName.contains(normalizedActivityName) || normalizedActivityName.contains(normalizedName)) {
                return activity;
            }
        }
        
        return null;
    }

    /**
     * Normalizes a name for comparison
     */
    private String normalizeName(String name) {
        if (name == null) return "";
        return name.trim().toUpperCase().replaceAll("\\s+", " ");
    }

    /**
     * Helper method to get text value from JsonNode
     */
    private String getTextValue(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return (field != null && !field.isNull()) ? field.asText() : null;
    }

    /**
     * Helper method to parse BigDecimal from JsonNode
     */
    private BigDecimal parseBigDecimal(JsonNode node, String fieldName) {
        String value = getTextValue(node, fieldName);
        if (value == null || value.isEmpty()) return null;
        
        try {
            // Remove commas and dollar signs
            value = value.replaceAll("[,$]", "");
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Data classes
    public static class ScheduleFData {
        public String businessName;
        public String principalProduct;
        public String ein;
        public Boolean materiallyParticipated = false;
        public BigDecimal grossIncome; // Line 9
        public BigDecimal totalExpenses; // Line 33
        public BigDecimal netFarmProfitLoss; // Line 34
        public BigDecimal calculatedLoss; // Line 9 - Line 33
    }

    public static class Form8582Data {
        public List<PassiveActivity> activities = new ArrayList<>();
    }

    public static class PassiveActivity {
        public String activityName;
        public BigDecimal currentYearLoss;
        public BigDecimal overallLoss;
    }

    public static class ValidationResult {
        private List<String> errors = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
        private List<String> info = new ArrayList<>();

        public void addError(String error) {
            errors.add(error);
        }

        public void addWarning(String warning) {
            warnings.add(warning);
        }

        public void addInfo(String info) {
            this.info.add(info);
        }

        public boolean isValid() {
            return errors.isEmpty();
        }

        public List<String> getErrors() {
            return errors;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public List<String> getInfo() {
            return info;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Validation Result:\n");
            sb.append("Status: ").append(isValid() ? "PASSED 3† ¢FAULED)"Ç`Vend("\n");
            
            if (!errors.isEmpty()) {
                sb.append("\nErrors:\n");
                errors.forEach(e => sb.append("  - ").append(e).append("\n"));
            }
            
            if (!warnings.isEmpty()) {
                sb.append("\nWarnings:\n");
                warnings.forEach(w => sb.append("  - ").append(w).append("\n"));
            }
            
            if (!info.isEmpty()) {
                sb.append("\nInfo:\n");
                info.forEach(i => sb.append("  - ").append(i).append("\n"));
            }
            
            return sb.toString();
        }
    }
}