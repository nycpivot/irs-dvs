package com.tax.dvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Validator for matching Schedule F farm income/loss with Form 8582 passive activity losses.
 * 
 * IRS Rules:
 * - Passive activity losses (non-material participation) are suspended and reported on Form 8582
 * - Schedule F Line 34 (NetFarmProfitLossAmt) shows $0 for passive losses
 * - Actual loss = Line 9 (GrossIncomeAmt) - Line 33 (TotalExpensesAmt)
 * - Form 8582 captures the true loss in CurrentYearNetLossAmt
 * - Business names must match between Schedule F and Form 8582
 */
@Service
public class ScheduleF8582Validator {

    /**
     * Validates the entire tax return payload for Schedule F to Form 8582 matching.
     * 
     * @param payload The full JSON payload containing tax return data
     * @return ValidationResult with status and detailed findings
     */
    public ValidationResult validate(JsonNode payload) {
        ValidationResult result = ValidationResult.builder()
                .validationStatus(ValidationStatus.PASS)
                .farmValidations(new ArrayList<>())
                .errors(new ArrayList<>())
                .build();

        try {
            JsonNode body = payload.get("body");
            if (body == null || !body.has("forms")) {
                result.getErrors().add("Missing body.or forms in payload");
                result.setValidationStatus(ValidationStatus.FAIL);
                return result;
            }

            JsonNode forms = body.get("forms");
            
            // Extract Schedule F data
            List<ScheduleFData> scheduleFList = extractScheduleFData(forms);
            
            // Extract Form 8582 data
            Form8582Data form8582 = extractForm8582Data(forms);
            
            // Validate each Schedule F against Form 8582
            for (ScheduleFData scheduleF : scheduleFList) {
                FarmValidation validation = validateFarm(scheduleF, form8582);
                result.getFarmValidations().add(validation);
                
                if (!validation.isPassed()) {
                    result.setValidationStatus(ValidationStatus.FAIL);
                    result.getErrors().addAll(validation.getErrors());
                }
            }
            
        } catch (Exception e) {
            result.setValidationStatus(ValidationStatus.ERROR);
            result.getErrors().add("Validation error: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Extracts all Schedule F data from the forms array.
     */
    private List<ScheduleFData> extractScheduleFData(JsonNode forms) {
        List<ScheduleFData> scheduleFList = new ArrayList<>();
        
        for (JsonNode form : forms) {
            if ("IRS1040ScheduleF".equals(form.get("formNum").asText())) {
                ScheduleFData data = ScheduleFData.builder().build();
                
                JsonNode lineItems = form.get("lineItems");
                for (JsonNode lineItem : lineItems) {
                    String lineName = lineItem.get("lineNameTxt").asText();
                    
                    // Extract business name
                    if (lineName.contains("FarmProprietorName") && lineItem.has("lineItems")) {
                        for (JsonNode subItem : lineItem.get("lineItems")) {
                            if (subItem.get("lineNameTxt").asText().contains("BusinessNameLine1Txt")) {
                                data.setBusinessName(subItem.get("perReturnValueTxt").asText());
                            }
                        }
                    }
                    
                    // Extract material participation
                    if (lineName.contains("MateriallyParticipatedInd")) {
                        data.setMateriallyParticipated("true".equals(lineItem.get("perReturnValueTxt").asText()));
                    }
                    
                    // Extract FarmIncomeCashMethodGrp
                    if (lineName.contains("FarmIncomeCashMethodGrp") && lineItem.has("lineItems")) {
                        for (JsonNode subItem : lineItem.get("lineItems")) {
                            String subLine = subItem.get("lineNameTxt").asText();
                            if (subLine.contains("GrossIncomeAmt")) {
                                data.setGrossIncome(new BigDecimal(subItem.get("perReturnValueTxt").asText("0")));
                            }
                        }
                    }
                    
                    // Extract FarmExpensesGrp
                    if (lineName.contains("FarmExpensesGrp") && lineItem.has("lineItems")) {
                        for (JsonNode subItem : lineItem.get("lineItems")) {
                            String subLine = subItem.get("lineNameTxt").asText();
                            if (subLine.contains("TotalExpensesAmt")) {
                                data.setTotalExpenses(new BigDecimal(subItem.get("perReturnValueTxt").asText("0")));
                            }
                            if (subLine.contains("NetFarmProfitLossAmt")) {
                                data.setNetProfitLoss(new BigDecimal(subItem.get("perReturnValueTxt").asText("0")));
                            }
                        }
                    }
                }
                
                // Calculate actual loss: Line 9 - Line 33
                if (data.getGrossIncome() != null && data.getTotalExpenses() != null) {
                    data.setCalculatedNet(data.getGrossIncome().subtract(data.getTotalExpenses()));
                }
                
                scheduleFList.add(data);
            }
        }
        
        return scheduleFList;
    }

    /**
     * Extracts Form 8582 data from the forms array.
     */
    private Form8582Data extractForm8582Data(JsonNode forms) {
        Form8582Data data = Form8582Data.builder()
                .passiveActivities(new ArrayList<>())
                .build();
        
        for (JsonNode form : forms) {
            if ("IRS8582".equals(form.get("formNum").asText())) {
                JsonNode lineItems = form.get("lineItems");
                
                for (JsonNode lineItem : lineItems) {
                    String lineName = lineItem.get("lineNameTxt").asText();
                    
                    // Extract ParentWrkshtPassiveGrp
                    if (lineName.contains("ParentWrkshtPassiveGrp") && lineItem.has("lineItems")) {
                        for (JsonNode subItem : lineItem.get("lineItems")) {
                            String subLine = subItem.get("lineNameTxt").asText();
                            
                            // Extract WrkshtPassiveGrp
                            if (subLine.contains("WrkshtPassiveGrp") && subItem.has("lineItems")) {
                                PassiveActivity activity = PassiveActivity.builder().build();
                                
                                for (JsonNode actItem : subItem.get("lineItems")) {
                                    String actLine = actItem.get("lineNameTxt").asText();
                                    
                                    if (actLine.contains("NonParticipateActivityNm")) {
                                        activity.setActivityName(actItem.get("perReturnValueTxt").asText());
                                    }
                                    if (actLine.contains("CurrentYearNetLossAmt")) {
                                        activity.setCurrentYearLoss(new BigDecimal(actItem.get("perReturnValueTxt").asText("0")));
                                    }
                                    if (actLine.contains("OverallLossAmt")) {
                                        activity.setOverallLoss(new BigDecimal(actItem.get("perReturnValueTxt").asText("0")));
                                    }
                                }
                                
                                if (activity.getActivityName() != null) {
                                    data.getPassiveActivities().add(activity);
                                }
                            }
                        }
                    }
                }
            }
        }
        
        return data;
    }

    /**
     * Validates a single farm against Form 8582 data.
     */
    private FarmValidation validateFarm(ScheduleFData scheduleF, Form8582Data form8582) {
        FarmValidation validation = FarmValidation.builder()
                .farmName(scheduleF.getBusinessName())
                .passed(true)
                .errors(new ArrayList<>())
                .warnings(new ArrayList<>())
                .build();

        // Rule 1: If materially participated, should NOT be on Form 8582
        if (scheduleF.isMateriallyParticipated()) {
            Optional<PassiveActivity> matched = findMatchingActivity(scheduleF, form8582);
            if (matched.isPresent()) {
                validation.getWarnings().add(
                    "Farm marked as materially participated but appears on Form 8582 as passive activity"
                );
            }
            validation.setMaterialParticipation(true);
            validation.setPassiveActivity(false);
            return validation; // No further validation needed for active farms
        }

        // Rule 2: If not materially participated and has a loss, must be on Form 8582
        if (scheduleF.getCalculatedNet() != null && scheduleF.getCalculatedNet().compareTo(BigDecimal.ZERO) < 0) {
            validation.setPassiveActivity(true);
            validation.setMaterialParticipation(false);
            
            Optional<PassiveActivity> matched = findMatchingActivity(scheduleF, form8582);
            
            if (matched.isEmpty()) {
                validation.setPassed(false);
                validation.getErrors().add(
                    "Passive loss of " + scheduleF.getCalculatedNet() + 
                    " not found on Form 8582 for farm: " + scheduleF.getBusinessName()
                );
                return validation;
            }
            
            PassiveActivity activity = matched.get();
            validation.setMatched8582Activity(activity.getActivityName());
            
            // Rule 3: Calculated loss (Line 9 - Line 33) must match Form 8582 CurrentYearNetLossAmt
            BigDecimal calculatedLoss = scheduleF.getCalculatedNet().abs(); // Convert to positive for comparison
            BigDecimal form8582Loss = activity.getCurrentYearLoss();
            
            validation.setScheduleFCalculatedLoss(calculatedLoss);
            validation.setForm8582Loss(form8582Loss);
            
            if (calculatedLoss.compareTo(form8582Loss) != 0) {
                validation.setPassed(false);
                validation.getErrors().add(
                    "Loss mismatch: Schedule F calculated loss (Line 9 - Line 33) = $" + 
                    calculatedLoss + " but Form 8582 shows $" + form8582Loss
                );
            }
            
            // Rule 4: Schedule F Line 34 (NetFarmProfitLossAmt) should be $0 for passive losses
            if (scheduleF.getNetProfitLoss() != null && scheduleF.getNetProfitLoss().compareTo(BigDecimal.ZERO) != 0) {
                validation.getWarnings().add(
                    "Schedule F Line 34 (NetFarmProfitLossAmt) shows $" + 
                    scheduleF.getNetProfitLoss() + 
                    " but should be $0 for passive activity losses per IRS rules"
                );
            }
            
            validation.setScheduleFLine34(scheduleF.getNetProfitLoss());
        } else {
            // No loss, should not be on Form 8582
            validation.setPassiveActivity(false);
            validation.setMaterialParticipation(false);
        }
        
        return validation;
    }

    /**
     * Finds a matching passive activity on Form 8582 based on business name.
     */
    private Optional<PassiveActivity> findMatchingActivity(ScheduleFData scheduleF, Form8582Data form8582) {
        if (scheduleF.getBusinessName() == null) {
            return Optional.empty();
        }
        
        return form8582.getPassiveActivities().stream()
                .filter(a -> scheduleF.getBusinessName().equalsIgnoreCase(a.getActivityName()))
                .findFirst();
    }

    // Data Models
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScheduleFData {
        private String businessName;
        private boolean materiallyParticipated;
        private BigDecimal grossIncome; // Line 9
        private BigDecimal totalExpenses; // Line 33
        private BigDecimal netProfitLoss; // Line 34
        private BigDecimal calculatedNet; // Line 9 - Line 33
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Form8582Data {
        private List<PassiveActivity> passiveActivities;
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
    public static class FarmValidation {
        private String farmName;
        private boolean passed;
        private boolean materialParticipation;
        private boolean passiveActivity;
        private String matched8582Activity;
        private BigDecimal scheduleFCalculatedLoss;
        private BigDecimal form8582Loss;
        private BigDecimal scheduleFLine34;
        private List<String> errors;
        private List<String> warnings;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationResult {
        private ValidationStatus validationStatus;
        private List<FarmValidation> farmValidations;
        private List<String> errors;
    }

    public enum ValidationStatus {
        PASS, FAIL, ERROR
    }
}