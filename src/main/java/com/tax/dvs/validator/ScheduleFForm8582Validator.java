package com.tax.dvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Component
public class ScheduleFForm8582Validator {

    /**
     * Validates Schedule F matching with Form 8582
     * 
     * Requirements:
     * 1. Match business names between Schedule F and Form 8582
     * 2. Validate loss amounts: Line 9 (Gross Income) - Line 33 (Total Expenses) = Loss
     * 3. Verify that Schedule F NetFarmProfitLossAmt shows zero for losses
     * 4. Confirm Form 8582 contains correct loss amounts
     */
    public ValidationResult validate(JsonNode payload) {
        ValidationResult result = ValidationResult.builder()
                .passed(true)
                .errors(new ArrayList<>())
                .warnings(new ArrayList<>())
                .farmValidations(new ArrayList<>())
                .build();

        try {
            // Extract Schedule F forms
            List<JsonNode> scheduleFForms = extractScheduleFForms(payload);
            
            // Extract Form 8582
            JsonNode form8582 = extractForm8582(payload);

            if (scheduleFForms.isEmpty()) {
                result.getErrors().add("No Schedule F forms found in payload");
                result.setPassed(false);
                return result;
            }

            // Process each Schedule F
            for (JsonNode scheduleF : scheduleFForms) {
                FarmValidation farmValidation = validateScheduleF(scheduleF, form8582);
                result.getFarmValidations().add(farmValidation);
                
                if (!farmValidation.isPassed()) {
                    result.setPassed(false);
                    result.getErrors().addAll(farmValidation.getErrors());
                }
                
                result.getWarnings().addAll(farmValidation.getWarnings());
            }

        } catch (Exception e) {
            result.setPassed(false);
            result.getErrors().add("Validation exception: " + e.getMessage());
        }

        return result;
    }

    private FarmValidation validateScheduleF(JsonNode scheduleF, JsonNode form8582) {
        FarmValidation farmVal = FarmValidation.builder()
                .passed(true)
                .errors(new ArrayList<>())
                .warnings(new ArrayList<>())
                .build();

        // Extract business name
        String businessName = extractBusinessName(scheduleF);
        farmVal.setBusinessName(businessName);

        // Extract EIN
        String ein = extractTextValue(scheduleF, "/IRS1040ScheduleF/EIN");
        farmVal.setEin(ein);

        // Extract material participation
        boolean materiallyParticipated = extractBooleanValue(scheduleF, "/IRS1040ScheduleF/MateriallyParticipatedInd");
        farmVal.setMateriallyParticipated(materiallyParticipated);

        // Extract Line 9 - Gross Income
        BigDecimal grossIncome = extractAmount(scheduleF, "/IRS1040ScheduleF/FarmIncomeCashMethodGrp/GrossIncomeAmt");
        farmVal.setGrossIncome(grossIncome);

        // Extract Line 33 - Total Expenses
        BigDecimal totalExpenses = extractAmount(scheduleF, "/IRS1040ScheduleF/FarmExpensesGrp/TotalExpensesAmt");
        farmVal.setTotalExpenses(totalExpenses);

        // Extract Net Farm Profit/Loss (Line 34)
        BigDecimal netFarmProfitLoss = extractAmount(scheduleF, "/IRS1040ScheduleF/FarmExpensesGrp/NetFarmProfitLossAmt");
        farmVal.setNetFarmProfitLoss(netFarmProfitLoss);

        // Calculate expected net (Line 9 - Line 33)
        BigDecimal calculatedNet = grossIncome.subtract(totalExpenses);
        farmVal.setCalculatedNet(calculatedNet);

        // Determine if this is a loss
        boolean isLoss = calculatedNet.compareTo(BigDecimal.ZERO) < 0;
        farmVal.setLoss(calculatedNet.negate());

        // Validation 1: Check if NetFarmProfitLossAmt shows zero for losses
        if (isLoss && netFarmProfitLoss.compareTo(BigDecimal.ZERO) == 0) {
            farmVal.getWarnings().add(
                String.format("Schedule F for '%s': NetFarmProfitLossAmt shows $0 but calculated loss is $%s", 
                    businessName, calculatedNet.toPlainString())
            );
        }

        // Validation 2: If loss and not materially participated, check Form 8582
        if (isLoss && !materiallyParticipated) {
            if (form8582 == null || form8582.isMissingNode()) {
                farmVal.setPassed(false);
                farmVal.getErrors().add(
                    String.format("Schedule F for '%s' has passive loss but Form 8582 is missing", businessName)
                );
            } else {
                // Validate against Form 8582
                validateAgainstForm8582(farmVal, businessName, calculatedNet.negate(), form8582);
            }
        }

        // Validation 3: If materially participated and loss, should NOT be on Form 8582
        if (isLoss && materiallyParticipated && form8582 != null) {
            if (isBusinessOnForm8582(businessName, form8582)) {
                farmVal.setPassed(false);
                farmVal.getErrors().add(
                    String.format("Schedule F for '%s': Materially participated but appears on Form 8582 (passive loss form)", 
                        businessName)
                );
            }
        }

        return farmVal;
    }

