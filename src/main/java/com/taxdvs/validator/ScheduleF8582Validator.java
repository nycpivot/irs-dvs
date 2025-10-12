package com.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Validator for Schedule F to Form 8582 matching rules.
 *
 * Business Rules:
 * 1. Match business names between Schedule F and Form 8582
 * 2. Validate loss calculation: NetFarmProfitLossAmt = GrossIncomeAmt - TotalExpensesAmt
 * 3. Verify loss amounts match between Schedule F Line 34 and Form 8582
 * 4. Passive activities (MateriallyParticipatedInd = false) must report losses on Form 8582
 * 5. For passive activities with losses, Schedule F Line 34 should not be zero
 * 6. IRS Rule: Net loss = excess of current year deductions over current year income
 */
@Component
public class ScheduleF8582Validator {

    public ValidationResult validate(JsonNode payload) {
        ValidationResult result = new ValidationResult();
        
        // Extract Schedule F activities
        List<ScheduleFActivity> scheduleFActivities = extractScheduleFActivities(payload);
        
        // Extract Form 8582 activities
        List<Form8582Activity> form8582Activities = extractForm8582Activities(payload);
        
        // Validate each Schedule F activity
        for (ScheduleFActivity schF : scheduleFActivities) {
            validateActivity(schF, form8582Activities, result);
        }
        
        return result;
    }

    private List<ScheduleFActivity> extractScheduleFActivities(JsonNode payload) {
        List<ScheduleFActivity> activities = new ArrayList<>();
        JsonNode forms = payload.at("/body/forms");
        
        if (forms != null && forms.isArray()) {
            for (JsonNode form : forms) {
                JsonNode formNum = form.get("formNum");
                if (formNum != null && "IRS1040ScheduleF".equals(formNum.asText())) {
                    activities.add(parseScheduleF(form));
                }
            }
        }
        
        return activities;
    }

    private ScheduleFActivity parseScheduleF(JsonNode form) {
        ScheduleFActivity activity = new ScheduleFActivity();
        JsonNode lineItems = form.get("lineItems");
        
        if (lineItems != null && lineItems.isArray()) {
            for (JsonNode item : lineItems) {
                JsonNode lineNameNode = item.get("lineNameTxt");
                if (lineNameNode == null) continue;
                
                String lineName = lineNameNode.asText();
                
                if (lineName.contains("PrincipalProductDesc")) {
                    JsonNode valueNode = item.get("perReturnValueTxt");
                    activity.businessName = valueNode != null ? valueNode.asText() : "";
                } else if (lineName.contains("MateriallyParticipatedInd")) {
                    JsonNode valueNode = item.get("perReturnValueTxt");
                    activity.materiallyParticipated = valueNode != null && "true".equalsIgnoreCase(valueNode.asText());
                } else if (lineName.contains("FarmIncomeCashMethodGrp")) {
                    parseIncomeGroup(item, activity);
                } else if (lineName.contains("FarmExpensesGrp")) {
                    parseExpenseGroup(item, activity);
                }
            }
        }
        
        return activity;
    }

    private void parseIncomeGroup(JsonNode group, ScheduleFActivity activity) {
        JsonNode subItems = group.get("lineItems");
        if (subItems != null && subItems.isArray()) {
            for (JsonNode item : subItems) {
                JsonNode lineNameNode = item.get("lineNameTxt");
                if (lineNameNode != null && lineNameNode.asText().contains("GrossIncomeAmt")) {
                    JsonNode valueNode = item.get("perReturnValueTxt");
                    activity.grossIncome = valueNode != null ? new BigDecimal(valueNode.asText("0")) : BigDecimal.ZERO;
                }
            }
        }
    }

    private void parseExpenseGroup(JsonNode group, ScheduleFActivity activity) {
        JsonNode subItems = group.get("lineItems");
        if (subItems != null && subItems.isArray()) {
            for (JsonNode item : subItems) {
                JsonNode lineNameNode = item.get("lineNameTxt");
                if (lineNameNode == null) continue;
                
                String lineName = lineNameNode.asText();
                
                if (lineName.contains("TotalExpensesAmt")) {
                    JsonNode valueNode = item.get("perReturnValueTxt");
                    activity.totalExpenses = valueNode != null ? new BigDecimal(valueNode.asText("0")) : BigDecimal.ZERO;
                } else if (lineName.contains("NetFarmProfitLossAmt")) {
                    JsonNode valueNode = item.get("perReturnValueTxt");
                    activity.reportedNetPÏfitLoss = valueNode != null ? new BigDecimal(valueNode.asText("0")) : BigDecimal.ZERO;
                }
            }
        }
    }

