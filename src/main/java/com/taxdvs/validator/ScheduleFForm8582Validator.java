package com.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Component
@Slf4j
public class ScheduleFForm8582Validator {

    private static final String SCHEDULE_F_PATH = "/IRS1040ScheduleF";
    private static final String FORM_8582_PATH = "/IRS8582";
    private static final String BUSINESS_NAME_PATH = "FarmProprietorName/BusinessNameLine1Txt";
    private static final String GROSS_INCOME_PATH = "FarSIncomeCashMethodGrp/GrossIncomeAmt";
    private static final String TOTAL_EXPENSES_PATH = "FarSEq÷ensesGrp/TotalExpensesAmt";
    private static final String NET_PROFIT_LOSS_PATH = "FarmExpensesGrp/NetFarmProfitLossAmt";
    private static final String MATERIALLY_PARTICIPATED_PATH = "MateriallyParticipatedInd";
    private static final String EIN_PATH = "EIN";

    public ValidationResult validate(JsonNode payload) {
        log.info("Starting Schedule F to Form 8582 validation");
        
        ValidationResult result = ValidationResult.builder()
                .passed(true)
                .errors(new ArrayList<>())
                .warnings(new ArrayList<>())
                .businessMatches(new ArrayList<>())
                .build();

        try {
            // Extract Schedule F data
            JsonNode body = payload.get("body");
            if (body == null || !body.has("forms")) {
                result.getErrors().add("Missing body/forms in payload");
                result.setPassed(false);
                return result;
            }

            List<ScheduleFData> scheduleFList = extractScheduleFData(body.get("forms"));
            List<Form8582Data> form8582List = extractForm8582Data(body.get("forms"));

            log.info("Found {} Schedule F entries and {} Form 8582 entries", 
                    scheduleFList.size(), form8582List.size());

            // Validate each Schedule F
            for (ScheduleFData schF : scheduleFList) {
                validateScheduleFEntry(schF, form8582List, result);
            }

            // Validate Form 8582 entries have corresponding Schedule F
            for (Form8582Data f8582 : form8582List) {
                validateForm8582Entry(f8582, scheduleFList, result);
            }

        } catch (Exception e) {
            log.error("Validation error", e);
            result.getErrors().add("Validation exception: " + e.getMessage());
            result.setPassed(false);
        }

        log.info("Validation completed. Passed: {}, Errors: {}, Warnings: {}", 
                result.isPassed(), result.getErrors().size(), result.getWarnings().size());
        return result;
    }

