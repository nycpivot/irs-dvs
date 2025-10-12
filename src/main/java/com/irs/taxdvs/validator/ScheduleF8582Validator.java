package com.irs.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Component
public class ScheduleF8582Validator {

    /**
     * Validates Schedule F to Form 8582 matching based on IRS rules.
     * 
     * Requirements:
     * 1. Match business names between Schedule F and Form 8582
     * 2. Match total business income/loss amounts
     * 3. Calculate loss: Line 9 (GrossIncomeAmt) - Line 33 (TotalExpensesAmt)
     * 4. Verify NetFarmProfitLossAmt shows $0 when loss exists
     * 5. Only passive activities (non-material participation) should appear on 8582
     */
    public ValidationResult validate(JsonNode payload) {
        ValidationResult result = ValidationResult.builder()
                .passed(true)
                .errors(new ArrayList<>())
                .warnings(new ArrayList<>())
                .build();

        try {
            // Extract Schedule F activities
            List<ScheduleFActivity> scheduleFActivities = extractScheduleFActivities(payload);
            
            // Extract Form 8582 activities
            List<Form8582Activity> form8582Activities = extractForm8582Activities(payload);
            
            // Validate each Schedule F activity
            for (ScheduleFActivity scheduleF : scheduleFActivities) {
                validateScheduleFActivity(scheduleF, form8582Activities, result);
            }
            
            // Validate that only passive activities are on 8582
            validateOnlyPassiveOn8582(scheduleFActivities, form8582Activities, result);
            
            // Validate total losses match
            validateTotalLosses(scheduleFActivities, form8582Activities, payload, result);
            
        } catch (Exception e) {
            result.setPassed(false);
            result.getErrors().add("Validation error: " + e.getMessage());
        }
        
        return result;
    }

    private List<ScheduleFActivity> extractScheduleFActivities(JsonNode payload) {
        List<ScheduleFActivity> activities = new ArrayList<>();
        
        JsonNode forms = payload.at("/body/forms");
        if (forms != null && forms.isArray()) {
            for (JsonNode form : forms) {
                String formNum = form.at("/formNum").asText("");
                
                if ("IRS1040ScheduleF".equals(formNum)) {
                    ScheduleFActivity activity = ScheduleFActivity.builder().build();
                    
                    // Extract business name
                    JsonNode businessNameNode = findLineItem(form, "/IRS1040ScheduleF/FarmProprietorName/BusinessNameLine1Txt");
                    if (businessNameNode != null) {
                        activity.setBusinessName(businessNameNode.asText(""));
                    }
                    
                    // Extract principal product
                    JsonNode productNode = findLineItem(form, "/IRS1040ScheduleF/PrincipalProductDesc");
                    if (productNode != null) {
                        activity.setPrincipalProduct(productNode.asText(""));
                    }
                    
                    // Extract material participation
                    JsonNode materialNode = findLineItem(form, "/IRS1040ScheduleF/MateriallyParticipatedInd");
                    if (materialNode != null) {
                        activity.setMateriallyParticipated(materialNode.asBoolean(false));
                    }
                    
                    // Extract gross income (Line 9)
                    JsonNode grossIncomeNode = findLineItem(form, "/IRS1040ScheduleF/FarmIncomeCashMethodGrp/GrossIncomeAmt");
                    if (grossIncomeNode != null) {
                        activity.setGrossIncome(new BigDecimal(grossIncomeNode.asText("0")));
                    }
                    
                    // Extract total expenses (Line 33)
                    JsonNode totalExpensesNode = findLineItem(form, "/IRS1040ScheduleF/FarmExpensesGrp/TotalExpensesAmt");
                    if (totalExpensesNode != null) {
                        activity.setTotalExpenses(new BigDecimal(totalExpensesNode.asText("0")));
                    }
                    
                    // Extract net farm profit/loss
                    JsonNode netProfitLossNode = findLineItem(form, "/IRS1040ScheduleF/FarmExpensesGrp/NetFarmProfitLossAmt");
                    if (netProfitLossNode != null) {
                        activity.setNetFarmProfitLoss(new BigDecimal(netProfitLossNode.asText("0")));
                    }
                    
                    // Calculate loss: Line 9 - Line 33
                    if (activity.getGrossIncome() != null && activity.getTotalExpenses() != null) {
                        BigDecimal calculatedNet = activity.getGrossIncome().subtract(activity.getTotalExpenses());
                        activity.setCalculatedNet(calculatedNet);
                    }
                    
                    activities.add(activity);
                }
            }
        }
        
        return activities;
    }

