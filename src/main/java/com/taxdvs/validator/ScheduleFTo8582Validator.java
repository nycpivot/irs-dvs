package com.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgusConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Validator for matching Schedule F data with Form 8582 Passive Activity Loss Limitations.
 * 
 * Business Rules:
 * 1. Match business names between Schedule F and Form 8582
 * 2. When Schedule F shows a loss, NetFarmProfitLossAmt may show $0
 * 3. Calculate actual loss: Line 9 (GrossIncome) - Line 33 (TotalExpenses)
 * 4. Verify calculated loss matches Form 8582 CurrentYearNetLossAmt
 * 5. Only non-materially participated farms should appear on Form 8582
 * 6. Passive losses are subject to limitation under IRC Section 469
 */
@Service
public class ScheduleFTo8582Validator {

    private static final String SCHEDULE_F_PATH = "/IRS1040ScheduleF";
    private static final String FORM_8582_PATH = "/IRS8582";
    private static final String BUSINESS_NAME_PATH = "/FarmProprietorName/BusinessNameLine1Txt";
    private static final String GROSS_INCOME_PATH = "/FarmIncomeCashMethodGrp/GrossIncomeAmt";
    private static final String TOTAL_EXPENSES_PATH = "/FarmExpensesGrp/TotalExpensesAmt";
    private static final String NET_PROFIT_LOSS_PATH = "/FarmExpensesGrp/NetFarmProfitLossAmt";
    private static final String MATERIALLY_PARTICIPATED_PATH = "/MateriallyParticipatedInd";
    private static final String PASSIVE_ACTIVITY_PATH = "/ParentWrkshtPassiveGrp/WrkshtPassiveGrp";
    private static final String ACTIVITY_NAME_PATH = "/NonParticipateActivityNm";
    private static final String CURRENT_YEAR_LOSS_PATH = "/CurrentYearNetLossAmt";

    /**
     * Validates the entire tax return payload for Schedule F to Form 8582 matching.
     * 
     * @param payload The full tax return JSON payload
     * @return ValidationResult containing all validation findings
     */
    public ValidationResult validate(JsonNode payload) {
        ValidationResult result = new ValidationResult();
        result.setPassed(true);

        try {
            JsonNode body = payload.get("body");
            if (body == null || !body.has("forms")) {
                result.addError("Missing 'body.forms' in payload");
                result.setPassed(false);
                return result;
            }

            JsonNode forms = body.get("forms");
            List<ScheduleFData> scheduleFList = extractScheduleFData(forms);
            List<Form8582Data> form8582List = extractForm8582Data(forms);

            // Validate each Schedule F
            for (ScheduleFData scheduleF : scheduleFList) {
                validateScheduleF(scheduleF, form8582List, result);
            }

            // Validate Form 8582 entries have corresponding Schedule F
            for (Form8582Data form8582 : form8582List) {
                validateForm8582(form8582, scheduleFList, result);
            }

        } catch (Exception e) {
            result.addError("Validation exception: " + e.getMessage());
            result.setPassed(false);
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
                ScheduleFData data = new ScheduleFData();
                data.setSequenceNum(form.get("sequenceNum").asText());
                
                JsonNode lineItems = form.get("lineItems");
                if (lineItems != null) {
                    for (JsonNode lineItem : lineItems) {
                        String lineName = lineItem.get("lineNameTxt").asText();
                        
                        if (lineName.contains(BUSINESS_NAME_PATH)) {
                            data.setBusinessName(lineItem.get("perReturnValueTxt").asText());
                        } else if (lineName.contains(GROSS_INCOME_PATH)) {
                            data.setGrossIncome(new BigDecimal(lineItem.get("perReturnValueTxt").asText("0")));
                        } else if (lineName.contains(TOTAL_EXPENSES_PATH)) {
                            data.setTotalExpenses(new BigDecimal(lineItem.get("perReturnValueTxt").asText("0")));
                        } else if (lineName.contains(NET_PROFIT_LOSS_PATH)) {
                            data.setNetProfitLossReported(new BigDecimal(lineItem.get("perReturnValueTxt").asText("0")));
                        } else if (lineName.contains(MATERIALLY_PARTICIPATED_PATH)) {
                            data.setMateriallyParticipated("true".equalsIgnoreCase(lineItem.get("perReturnValueTxt").asText()));
                        }
                    }
                }
                
                // Calculate actual net profit/loss per IRS rules
                data.setNetProfitLossCalculated(
                    data.getGrossIncome().subtract(data.getTotalExpenses())
                );
                
                scheduleFList.add(data);
            }
        }
        
