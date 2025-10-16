package com.tax.dvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Component
public class ScheduleFForm8582Validator {

    /**
     * Validates Schedule F and Form 8582 matching based on IRS rules and Jira requirements.
     * 
     * Key Validations:
     * 1. Business name matching between Schedule F and Form 8582
     * 2. NetFarmProfitLossAmt calculation: Line 9 (GrossIncome) - Line 33 (TotalExpenses)
     * 3. Loss amounts must be negative when expenses > income
     * 4. Passive activity losses (MateriallyParticipatedInd = false) must appear on Form 8582
     * 5. Loss amounts must match between Schedule F and Form 8582
     */
    public ValidationResult validate(JsonNode payload) {
        ValidationResult result = new ValidationResult();
        
        try {
            JsonNode forms = payload.get("body").get("forms");
            
            // Extract Schedule F forms
            List<JsonNode> scheduleFForms = extractScheduleFForms(forms);
            
            // Extract Form 8582
            JsonNode form8582 = extractForm8582(forms);
            
            if (scheduleFForms.isEmpty()) {
                result.addError("No Schedule F forms found in payload");
                return result;
            }
            
            // Validate each Schedule F
            for (JsonNode scheduleF : scheduleFForms) {
                validateScheduleF(scheduleF, form8582, result);
            }
            
            // Validate Form 8582 if present
            if (form8582 != null) {
                validateForm8582(form8582, scheduleFForms, result);
            }
            
        } catch (Exception e) {
            result.addError("Validation exception: " + e.getMessage());
        }
        
        return result;
    }
    
    private void validateScheduleF(JsonNode scheduleF, JsonNode form8582, ValidationResult result) {
        String businessName = getBusinessName(scheduleF);
        String sequenceNum = getValue(scheduleF, "sequenceNum");
        
        // Rule 1: Validate NetFarmProfitLossAmt calculation (Line 9 - Line 33)
        BigDecimal grossIncome = getGrossIncome(scheduleF);
        BigDecimal totalExpenses = getTotalExpenses(scheduleF);
        BigDecimal netFarmProfitLoss = getNetFarmProfitLoss(scheduleF);
        
        BigDecimal calculatedLoss = grossIncome.subtract(totalExpenses);
        
        // Rule 2: When expenses > income, NetFarmProfitLossAmt should be negative or reflect loss
        if (totalExpenses.compareTo(grossIncome) > 0) {
            // This is a loss situation
            if (netFarmProfitLoss.compareTo(BigDecimal.ZERO) == 0 && calculatedLoss.compareTo(BigDecimal.ZERO) != 0) {
                result.addError(String.format(
                    "Schedule F %s (%s): NetFarmProfitLossAmt shows $0 but should show loss of %s. " +
                    "GrossIncome=%s, TotalExpenses=%s, CalculatedLoss=%s",
                    sequenceNum, businessName, calculatedLoss, grossIncome, totalExpenses, calculatedLoss
                ));
            }
        }
        
        // Rule 3: Validate calculation accuracy
        if (netFarmProfitLoss.compareTo(BigDecimal.ZERO) != 0 && 
            netFarmProfitLoss.compareTo(calculatedLoss) != 0) {
            result.addWarning(String.format(
                "Schedule F %s (%s): NetFarmProfitLossAmt (%s) does not match calculated value (%s)",
                sequenceNum, businessName, netFarmProfitLoss, calculatedLoss
            ));
        }
        
        // Rule 4: Validate material participation and passive activity loss reporting
        boolean materiallyParticipated = getMateriallyParticipated(scheduleF);
        
        if (!materiallyParticipated && calculatedLoss.compareTo(BigDecimal.ZERO) < 0) {
            // Passive activity with loss - must appear on Form 8582
            if (form8582 == null) {
                result.addError(String.format(
                    "Schedule F %s (%s): MateriallyParticipatedInd=false with loss of %s but Form 8582 not found",
                    sequenceNum, businessName, calculatedLoss
                ));
            } else {
                // Validate business name appears on Form 8582
                boolean foundOnForm8582 = isBusinessOnForm8582(businessName, form8582);
                
                if (!foundOnForm8582) {
                    result.addError(String.format(
                        "Schedule F %s (%s): Passive activity with loss not found on Form 8582",
                        sequenceNum, businessName
                    ));
                } else {
                    // Validate loss amount matching
                    BigDecimal form8582Loss = getLossFromForm8582(businessName, form8582);
                    
                    if (form8582Loss != null) {
                        BigDecimal expectedLoss = calculatedLoss.abs();
                        
                        if (form8582Loss.compareTo(expectedLoss) != 0) {
                            result.addError(String.format(
                                "Schedule F %s (%s): Loss mismatch. Schedule F calculated loss=%s, Form 8582 loss=%s",
                                sequenceNum, businessName, expectedLoss, form8582Loss
                            ));
                        }
                    }
                }
            }
        }
    }
    
