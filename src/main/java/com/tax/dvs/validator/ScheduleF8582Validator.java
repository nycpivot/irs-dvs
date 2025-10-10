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

@Component
public class ScheduleF8582Validator {

    /**
     * Validates that Schedule F farm activities correctly match with Form 8582 passive activity losses.
     * 
     * Business Rules:
     * 1. Business names on Schedule F must match Form 8582 activity names for passive activities
     * 2. When a loss exists, Schedule F Line 34 (NetFarmProfitLossAmt) may show $0
     * 3. Actual loss is calculated as: Line 9 (GrossIncomeAmt) - Line 33 (TotalExpensesAmt)
     * 4. Calculated loss must match Form 8582 CurrentYearNetLossAmt
     * 5. Only passive activities (MateriallyParticipatedInd = false) should appear on Form 8582
     * 6. Active activities (MateriallyParticipatedInd = true) should NOT appear on Form 8582
     *
     * IRS Rules:
     * - Passive activity losses are limited under IRC Section 469
     * - Material participation determines if activity is active or passive
     * - Form 8582 tracks only passive activity losses
     * 
     * @param payload The full tax return JSON payload
     * @return ValidationResult containing validation status and details
     */
    public ValidationResult validateScheduleFToForm8582(JsonNode payload) {
        ValidationResult result = ValidationResult.builder()
                .valid(true)
                .errors(new ArrayList<>())
                .warnings(new ArrayList<>())
                .scheduleFDetails(new ArrayList<>())
                .form8582Details(new ArrayList<>())
                .build();

        try {
            // Extract Schedule F data
            List<ScheduleFData> scheduleFList = extractScheduleFData(payload);
            result.getScheduleFDetails().addAll(scheduleFList);

            // Extract Form 8582 data
            List<Form8582Data> form8582List = extractForm8582Data(payload);
            result.getForm8582Details().addAll(form8582List);

            // Validation 1: Check passive activities match between Schedule F and Form 8582
            validatePassiveActivityMatching(scheduleFList, form8582List, result);

            // Validation 2: Verify loss calculations and matching
            validateLossCalculations(scheduleFList, form8582List, result);

            // Validation 3: Verify active activities are NOT in Form 8582
            validateActiveActivitiesExcluded(scheduleFList, form8582List, result);

            // Validation 4: Verify Line 34 shows $0 for losses
            validateLine34ZeroForLosses(scheduleFList, result);

        } catch (Exception e) {
            result.setValid(false);
            result.getErrors().add("Validation error: " + e.getMessage());
        }

        return result;
    }

    /**
     * Extracts Schedule F data from the payload
     */
    private List<ScheduleFData> extractScheduleFData(JsonNode payload) {
        List<ScheduleFData> scheduleFList = new ArrayList<>();
        JsonNode forms = payload.at("/body/forms");

        if (forms.isArray()) {
            for (JsonNode form : forms) {
                String formNum = form.at("/formNum").asText("");
                if ("IRS1040ScheduleF".equals(formNum)) {
                    ScheduleFData data = parseScheduleFForm(form);
                    scheduleFList.add(data);
                }
            }
        }

        return scheduleFList;
    }

    /**
     * Parses a single Schedule F form
     */
    private ScheduleFData parseScheduleFForm(JsonNode form) {
        ScheduleFData data = new ScheduleFData();
        
        data.setSequenceNum(form.at("/sequenceNum").asText(""));
        data.setEin(getLineItemValue(form, "/IRS1040ScheduleF/EIN"));
        
        // Extract business name
        String businessName = getLineItemValue(form, "/IRS1040ScheduleF/FarmProprietorName/BusinessNameLine1Txt");
        if (businessName.isEmpty()) {
            businessName = getLineItemValue(form, "/IRS1040ScheduleF/PrincipalProductDesc");
        }
        data.setBusinessName(businessName);
        
        data.setPrincipalProduct(getLineItemValue(form, "/IRS1040ScheduleF/PrincipalProductDesc"));
        
        // Material participation indicator
        String materialParticipation = getLineItemValue(form, "/IRS1040ScheduleF/MateriallyParticipatedInd");
        data.setMateriallyParticipated("true".equalsIgnoreCase(materialParticipation));
        
        // Line 9 - Gross Income
        String grossIncomeStr = getLineItemValue(form, "/IRS1040ScheduleF/FarmIncomeCashMethodGrp/GrossIncomeAmt");
        data.setGrossIncomeAmt(parseAmount(grossIncomeStr));
        
        // Line 33 - Total Expenses
        String totalExpensesStr = getLineItemValue(form, "/IRS1040ScheduleF/FarmExpensesGrp/TotalExpensesAmt");
        data.setTotalExpensesAmt(parseAmount(totalExpensesStr));
        
        // Line 34 - Net Farm Profit/Loss
        String netProfitLossStr = getLineItemValue(form, "/IRS1040ScheduleF/FarmExpensesGrp/NetFarmProfitLossAmt");
        data.setNetFarmProfitLossAmt(parseAmount(netProfitLossStr));
        
        // Calculate actual loss (Line 9 - Line 33)
        data.setCalculatedNetAmt(data.getGrossIncomeAmt().subtract(data.getTotalExpensesAmt()));
        
        return data;
    }

