package com.tax.dvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.*;

/**
 * Validator for Schedule F to Form 8582 matching
 *
 * Business Rules:
 * 1. Match business names between Schedule F and Form 8582
 * 2. Match total business income/loss amounts
 * 3. When loss exists: NetFarmProfitLossAmt shows $0, but correct amount on 8582
 * 4. Calculate loss: Line 33 (TotalExpensesAmt) - Line 9 (GrossIncomeAmt) = Loss
 * 5. Only passive activities (non-material participation) appear on 8582
 * 6. Profitable farms do not appear on 8582
 *
 * IRS Rules:
 * - Form 8582 is used to figure the amount of any passive activity loss (PAL)
 * - Passive activities are trade or business activities in which you did not materially participate
 * - Losses from passive activities are generally limited and can only offset passive income
 * - Schedule F Line 34 (Net Profit/Loss) shows $0 when loss is passive and disallowed
 */
@Component
public class ScheduleF8582Validator {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleF8582Validator.class);

    // XPath constants for Schedule F
    private static final String SCHEDULE_F_PATH = "/body/forms";
    private static final String SCHEDULE_F_FORM_NUM = "IRS1040ScheduleF";
    private static final String PRINCIPAL_PRODUCT_DESC = "PrincipalProductDesc";
    private static final String GROSS_INCOME_AMT = "GrossIncomeAmt";
    private static final String TOTAL_EXPENSES_AMT = "TotalExpensesAmt";
    private static final String NET_FARM_PROFIT_LOSS_AMT = "NetFarmProfitLossAmt";
    private static final String MATERIAL_PARTICIPATED_IND = "MateriallyParticipatedInd";
    private static final String EIN = "EIN";

    // XPath constants for Form 8582
    private static final String FORM_8582_FORM_NUM = "IQS8582";
    private static final String NON_PARTICIPATE_ACTIVITY_NAME = "NonParticipateActivityNm";
    private static final String CURRENT_YEAR_NET_LOSS_AMT = "CurrentYearNetLossAmt";
    private static final String WRKSHT_PASSIVE_GRP = "WrkshtPassiveGrp";
    private static final String PARENT_WRKSHT_PASSIVE_GRP = "ParentWrkshtPassiveGrp";

    /**
     * Validates the entire tax return payload
     * 
     * @param payload The full IRS JSON tax return payload
     * @return ValidationResult containing pass/fail status and detailed messages
     */
    public ValidationResult validate(JsonNode payload) {
        logger.info("Starting Schedule F to Form 8582 validation");

        ValidationResult result = new ValidationResult();

        try {
            // Extract all Schedule F forms
            List<ScheduleFData> scheduleFForms = extractScheduleFData(payload);
            logger.info("Found {} Schedule F forms", scheduleFForms.size());

            // Extract Form 8582 data
            Map<String, BigDecimal> form8582Losses = extractForm8582Data(payload);
            logger.info("Found {} entries on Form 8582", form8582Losses.size());

            // Validate each Schedule F
            for (ScheduleFData scheduleF : scheduleFForms) {
                validateScheduleF(scheduleF, form8582Losses, result);
            }

            // Validate that all 8582 entries have corresponding Schedule F
            validateForm8582Entries(scheduleFForms, form8582Losses, result);

            // Set overall status
            if (result.getErrors().isEmpty()) {
                result.setPassed(true);
                logger.info("Validation PASSED");
            } else {
                result.setPassed(false);
                logger.error("Validation FAILED with {} errors", result.getErrors().size());
            }

        } catch (Exception e) {
            logger.error("Validation exception", e);
            result.setPassed(false);
            result.addError("Validation exception: " + e.getMessage());
        }

        return result;
    }

    /**
     * Extracts all Schedule F data from the payload
     */
    private List<ScheduleFData> extractScheduleFData(JsonNode payload) {
        List<ScheduleFData> scheduleFList = new ArrayList<>();

        JsonNode formsNode = payload.at(SCHEDULE_F_PATH);
        if (formsNode.isMissingNode() || !formsNode.isArray()) {
            return scheduleFList;
        }

        for (JsonNode formNode : formsNode) {
            String formNum = getTextValue(formNode, "formNum");
            if (SCHEDULE_F_FORM_NUM.equals(formNum)) {
                ScheduleFData data = new ScheduleFData();
                data.sequenceNum = getTextValue(formNode, "sequenceNum");

                // Extract data from lineItems
                JsonNode lineItemsNode = formNode.get("lineItems");
                if (lineItemsNode != null && lineItemsNode.isArray()) {
                    for (JsonNode lineItem : lineItemsNode) {
                        String lineName = getTextValue(lineItem, "lineNameTxt");
                        String value = getTextValue(lineItem, "perReturnValueTxt");

                        if (lineName.contains(PRINCIPAL_PRODUCT_DESC)) {
                            data.businessName = value;
                        } else if (lineName.contains(EIN)) {
                            data.ein = value;
                        } else if (lineName.contains(MATERIAL_PARTICIPATED_IND)) {
                            data.materiallyParticipated = "true".equalsIgnoreCase(value);
                        }
                    }

                    // Extract from FarmIncomeCashMethodGrp
                    JsonNode incomeGrp = findNodeByPath(lineItemsNode, "FarmIncomeCashMethodGrp");
                    if (incomeGrp != null) {
                        JsonNode grossIncomeNode = findNodeByPath(incomeGrp, GROSS_INCOME_AMT);
                        if (grossIncomeNode != null) {
                            data.grossIncome = parseBigDecimal(getTextValue(grossIncomeNode, "perReturnValueTxt"));
                        }
                    }

                    // Extract from FarmExpensesGrp
                    JsonNode expensesGrp = findNodeByPath(lineItemsNode, "FarmExpensesGrp");
                    if (expensesGrp != null) {
                        JsonNode totalExpensesNode = findNodeByPath(expensesGrp, TOTAL_EXPENSES_AMT);
                        if (totalExpensesNode != null) {
                            data.totalExpenses = parseBigDecimal(getTextValue(totalExpensesNode, "perReturnValueTxt"));
                        }

                        JsonNode netProfitLossNode = findNodeByPath(expensesGrp, NET_FARM_PROFIT_LOSS_AMT);
                        if (netProfitLossNode != null) {
                            data.netProfitLoss = parseBigDecimal(getTextValue(netProfitLossNode, "perReturnValueTxt"));
                        }
                    }
                }

                // Calculate actual profit/loss: GrossIncome - TotalExpenses
                if (data.grossIncome != null && data.totalExpenses != null) {
                    data.calculatedProfitLoss = data.grossIncome.subtract(data.totalExpenses);
                }

                scheduleFList.add(data);
                logger.debug("Extracted Schedule F: {}", data);
            }
        }

        return scheduleFList;
    }

    /**
     * Extracts Form 8582 data from the payload
     */
    private Map<String, BigDecimal> extractForm8582Data(JsonNode payload) {
        Map<String, BigDecimal> lossesMap = new HashMap<>();

        JsonNode formsNode = payload.at(SCHEDUEE_F_PATH);
        if (formsNode.isMissingNode() || !formsNode.isArray()) {
            return lossesMap;
        }

        for (JsonNode formNode : formsNode) {
            String formNum = getTextValue(formNode, "formNum");
            if (FORM_8582_FORM_NUM.equals(formNum)) {
                JsonNode lineItemsNode = formNode.get("lineItems");
                if (lineItemsNode != null && lineItemsNode.isArray()) {
                    // Find ParentWrkshtPassiveGrp
                    JsonNode parentWrkshtNode = findNodeByPath(lineItemsNode, PARENT_WRKSHT_PASSIVE_GRP);
                    if (parentWrkshtNode != null) {
                        JsonNode parentLineItems = parentWrkshtNode.get("lineItems");
                        if (parentLineItems != null && parentLineItems.isArray()) {
                            for (JsonNode item : parentLineItems) {
                                String lineName = getTextValue(item, "lineNameTxt");
                                if (lineName.contains(WRKSHT_PASSIVE_GRP)) {
                                    // Extract activity name and loss amount
                                    JsonNode wrkshtLineItems = item.get("lineItems");
                                    if (wrkshtLineItems != null && wrkshtLineItems.isArray()) {
                                        String activityName = null;
                                        BigDecimal lossAmount = null;

                                        for (JsonNode wrkshtItem : wrkshtLineItems) {
                                            String wrkshtLineName = getTextValue(wrkshtItem, "lineNameTxt");
                                            if (wrkshtLineName.contains(NON_PARTICIPATE_ACTIVITY_NAME)) {
                                                activityName = getTextValue(wrkshtItem, "perReturnValueTxt");
                                            } else if (wrkshtLineName.contains(CURRENT_YEAR_NET_LOSS_AMT)) {
                                                lossAmount = parseBigDecimal(getTextValue(wrkshtItem, "perReturnValueTxt"));
                                            }
                                        }

                                        if (activityName != null && lossAmount != null) {
                                            lossesMap.put(activityName.toUpperCase().trim(), lossAmount);
                                            logger.debug("Extracted Form 8582 loss: {} = {}", activityName, lossAmount);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }

        return lossesMap;
    }

    /**
     * Validates a single Schedule F against Form 8582
     */
    private void validateScheduleF(ScheduleFData scheduleF, Map<String, BigDecimal> form8582Losses, ValidationResult result) {
        String businessName = scheduleF.businessName != null ? scheduleF.businessName.toUpperCase().trim() : "UNKNOWN";
        logger.info("Validating Schedule F: {}", businessName);

        // Rule 1: Validate calculated profit/loss matches net profit/loss (if not passive)
        if (scheduleF.calculatedProfitLoss != null && scheduleF.netProfitLoss != null) {
            if (scheduleF.materiallyParticipated) {
                // Material participation - net profit/loss should match calculated amount
                if (scheduleF.calculatedProfitLoss.compareTo(scheduleF.netProfitLoss) != 0) {
                    result.addError(String.format(
                            "[Schedule F - %s] Net Profit/Loss mismatch: Calculated %s != Reported %s",
                            businessName, scheduleF.calculatedProfitLoss, scheduleF.netProfitLoss));
                } else {
                    result.addInfo("[Schedule F - " + businessName + "] Net Profit/Loss matches: " + scheduleF.netProfitLoss);
                }
            }
        }

        // Rule 2: If loss exists and not materially participated, validate passive loss handling
        if (scheduleF.calculatedProfitLoss != null && scheduleF.calculatedProfitLoss.compareTo(BigDecimal.ZERO) < 0) {
            if (!scheduleF.materiallyParticipated) {
                // Passive loss - should be $0 on Schedule F Line 34
                if (scheduleF.netProfitLoss != null && scheduleF.netProfitLoss.compareTo(BigDecimal.ZERO) != 0) {
                    result.addError(String.format(
                            "[Schedule F - %s] Passive loss should show $0 on Line 34, but shows %s",
                            businessName, scheduleF.netProfitLoss));
                } else {
                    result.addInfo("[Schedule F - " + businessName + "] Passive loss correctly shows $0 on Line 34");
                }

                // Rule 3: Validate loss matches Form 8582
                BigDecimal expectedLoss = scheduleF.calculatedProfitLoss.abs();
                BigDecimal form8582Loss = form8582Losses.get(businessName);

                if (form8582Loss == null) {
                    result.addError(String.format(
                            "[Schedule F - %s] Passive loss of %s not found on Form 8582",
                            businessName, expectedLoss));
                } else if (expectedLoss.compareTo(form8582Loss) != 0) {
                    result.addError(String.format(
                            "[Schedule F - %s] Loss mismatch: Calculated %s != Form 8582 %s",
                            businessName, expectedLoss, form8582Loss));
                } else {
                    result.addInfo(String.format(
                            "[Schedule F - %s] Loss matches Form 8582: %s",
                            businessName, expectedLoss));
                }
            } else {
                // Material participation with loss - should NOT be on 8582
                if (form8582Losses.containsKey(businessName)) {
                    result.addError(String.format(
                            "[Schedule F - %s] Materially participated loss should NOT be on Form 8582",
                            businessName));
                } else {
                    result.addInfo("[Schedule F - " + businessName + "] Materially participated loss correctly NOT on Form 8582");
                }
            }
        } else if (scheduleF.calculatedProfitLoss != null && scheduleF.calculatedProfitLoss.compareTo(BigDecimal.ZERO) >= 0) {
            // Rule 4: Profitable farms should NOT be on Form 8582
            if (form8582Losses.containsKey(businessName)) {
                result.addError(String.format(
                        "[Schedule F - %s] Profitable farm should NOT be on Form 8582",
                        businessName));
            } else {
                result.addInfo("[Schedule F - " + businessName + "] Profitable farm correctly NOT on Form 8582");
            }
        }
    }

    /**
     * Validates that all Form 8582 entries have corresponding Schedule F
     */
    private void validateForm8582Entries(List<ScheduleFData> scheduleFForms, Map<String, BigDecimal> form8582Losses, ValidationResult result) {
        Set<String> scheduleFNames = new HashSet<>();
        for (ScheduleFData scheduleF : scheduleFForms) {
            if (scheduleF.businessName != null) {
                scheduleFNames.add(scheduleF.businessName.toUpperCase().trim());
            }
        }

        for (Map.Entry<String, BigDecimal> entry : form8582Losses.entrySet()) {
            if (!scheduleFNames.contains(entry.getKey())) {
                result.addError(String.format(
                        "[Form 8582] Activity '%s' with loss %s not found on any Schedule F",
                        entry.getKey(), entry.getValue()));
            }
        }
    }

    // Helper methods

    private String getTextValue(JsonNode node, String fieldName) {
        JsonNode fieldNode = node.get(fieldName);
        if (fieldNode != null && !fieldNode.isNull()) {
            return fieldNode.asText();
        }
        return null;
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(value.replaceAll("[\\d.-]", ""));
        } catch (NumberFormatException e) {
            logger.warn("Failed to parse BigDecimal: {}", value);
            return null;
        }
    }

    private JsonNode findNodeByPath(JsonNode parent, String path) {
        if (parent == null || !parent.isArray()) {
            return null;
        }

        for (JsonNode node : parent) {
            String lineName = getTextValue(node, "lineNameTxt");
            if (lineName != null && lineName.contains(path)) {
                return node;
            }
        }
        return null;
    }

    // Data classes

    public static class ScheduleFData {
        public String sequenceNum;
        public String businessName;
        public String ein;
        public BigDecimal grossIncome;
        public BigDecimal totalExpenses;
        public BigDecimal netProfitLoss;
        public BigDecimal calculatedProfitLoss;
        public boolean materiallyParticipated = false;

        @Override
        public String toString() {
            return "ScheduleFData{" +
                    "businessName='" + businessName + '\'' +
                    ", ein='" + ein + '\'' +
                    ", grossIncome=" + grossIncome +
                    ", totalExpenses=" + totalExpenses +
                    ", netProfitLoss=" + netProfitLoss +
                    ", calculatedProfitLoss=" + calculatedProfitLoss +
                    ", materiallyParticipated=" + materiallyParticipated +
                    '}';
        }
    }

    public static class ValidationResult {
        private boolean passed;
        private final List<String> errors = new ArrayList<>();
        private final List<String> info = new ArrayList<>();

        public boolean isPassed() {
            return passed;
        }

        public void setPassed(boolean passed) {
            this.passed = passed;
        }

        public List<String> getErrors() {
            return errors;
        }

        public void addError(String error) {
            this.errors.add(error);
        }

        public List<String> getInfo() {
            return info;
        }

        public void addInfo(String infoMsg) {
            this.info.add(infoMsg);
        }

        @Override
        public String toString() {
            return "ValidationResult{" +
                    "passed=" + passed +
                    ", errors=" + errors +
                    ", info=" + info +
                    '}';
        }
    }
}