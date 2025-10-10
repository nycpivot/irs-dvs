package com.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Component
public class ScheduleF8582Validator {

    /**
     * Validates Schedule F against Form 8582 based on IRS rules and business requirements.
     * 
     * Requirements:
     * 1. Match business names between Schedule F and Form 8582
     * 2. Validate loss amounts: Line 9 (Gross Income) - Line 33 (Total Expenses) = 8582 Loss
     * 3. Verify NetFarmProfitLossAmt accuracy (should not be zero for loss activities)
     * 4. Validate passive activity limitations
     * 5. Ensure only non-materially participated activities appear on 8582
     */
    public ValidationResult validate(JsonNode taxReturnPayload) {
        ValidationResult result = new ValidationResult();
        
        try {
            JsonNode body = taxReturnPayload.get("body");
            if (body == null || !body.has("forms")) {
                result.addError("Missing body or forms in payload");
                return result;
            }
            
            // Extract Schedule F forms
            List<ScheduleFData> scheduleFList = extractScheduleFData(body);
            
            // Extract Form 8582 data
            Form8582Data form8582Data = extractForm8582Data(body);
            
            // Validation 1: Business Name Matching
            validateBusinessNameMatching(scheduleFList, form8582Data, result);
            
            // Validation 2: Loss Amount Calculation
            validateLossCalculation(scheduleFList, form8582Data, result);
            
            // Validation 3: NetFarmProfitLossAmt Accuracy
            validateNetFarmProfitLossAmt(scheduleFList, result);
            
            // Validation 4: Passive Activity Limitations
            validatePassiveActivityLimitations(scheduleFList, form8582Data, result);
            
            // Validation 5: Material Participation
            validateMaterialParticipation(scheduleFList, form8582Data, result);
            
        } catch (Exception e) {
            result.addError("Validation exception: " + e.getMessage());
        }
        
        return result;
    }
    
    private List<ScheduleFData> extractScheduleFData(JsonNode body) {
        List<ScheduleFData> scheduleFList = new ArrayList<>();
        
        for (JsonNode form : body.get("forms")) {
            if ("IRS1040ScheduleF".equals(form.get("formNum").asText())) {
                ScheduleFData data = new ScheduleFData();
                data.sequenceNum = form.get("sequenceNum").asText();
                
                for (JsonNode lineItem : form.get("lineItems")) {
                    String lineName = lineItem.get("lineNameTxt").asText();
                    
                    if (lineName.contains("FarmProprietorName/BusinessNameLine1Txt")) {
                        data.businessName = lineItem.get("perReturnValueTxt").asText().trim();
                    } else if (lineName.contains("PrincipalProductDesc")) {
                        data.principalProduct = lineItem.get("perReturnValueTxt").asText().trim();
                    } else if (lineName.contains("MateriallyParticipatedInd")) {
                        data.materiallyParticipated = "X".equals(lineItem.get("perReturnValueTxt").asText()) || 
                            "true".equalsIgnoreCase(lineItem.get("perReturnValueTxt").asText());
                    } else if (lineName.contains("FarmIncomeCashMethodGrp/GrossIncomeAmt")) {
                        data.grossIncome = new BigDecimal(lineItem.get("perReturnValueTxt").asText("0"));
                    } else if (lineName.contains("FarmExpensesGrp/TotalExpensesAmt")) {
                        data.totalExpenses = new BigDecimal(lineItem.get("perReturnValueTxt").asText("0"));
                    } else if (lineName.contains("FarmExpensesGrp/NetFarmProfitLossAmt")) {
                        data.netFarmProfitLoss = new BigDecimal(lineItem.get("perReturnValueTxt").asText("0"));
                    }
                }
                
                scheduleFList.add(data);
            }
        }
        
        return scheduleFList;
    }
    