    private List<Form8582Activity> extractForm8582Activities(JsonNode payload) {
        List<Form8582Activity> activities = new ArrayList<>();
        
        JsonNode forms = payload.at("/body/forms");
        if (forms != null && forms.isArray()) {
            for (JsonNode form : forms) {
                String formNum = form.at("/formNum").asText("");
                
                if ("IRS8582".equals(formNum)) {
                    // Extract activities from ParentWrkshtPassiveGrp
                    JsonNode parentGrpNode = findLineItem(form, "/IRS8582/ParentWrkshtPassiveGrp");
                    if (parentGrpNode != null) {
                        JsonNode wrkshtGrp = parentGrpNode.get("wrkshtGrp");
                        if (wrkshtGrp != null) {
                            if (wrkshtGrp.isArray()) {
                                for (JsonNode activityNode : wrkshtGrp) {
                                    activities.add(extract8582Activity(activityNode));
                                }
                            } else {
                                activities.add(extract8582Activity(wrkshtGrp));
                            }
                        }
                    }
                }
            }
        }
        
        return activities;
    }

    private Form8582Activity extract8582Activity(JsonNode activityNode) {
        Form8582Activity activity = Form8582Activity.builder().build();
        
        JsonNode nameNode = activityNode.get("NonParticipateActivityNm");
        if (nameNode != null) {
            activity.setActivityName(nameNode.asText(""));
        }
        
        JsonNode currentLossNode = activityNode.get("CurrentYearNetLossAmt");
        if (currentLossNode != null) {
            activity.setCurrentYearLoss(new BigDecimal(currentLossNode.asText("0")));
        }
        
        JsonNode overallLossNode = activityNode.get("OverallLossAmt");
        if (overallLossNode != null) {
            activity.setOverallLoss(new BigDecimal(overallLossNode.asText("0")));
        }
        
        return activity;
    }

