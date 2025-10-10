package com.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgusConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Validator for matching Schedule F data with Form 8582 Passive Activity Losses
 * Based on IRS Publication 925 and Form 8582 instructions
 */
@Service
public class ScheduleFTo8582Validator {

    private static final String SCHEME_F_PATH = "/IRS1040ScheduleF";
    private static final String FORM_8582_PATH = "/IRS8582";
    private static final String FORM_1040_PATH = "/IRS1040";

    /**
     * Validates the entire tax return payload for Schedule F to Form 8582 consistency
     */
    public ValidationResult validate(JsonNode payload) {
        ValidationResult result = ValidationResult.builder()
                .passed(true)
                .errors(new ArrayList<>())
                .warnings(new ArrayList<>())
                .details(new ArrayList<>())
                .build();

        try {
            JsonNode body = payload.get("body");
            if (body == null || !body.has("forms")) {
                result.getErrors().add("Missing 'body.forms' in payload");
                result.setPassed(false);
                return result;
            }

            // Extract all Schedule F forms
            List<ScheduleFData> scheduleFList = extractScheduleFData(body);
            
            // Extract Form 8582 data
            List<Form8582Data> form8582List = extractForm8582Data(body);

            // Validate each Schedule F
            for (ScheduleFData scheduleF : scheduleFList) {
                validateScheduleF(scheduleF, form8582List, result);
            }

            // Validate Form 8582 completeness
            validateForm8582Completeness(scheduleFList, form8582List, result);

            // Validate aggregate amounts
            validateAggregateAmounts(scheduleFList, form8582List, body, result);

        } catch (Exception e) {
            result.getErrors().add("Validation exception: " + e.getMessage());
            result.setPassed(false);
        }

        return result;
    }

    /**
     * Extracts all Schedule F data from the payload
     */
    private List<ScheduleFData> extractScheduleFData(JsonNode body) {
        List<ScheduleFData> scheduleFList = new ArrayList<>();
        
        for (JsonNode form : body.get("forms")) {
            if ("IRS1040ScheduleF".equals(form.get("formNum").asText())) {
                ScheduleFData data = ScheduleFData.builder()
                        .sequenceNum(form.get("sequenceNum").asText())
                        .businessName(extractTextValue(form, "/IRS1040ScheduleF/FarmProprietorName/BusinessNameLine1Txt"))
                        .principalProduct(extractTextValue(form, "/IRS1040ScheduleF/PrincipalProductDesc"))
                        .ein(extractTextValue(form, "/IRS1040ScheduleF/EIN"))
                        .materiallyParticipated(extractBooleanValue(form, "/IRS1040ScheduleF/MateriallyParticipatedInd"))
                        .grossIncome(extractAmount(form, "/IRS1040ScheduleF/FarmIncomeCashMethodGrp/GrossIncomeAmt"))
                        .totalExpenses(extractAmount(form, "/IRS1040ScheduleF/FarmExpensesGrp/TotalExpensesAmt"))
                        .netProfitLoss(extractAmount(form, "/IRS1040ScheduleF/FarmExpensesGrp/NetFarmProfitLossAmt"))
                        .build();
                
                scheduleFList.add(data);
            }
        }
        
        return scheduleFList;
    }

    /**
     * Extracts Form 8582 passive activity data
     */
    private List<Form8582Data> extractForm8582Data(JsonNode body) {
        List<Form8582Data> form8582List = new ArrayList<>();
        
        for (JsonNode form : body.get("forms")) {
            if ("IRS8582".equals(form.get("formNum").asText())) {
                // Extract activities from Worksheet Passive Group
                JsonNode parentWrksht = findLineItem(form, "/IRS8582/ParentWrkshtPassiveGrp");
                if (parentWrksht != null && parentWrksht.has("lineItems")) {
                    for (JsonNode lineItem : parentWrksht.get("lineItems")) {
                        if ("/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp".equals(lineItem.get("lineNameTxt").asText())) {
                            Form8582Data data = Form8582Data.builder()
                                    .activityName(extractTextValue(lineItem, "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/NonParticipateActivityNm"))
                                    .currentYearNetLoss(extractAmount(lineItem, "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/CurrentYearNetLossAmt"))
                                    .overallLoss(extractAmount(lineItem, "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/OverallLossAmt"))
                                    .build();
                            
                            if (data.getActivityName() != null && !data.getActivityName().isEmpty()) {
                                form8582List.add(data);
                            }
                        }
                    }
                }
            }
        }
        
        return form8582List;
    }