    /**
     * Extracts Form 8582 data from the payload
     */
    private List<Form8582Data> extractForm8582Data(JsonNode payload) {
        List<Form8582Data> form8582List = new ArrayList<>();
        JsonNode forms = payload.at("/body/forms");

        if (forms.isArray()) {
            for (JsonNode form : forms) {
                String formNum = form.at("/formNum").asText("");
                if ("IRS8582".equals(formNum)) {
                    form8582List.addAll(parseForm8582(form));
                }
            }
        }

        return form8582List;
    }

    /**
     * Parses Form 8582 data
     */
    private List<Form8582Data> parseForm8582(JsonNode form) {
        List<Form8582Data> dataList = new ArrayList<>();
        
        // Parse Worksheet Passive Group
        JsonNode parentWrkshtPassiveGrp = findLineItem(form, "/IRS8582/ParentWrkshtPassiveGrp");
        if (parentWrkshtPassiveGrp != null && !parentWrkshtPassiveGrp.isMissingNode()) {
            JsonNode wrkshtPassiveGrpArray = parentWrkshtPassiveGrp.at("/lineItems");
            if (wrkshtPassiveGrpArray.isArray()) {
                for (JsonNode item : wrkshtPassiveGrpArray) {
                    String lineName = item.at("/lineNameTxt").asText("");
                    if (lineName.contains("WrkshtPassiveGrp") && !lineName.contains("Total")) {
                        Form8582Data data = parseWrkshtPassiveGrp(item);
                        if (data != null) {
                            dataList.add(data);
                        }
                    }
                }
            }
        }
        
        return dataList;
    }

    /**
     * Parses a single Worksheet Passive Group entry
     */
    private Form8582Data parseWrkshtPassiveGrp(JsonNode group) {
        Form8582Data data = new Form8582Data();
        
        JsonNode lineItems = group.at("/lineItems");
        if (lineItems.isArray()) {
            for (JsonNode item : lineItems) {
                String lineName = item.at("/lineNameTxt").asText("");
                String value = item.at("/perReturnValueTxt").asText("");
                
                if (lineName.contains("NonParticipateActivityNm")) {
                    data.setActivityName(value);
                } else if (lineName.contains("CurrentYearNetLossAmt")) {
                    data.setCurrentYearNetLossAmt(parseAmount(value));
                } else if (lineName.contains("OverallLossAmt")) {
                    data.setOverallLossAmt(parseAmount(value));
                }
            }
        }
        
        return data.getActivityName() != null ? data : null;
    }

    /**
     * Validation 1: Verify passive activities match between Schedule F and Form 8582
     */
    private void validatePassiveActivityMatching(List<ScheduleFData> scheduleFList,
                                                       List<Form8582Data> form8582List,
                                                       ValidationResult result) {
        // Get all passive activities from Schedule F
        List<ScheduleFData> passiveActivities = scheduleFList.stream()
                .filter(f -> !f.isMateriallyParticipated())
                .collect(Collectors.toList());
        
        // Create map of Form 8582 activities
        Map<String, Form8582Data> form8582Map = form8582List.stream()
                .collect(Collectors.toMap(
                        Form8582Data::getActivityName,
                        f -> f,
                        (existing, replacement) -> existing
                ));
        
        // Check each passive activity
        for (ScheduleFData scheduleF : passiveActivities) {
            String businessName = scheduleF.getBusinessName();
            String principalProduct = scheduleF.getPrincipalProduct();
            
            // Try to match by business name or principal product
            boolean found = form8582Map.containsKey(businessName) || 
                              form8582Map.containsKey(principalProduct);
            
            if (!found) {
                result.setValid(false);
                result.getErrors().add(String.format(
                        "Passive activity '%s' from Schedule F (Seq %s) not found on Form 8582",
                        principalProduct, scheduleF.getSequenceNum()
                ));
            }
        }
    }