    private void validateForm8582(JsonNode form8582, List<JsonNode> scheduleFForms, ValidationResult result) {
        // Validate that all activities on Form 8582 have corresponding Schedule F
        List<String> form8582Activities = getForm8582ActivityNames(form8582);
        List<String> scheduleFBusinesses = new ArrayList<>();
        
        for (JsonNode scheduleF : scheduleFForms) {
            scheduleFBusinesses.add(getBusinessName(scheduleF));
        }
        
        for (String activity : form8582Activities) {
            boolean found = false;
            for (String business : scheduleFBusinesses) {
                if (activity.equalsIgnoreCase(business)) {
                    found = true;
                    break;
                }
            }
            
            if (!found) {
                result.addWarning(String.format(
                    "Form 8582 activity '%s' does not have corresponding Schedule F",
                    activity
                ));
            }
        }
    }
    
    private List<JsonNode> extractScheduleFForms(JsonNode forms) {
        List<JsonNode> result = new ArrayList<>();
        if (forms != null && forms.isArray()) {
            for (JsonNode form : forms) {
                String formNum = getValue(form, "formNum");
                if ("IRS1040ScheduleF".equals(formNum)) {
                    result.add(form);
                }
            }
        }
        return result;
    }
    
    private JsonNode extractForm8582(JsonNode forms) {
        if (forms != null && forms.isArray()) {
            for (JsonNode form : forms) {
                String formNum = getValue(form, "formNum");
                if ("IRS8582".equals(formNum)) {
                    return form;
                }
            }
        }
        return null;
    }
    
    private String getBusinessName(JsonNode scheduleF) {
        return getNestedValue(scheduleF, 
            "lineItems", "/IRS1040ScheduleF/FarmProprietorName", 
            "lineItems", "/IRS1040ScheduleF/FarmProprietorName/BusinessNameLine1Txt", 
            "perReturnValueTxt");
    }
    
    private BigDecimal getGrossIncome(JsonNode scheduleF) {
        String value = getNestedValue(scheduleF, 
            "lineItems", "/IRS1040ScheduleF/FarmIncomeCashMethodGrp", 
            "lineItems", "/IRS1040ScheduleF/FarmIncomeCashMethodGrp/GrossIncomeAmt", 
            "perReturnValueTxt");
        return parseBigDecimal(value);
    }
    
    private BigDecimal getTotalExpenses(JsonNode scheduleF) {
        String value = getNestedValue(scheduleF, 
            "lineItems", "/IRS1040ScheduleF/FarmExpensesGrp", 
            "lineItems", "/IRS1040ScheduleF/FarmExpensesGrp/TotalExpensesAmt", 
            "perReturnValueTxt");
        return parseBigDecimal(value);
    }
    
    private BigDecimal getNetFarmProfitLoss(JsonNode scheduleF) {
        String value = getNestedValue(scheduleF, 
            "lineItems", "/IRS1040ScheduleF/FarmExpensesGrp", 
            "lineItems", "/IRS1040ScheduleF/FarmExpensesGrp/NetFarmProfitLossAmt", 
            "perReturnValueTxt");
        return parseBigDecimal(value);
    }
    