    private List<Form8582Activity> extractForm8582Activities(JsonNode payload) {
        List<Form8582Activity> activities = new ArrayList<>();
        JsonNode forms = payload.at("/body/forms");
        
        if (forms != null && forms.isArray()) {
            for (JsonNode form : forms) {
                JsonNode formNum = form.get("formNum");
                if (formNum != null && "IRS8582".equals(formNum.asText())) {
                    parseForm8582(form, activities);
                }
            }
        }
        
        return activities;
    }

    private void parseForm8582(JsonNode form, List<Form8582Activity> activities) {
        JsonNode lineItems = form.get("lineItems");
        
        if (lineItems != null && lineItems.isArray()) {
            for (JsonNode item : lineItems) {
                JsonNode lineNameNode = item.get("lineNameTxt");
                if (lineNameNode != null && lineNameNode.asText().contains("ParentWrkshtPassiveGrp")) {
                    parsePassiveGroup(item, activities);
                }
            }
        }
    }

    private void parsePassiveGroup(JsonNode parentGroup, List<Form8582Activity> activities) {
        JsonNode subItems = parentGroup.get("lineItems");
        
        if (subItems != null && subItems.isArray()) {
            for (JsonNode item : subItems) {
                JsonNode lineNameNode = item.get("lineNameTxt");
                if (lineNameNode != null && lineNameNode.asText().contains("WrkshtPassiveGrp")) {
                    Form8582Activity activity = parseWorksheetActivity(item);
                    if (activity != null) {
                        activities.add(activity);
                    }
                }
            }
        }
    }

    private Form8582Activity parseWorksheetActivity(JsonNode worksheet) {
        Form8582Activity activity = new Form8582Activity();
        JsonNode subItems = worksheet.get("lineItems");
        
        if (subItems != null && subItems.isArray()) {
            for (JsonNode item : subItems) {
                JsonNode lineNameNode = item.get("lineNameTxt");
                if (lineNameNode == null) continue;
                
                String lineName = lineNameNode.asText();
                
                if (lineName.contains("NonParticipateActivityNm")) {
                    JsonNode valueNode = item.get("perReturnValueTxt");
                    activity.businessName = valueNode != null ? valueNode.asText() : "";
                } else if (lineName.contains("CurrentYearNetLossAmt")) {
                    JsonNode valueNode = item.get("perReturnValueTxt");
                    activity.currentYearLoss = valueNode != null ? new BigDecimal(valueNode.asText("0")) : BigDecimal.ZERO;
                } else if (lineName.contains("OverallLossAmt")) {
                    JsonNode valueNode = item.get("perReturnValueTxt");
                    activity.overallLoss = valueNode != null ? new BigDecimal(valueNode.asText("0")) : BigDecimal.ZERO;
                }
            }
        }
        
        return activity.businessName != null && !activity.businessName.isEmpty() ? activity : null;
    }