    private void validateScheduleFEntry(ScheduleFData schF, List<Form8582Data> form8582List, ValidationResult result) {
        String businessName = schF.getBusinessName();
        log.debug("Validating Schedule F: {}", businessName);

        // Rule 1: Calculate net profit/loss (Line 9 - Line 33)
        BigDecimal calculatedNet = schF.getGrossIncome().subtract(schF.getTotalExpenses());
        log.debug("Calculated net: {} ({} - {})", calculatedNet, schF.getGrossIncome(), schF.getTotalExpenses());

        // Rule 2: If calculated net is a loss (negative), validate against Form 8582
        if (calculatedNet.compareTo(BigDecimal.ZERO) < 0) {
            BigDecimal lossAmount = calculatedNet.abs();
            log.debug("Loss detected: {} for {}", lossAmount, businessName);

            // Rule 3: Verify NetFarmProfitLossAmt should be zero for passive losses
            if (!schF.isMateriallyParticipated() && schF.getNetProfitLoss().compareTo(BigDecimal.ZERO) != 0) {
                result.getWarnings().add(String.format(
                    "Schedule F '%s': NetFarmProfitLossAmt should be $0 for passive loss activities, found: %s",
                    businessName, schF.getNetProfitLoss()));
            }

            // Rule 4: Match with Form 8582
            Optional<Form8582Data> matchingForm8582 = form8582List.stream()
                    .filter(f8582 -> f8582.getActivityName().equalsIgnoreCase(businessName))
                    .findFirst();

            if (matchingForm8582.isPresent()) {
                Form8582Data f8582 = matchingForm8582.get();
                BigDecimal form8582Loss = f8582.getLossAmount();

                // Rule 5: Validate loss amount matches
                if (lossAmount.compareTo(form8582Loss) != 0) {
                    result.getErrors().add(String.format(
                        "Loss amount mismatch for '%s': Schedule F calculated %,d but Form 8582 shows %,d",
                        businessName, lossAmount, form8582Loss));
                    result.setPassed(false);
                } else {
                    result.getBusinessMatches().add(BusinessMatch.builder()
                            .businessName(businessName)
                            .scheduleFLoss(calculatedNet)
                            .form8582Loss(form8582Loss.negate())
                            .matched(true)
                            .build());
                    log.info("Match found: {} - Schedule F: {}, Form 8582: {}",
                            businessName, calculatedNet, form8582Loss);
                }
            } else {
                // Rule 6: Passive loss must be on Form 8582
                if (!schF.isMateriallyParticipated()) {
                    result.getErrors().add(String.format(
                        "Passive loss for '%s' (%,d) not found on Form 8582",
                        businessName, lossAmount));
                    result.setPassed(false);
                }
            }
        } else if (calculatedNet.compareTo(BigDecimal.ZERO) > 0) {
            // Rule 7: Profitable activities should not be on Form 8582 as losses
            boolean foundOn8582 = form8582List.stream()
                    .anyMatch(f8582 -> f8582.getActivityName().equalsIgnoreCase(businessName));
            if (foundOn8582) {
                result.getWarnings().add(String.format(
                    "Profitable activity '%s' (%,d) should not appear on Form 8582",
                    businessName, calculatedNet));
            }
        }

        // Rule 8: Validate EIN is present
        if (schF.getEin() == null || schF.getEin().isBlank()) {
            result.getWarnings().add(String.format(
                "Schedule F '%s': Missing EIN", businessName));
        }
    }

    private void validateForm8582Entry(Form8582Data f8582, List<ScheduleFData> scheduleFList, ValidationResult result) {
        String activityName = f8582.getActivityName();
        log.debug("Validating Form 8582 entry: {}", activityName);

        // Rule 9: Every Form 8582 entry should have a corresponding Schedule F
        boolean foundMatch = scheduleFList.stream()
                .anyMatch(schF -> schF.getBusinessName().equalsIgnoreCase(activityName));

        if (!foundMatch) {
            result.getErrors().add(String.format(
                "Form 8582 activity '%s' has no corresponding Schedule F",
                activityName));
            result.setPassed(false);
        }
    }

    private List<ScheduleFData> extractScheduleFData(JsonNode formsNode) {
        List<ScheduleFData> result = new ArrayList<>();
        if (!formsNode.isArray()) return result;

        for (JsonNode form : formsNode) {
            if ("IRS1040ScheduleF".equals(getTextValue(form, "formNum"))) {
                ScheduleFData data = ScheduleFData.builder()
                        .businessName(extractBusinessName(form))
                        .ein(extractValueFromLineItems(form, EIN_PATH))
                        .grossIncome(extractAmountFromLineItems(form, GROSS_INCOME_PATH))
                        .totalExpenses(extractAmountFromLineItems(form, TOTAL_EXPENSES_PATH))
                        .netProfitLoss(extractAmountFromLineItems(form, NET_PROFIT_LOSS_PATH))
                        .materiallyParticipated(extractBooleanFromLineItems(form, MATERIALLY_PARTICIPATED_PATH))
                        .build();
                result.add(data);
            }
        }
        return result;
    }