    private Form8582Data extractForm8582Data(JsonNode body) {
        Form8582Data data = new Form8582Data();
        
        for (JsonNode form : body.get("forms")) {
            if ("IRS8582".equals(form.get("formNum").asText())) {
                for (JsonNode lineItem : form.get("lineItems")) {
                    String lineName = lineItem.get("lineNameTxt").asText();
                    
                    if (lineName.contains("ParentWrkshtPassiveGrp/WrkshtPassiveGrp")) {
                        if (lineItem.has("lineItems")) {
                            PassiveActivity activity = new PassiveActivity();
                            
                            for (JsonNode subItem : lineItem.get("lineItems")) {
                                String subLineName = subItem.get("lineNameTxt").asText();
                                
                                if (subLineName.contains("NonParticipateActivityNm")) {
                                    activity.activityName = subItem.get("perReturnValueTxt").asText().trim();
                                } else if (subLineName.contains("CurrentYearNetLossAmt")) {
                                    activity.currentYearLoss = new BigDecimal(subItem.get("perReturnValueTxt").asText("0"));
                                }
                            }
                            
                            if (activity.activityName != null) {
                                data.passiveActivities.add(activity);
                            }
                        }
                    }
                }
                break;
            }
        }
        
        return data;
    }
    
    private void validateBusinessNameMatching(List<ScheduleFData> scheduleFList, 
                                                   Form8582Data form8582Data, 
                                                   ValidationResult result) {
        // Only passive activities (non-materially participated) should appear on 8582
        for (ScheduleFData scheduleF : scheduleFList) {
            if (!scheduleF.materiallyParticipated) {
                boolean found = false;
                
                for (PassiveActivity activity : form8582Data.passiveActivities) {
                    if (activity.activityName.equalsIgnoreCase(scheduleF.principalProduct)) {
                        found = true;
                        result.addInfo("Business name match: " + scheduleF.principalProduct + 
                                       " found on Form 8582");
                        break;
                    }
                }
                
                if (!found) {
                    result.addError("Passive activity '" + scheduleF.principalProduct + 
                                     "' from Schedule F not found on Form 8582");
                }
            }
        }
    }
    
    private void validateLossCalculation(List<ScheduleFData> scheduleFList, 
                                                Form8582Data form8582Data, 
                                                ValidationResult result) {
        // Validate: Line 9 (Gross Income) - Line 33 (Total Expenses) = 8582 Loss
        for (ScheduleFData scheduleF : scheduleFList) {
            if (!scheduleF.materiallyParticipated) {
                BigDecimal calculatedLoss = scheduleF.grossIncome.subtract(scheduleF.totalExpenses);
                
                for (PassiveActivity activity : form8582Data.passiveActivities) {
                    if (activity.activityName.equalsIgnoreCase(scheduleF.principalProduct)) {
                        BigDecimal form8582Loss = activity.currentYearLoss.negate(); // 8582 shows positive numbers for losses
                        
                        if (calculatedLoss.compareTo(form8582Loss) != 0) {
                            result.addError("Loss calculation mismatch for '" + scheduleF.principalProduct + 
                                            "': Schedule F (Line 9 - Line 33) = " + calculatedLoss + 
                                             ", but Form 8582 shows " + form8582Loss);
                        } else {
                            result.addInfo("Loss calculation match for '" + scheduleF.principalProduct + 
                                            "': " + calculatedLoss);
                        }
                        break;
                    }
                }
            }
        }
    }
    
    private void validateNetFarmProfitLossAmt(List<ScheduleFData> scheduleFList, 
                                                      ValidationResult result) {
        // Validate that NetFarmProfitLossAmt is not zero when there is a loss
        for (ScheduleFData scheduleF : scheduleFList) {
            BigDecimal calculatedNet = scheduleF.grossIncome.subtract(scheduleF.totalExpenses);
            
            if (calculatedNet.compareTo(BigDecimal.ZERO) != 0 && 
                scheduleF.netFarmProfitLoss.compareTo(BigDecimal.ZERO) == 0) {
                
                result.addError("CRITICAL: NetFarmProfitLossAmt shows $0 for '" + scheduleF.principalProduct + 
                                "', but calculated amount is " + calculatedNet + 
                                ". This is the known issue described in the ticket.");
            } else if (calculatedNet.compareTo(scheduleF.netFarmProfitLoss) != 0) {
                result.addWarning("NetFarmProfitLossAmt mismatch for '" + scheduleF.principalProduct + 
                                    "': Reported = " + scheduleF.netFarmProfitLoss + 
                                    ", Calculated = " + calculatedNet);
            } else {
                result.addInfo("NetFarmProfitLossAmt correct for '" + scheduleF.principalProduct + 
                                    "': " + scheduleF.netFarmProfitLoss);
            }
        }
    }
    