        return scheduleFList;
    }

    /**
     * Extracts all Form 8582 passive activity data.
     */
    private List<Form8582Data> extractForm8582Data(JsonNode forms) {
        List<Form8582Data> form8582List = new ArrayList<>();
        
        for (JsonNode form : forms) {
            if ("IRS8582".equals(form.get("formNum").asText())) {
                JsonNode lineItems = form.get("lineItems");
                if (lineItems != null) {
                    for (JsonNode lineItem : lineItems) {
                        String lineName = lineItem.get("lineNameTxt").asText();
                        
                        if (lineName.contains(PASSIVE_ACTIVITY_PATH)) {
                            JsonNode activities = lineItem.get("lineItems");
                            if (activities != null) {
                                for (JsonNode activity : activities) {
                                    Form8582Data data = new Form8582Data();
                                    
                                    JsonNode subItems = activity.get("lineItems");
                                    if (subItems != null) {
                                        for (JsonNode subItem : subItems) {
                                            String subLineName = subItem.get("lineNameTxt").asText();
                                            
                                            if (subLineName.contains(ACTIVITY_NAME_PATH)) {
                                                data.setActivityName(subItem.get("perReturnValueTxt").asText());
                                            } else if (subLineName.contains(CURRENT_YEAR_LOSS_PATH)) {
                                                data.setCurrentYearLoss(new BigDecimal(subItem.get("perReturnValueTxt").asText("0")));
                                            }
                                        }
                                    }
                                    
                                    if (data.getActivityName() != null) {
                                        form8582List.add(data);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        return form8582List;
    }

    /**
     * Validates a single Schedule F against Form 8582 data.
     */
    private void validateScheduleF(ScheduleFData scheduleF, List<Form8582Data> form8582List, 
                                       ValidationResult result) {
        String businessName = scheduleF.getBusinessName();
        
        // Rule 1: Check if calculated loss matches reported loss
        BigDecimal calculatedLoss = scheduleF.getNetProfitLossCalculated();
        BigDecimal reportedLoss = scheduleF.getNetProfitLossReported();
        
        // Rule 2: If calculated loss is negative and reported is $0, this is the known issue
        if (calculatedLoss.compareTo(BigDecimal.ZERO) < 0 && reportedLoss.compareTo(BigDecimal.ZERO) == 0) {
            result.addWarning(String.format(
                "Schedule F '%s': NetProfitLossAmt shows $0 but calculated loss is %s. " +
                "This is expected behavior for passive losses.",
                businessName, calculatedLoss.toPlainString()
            ));
            
            // Rule 3: Check if non-materially participated farm is on Form 8582
            if (!scheduleF.isMateriallyParticipated()) {
                Optional<Form8582Data> matching8582 = findMatchingForm8582(businessName, form8582List);
                
                if (matching8582.isPresent()) {
                    // Rule 4: Verify loss amount matches
                    BigDecimal form8582Loss = matching8582.get().getCurrentYearLoss();
                    BigDecimal expectedLoss = calculatedLoss.abs(); // 8582 stores as positive
                    
                    if (form8582Loss.compareTo(expectedLoss) != 0) {
                        result.addError(String.format(
                            "Schedule F '%s': Calculated loss (%s) does not match Form 8582 loss (%s)",
                            businessName, expectedLoss.toPlainString(), form8582Loss.toPlainString()
                        ));
                        result.setPassed(false);
                    } else {
                        result.addSuccess(String.format(
                            "Schedule F '%s': Calculated loss correctly matches Form 8582 (%s)",
                            businessName, expectedLoss.toPlainString()
                        ));
                    }
                } else {
                    result.addError(String.format(
                        "Schedule F '%s': Non-materially participated with loss but not found on Form 8582",
                        businessName
                    ));
                    result.setPassed(false);
                }
            } else {
                // Rule 5: Materially participated farms should NOT be on Form 8582
                Optional<Form8582Data> matching8582 = findMatchingForm8582(businessName, form8582List);
                if (matching8582.isPresent()) {
                    result.addError(String.format(
                        "Schedule F '%s': Materially participated farm should NOT appear on Form 8582",
                        businessName
                    ));
                    result.setPassed(false);
                }
            }
        } else if (calculatedLoss.compareTo(reportedLoss) != 0) {
            // Rule 6: For profitable farms, calculated should match reported
            result.addError(String.format(
                "Schedule F '%s': Calculated net profit/loss (%s) does not match reported (%s)",
                businessName, calculatedLoss.toPlainString(), reportedLoss.toPlainString()
            ));
            result.setPassed(false);
        }
    }

    /**
     * Validates Form 8582 entries have corresponding Schedule F.
     */
    private void validateForm8582(Form8582Data form8582, List<ScheduleFData> scheduleFList, 
                                     ValidationResult result) {
        String activityName = form8582.getActivityName();
        
        Optional<ScheduleFData> matchingScheduleF = findMatchingScheduleF(activityName, scheduleFList);
        
        if (!matchingScheduleF.isPresent()) {
            result.addError(String.format(
                "Form 8582 activity '%s': No matching Schedule F found",
                activityName
            ));
            result.setPassed(false);
        }
    }

    /**
     * Finds matching Form 8582 entry by business name.
     */
    private Optional<Form8582Data> findMatchingForm8582(String businessName, 
                                                              List<Form8582Data> form8582List) {
        return form8582List.stream()
            .filter(f -> businessName.equalsIgnoreCase(f.getActivityName()))
            .findFirst();
    }

    /**
     * Finds matching Schedule F entry by business name.
     */
    private Optional<ScheduleFData> findMatchingScheduleF(String activityName, 
                                                              List<ScheduleFData> scheduleFList) {
        return scheduleFList.stream()
            .filter(f -> activityName.equalsIgnoreCase(f.getBusinessName()))
            .findFirst();
    }

    /**
     * Data class for Schedule F information.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ScheduleFData {
        private String sequenceNum;
        private String businessName;
        private BigDecimal grossIncome = BigDecimal.ZERO;
        private BigDecimal totalExpenses = BigDecimal.ZERO;
        private BigDecimal netProfitLossReported = BigDecimal.ZERO;
        private BigDecimal netProfitLossCalculated = BigDecimal.ZERO;
        private boolean materiallyParticipated;
    }

    /**
     * Data class for Form 8582 information.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Form8582Data {
        private String activityName;
        private BigDecimal currentYearLoss = BigDecimal.ZERO;
    }

    /**
     * Validation result class.
     */
    @Data
    public static class ValidationResult {
        private boolean passed;
        private List<String> errors = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
        private List<String> successMessages = new ArrayList<>();
        
        public void addError(String error) {
            this.errors.add(error);
        }
        
        public void addWarning(String warning) {
            this.warnings.add(warning);
        }
        
        public void addSuccess(String message) {
            this.successMessages.add(message);
        }
    }
}