    private void validateAgainstForm8582(FarmValidation farmVal, String businessName, 
                                                  BigDecimal expectedLoss, JsonNode form8582) {
        // Find matching activity on Form 8582
        BigDecimal form8582Loss = findLossOnForm8582(businessName, form8582);

        if (form8582Loss == null) {
            farmVal.setPassed(false);
            farmVal.getErrors().add(
                String.format("Schedule F for '%s': Passive loss of $%s not found on Form 8582", 
                    businessName, expectedLoss.toPlainString())
            );
        } else {
            farmVal.setForm8582Loss(form8582Loss);
            
            // Compare amounts
            if (expectedLoss.compareTo(form8582Loss) != 0) {
                farmVal.setPassed(false);
                farmVal.getErrors().add(
                    String.format("Schedule F for '%s': Calculated loss $%s does not match Form 8582 loss $%s", 
                        businessName, expectedLoss.toPlainString(), form8582Loss.toPlainString())
                );
            } else {
                farmVal.getWarnings().add(
                    String.format("Schedule F for '%s': Loss amount $%s correctly matches Form 8582", 
                        businessName, expectedLoss.toPlainString())
                );
            }
        }
    }

    private List<JsonNode> extractScheduleFForms(JsonNode payload) {
        List<JsonNode> scheduleFForms = new ArrayList<>();
        JsonNode forms = payload.at("/body/forms");
        
        if (forms != null && forms.isArray()) {
            for (JsonNode form : forms) {
                String formNum = form.at("/formNum").asText("");
                if ("IRS1040ScheduleF".equals(formNum)) {
                    scheduleFForms.add(form);
                }
            }
        }
        
        return scheduleFForms;
    }

    private JsonNode extractForm8582(JsonNode payload) {
        JsonNode forms = payload.at("/body/forms");
        
        if (forms != null && forms.isArray()) {
            for (JsonNode form : forms) {
                String formNum = form.at("/formNum").asText("");
                if ("IRS8582".equals(formNum)) {
                    return form;
                }
            }
        }
        
        return null;
    }

    private String extractBusinessName(JsonNode scheduleF) {
        JsonNode nameNode = findLineItem(scheduleF, "/IRS1040ScheduleF/FarmProprietorName/BusinessNameLine1Txt");
        if (nameNode != null) {
            return nameNode.asText();
        }
        
        // Fallback to principal product
        JsonNode productNode = findLineItem(scheduleF, "/IRS1040ScheduleF/PrincipalProductDesc");
        return productNode != null ? productNode.asText() : "UKNOWN";
    }