    private void validatePassiveActivityLimitations(List<ScheduleFData> scheduleFList, 
                                                           Form8582Data form8582Data, 
                                                           ValidationResult result) {
        // Validate that passive losses are properly limited
        // Per IRS rules, passive losses cannot exceed passive income unless special exceptions apply
        
        BigDecimal totalPassiveLosses = BigDecimal.ZERO;
        BigDecimal totalPassiveIncome = BigDecimal.ZERO;
        
        for (ScheduleFData scheduleF : scheduleFList) {
            if (!scheduleF.materiallyParticipated) {
                BigDecimal netAmount = scheduleF.grossIncome.subtract(scheduleF.totalExpenses);
                
                if (netAmount.compareTo(BigDecimal.ZERO) < 0) {
                    totalPassiveLosses = totalPassiveLosses.add(netAmount.abs());
                } else {
                    totalPassiveIncome = totalPassiveIncome.add(netAmount);
                }
            }
        }
        
        if (totalPassiveLosses.compareTo(BigDecimal.ZERO) > 0) {
            if (totalPassiveIncome.compareTo(BigDecimal.ZERO) == 0) {
                result.addInfo("Total passive losses of $" + totalPassiveLosses + 
                                " with no passive income - all losses carried forward per IRS rules");
            } else {
                BigDecimal allowedLoss = totalPassiveIncome;
                BigDecimal disallowedLoss = totalPassiveLosses.subtract(allowedLoss);
                
                if (disallowedLoss.compareTo(BigDecimal.ZERO) > 0) {
                    result.addInfo("Passive loss limitation: $" + disallowedLoss + 
                                    " disallowed and carried forward");
                }
            }
        }
    }
    
    private void validateMaterialParticipation(List<ScheduleFData> scheduleFList, 
                                                       Form8582Data form8582Data, 
                                                       ValidationResult result) {
        // Validate that only non-materially participated activities appear on 8582
        for (ScheduleFData scheduleF : scheduleFList) {
            if (scheduleF.materiallyParticipated) {
                // Check if this activity incorrectly appears on 8582
                for (PassiveActivity activity : form8582Data.passiveActivities) {
                    if (activity.activityName.equalsIgnoreCase(scheduleF.principalProduct)) {
                        result.addError("Materially participated activity '" + scheduleF.principalProduct + 
                                        "' should NOT appear on Form 8582");
                    }
                }
                
                result.addInfo("Materially participated activity '" + scheduleF.principalProduct + 
                                   "' correctly excluded from Form 8582");
            }
        }
    }
    
    // Data Models
    public static class ScheduleFData {
        public String sequenceNum;
        public String businessName;
        public String principalProduct;
        public boolean materiallyParticipated = false;
        public BigDecimal grossIncome = BigDecimal.ZERO;
        public BigDecimal totalExpenses = BigDecimal.ZERO;
        public BigDecimal netFarmProfitLoss = BigDecimal.ZERO;
    }
    
    public static class Form8582Data {
        public List<PassiveActivity> passiveActivities = new ArrayList<>();
    }
    
    public static class PassiveActivity {
        public String activityName;
        public BigDecimal currentYearLoss = BigDecimal.ZERO;
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
            sb.append("Status: ").append(isValid() ? "PASS" : "FAIL").append("\n\n");
            
            if (!errors.isEmpty()) {
                sb.append("ERRORS:\n");
                errors.forEach(e -> sb.append("  - ").append(e).append("\n"));
                sb.append("\n");
            }
            
            if (!warnings.isEmpty()) {
                sb.append("WARNINGS:\n");
                warnings.forEach(w -> sb.append("  - ").append(w).append("\n"));
                sb.append("\n");
            }
            
            if (!info.isEmpty()) {
                sb.append("INFO:\n");
                info.forEach(i -> sb.append("  - ").append(i).append("\n"));
            }
            
            return sb.toString();
        }
    }
}