    /**
     * Validates a single Schedule F against Form 8582
     */
    private void validateScheduleF(ScheduleFData scheduleF, List<Form8582Data> form8582List, ValidationResult result) {
        
        // Calculate expected net profit/loss (Line 9 - Line 33)
        BigDecimal calculatedNet = scheduleF.getGrossIncome().subtract(scheduleF.getTotalExpenses());
        
        // Check if this is a passive activity (not materially participated)
        if (!scheduleF.isMateriallyParticipated()) {
            
            // Find matching Form 8582 entry
            Optional<Form8582Data> matching8582 = form8582List.stream()
                    .filter(f => isBusinessNameMatch(scheduleF.getBusinessName(), scheduleF.getPrincipalProduct(), f.getActivityName()))
                    .findFirst();

            if (calculatedNet.compareTo(BigDecimal.ZERO) < 0) {
                // This is a loss - should be on Form 8582
                
                if (matching8582.isPresent()) {
                    Form8582Data form8582 = matching8582.get();
                    
                    // Validate loss amount matches
                    BigDecimal expectedLoss = calculatedNet.abs();
                    if (!amountsMatch(expectedLoss, form8582.getCurrentYearNetLoss())) {
                        result.getErrors().add(String.format(
                            "Schedule F '%s' loss mismatch: Calculated $%s (Line 9 - Line 33), Form 8582 shows $%s",
                            scheduleF.getBusinessName(),
                            expectedLoss,
                            form8582.getCurrentYearNetLoss()
                        ));
                        result.setPassed(false);
                    }
                    
                    // Validate Schedule F Line 34 shows zero for passive losses
                    if (scheduleF.getNetProfitLoss().compareTo(BigDecimal.ZERO) != 0) {
                        result.getErrors().add(String.format(
                            "Schedule F '%s' Line 34 (NetFarmProfitLossAmt) should be $0 for passive losses, but shows $%s",
                            scheduleF.getBusinessName(),
                            scheduleF.getNetProfitLoss()
                        ));
                        result.setPassed(false);
                    }
                    
                    result.getDetails().add(String.format(
                        "Schedule F '%s': Passive loss $%s correctly reported on Form 8582",
                        scheduleF.getBusinessName(),
                        expectedLoss
                    ));
                    
                } else {
                    result.getErrors().add(String.format(
                        "Schedule F '%s' has passive loss of $%s but not found on Form 8582",
                        scheduleF.getBusinessName(),
                        calculatedNet.abs()
                    ));
                    result.setPassed(false);
                }
                
            } else if (calculatedNet.compareTo(BigDecimal.ZERO) >= 0) {
                // Passive income or zero - should NOT be on Form 8582
                if (matching8582.isPresent()) {
                    result.getWarnings().add(String.format(
                        "Schedule F '%s' has passive income $%s but appears on Form 8582 (may be offsetting prior losses)",
                        scheduleF.getBusinessName(),
                        calculatedNet
                    ));
                }
            }
            
        } else {
            // Materially participated - should NOT be on Form 8582
            Optional<Form8582Data> matching8582 = form8582List.stream()
                    .filter(f => isBusinessNameMatch(scheduleF.getBusinessName(), scheduleF.getPrincipalProduct(), f.getActivityName()))
                    .findFirst();
                    
            if (matching8582.isPresent()) {
                result.getErrors().add(String.format(
                    "Schedule F '%s' marked as materially participated but appears on Form 8582 (passive activities only)",
                    scheduleF.getBusinessName()
                ));
                result.setPassed(false);
            }
            
            // Validate Line 34 matches calculated amount
            if (!amountsMatch(calculatedNet, scheduleF.getNetProfitLoss())) {
                result.getErrors().add(String.format(
                    "Schedule F '%s' Line 34 mismatch: Calculated $%s (Line 9 - Line 33), Line 34 shows $%s",
                    scheduleF.getBusinessName(),
                    calculatedNet,
                    scheduleF.getNetProfitLoss()
                ));
                result.setPassed(false);
            }
        }
    }

    /**
     * Validates that all passive activities on Form 8582 have corresponding Schedule F
     */
    private void validateForm8582Completeness(List<ScheduleFData> scheduleFList, List<Form8582Data> form8582List, ValidationResult result) {
        
        for (Form8582Data form8582 : form8582List) {
            boolean found = scheduleFList.stream()
                    .anyMatch(s => isBusinessNameMatch(s.getBusinessName(), s.getPrincipalProduct(), form8582.getActivityName()));

            if (!found) {
                result.getWarnings().add(String.format(
                    "Form 8582 activity '%s' with loss $%s not found on any Schedule F",
                    form8582.getActivityName(),
                    form8582.getCurrentYearNetLoss()
                ));
            }
        }
    }