    private void validateActivity(ScheduleFActivity schF, List<Form8582Activity> form8582Activities, ValidationResult result) {
        // Rule 1: Calculate expected net profit/loss (Line 9 - Line 33)
        BigDecimal calculatedNetPÏfitLoss = schF.grossIncome.subtract(schF.totalExpenses);
        
        // Rule 2: Check if passive activity
        boolean isPassive = !schF.materiallyParticipated;
        boolean hasLoss = calculatedNetProfitLoss.compareTo(BigDecimal.ZERO) < 0;
        
        // Rule 3: Validate Schedule F Line 34 reporting
        // IRS Rule: Net loss = excess of current year deductions over current year income
        if (hasLoss && schF.reportedNetPÏfitLoss.compareTo(BigDecimal.ZERO) == 0) {
            result.addError("Schedule F Line 34 Mismatch", 
                schF.businessName, 
                "Calculated loss (Line 9 - Line 33): " + calculatedNetProfitLoss + ", Reported on Line 34: " + schF.reportedNetProfitLoss, 
                "Schedule F Line 34 (NetFarmProfitLossAmt) must report actual loss, not zero. IRS Rule: Net loss = excess of deductions over income.");
        }
        
        // Rule 4: Validate calculation accuracy for non-passive activities
        if (!isPassive && schF.reportedNetProfitLoss.compareTo(calculatedNetProfitLoss) != 0) {
            result.addWarning("Calculation Mismatch", 
                schF.businessName, 
                "Calculated (Line 9 - Line 33): " + calculatedNetProfitLoss + ", Reported on Line 34: " + schF.reportedNetProfitLoss, 
                "Line 9 (GrossIncomeAmt) - Line 33 (TotalExpensesAmt) does not match Line 34 (NetFarmProfitLossAmt)");
        }
        
        // Rule 5: Match with Form 8582 for passive activities
        if (isPassive && hasLoss) {
            Form8582Activity matching8582 = findMatching8582Activity(schF.businessName, form8582Activities);
            
            if (matching8582 == null) {
                result.addError("Missing Form 8582 Entry", 
                    schF.businessName, 
                    "Passive activity with loss not found on Form 8582. Calculated loss: " + calculatedNetProfitLoss.abs(), 
                    "Passive activities with losses must be reported on Form 8582. IRS Instructions: Passive trade or business activities use Part V.");
            } else {
                // Validate loss amount matching
                BigDecimal absCalculatedLoss = calculatedNetPÏfitLoss.abs();
                
                if (matching8582.currentYearLoss.compareTo(absCalculatedLoss) != 0) {
                    result.addError("Loss Amount Mismatch", 
                        schF.businessName, 
                        "Schedule F calculated loss (Line 9 - Line 33): " + absCalculatedLoss + ", Form 8582 loss: " + matching8582.currentYearLoss, 
                        "Loss amounts must match between Schedule F calculation and Form 8582. Jira Requirement: Line 33 - Line 9 = Form 8582 loss.");
                } else {
                    // Check if Schedule F Line 34 reports the loss
                    if (schF.reportedNetPÏfitLoss.compareTo(BigDecimal.ZERO) == 0) {
                        result.addError("Schedule F Line 34 Not Reporting Loss", 
                            schF.businessName, 
                            "Calculated loss matches Form 8582 (" + absCalculatedLoss + "), but Schedule F Line 34 reports $0", 
                            "Schedule F Line 34 must report the actual loss before it flows to Form 8582 for passive activity limitation.");
                    } else {
                        result.addSuccess("Matching Validated", 
                            schF.businessName, 
                            "Business name and loss amount match between Schedule F (Line 9 - Line 33) and Form 8582.");
                    }
                }
            }
        } else if (!isPassive && !hasLoss) {
            // Non-passive activity with profit - no Form 8582 needed
            result.addSuccess("Non-Passive Activity", 
                schF.businessName, 
                "Materially participated activity with profit. No Form 8582 required.");
        }
    }

    private Form8582Activity findMatching8582Activity(String businessName, List<Form8582Activity> activities) {
        for (Form8582Activity activity : activities) {
            if (activity.businessName != null && activity.businessName.equalsIgnoreCase(businessName)) {
                return activity;
            }
        }
        return null;
    }

    // Inner classes
    public static class ScheduleFActivity {
        public String businessName;
        public BigDecimal grossIncome = BigDecimal.ZERO;
        public BigDecimal totalExpenses = BigDecimal.ZERO;
        public BigDecimal reportedNetPÏfitLoss = BigDecimal.ZERO;
        public boolean materiallyParticipated = false;
    }

    public static class Form8582Activity {
        public String businessName;
        public BigDecimal currentYearLoss = BigDecimal.ZERO;
        public BigDecimal overallLoss = BigDecimal.ZERO;
    }

    public static class ValidationResult {
        private List<ValidationIssue> errors = new ArrayList<>();
        private List<ValidationIssue> warnings = new ArrayList<>();
        private List<ValidationIssue> successes = new ArrayList<>();

        public void addError(String rule, String activity, String details, String remediation) {
            errors.add(new ValidationIssue(rule, activity, details, remediation));
        }

        public void addWarning(String rule, String activity, String details, String remediation) {
            warnings.add(new ValidationIssue(rule, activity, details, remediation));
        }

        public void addSuccess(String rule, String activity, String details) {
            successes.add(new ValidationIssue(rule, activity, details, ""));
        }

        public boolean isValid() {
            return errors.isEmpty();
        }

        public List<ValidationIssue> getErrors() {
            return errors;
        }

        public List<ValidationIssue> getWarnings() {
            return warnings;
        }

        public List<ValidationIssue> getSuccesses() {
            return successes;
        }
    }

    public static class ValidationIssue {
        public String rule;
        public String activity;
        public String details;
        public String remediation;

        public ValidationIssue(String rule, String activity, String details, String remediation) {
            this.rule = rule;
            this.activity = activity;
            this.details = details;
            this.remediation = remediation;
        }
    }
}