    private boolean getMateriallyParticipated(JsonNode scheduleF) {
        String value = getNestedValue(scheduleF, 
            "lineItems", "/IRS1040ScheduleF/MateriallyParticipatedInd", 
            "perReturnValueTxt");
        return "true".equalsIgnoreCase(value);
    }
    
    private boolean isBusinessOnForm8582(String businessName, JsonNode form8582) {
        List<String> activities = getForm8582ActivityNames(form8582);
        for (String activity : activities) {
            if (activity.equalsIgnoreCase(businessName)) {
                return true;
            }
        }
        return false;
    }
    
    private List<String> getForm8582ActivityNames(JsonNode form8582) {
        List<String> result = new ArrayList<>();
        
        // Check ParentWrkshtPassiveGrp
        JsonNode passiveGrp = findNestedNode(form8582, 
            "lineItems", "/IRS8582/ParentWrkshtPassiveGrp");
        
        if (passiveGrp != null) {
            JsonNode lineItems = passiveGrp.get("lineItems");
            if (lineItems != null && lineItems.isArray()) {
                for (JsonNode item : lineItems) {
                    String lineName = getValue(item, "lineNameTxt");
                    if ("/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp".equals(lineName)) {
                        String activityName = getNestedValue(item, 
                            "lineItems", "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/NonParticipateActivityNm", 
                            "perReturnValueTxt");
                        if (activityName != null && !activityName.isEmpty()) {
                            result.add(activityName);
                        }
                    }
                }
            }
        }
        
        return result;
    }
    
    private BigDecimal getLossFromForm8582(String businessName, JsonNode form8582) {
        JsonNode passiveGrp = findNestedNode(form8582, 
            "lineItems", "/IRS8582/ParentWrkshtPassiveGrp");
        
        if (passiveGrp != null) {
            JsonNode lineItems = passiveGrp.get("lineItems");
            if (lineItems != null && lineItems.isArray()) {
                for (JsonNode item : lineItems) {
                    String lineName = getValue(item, "lineNameTxt");
                    if ("/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp".equals(lineName)) {
                        String activityName = getNestedValue(item, 
                            "lineItems", "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/NonParticipateActivityNm", 
                            "perReturnValueTxt");
                        
                        if (businessName.equalsIgnoreCase(activityName)) {
                            String lossValue = getNestedValue(item, 
                                "lineItems", "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/CurrentYearNetLossAmt", 
                                "perReturnValueTxt");
                            return parseBigDecimal(lossValue);
                        }
                    }
                }
            }
        }
        
        return null;
    }
    
    private String getValue(JsonNode node, String fieldName) {
        if (node != null && node.has(fieldName)) {
            JsonNode field = node.get(fieldName);
            return field.isNull() ? "" : field.asText();
        }
        return "";
    }
    
    private String getNestedValue(JsonNode node, String... path) {
        JsonNode current = node;
        
        for (int i = 0; i < path.length - 1; i += 2) {
            String arrayField = path[i];
            String targetLineName = path[i + 1];
            
            current = findInArray(current, arrayField, "lineNameTxt", targetLineName);
            if (current == null) {
                return "";
            }
        }
        
        if (path.length % 2 == 1) {
            return getValue(current, path[path.length - 1]);
        }
        
        return "";
    }
    
    private JsonNode findNestedNode(JsonNode node, String... path) {
        JsonNode current = node;
        
        for (int i = 0; i < path.length; i += 2) {
            String arrayField = path[i];
            String targetLineName = path[i + 1];
            
            current = findInArray(current, arrayField, "lineNameTxt", targetLineName);
            if (current == null) {
                return null;
            }
        }
        
        return current;
    }
    
    private JsonNode findInArray(JsonNode node, String arrayField, String matchField, String matchValue) {
        if (node == null || !node.has(arrayField)) {
            return null;
        }
        
        JsonNode array = node.get(arrayField);
        if (!array.isArray()) {
            return null;
        }
        
        for (JsonNode item : array) {
            String value = getValue(item, matchField);
            if (matchValue.equals(value)) {
                return item;
            }
        }
        
        return null;
    }
    
    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
    
    @Data
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