    private List<Form8582Data> extractForm8582Data(JsonNode formsNode) {
        List<Form8582Data> result = new ArrayList<>();
        if (!formsNode.isArray()) return result;

        for (JsonNode form : formsNode) {
            if ("IRS8582".equals(getTextValue(form, "formNum"))) {
                JsonNode lineItems = form.get("lineItems");
                if (lineItems != null && lineItems.isArray()) {
                    for (JsonNode lineItem : lineItems) {
                        String lineName = getTextValue(lineItem, "lineNameTxt");
                        if ("/IRS8582/ParentWrkshtLossGrp".equals(lineName)) {
                            JsonNode wrkShtLossGrp = lineItem.get("lineItems");
                            if (wrkShtLossGrp != null && wrkShtLossGrp.isArray()) {
                                for (JsonNode wrkshtItem : wrkShtLossGrp) {
                                    String wrkshtLineName = getTextValue(wrkhstItem, "lineNameTxt");
                                    if ("/IRS8582/ParentWrkshtLossGrp/WrkshtLossGrp".equals(wrkshtLineName)) {
                                        Form8582Data data = extract8582WorksheetData(wrkshtItem);
                                        if (data != null) {
                                            result.add(data);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private Form8582Data extract8582WorksheetData(JsonNode wrkshtGrp) {
        String activityName = null;
        BigDecimal lossAmount = BigDecimal.ZERO;

        JsonNode lineItems = wrkshtGrp.get("lineItems");
        if (lineItems != null && lineItems.isArray()) {
            for (JsonNode item : lineItems) {
                String lineName = getTextValue(item, "lineNameTxt");
                if ("/IRS8582/ParentWrkshtLossGrp/WrkshtLossGrp/UnallowedLossActivityNm".equals(lineName)) {
                    activityName = getTextValue(item, "perReturnValueTxt");
                } else if ("/IRS8582/ParentWrkshtLossGrp/WrkshtLossGrp/F8582WrkshtLossesAmt".equals(lineName)) {
                    lossAmount = parseAmount(getTextValue(item, "perReturnValueTxt"));
                }
            }
        }

        if (activityName != null) {
            return Form8582Data.builder()
                    .activityName(activityName)
                    .lossAmount(lossAmount)
                    .build();
        }
        return null;
    }

    private String extractBusinessName(JsonNode form) {
        JsonNode lineItems = form.get("lineItems");
        if (lineItems != null && lineItems.isArray()) {
            for (JsonNode item : lineItems) {
                String lineName = getTextValue(item, "lineNameTxt");
                if (lineName != null && lineName.contains(BUSINESS_NAME_PATH)) {
                    JsonNode nestedItems = item.get("lineItems");
                    if (nestedItems != null && nestedItems.isArray() && nestedItems.size() > 0) {
                        return getTextValue(nestedItems.get(0), "perReturnValueTxt");
                    }
                }
            }
        }
        return "UNKNOWN";
    }

    private String extractValueFromLineItems(JsonNode form, String path) {
        JsonNode lineItems = form.get("lineItems");
        if (lineItems != null && lineItems.isArray()) {
            for (JsonNode item : lineItems) {
                String lineName = getTextValue(item, "lineNameTxt");
                if (lineName != null && lineName.endsWith(path)) {
                    return getTextValue(item, "perReturnValueTxt");
                }
            }
        }
        return null;
    }

    private BigDecimal extractAmountFromLineItems(JsonNode form, String path) {
        String value = extractValueFromLineItems(form, path);
        return parseAmount(value);
    }

    private boolean extractBooleanFromLineItems(JsonNode form, String path) {
        String value = extractValueFromLineItems(form, path);
        return "true".equalsIgnoreCase(value);
    }

    private BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.replaceAll("[^0-9.-]", ""));
        } catch (NumberFormatException e) {
            log.warn("Failed to parse amount: {}", value);
            return BigDecimal.ZERO;
        }
    }

    private String getTextValue(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field != null && !field.isNull() ? field.asText() : null;
    }

    @Data
    @Builder
    public static class ScheduleFData {
        private String businessName;
        private String ein;
        private BigDecimal grossIncome;
        private BigDecimal totalExpenses;
        private BigDecimal netProfitLoss;
        private boolean materiallyParticipated;
    }

    @Data
    @Builder
    public static class Form8582Data {
        private String activityName;
        private BigDecimal lossAmount;
    }

    @Data
    @Builder
    public static class BusinessMatch {
        private String businessName;
        private BigDecimal scheduleFLoss;
        private BigDecimal form8582Loss;
        private boolean matched;
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class ValidationResult {
        private boolean passed;
        private List<String> errors;
        private List<String> warnings;
        private List<BusinessMatch> businessMatches;
    }
}