    private BigDecimal extractAmount(JsonNode scheduleF, String lineName) {
        JsonNode amountNode = findLineItem(scheduleF, lineName);
        if (amountNode != null && !amountNode.isMissingNode()) {
            try {
                return new BigDecimal(amountNode.asText());
            } catch (NumberFormatException e) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    private String extractTextValue(JsonNode scheduleF, String lineName) {
        JsonNode textNode = findLineItem(scheduleF, lineName);
        return textNode != null ? textNode.asText() : "";
    }

    private boolean extractBooleanValue(JsonNode scheduleF, String lineName) {
        JsonNode boolNode = findLineItem(scheduleF, lineName);
        if (boolNode != null) {
            String value = boolNode.asText().toLowerCase();
            return "true".equals(value) || "x".equals(value);
        }
        return false;
    }

    private JsonNode findLineItem(JsonNode form, String lineName) {
        JsonNode lineItems = form.at("/lineItems");
        
        if (lineItems != null && lineItems.isArray()) {
            for (JsonNode lineItem : lineItems) {
                JsonNode result = searchLineItem(lineItem, lineName);
                if (result != null) {
                    return result;
                }
            }
        }
        
        return null;
    }

    private JsonNode searchLineItem(JsonNode lineItem, String lineName) {
        JsonNode lineNameNode = lineItem.get("lineNameTxt");
        
        if (lineNameNode != null && lineName.equals(lineNameNode.asText())) {
            return lineItem.get("perReturnValueTxt");
        }
        
        // Recursively search nested lineItems
        JsonNode nestedLineItems = lineItem.get("lineItems");
        if (nestedLineItems != null && nestedLineItems.isArray()) {
            for (JsonNode nested : nestedLineItems) {
                JsonNode result = searchLineItem(nested, lineName);
                if (result != null) {
                    return result;
                }
            }
        }
        
        return null;
    }

    private BigDecimal findLossOnForm8582(String businessName, JsonNode form8582) {
        // Search in WrkshtPassiveGrp
        JsonNode worksheets = findLineItem(form8582, "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp");
        
        if (worksheets != null && worksheets.isArray()) {
            for (JsonNode worksheet : worksheets) {
                JsonNode activityNameNode = worksheet.get("NonParticipateActivityNm");
                if (activityNameNode != null) {
                    JsonNode activityNameValue = activityNameNode.get("perReturnValueTxt");
                    if (activityNameValue != null && businessName.equalsIgnoreCase(activityNameValue.asText())) {
                        JsonNode lossNode = worksheet.get("CurrentYearNetLossAmt");
                        if (lossNode != null) {
                            JsonNode lossValue = lossNode.get("perReturnValueTxt");
                            if (lossValue != null) {
                                try {
                                    return new BigDecimal(lossValue.asText());
                                } catch (NumberFormatException e) {
                                    return null;
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Also search in WrkshtLossGrp
        JsonNode lossWorksheets = findLineItem(form8582, "/IRS8582/ParentWrkshtLossGrp/WrkshtLossGrp");
        
        if (lossWorksheets != null && lossWorksheets.isArray()) {
            for (JsonNode worksheet : lossWorksheets) {
                JsonNode activityNameNode = worksheet.get("UnallowedLossActivityNm");
                if (activityNameNode != null) {
                    JsonNode activityNameValue = activityNameNode.get("perReturnValueTxt");
                    if (activityNameValue != null && businessName.equalsIgnoreCase(activityNameValue.asText())) {
                        JsonNode lossNode = worksheet.get("F8582WrkshtLossesAmt");
                        if (lossNode != null) {
                            JsonNode lossValue = lossNode.get("perReturnValueTxt");
                            if (lossValue != null) {
                                try {
                                    return new BigDecimal(lossValue.asText());
                                } catch (NumberFormatException e) {
                                    return null;
                                }
                            }
                        }
                    }
                }
            }
        }
        
        return null;
    }

    private boolean isBusinessOnForm8582(String businessName, JsonNode form8582) {
        return findLossOnForm8582(businessName, form8582) != null;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationResult {
        private boolean passed;
        private List<String> errors;
        private List<String> warnings;
        private List<FarmValidation> farmValidations;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FarmValidation {
        private String businessName;
        private String ein;
        private boolean materiallyParticipated;
        private BigDecimal grossIncome;
        private BigDecimal totalExpenses;
        private BigDecimal netFarmProfitLoss;
        private BigDecimal calculatedNet;
        private BigDecimal loss;
        private BigDecimal form8582Loss;
        private boolean passed;
        private List<String> errors;
        private List<String> warnings;
    }
}