    /**
     * Validates aggregate amounts on Form 1040
     */
    private void validateAggregateAmounts(List<ScheduleFData> scheduleFList, List<Form8582Data> form8582List, JsonNode body, ValidationResult result) {
        
        // Calculate total net farm profit from all Schedule Fs
        BigDecimal totalScheduleFNet = scheduleFList.stream()
                .map(s => {
                    if (s.isMateriallyParticipated()) {
                        // Use reported net for material participation
                        return s.getNetProfitLoss();
                    } else {
                        // Passive losses are not included in AGI
                        return BigDecimal.ZERO;
                    }
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Extract reported net farm profit from Form 1040
        BigDecimal reportedNetFarmProfit = BigDecimal.ZERO;
        for (JsonNode form : body.get("forms")) {
            if ("IRS1040Schedule1".equals(form.get("formNum").asText())) {
                reportedNetFarSPofit = extractAmount(form, "/IRS1040Schedule1/NetFarmProfitLossAmt");
                break;
            }
        }

        if (!amountsMatch(totalScheduleFNet, reportedNetFarmProfit)) {
            result.getErrors().add(String.format(
                "Form 1040 Schedule 1 NetFarmProfitLossAmt mismatch: Calculated $%s from Schedule Fs, reported $%s",
                totalScheduleFNet,
                reportedNetFarSPofit
            ));
            result.setPassed(false);
        }
    }

    /**
     * Checks if business names match (case-insensitive, handles partial matches)
     */
    private boolean isBusinessNameMatch(String businessName, String principalProduct, String activityName) {
        if (businessName == null || activityName == null) {
            return false;
        }
        
        String normalizedBastness = businessName.toUpperCase().trim();
        String normalizedActivity = activityName.toUpperCase().trim();
        String normalizedProduct = (principalProduct != null) ? principalProduct.toUpperCase().trim() : "";

        // Direct match
        if (normalizedBusiness.equals(normalizedActivity)) {
            return true;
        }
        
        // Match by principal product
        if (!normalizedProduct.isEmpty() && normalizedProduct.equals(normalizedActivity)) {
            return true;
        }
        
        // Partial match
        return normalizedBastness.contains(normalizedActivity) || normalizedActivity.contains(normalizedBusiness);
    }

    /**
     * Compares two amounts with tolerance for rounding
     */
    private boolean amountsMatch(BigDecimal amount1, BigDecimal amount2) {
        if (amount1 == null || amount2 == null) {
            return false;
        }
        
        // Allow 1 cent tolerance for rounding
        BigDecimal diff = amount1.subtract(amount2).abs();
        return diff.compareTo(new BigDecimal("0.01")) <= 0;
    }

    /**
     * Extracts text value from line items
     */
    private String extractTextValue(JsonNode form, String path) {
        JsonNode lineItem = findLineItem(form, path);
        if (lineItem != null && lineItem.has("perReturnValueTxt")) {
            return lineItem.get("perReturnValueTxt").asText();
        }
        return null;
    }

    /**
     * Extracts boolean value from line items
     */
    private boolean extractBooleanValue(JsonNode form, String path) {
        JsonNode lineItem = findLineItem(form, path);
        if (lineItem != null && lineItem.has("perReturnValueTxt")) {
            String value = lineItem.get("perReturnValueTxt").asText();
            return "true".equalsIgnoreCase(value) || "X".equals(value);
        }
        return false;
    }

    /**
     * Extracts monetary amount from line items
     */
    private BigDecimal extractAmount(JsonNode form, String path) {
        JsonNode lineItem = findLineItem(form, path);
        if (lineItem != null && lineItem.has("perReturnValueTxt")) {
            try {
                return new BigDecimal(lineItem.get("perReturnValueTxt").asText());
            } catch (NumberFormatException e) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * Finds a line item by path
     */
    private JsonNode findLineItem(JsonNode form, String path) {
        if (!form.has("lineItems")) {
            return null;
        }
        
        for (JsonNode lineItem : form.get("lineItems")) {
            if (lineItem.has("lineNameTxt") && path.equals(lineItem.get("lineNameTxt").asText())) {
                return lineItem;
            }
            
            // Recursively search nested lineItems
            if (lineItem.has("lineItems")) {
                JsonNode nested = findLineItem(lineItem, path);
                if (nested != null) {
                    return nested;
                }
            }
        }
        
        return null;
    }

    // Data Transfer Objects
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScheduleFData {
        private String sequenceNum;
        private String businessName;
        private String principalProduct;
        private String ein;
        private boolean materiallyParticipated;
        private BigDecimal grossIncome;
        private BigDecimal totalExpenses;
        private BigDecimal netProfitLoss;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Form8582Data {
        private String activityName;
        private BigDecimal currentYearNetLoss;
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
        private List<String> details;
    }
}