    /**
     * Validation 2: Verify loss calculations and matching
     */
    private void validateLossCalculations(List<ScheduleFData> scheduleFList,
                                                 List<Form8582Data> form8582List,
                                                 ValidationResult result) {
        // Create map of Form 8582 activities
        Map<String, Form8582Data> form8582Map = form8582List.stream()
                .collect(Collectors.toMap(
                        Form8582Data::getActivityName,
                        f -> f,
                        (existing, replacement) -> existing
                ));
        
        // Check each Schedule F with a loss
        for (ScheduleFData scheduleF : scheduleFList) {
            BigDecimal calculatedNet = scheduleF.getCalculatedNetAmt();
            
            // Only validate if there's a loss
            if (calculatedNet.compareTo(BigDecimal.ZERO) < 0) {
                String activityName = scheduleF.getPrincipalProduct();
                Form8582Data form8582Data = form8582Map.get(activityName);
                
                if (form8582Data != null) {
                    BigDecimal form8582Loss = form8582Data.getCurrentYearNetLossAmt();
                    
                    // Compare absolute values (losses are negative on Schedule F, positive on 8582)
                    BigDecimal calculatedLossAbs = calculatedNet.abs();
                    
                    if (calculatedLossAbs.compareTo(form8582Loss) != 0) {
                        result.setValid(false);
                        result.getErrors().add(String.format(
                                "Loss mismatch for '%s': Schedule F calculated loss = $%s, Form 8582 loss = $%s",
                                activityName, calculatedLossAbs, form8582Loss
                        ));
                    }
                } else if (!scheduleF.isMateriallyParticipated()) {
                    // Passive activity with loss should be on Form 8582
                    result.setValid(false);
                    result.getErrors().add(String.format(
                            "Passive activity '%s' with loss $%s not found on Form 8582",
                            activityName, calculatedNet.abs()
                    ));
                }
            }
        }
    }

    /**
     * Validation 3: Verify active activities are NOT in Form 8582
     */
    private void validateActiveActivitiesExcluded(List<ScheduleFData> scheduleFList,
                                                        List<Form8582Data> form8582List,
                                                        ValidationResult result) {
        // Get all active activities from Schedule F
        List<ScheduleFData> activeActivities = scheduleFList.stream()
                .filter(ScheduleFData::isMateriallyParticipated)
                .collect(Collectors.toList());
        
        // Create set of Form 8582 activity names
        Set<String> form8582Names = form8582List.stream()
                .map(Form8582Data::getActivityName)
                .collect(Collectors.toSet());
        
        // Check if any active activity appears on Form 8582
        for (ScheduleFData scheduleF : activeActivities) {
            String activityName = scheduleF.getPrincipalProduct();
            
            if (form8582Names.contains(activityName)) {
                result.setValid(false);
                result.getErrors().add(String.format(
                        "Active activity '%s' (materially participated) should NOT appear on Form 8582",
                        activityName
                ));
            }
        }
    }

    /**
     * Validation 4: Verify Line 34 shows $0 for losses
     */
    private void validateLine34ZeroForLosses(List<ScheduleFData> scheduleFList,
                                                    ValidationResult result) {
        for (ScheduleFData scheduleF : scheduleFList) {
            BigDecimal calculatedNet = scheduleF.getCalculatedNetAmt();
            BigDecimal reportedNet = scheduleF.getNetFarmProfitLossAmt();
            
            // If there's a loss, Line 34 should show $0
            if (calculatedNet.compareTo(BigDecimal.ZERO) < 0) {
                if (reportedNet.compareTo(BigDecimal.ZERO) != 0) {
                    result.getWarnings().add(String.format(
                            "Schedule F '%s' (Seq %s): Line 34 shows $%s but should show $0 for losses (calculated loss: $%s)",
                            scheduleF.getPrincipalProduct(),
                            scheduleF.getSequenceNum(),
                            reportedNet,
                            calculatedNet.abs()
                        ));
                }
            }
        }
    }

    /**
     * Helper method to get line item value from form
     */
    private String getLineItemValue(JsonNode form, String lineNamePath) {
        JsonNode lineItem = findLineItem(form, lineNamePath);
        if (lineItem != null && !lineItem.isMissingNode()) {
            JsonNode valueNode = lineItem.at("/perReturnValueTxt");
            if (!valueNode.isMissingNode()) {
                return valueNode.asText("");
            }
        }
        return "";
    }

    /**
     * Helper method to find a line item by path
     */
    private JsonNode findLineItem(JsonNode form, String lineNamePath) {
        JsonNode lineItems = form.at("/lineItems");
        if (lineItems.isArray()) {
            for (JsonNode item : lineItems) {
                String lineName = item.at("/lineNameTxt").asText("");
                if (lineName.equals(lineNamePath)) {
                    return item;
                }
                // Check nested lineItems
                JsonNode nested = findLineItem(item, lineNamePath);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /**
     * Helper method to parse amount string to BigDecimal
     */
    private BigDecimal parseAmount(String amountStr) {
        if (amountStr == null || amountStr.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            // Remove commas and dollar signs
            String cleaned = amountStr.replaceAll("[,$]", "").trim();
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    // Data classes
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationResult {
        private boolean valid;
        private List<String> errors;
        private List<String> warnings;
        private List<ScheduleFData> scheduleFDetails;
        private List<Form8582Data> form8582Details;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScheduleFData {
        private String sequenceNum;
        private String ein;
        private String businessName;
        private String principalProduct;
        private boolean materiallyParticipated;
        private BigDecimal grossIncomeAmt; // Line 9
        private BigDecimal totalExpensesAmt; // Line 33
        private BigDecimal netFarmProfitLossAmt; // Line 34
        private BigDecimal calculatedNetAmt; // Line 9 - Line 33
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Form8582Data {
        private String activityName;
        private BigDecimal currentYearNetLossAmt;
        private BigDecimal overallLossAmt;
    }
}