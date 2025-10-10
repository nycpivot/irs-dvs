package com.tax.dvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Validator for IRS Schedule F to Form 8582 Matching
 * 
 * Business Rules:
 * 1. Match business names between Schedule F and Form 8582
 * 2. Validate loss calculation: Line 9 (GrossIncome) - Line 33 (TotalExpenses)
 * 3. Verify calculated loss matches Form 8582 reported loss
 * 4. Validate passive activity treatment (non-material participation)
 * 5. Verify Schedule F Line 34 (NetFarmProfitLossAmt) reporting
 * 
 * IRS Rules:
 * - Passive activity losses are limited per IRC Section 469
 * - Non-materially participated farming activities are passive
 * - Passive losses must be tracked on Form 8582
 * - Schedule F Line 34 may show $0 for passive losses disallowed by Form 8582
 */
@Component
public class ScheduleF8582Validator {

    private static final String SCHEDULE_F_PATH = "/body/forms[]";
    private static final String FORM_8582_PATH = "/body/forms[]";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /**
     * Validates the entire payload for Schedule F to Form 8582 matching
     */
    public ValidationResult validate(JsonNode payload) {
        ValidationResult result = new ValidationResult();
        
        try {
            // Extract Schedule F forms
            List<ScheduleFData> scheduleFForms = extractScheduleFData(payload);
            
            // Extract Form 8582 data
            Form8582Data form8582 = extractForm8582Data(payload);
            
            if (scheduleFForms.isEmpty()) {
                result.addError("No Schedule F forms found in payload");
                return result;
            }
            
            // Validate each Schedule F
            for (ScheduleFData scheduleF : scheduleFForms) {
                validateScheduleF(scheduleF, form8582, result);
            }
            
            // Validate Form 8582 completeness
            validateForm8582Completeness(scheduleFForms, form8582, result);
            
        } catch (Exception e) {
            result.addError("Validation exception: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Validates a single Schedule F against Form 8582
     */
    private void validateScheduleF(ScheduleFData scheduleF, Form8582Data form8582, ValidationResult result) {
        String businessName = scheduleF.getBusinessName();
        
        // Rule 1: Calculate net profit/loss (Line 9 - Line 33)
        BigDecimal calculatedNet = scheduleF.getGrossIncome().subtract(scheduleF.getTotalExpenses());
        
        // Rule 2: Validate material participation status
        boolean isPassive = !scheduleF.isMateriallyParticipated();
        
        if (isPassive) {
            // Rule 3: For passive activities, validate Form 8582 matching
            validatePassiveActivity(scheduleF, calculatedNet, form8582, result);
        } else {
            // Rule 4: For materially participated activities, validate Line 34 reporting
            validateActiveActivity(scheduleF, calculatedNet, result);
        }
    }

    /**
     * Validates passive activity treatment
     */
    private void validatePassiveActivity(ScheduleFData scheduleF, BigDecimal calculatedNet,
                                              Form8582Data form8582, ValidationResult result) {
        String businessName = scheduleF.getBusinessName();
        
        // Find matching entry in Form 8582
        PassiveActivity passiveActivity = form8582.findActivityByName(businessName);
        
        if (passiveActivity == null) {
            if (calculatedNet.compareTo(ZERO) < 0) {
                result.addError("Schedule F '" + businessName + "' has a loss of " + 
                    calculatedNet + " but is not found on Form 8582");
            }
            return;
        }
        
        // Rule 5: Validate business name matching
        if (!businessName.equalsIgnoreCase(passiveActivity.getActivityName())) {
            result.addWarning("Business name mismatch: Schedule F '" + businessName + 
                "' vs Form 8582 '" + passiveActivity.getActivityName() + "'");
        }
        
        // Rule 6: Validate loss amount matching
        if (calculatedNet.compareTo(ZERO) < 0) {
            BigDecimal form8582Loss = passiveActivity.getCurrentYearLoss();
            
            if (calculatedNet.abs().compareTo(form8582Loss) != 0) {
                result.addError("Loss amount mismatch for '" + businessName + "': " +
                    "Calculated loss (Line 9 - Line 33) = " + calculatedNet +
                    ", Form 8582 loss = " + form8582Loss);
            } else {
                result.addSuccess("Loss amount matches for '" + businessName + "': " + 
                    form8582Loss);
            }
        }
        
        // Rule 7: Validate Schedule F Line 34 reporting for passive losses
        if (calculatedNet.compareTo(ZERO) < 0) {
            if (scheduleF.getNetFarmProfitLoss().compareTo(ZERO) != 0) {
                result.addWarning("Schedule F Line 34 for '" + businessName + 
                    "' shows " + scheduleF.getNetFarmProfitLoss() + 
                    " but should show $0 due to passive loss limitation (loss tracked on Form 8582)");
            } else {
                result.addInfo("Schedule F Line 34 for '" + businessName + 
                    "' correctly shows $0 (passive loss limited by Form 8582)");
            }
        }
    }

    /**
     * Validates active (materially participated) activity
     */
    private void validateActiveActivity(ScheduleFData scheduleF, BigDecimal calculatedNet,
                                           ValidationResult result) {
        String businessName = scheduleF.getBusinessName();
        
        // Rule 8: For active activities, Line 34 should match calculated net
        if (calculatedNet.compareTo(scheduleF.getNetFarmProfitLoss()) != 0) {
            result.addError("Schedule F Line 34 mismatch for active activity '" + businessName + "': " +
                "Calculated (Line 9 - Line 33) = " + calculatedNet +
                ", Line 34 = " + scheduleF.getNetFarmProfitLoss());
        } else {
            result.addSuccess("Schedule F Line 34 correctly reports " + calculatedNet + 
                "for active activity '" + businessName + "'");
        }
    }

    /**
     * Validates that all passive losses are properly reported on Form 8582
     */
    private void validateForm8582Completeness(List<ScheduleFData> scheduleFForms,
                                                    Form8582Data form8582, ValidationResult result) {
        // Count passive activities with losses
        long passiveLossCount = scheduleFForms.stream()
            .filter(sf -> !sf.isMateriallyParticipated())
            .filter(sf -> sf.getGrossIncome().subtract(sf.getTotalExpenses()).compareTo(ZERO) < 0)
            .count();
            
        if (passiveLossCount > form8582.getActivities().size()) {
            result.addError("Form 8582 is missing passive activities: " + 
                passiveLossCount + " passive losses found, but only " + 
                form8582.getActivities().size() + " activities on Form 8582");
        }
    }

    /**
     * Extracts Schedule F data from payload
     */
    private List<ScheduleFData> extractScheduleFData(JsonNode payload) {
        List<ScheduleFData> result = new ArrayList<>();
        
        JsonNode forms = payload.at("/body/forms");
        if (forms == null || !forms.isArray()) {
            return result;
        }
        
        for (JsonNode form : forms) {
            String formNum = form.path("formNum").asText();
            if ("IRS1040ScheduleF".equals(formNum)) {
                result.add(parseScheduleF(form));
            }
        }
        
        return result;
    }

    /**
     * Parses a single Schedule F form
     */
    private ScheduleFData parseScheduleF(JsonNode form) {
        ScheduleFData data = new ScheduleFData();
        
        JsonNode lineItems = form.path("lineItems");
        if (!lineItems.isArray()) {
            return data;
        }
        
        for (JsonNode lineItem : lineItems) {
            String lineName = lineItem.path("lineNameTxt").asText();
            
            if (lineName.contains("/FarmProprietorName/BusinessNameLine1Txt")) {
                data.setBusinessName(lineItem.path("perReturnValueTxt").asText());
            } else if (lineName.contains("/MateriallyParticipatedInd")) {
                data.setMateriallyParticipated("true".equalsIgnoreCase(
                    lineItem.path("perReturnValueTxt").asText()));
            } else if (lineName.contains("/FarmIncomeCashMethodGrp/GrossIncomeAmt")) {
                data.setGrossIncome(new BigDecimal(lineItem.path("perReturnValueTxt").asText("0")));
            } else if (lineName.contains("/FarmExpensesGrp/TotalExpensesAmt")) {
                data.setTotalExpenses(new BigDecimal(lineItem.path("perReturnValueTxt").asText("0")));
            } else if (lineName.contains("/FarmExpensesGrp/NetFarmProfitLossAmt")) {
                data.setNetFarmProfitLoss(new BigDecimal(lineItem.path("perReturnValueTxt").asText("0")));
            }
        }
        
        return data;
    }

    /**
     * Extracts Form 8582 data from payload
     */
    private Form8582Data extractForm8582Data(JsonNode payload) {
        Form8582Data data = new Form8582Data();
        
        JsonNode forms = payload.at("/body/forms");
        if (forms == null || !forms.isArray()) {
            return data;
        }
        
        for (JsonNode form : forms) {
            String formNum = form.path("formNum").asText();
            if ("IRS8582".equals(formNum)) {
                parseForm8582(form, data);
                break;
            }
        }
        
        return data;
    }

    /**
     * Parses Form 8582 data
     */
    private void parseForm8582(JsonNode form, Form8582Data data) {
        JsonNode lineItems = form.path("lineItems");
        if (!lineItems.isArray()) {
            return;
        }
        
        for (JsonNode lineItem : lineItems) {
            String lineName = lineItem.path("lineNameTxt").asText();
            
            if (lineName.contains("/ParentWrkshtPassiveGrp/WrkshtPassiveGrp")) {
                parsePassiveActivity(lineItem, data);
            }
        }
    }

    /**
     * Parses a single passive activity from Form 8582
     */
    private void parsePassiveActivity(JsonNode group, Form8582Data data) {
        PassiveActivity activity = new PassiveActivity();
        
        JsonNode lineItems = group.path("lineItems");
        if (!lineItems.isArray()) {
            return;
        }
        
        for (JsonNode lineItem : lineItems) {
            String lineName = lineItem.path("lineNameTxt").asText();
            
            if (lineName.contains("/NonParticipateActivityNm")) {
                activity.setActivityName(lineItem.path("perReturnValueTxt").asText());
            } else if (lineName.contains("/CurrentYearNetLossAmt")) {
                activity.setCurrentYearLoss(new BigDecimal(lineItem.path("perReturnValueTxt").asText("0")));
            }
        }
        
        if (activity.getActivityName() != null && !activity.getActivityName().isBlank()) {
            data.addActivity(activity);
        }
    }

    // Data classes
    public static class ScheduleFData {
        private String businessName;
        private boolean materiallyParticipated;
        private BigDecimal grossIncome = ZERO;
        private BigDecimal totalExpenses = ZERO;
        private BigDecimal netFarmProfitLoss = ZERO;

        public String getBusinessName() { return businessName; }
        public void setBusinessName(String businessName) { this.businessName = businessName; }
        public boolean isMateriallyParticipated() { return materiallyParticipated; }
        public void setMateriallyParticipated(boolean materiallyParticipated) { this.materiallyParticipated = materiallyParticipated; }
        public BigDecimal getGrossIncome() { return grossIncome; }
        public void setGrossIncome(BigDecimal grossIncome) { this.grossIncome = grossIncome; }
        public BigDecimal getTotalExpenses() { return totalExpenses; }
        public void setTotalExpenses(BigDecimal totalExpenses) { this.totalExpenses = totalExpenses; }
        public BigDecimal getNetFarmProfitLoss() { return netFarmProfitLoss; }
        public void setNetFarmProfitLoss(BigDecimal netFarmProfitLoss) { this.netFarmProfitLoss = netFarmProfitLoss; }
    }

    public static class Form8582Data {
        private List<PassiveActivity> activities = new ArrayList<>();

        public void addActivity(PassiveActivity activity) {
            activities.add(activity);
        }

        public PassiveActivity findActivityByName(String name) {
            return activities.stream()
                .filter(a -> a.getActivityName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
        }

        public List<PassiveActivity> getActivities() { return activities; }
    }

    public static class PassiveActivity {
        private String activityName;
        private BigDecimal currentYearLoss = ZERO;

        public String getActivityName() { return activityName; }
        public void setActivityName(String activityName) { this.activityName = activityName; }
        public BigDecimal getCurrentYearLoss() { return currentYearLoss; }
        public void setCurrentYearLoss(BigDecimal currentYearLoss) { this.currentYearLoss = currentYearLoss; }
    }

    public static class ValidationResult {
        private List<String> errors = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
        private List<String> successes = new ArrayList<>();
        private List<String> infos = new ArrayList<>();

        public void addError(String error) { errors.add(error); }
        public void addWarning(String warning) { warnings.add(warning); }
        public void addSuccess(String success) { successes.add(success); }
        public void addInfo(String info) { infos.add(info); }

        public boolean isValid() { return errors.isEmpty(); }
        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }
        public List<String> getSuccesses() { return successes; }
        public List<String> getInfos() { return infos; }
    }
}