    private void validateScheduleFActivity(ScheduleFActivity scheduleF, 
                                                      List<Form8582Activity> form8582Activities, 
                                                      ValidationResult result) {
                                                      
        // Check if activity has a loss
        if (scheduleF.getCalculatedNet() != null && 
            scheduleF.getCalculatedNet).compareTo(BigDecimal.ZERO) < 0) {
            
            // Validate NetFarmProfitLossAmt shows $0 for losses
            if (scheduleF.getNetFarmProfitLoss() != null && 
                scheduleF.getNetFarmProfitLoss().compareTo(BigDecimal.ZERO) != 0) {
                result.getErrors().add(
                    String.format("Schedule F %s: NetFarmProfitLossAmt should be $0 when loss exists, but found %s",
                        scheduleF.getBusinessName(), scheduleF.getNetFarmProfitLoss())
                );
                result.setPassed(false);
            }
            
            // If not materially participated, should be on 8582
            if (!scheduleF.isMateriallyParticipated()) {
                Form8582Activity matching8582 = findMatching8582Activity(scheduleF, form8582Activities);
                
                if (matching8582 == null) {
                    result.getErrors().add(
                        String.format("Schedule F %s: Passive activity with loss not found on Form 8582",
                            scheduleF.getBusinessName())
                    );
                    result.setPassed(false);
                } else {
                    // Validate loss amount match
                    BigDecimal expectedLoss = scheduleF.getCalculatedNet().abs();
                    BigDecimal actualLoss = matching8582.getOverallLoss();
                    
                    if (actualLoss == null || expectedLoss.compareTo(actualLoss) != 0) {
                        result.getErrors().add(
                            String.format("Schedule F %s: Loss amount mismatch. Calculated: %s, Form 8582: %s",
                                scheduleF.getBusinessName(), expectedLoss, actualLoss)
                        );
                        result.setPassed(false);
                    }
                }
            }
        }
    }

    private void validateOnlyPassiveOn8582(List<ScheduleFActivity> scheduleFActivities,
                                                        List<Form8582Activity> form8582Activities,
                                                        ValidationResult result) {
        // Check that all activities on 8582 are passive (non-material participation)
        for (Form8582Activity form8582 : form8582Activities) {
            ScheduleFActivity matchingScheduleF = findMatchingScheduleF(form8582, scheduleFActivities);
            
            if (matchingScheduleF != null && matchingScheduleF.isMateriallyParticipated()) {
                result.getErrors().add(
                    String.format("Form 8582 %s: Activity with material participation should NOT be on Form 8582",
                        form8582.getActivityName())
                );
                result.setPassed(false);
            }
        }
    }

    private void validateTotalLosses(List<ScheduleFActivity> scheduleFActivities,
                                               List<Form8582Activity> form8582Activities,
                                               JsonNode payload,
                                               ValidationResult result) {
        // Calculate total losses from passive Schedule F activities
        BigDecimal totalScheduleFLosses = BigDecimal.ZERO;
        for (ScheduleFActivity activity : scheduleFActivities) {
            if (!activity.isMateriallyParticipated() && 
                activity.getCalculatedNet() != null && 
                activity.getCalculatedNet().compareTo(BigDecimal.ZERO) < 0) {
                totalScheduleFLosses = totalScheduleFLosses.add(activity.getCalculatedNet().abs());
            }
        }
        
        // Get total losses from Form 8582
        BigDecimal total8582Losses = BigDecimal.ZERO;
        for (Form8582Activity activity : form8582Activities) {
            if (activity.getOverallLoss() != null) {
                total8582Losses = total8582Losses.add(activity.getOverallLoss());
            }
        }
        
        // Validate totals match
        if (totalScheduleFLosses.compareTo(total8582Losses) != 0) {
            result.getWarnings().add(
                String.format("Total loss mismatch: Schedule F passive losses %s, Form 8582 total losses %s",
                    totalScheduleFLosses, total8582Losses)
            );
        }
    }

    private Form8582Activity findMatching8582Activity(ScheduleFActivity scheduleF,
                                                                    List<Form8582Activity> form8582Activities) {
        for (Form8582Activity form8582 : form8582Activities) {
            if (matchBusinessName(scheduleF.getBusinessName(), form8582.getActivityName()) ||
                matchBusinessName(scheduleF.getPrincipalProduct(), form8582.getActivityName())) {
                return form8582;
            }
        }
        return null;
    }

    private ScheduleFActivity findMatchingScheduleF(Form8582Activity form8582,
                                                                    List<ScheduleFActivity> scheduleFActivities) {
        for (ScheduleFActivity scheduleF : scheduleFActivities) {
            if (matchBusinessName(scheduleF.getBusinessName(), form8582.getActivityName()) ||
                matchBusinessName(scheduleF.getPrincipalProduct(), form8582.getActivityName())) {
                return scheduleF;
            }
        }
        return null;
    }

    private boolean matchBusinessName(String name1, String name2) {
        if (name1 == null || name2 == null) {
            return false;
        }
        // Normalize and compare
        String normalized1 = name1.trim().toUpperCase().replaceAll("\\s+", " ");
        String normalized2 = name2.trim().toUpperCase().replaceAll("\\s+", " ");
        return normalized1.equals(normalized2);
    }

    private JsonNode findLineItem(JsonNode form, String lineNamePath) {
        JsonNode lineItems = form.get("lineItems");
        if (lineItems != null && lineItems.isArray()) {
            for (JsonNode lineItem : lineItems) {
                JsonNode lineName = lineItem.get("lineNameTxt");
                if (lineName != null && lineName.asText().equals(lineNamePath)) {
                    JsonNode value = lineItem.get("perReturnValueTxt");
                    if (value != null) {
                        return value;
                    }
                }
                // Check nested lineItems
                JsonNode nestedLineItems = lineItem.get("lineItems");
                if (nestedLineItems != null) {
                    JsonNode result = findLineItemRecursive(nestedLineItems, lineNamePath);
                    if (result != null) {
                        return result;
                    }
                }
            }
        }
        return null;
    }

    private JsonNode findLineItemRecursive(JsonNode lineItems, String lineNamePath) {
        if (lineItems.isArray()) {
            for (JsonNode lineItem : lineItems) {
                JsonNode lineName = lineItem.get("lineNameTxt");
                if (lineName != null && lineName.asText().equals(lineNamePath)) {
                    JsonNode value = lineItem.get("perReturnValueTxt");
                    if (value != null) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScheduleFActivity {
        private String businessName;
        private String principalProduct;
        private boolean materiallyParticipated;
        private BigDecimal grossIncome;
        private BigDecimal totalExpenses;
        private BigDecimal netFarmProfitLoss;
        private BigDecimal calculatedNet;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Form8582Activity {
        private String activityName;
        private BigDecimal currentYearLoss;
        private BigDecimal overallLoss;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationResult {
        private boolean passed;
        private List<String> errors;
        private List<String> warnings;
    }
}
