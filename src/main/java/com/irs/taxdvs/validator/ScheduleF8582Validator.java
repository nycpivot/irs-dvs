package com.irs.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ScheduleF8582Validator {

    /**
     * Validates Schedule F matching with Form 8582 for passive activities.
     * 
     * Requirements:
     * 1. Match business names between Schedule F and Form 8582
     * 2. Verify Schedule F NetFarmProfitLossAmt = GrossIncomeAmt - TotalExpensesAmt
     * 3. Match Schedule F losses with Form 8582 CurrentYearNetLossAmt
     * 4. Validate only non-materially participated activities are on 8582
     */
    public ValidationResult validate(JsonNode payload) {
        ValidationResult result = new ValidationResult();
        
        try {
            // Extract Schedule F activities
            List<ScheduleFActivity> scheduleFActivities = extractScheduleFActivities(payload);
            
            // Extract Form 8582 activities
            List<Form8582Activity> form8582Activities = extractForm8582Activities(payload);
            
            // Validation 1 & 2: Verify Schedule F calculations
            for (ScheduleFActivity schF : scheduleFActivities) {
                validateScheduleFCalculation(schF, result);
            }
            
            // Validation 3 & 4: Match Schedule F with Form 8582
            for (ScheduleFActivity schF : scheduleFActivities) {
                // Only validate non-materially participated activities
                if (!schF.isMateriallyParticipated()) {
                    validateScheduleFToForm8582Matching(schF, form8582Activities, result);
                }
            }
            
            // Validation 5: Verify no materially participated activities on 8582
            validateNoMateriallyParticipatedOn8582(scheduleFActivities, form8582Activities, result);
            
            result.setPassed(result.getErrors().isEmpty());
            
        } catch (Exception e) {
            result.addError("VALIDATION_ERROR", "Error validating payload: " + e.getMessage());
            result.setPassed(false);
        }
        
        return result;
    }
    
    private List<ScheduleFActivity> extractScheduleFActivities(JsonNode payload) {
        List<ScheduleFActivity> activities = new ArrayList<>();
        
        JsonNode body = payload.get("body");
        if (body == null || !body.has("forms")) {
            return activities;
        }
        
        JsonNode forms = body.get("forms");
        for (JsonNode form : forms) {
            JsonNode formNum = form.get("formNum");
            if (formNum != null && "IRS1040ScheduleF".equals(formNum.asText())) {
                ScheduleFActivity activity = parseScheduleFActivity(form);
                if (activity != null) {
                    activities.add(activity);
                }
            }
        }
        
        return activities;
    }
    
    private ScheduleFActivity parseScheduleFActivity(JsonNode form) {
        ScheduleFActivity activity = new ScheduleFActivity();
        
        JsonNode lineItems = form.get("lineItems");
        if (lineItems == null) {
            return null;
        }
        
        for (JsonNode lineItem : lineItems) {
            String lineName = getTextValue(lineItem, "lineNameTxt");
            
            if (lineName == null) {
                continue;
            }
            
            if (lineName.contains("/FarmProprietorName/BusinessNameLine1Txt")) {
                activity.setBusinessName(getTextValue(lineItem, "perReturnValueTxt"));
            } else if (lineName.contains("/PrincipalProductDesc")) {
                activity.setPrincipalProduct(getTextValue(lineItem, "perReturnValueTxt"));
            } else if (lineName.contains("/EIN")) {
                activity.setEin(getTextValue(lineItem, "perReturnValueTxt"));
            } else if (lineName.contains("/MateriallyParticipatedInd")) {
                String value = getTextValue(lineItem, "perReturnValueTxt");
                activity.setMateriallyParticipated("true".equalsIgnoreCase(value));
            } else if (lineName.contains("/FarmIncomeCashMethodGrp/GrossIncomeAmt")) {
                activity.setGrossIncome(parseBigDecimal(lineItem, "perReturnValueTxt"));
            } else if (lineName.contains("/FarmExpensesGrp/TotalExpensesAmt")) {
                activity.setTotalExpenses(parseBigDecimal(lineItem, "perReturnValueTxt"));
            } else if (lineName.contains("/FarmExpensesGrp/NetFarmProfitLossAmt")) {
                activity.setNetFarmProfitLoss(parseBigDecimal(lineItem, "perReturnValueTxt"));
            }
        }
        
        return activity;
    }
    
    private List<Form8582Activity> extractForm8582Activities(JsonNode payload) {
        List<Form8582Activity> activities = new ArrayList<>();
        
        JsonNode body = payload.get("body");
        if (body == null || !body.has("forms")) {
            return activities;
        }
        
        JsonNode forms = body.get("forms");
        for (JsonNode form : forms) {
            JsonNode formNum = form.get("formNum");
            if (formNum != null && "IRS8582".equals(formNum.asText())) {
                parseForm8582Activities(form, activities);
            }
        }
        
        return activities;
    }
    
    private void parseForm8582Activities(JsonNode form, List<Form8582Activity> activities) {
        JsonNode lineItems = form.get("lineItems");
        if (lineItems == null) {
            return;
        }
        
        for (JsonNode lineItem : lineItems) {
            String lineName = getTextValue(lineItem, "lineNameTxt");
            
            if (lineName != null && lineName.contains("/ParentWrkshtPassiveGrp/WrkshtPassiveGrp")) {
                Form8582Activity activity = parseForm8582Activity(lineItem);
                if (activity != null) {
                    activities.add(activity);
                }
            }
        }
    }
    
    private Form8582Activity parseForm8582Activity(JsonNode wrkshtPassiveGrp) {
        Form8582Activity activity = new Form8582Activity();
        
        JsonNode lineItems = wrkshtPassiveGrp.get("lineItems");
        if (lineItems == null) {
            return null;
        }
        
        for (JsonNode lineItem : lineItems) {
            String lineName = getTextValue(lineItem, "lineNameTxt");
            
            if (lineName == null) {
                continue;
            }
            
            if (lineName.contains("/NonParticipateActivityNm")) {
                activity.setActivityName(getTextValue(lineItem, "perReturnValueTxt"));
            } else if (lineName.contains("/CurrentYearNetLossAmt")) {
                activity.setCurrentYearNetLoss(parseBigDecimal(lineItem, "perReturnValueTxt"));
            } else if (lineName.contains("/OverallLossAmt")) {
                activity.setOverallLoss(parseBigDecimal(lineItem, "perReturnValueTxt"));
            }
        }
        
        return activity.getActivityName() != null ? activity : null;
    }
    
    private void validateScheduleFCalculation(ScheduleFActivity schF, ValidationResult result) {
        // Validation: NetFarmProfitLossAmt should equal GrossIncomeAmt - TotalExpensesAmt
        if (schF.getGrossIncome() != null && schF.getTotalExpenses() != null && schF.getNetFarmProfitLoss() != null) {
            BigDecimal calculatedNet = schF.getGrossIncome().subtract(schF.getTotalExpenses());
            BigDecimal reportedNet = schF.getNetFarmProfitLoss();
            
            // Check if calculated loss doesn't match reported loss
            if (calculatedNet.compareTo(reportedNet) != 0) {
                // Special case: If calculated is negative but reported is zero
                if (calculatedNet.compareTo(BigDecimal.ZERO) < 0 && reportedNet.compareTo(BigDecimal.ZERO) == 0) {
                    result.addError(
                        "SCHEDULE_F_NET_LOSS_INCORRECT",
                        String.format(
                            "Schedule F for '%s': NetFarmProfitLossAmt (Line 34) shows $s but should show %s. " +
                            "Calculation: GrossIncomeAmt (Line 9) %s - TotalExpensesAmt (Line 33) %s = %s. " +
                            "Passive activity losses must be reported on Schedule F and then limited on Form 8582.",
                            schF.getBusinessName() != null ? schF.getBusinessName() : schF.getPrincipalProduct(),
                            formatAmount(reportedNet),
                            formatAmount(calculatedNet),
                            formatAmount(schF.getGrossIncome()),
                            formatAmount(schF.getTotalExpenses()),
                            formatAmount(calculatedNet)
                        )
                    );
                } else {
                    result.addWarning(
                        "SCHEDULE_F_CALCULATION_MISMATCH",
                        String.format(
                            "Schedule F for '%s': NetFarmProfitLossAmt %s does not match calculated value %s (%s - %s).",
                            schF.getBusinessName() != null ? schF.getBusinessName() : schF.getPrincipalProduct(),
                            formatAmount(reportedNet),
                            formatAmount(calculatedNet),
                            formatAmount(schF.getGrossIncome()),
                            formatAmount(schF.getTotalExpenses())
                        )
                    );
                }
            }
        }
    }
    
    private void validateScheduleFToForm8582Matching(ScheduleFActivity schF, 
                                                                 List<Form8582Activity> form8582Activities, 
                                                                 ValidationResult result) {
                                                                 
        // Find matching activity on Form 8582 by name
        Optional<Form8582Activity> matching8582 = findMatching8582Activity(schF, form8582Activities);
        
        if (!matching8582.isPresent()) {
            // Non-materially participated activity should be on Form 8582
            BigDecimal calculatedNet = schF.getGrossIncome() != null && schF.getTotalExpenses() != null ?
                schF.getGrossIncome().subtract(schF.getTotalExpenses()) : BigDecimal.ZERO;
            
            if (calculatedNet.compareTo(BigDecimal.ZERO) < 0) {
                result.addError(
                    "MISSING_ON_FORM_8582",
                    String.format(
                        "Schedule F activity '%s' (non-materially participated) with loss of %s not found on Form 8582. " +
                        "Passive activity losses must be reported on Form 8582.",
                        schF.getBusinessName() != null ? schF.getBusinessName() : schF.getPrincipalProduct(),
                        formatAmount(calculatedNet)
                    )
                );
            }
            return;
        }
        
        Form8582Activity form8582 = matching8582.get();
        
        // Validate: Schedule F loss should match Form 8582 CurrentYearNetLossAmt
        BigDecimal calculatedNet = schF.getGrossIncome() != null && schF.getTotalExpenses() != null ?
            schF.getGrossIncome().subtract(schF.getTotalExpenses()) : BigDecimal.ZERO;
        
        if (calculatedNet.compareTo(BigDecimal.ZERO) < 0) {
            // Convert to positive for comparison (losses are stored as positive on 8582)
            BigDecimal expectedLoss = calculatedNet.abs();
            BigDecimal form8582Loss = form8582.getCurrentYearNetLoss() != null ? 
                form8582.getCurrentYearNetLoss() : BigDecimal.ZERO;
            
            if (expectedLoss.compareTo(form8582Loss) != 0) {
                result.addError(
                    "FORM_8582_LOSS_MISMATCH",
                    String.format(
                        "Loss mismatch for '%s': Schedule F calculated loss %s does not match Form 8582 CurrentYearNetLossAmt %s.",
                        schF.getBusinessName() != null ? schF.getBusinessName() : schF.getPrincipalProduct(),
                        formatAmount(expectedLoss),
                        formatAmount(form8582Loss)
                    )
                );
            }
        }
    }
    
    private void validateNoMateriallyParticipatedOn8582(List<ScheduleFActivity> scheduleFActivities,
                                                                          List<Form8582Activity> form8582Activities,
                                                                          ValidationResult result) {
        // Check if any materially participated activities are incorrectly on Form 8582
        for (Form8582Activity form8582 : form8582Activities) {
            Optional<ScheduleFActivity> matchingSchF = findMatchingScheduleFActivity(form8582, scheduleFActivities);
            
            if (matchingSchF.isPresent() && matchingSchF.get().isMateriallyParticipated()) {
                result.addError(
                    "MATERIALLY_PARTICIPATED_ON_8582",
                    String.format(
                        "Activity '%s' is marked as materially participated on Schedule F but appears on Form 8582. " +
                        "Only non-materially participated activities should be on Form 8582.",
                        form8582.getActivityName()
                    )
                );
            }
        }
    }
    
    private Optional<Form8582Activity> findMatching8582Activity(ScheduleFActivity schF, 
                                                                                List<Form8582Activity> form8582Activities) {
        return form8582Activities.stream()
            .filter(form8582 -> matchActivityNames(schF, form8582))
            .findFirst();
    }
    
    private Optional<ScheduleFActivity> findMatchingScheduleFActivity(Form8582Activity form8582, 
                                                                                      List<ScheduleFActivity> scheduleFActivities) {
        return scheduleFActivities.stream()
            .filter(schF -> matchActivityNames(schF, form8582))
            .findFirst();
    }
    
    private boolean matchActivityNames(ScheduleFActivity schF, Form8582Activity form8582) {
        String schFName = schF.getBusinessName() != null ? schF.getBusinessName() : schF.getPrincipalProduct();
        String form8582Name = form8582.getActivityName();
        
        if (schFName == null || form8582Name == null) {
            return false;
        }
        
        // Case-insensitive matching and trim whitespace
        return schFName.trim().equalsIgnoreCase(form8582Name.trim());
    }
    
    private String getTextValue(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field != null && !field.isNull() ? field.asText() : null;
    }
    
    private BigDecimal parseBigDecimal(JsonNode node, String fieldName) {
        String value = getTextValue(node, fieldName);
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
    
    private String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return "$0";
        }
        String sign = amount.compareTo(BigDecimal.ZERO) < 0 ? "-$" : "$";
        return sign + amount.abs().setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
    
    // Data classes
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScheduleFActivity {
        private String businessName;
        private String principalProduct;
        private String ein;
        private boolean materiallyParticipated;
        private BigDecimal grossIncome;
        private BigDecimal totalExpenses;
        private BigDecimal netFarmProfitLoss;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Form8582Activity {
        private String activityName;
        private BigDecimal currentYearNetLoss;
        private BigDecimal overallLoss;
    }
    
    @Data
    @NoArgsConstructor
    public static class ValidationResult {
        private boolean passed;
        private Map<String, List<String>> errors = new HashMap<>();
        private Map<String, List<String>> warnings = new HashMap<>();
        
        public void addError(String code, String message) {
            errors.computeIfAbsent(code, k -> new ArrayList<>()).add(message);
        }
        
        public void addWarning(String code, String message) {
            warnings.computeIfAbsent(code, k -> new ArrayList<>()).add(message);
        }
    }
}