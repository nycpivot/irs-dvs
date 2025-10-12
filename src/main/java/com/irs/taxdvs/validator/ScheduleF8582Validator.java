package com.irs.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Validator for Schedule F to Form 8582 matching
 * 
 * Business Requirements:
 * 1. Match business name between Schedule F and Form 8582
 * 2. Match total business income/loss between forms
 * 3. When there is a loss, Schedule F Line 34 (NetFarmProfitLossAmt) shows zero but correct amount shows on Form 8582
 * 4. Loss calculation: Line 9 (GrossIncomeAmt) - Line 33 (TotalExpensesAmt) = Loss amount
 * 
 * IRS Rules:
 * - Passive activities (non-materially participated) must be reported on Form 8582
 * - Schedule F must report actual calculated loss on Line 34
 * - Form 8582 limits how much of that loss can be deducted
 * - Business names must match between forms for proper tracking
 */
@Component
public class ScheduleF8582Validator {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleF8582Validator.class);

    // XPath constants
    private static final String XPATH_SCHEDULE_F = "/IRS1040ScheduleF";
    private static final String XPATH_FORM_8582 = "/IRS8582";
    private static final String XPATH_BUSINESS_NAME = "/FarmProprietorName/BusinessNameLine1Txt";
    private static final String XPATH_PRINCIPAL_PRODUCT = "/PrincipalProductDesc";
    private static final String XPATH_GROSS_INCOME = "/FarmIncomeCashMethodGrp/GrossIncomeAmt";
    private static final String XPATH_TOTAL_EXPENSES = "/FarmExpensesGrp/TotalExpensesAmt";
    private static final String XPATH_NET_PROFIT_LOSS = "/FarmExpensesGrp/NetFarmProfitLossAmt";
    private static final String XPATH_MATERIALLY_PARTICIPATED = "/MateriallyParticipatedInd";
    private static final String XPATH_EIN = "/EIN";

    private static final String XPATH_8582_WRKSHT_PASSIVE = "/ParentWrkshtPassiveGrp/WrkshtPassiveGrp";
    private static final String XPATH_8582_ACTIVITY_NAME = "/NonParticipateActivityNm";
    private static final String XPATH_8582_CURRENT_YEAR_LOSS = "/CurrentYearNetLossAmt";
    private static final String XPATH_8582_OVERALL_LOSS = "/OverallLossAmt";

    /**
     * Validates the entire tax return payload for Schedule F to Form 8582 matching
     * 
     * @param payload The full tax return JSON payload
     * @return ValidationResult containing all validation findings
     */
    public ValidationResult validate(JsonNode payload) {
        logger.info("Starting Schedule F to Form 8582 validation");
        
        ValidationResult result = new ValidationResult();
        
        try {
            // Extract forms from payload
            JsonNode body = payload.get("body");
            if (body == null || !body.has("forms")) {
                result.addError("Payload missing 'body.forms' structure");
                return result;
            }
            
            JsonNode forms = body.get("forms");
            
            // Parse Schedule F forms
            List<ScheduleFData> scheduleFList = parseScheduleFForms(forms);
            logger.info("Found {} Schedule F forms", scheduleFList.size());
            
            // Parse Form 8582 data
            Form8582Data form8582Data = parseForm8582(forms);
            
            if (form8582Data == null) {
                logger.warn("No Form 8582 found in payload");
                result.addWarning("No Form 8582 found - unable to validate passive activity matching");
                return result;
            }
            
            logger.info("Found Form 8582 with {} passive activities", form8582Data.passiveActivities.size());
            
            // Validate each Schedule F against Form 8582
            for (ScheduleFData scheduleF : scheduleFList) {
                validateScheduleFAgainst8582(scheduleF, form8582Data, result);
            }
            
            // Check for orphaned 8582 entries (in 8582 but not in Schedule F)
            validateOrphaned8582Entries(scheduleFList, form8582Data, result);
            
        } catch (Exception e) {
            logger.error("Error during validation", e);
            result.addError("Validation failed: " + e.getMessage());
        }
        
        logger.info("Validation completed. Pass: {}, Errors: {}, Warnings: {}",
                result.isPass(), result.getErrors().size(), result.getWarnings().size());
        
        return result;
    }

    /**
     * Parses all Schedule F forms from the payload
     */
    private List<ScheduleFData> parseScheduleFForms(JsonNode forms) {
        List<ScheduleFData> scheduleFList = new ArrayList<>();
        
        for (JsonNode form : forms) {
            String formNum = getTextValue(form, "formNum");
            
            if ("IRS1040ScheduleF".equals(formNum)) {
                ScheduleFData scheduleF = new ScheduleFData();
                scheduleF.sequenceNum = getTextValue(form, "sequenceNum");
                
                // Extract data from lineItems
                JsonNode lineItems = form.get("lineItems");
                if (lineItems != null && lineItems.isArray()) {
                    for (JsonNode lineItem : lineItems) {
                        String lineName = getTextValue(lineItem, "lineNameTxt");
                        
                        if (lineName.contains(XPATH_BUSINESS_NAME)) {
                            scheduleF.businessName = getTextValue(lineItem, "perReturnValueTxt");
                        } else if (lineName.contains(XPATH_PRINCIPAL_PRODUCT)) {
                            scheduleF.principalProduct = getTextValue(lineItem, "perReturnValueTxt");
                        } else if (lineName.contains(XPATH_EIN)) {
                            scheduleF.ein = getTextValue(lineItem, "perReturnValueTxt");
                        } else if (lineName.contains(XPATH_MATERIALLRÅPARTCIPATED) {
                            scheduleF.materiallyParticipated = "true".equalsIgnoreCase(getTextValue(lineItem, "perReturnValueTxt"));
                        } else if (lineName.contains(XPATH_GROSS_INCOME)) {
                            scheduleF.grossIncome = parseBigDecimal(lineItem.get("perReturnValueTxt"));
                        } else if (lineName.contains(XPATH_TOTAL_EXPENSES)) {
                            scheduleF.totalExpenses = parseBigDecimal(lineItem.get("perReturnValueTxt"));
                        } else if (lineName.contains(XPATH_NET_PROFIT_LOSS)) {
                            scheduleF.netProfitLoss = parseBigDecimal(lineItem.get("perReturnValueTxt"));
                        }
                    }
                }
                
                scheduleFList.add(scheduleF);
            }
        }
        
        return scheduleFList;
    }

    /**
     * Parses Form 8582 data from the payload
     */
    private Form8582Data parseForm8582(JsonNode forms) {
        for (JsonNode form : forms) {
            String formNum = getTextValue(form, "formNum");
            
            if ("IRS8582".equals(formNum)) {
                Form8582Data form8582 = new Form8582Data();
                
                JsonNode lineItems = form.get("lineItems");
                if (lineItems != null && lineItems.isArray()) {
                    for (JsonNode lineItem : lineItems) {
                        String lineName = getTextValue(lineItem, "lineNameTxt");
                        
                        // Parse passive activities
                        if (lineName.contains(XPATH_8582_WRKSHT_PASSIVE)) {
                            parsePassiveActivity(lineItem, form8582);
                        }
                    }
                }
                
                return form8582;
            }
        }
        
        return null;
    }

    /**
     * Parses a single passive activity from Form 8582
     */
    private void parsePassiveActivity(JsonNode lineItem, Form8582Data form8582) {
        JsonNode nestedItems = lineItem.get("lineItems");
        if (nestedItems != null && nestedItems.isArray()) {
            PassiveActivity8582 activity = new PassiveActivity8582();
            
            for (JsonNode nested : nestedItems) {
                String nestedName = getTextValue(nested, "lineNameTxt");
                
                if (nestedName.contains(XPATH_8582_ACTIVITY_NAME)) {
                    activity.activityName = getTextValue(nested, "perReturnValueTxt");
                } else if (nestedName.contains(XPATH_8582_CURRENT_YEAR_LOSS)) {
                    activity.currentYearLoss = parseBigDecimal(nested.get("perReturnValueTxt"));
                } else if (nestedName.contains(XPATH_8582_OVERALEÌLOSS)) {
                    activity.overallLoss = parseBigDecimal(nested.get("perReturnValueTxt"));
                }
            }
            
            if (activity.activityName != null) {
                form8582.passiveActivities.add(activity);
            }
        }
    }

    /**
     * Validates a single Schedule F against Form 8582
     */
    private void validateScheduleFAgainst8582(ScheduleFData scheduleF, Form8582Data form8582, ValidationResult result) {
        String context = String.format("Schedule F (%s - %s)", 
            scheduleF.businessName, scheduleF.principalProduct);
        
        logger.debug("Validating {}", context);
        
        // Calculate expected net profit/loss: Line 9 - Line 33
        BigDecimal calculatedNet = calculateNetProfitLoss(scheduleF.grossIncome, scheduleF.totalExpenses);
        
        // Check if this is a passive activity (non-materially participated)
        boolean isPassive = !scheduleF.materiallyParticipated;
        
        if (isPassive) {
            // For passive activities, validate against Form 8582
            validatePassiveActivity(scheduleF, calculatedNet, form8582, result, context);
        } else {
            // For active activities, validate Schedule F Line 34 matches calculation
            validateActiveActivity(scheduleF, calculatedNet, result, context);
        }
    }

    /**
     * Validates a passive activity against Form 8582
     */
    private void validatePassiveActivity(ScheduleFData scheduleF, BigDecimal calculatedNet, 
                                           Form8582Data form8582, ValidationResult result, String context) {
        
        // Find matching activity in Form 8582
        PassiveActivity8582 matchingActivity = findMatching8582Activity(scheduleF, form8582);
        
        if (matchingActivity == null) {
            // Passive activity not found in Form 8582
            if (calculatedNet.compareTo(BigDecimal.ZERO) < 0) {
                result.addError(context + ": Passive activity with loss NOT found in Form 8582. " +
                        "Calculated loss: " + formatAmount(calculatedNet));
            } else {
                logger.debug("{}: Active activity or profitable passive activity - not required in 8582", context);
            }
            return;
        }
        
        // Validate business name matching
        validateBusinessNameMatch(scheduleF, matchingActivity, result, context);
        
        // Validate loss amount matching
        validateLossAmountMatch(scheduleF, calculatedNet, matchingActivity, result, context);
        
        // Validate Schedule F Line 34 reporting
        validateScheduleFLine34(scheduleF, calculatedNet, result, context);
    }

    /**
     * Validates an active activity (materially participated)
     */
    private void validateActiveActivity(ScheduleFData scheduleF, BigDecimal calculatedNet,
                                           ValidationResult result, String context) {
        // For active activities, Line 34 should match calculated net
        if (!amountsMatch(scheduleF.netProfitLoss, calculatedNet)) {
            result.addError(context + ": Line 34 (NetProfitLoss) mismatch. " +
                    "Reported: " + formatAmount(scheduleF.netProfitLoss) + 
                    ", Calculated (Line 9 - Line 33): " + formatAmount(calculatedNet));
        } else {
            logger.debug("{}: Active activity validation passed", context);
        }
    }

    /**
     * Validates business name matching between Schedule F and Form 8582
     */
    private void validateBusinessNameMatch(ScheduleFData scheduleF, PassiveActivity8582 activity,
                                             ValidationResult result, String context) {
        String scheduleFName = normalizeName(scheduleF.businessName);
        String form8582Name = normalizeName(activity.activityName);
        
        // Also check principal product as alternative match
        String principalProduct = normalizeName(scheduleF.principalProduct);
        
        boolean nameMatches = scheduleFName.equals(form8582Name) || 
                                principalProduct.equals(form8582Name) ||
                                form8582Name.contains(principalProduct) ||
                                principalProduct.contains(form8582Name);
        
        if (!nameMatches) {
            result.addWarning(context + ": Business name mismatch. " +
                    "Schedule F: '" + scheduleF.businessName + "' (" + scheduleF.principalProduct + "), " +
                    "Form 8582: '" + activity.activityName + "'");
        } else {
            logger.debug("{}: Business name match validated", context);
        }
    }

    /**
     * Validates loss amount matching between Schedule F and Form 8582
     */
    private void validateLossAmountMatch(ScheduleFData scheduleF, BigDecimal calculatedNet,
                                             PassiveActivity8582 activity, ValidationResult result, String context) {
        // For losses, check if calculated loss matches Form 8582 amount
        if (calculatedNet.compareTo(BigDecimal.ZERO) < 0) {
            // Use overall loss if available, otherwise current year loss
            BigDecimal form8582Loss = activity.overallLoss != null ? 
                                     activity.overallLoss : activity.currentYearLoss;
            
            if (form8582Loss == null) {
                result.addError(context + ": No loss amount found in Form 8582 for passive activity with loss");
                return;
            }
            
            // Convert both to positive for comparison
            BigDecimal calculatedLossAbs = calculatedNet.abs();
            BigDecimal form8582LossAbs = form8582Loss.abs();
            
            if (!amountsMatch(calculatedLossAbs, form8582LossAbs)) {
                result.addError(context + ": Loss amount mismatch. " +
                        "Calculated (Line 9 - Line 33): " + formatAmount(calculatedNet) + 
                        ", Form 8582: " + formatAmount(form8582Loss.negate()));
            } else {
                logger.debug("{}: Loss amount match validated", context);
            }
        }
    }

    /**
     * Validates Schedule F Line 34 reporting for passive activities
     */
    private void validateScheduleFLine34(ScheduleFData scheduleF, BigDecimal calculatedNet,
                                            ValidationResult result, String context) {
        // Key validation: When there is a loss, Schedule F Line 34 should NOT show zero
        if (calculatedNet.compareTo(BigDecimal.ZERO) < 0) {
            if (scheduleF.netProfitLoss == null || scheduleF.netProfitLoss.compareTo(BigDecimal.ZERO) == 0) {
                result.addError(context + ": Schedule F Line 34 (NetFarmProfitLossAmt) shows $0 but should show " +
                        formatAmount(calculatedNet) + ". " +
                        "Per IRS rules, Schedule F must report the actual loss; Form 8582 limits the deduction.");
            } else if (!amountsMatch(scheduleF.netProfitLoss, calculatedNet)) {
                result.addError(context + ": Schedule F Line 34 mismatch. " +
                        "Reported: " + formatAmount(scheduleF.netProfitLoss) + 
                        ", Calculated (Line 9 - Line 33): " + formatAmount(calculatedNet));
            } else {
                logger.debug("{}: Schedule F Line 34 correctly reports loss", context);
            }
        }
    }

    /**
     * Checks for orphaned entries in Form 8582 that don't have corresponding Schedule F
     */
    private void validateOrphaned8582Entries(List<ScheduleFData> scheduleFList, 
                                                  Form8582Data form8582, ValidationResult result) {
        for (PassiveActivity8582 activity : form8582.passiveActivities) {
            boolean found = false;
            
            for (ScheduleFData scheduleF : scheduleFList) {
                if (namesMatch(scheduleF.businessName, activity.activityName) ||
                    namesMatch(scheduleF.principalProduct, activity.activityName)) {
                    found = true;
                    break;
                }
            }
            
            if (!found) {
                result.addWarning("Form 8582 contains activity '" + activity.activityName + 
                        "' with no matching Schedule F");
            }
        }
    }

    /**
     * Finds matching activity in Form 8582
     */
    private PassiveActivity8582 findMatching8582Activity(ScheduleFData scheduleF, Form8582Data form8582) {
        for (PassiveActivity8582 activity : form8582.passiveActivities) {
            if (namesMatch(scheduleF.businessName, activity.activityName) ||
                namesMatch(scheduleF.principalProduct, activity.activityName)) {
                return activity;
            }
        }
        return null;
    }

    /**
     * Calculates net profit/loss: Line 9 - Line 33
     */
    private BigDecimal calculateNetProfitLoss(BigDecimal grossIncome, BigDecimal totalExpenses) {
        if (grossIncome == null) grossIncome = BigDecimal.ZERO;
        if (totalExpenses == null) totalExpenses = BigDecimal.ZERO;
        return grossIncome.subtract(totalExpenses);
    }

    /**
     * Normalizes name for comparison (uppercase, trim, remove extra spaces)
     */
    private String normalizeName(String name) {
        if (name == null) return "";
        return name.trim().toUpperCase().replaceAll("\\s+", " ");
    }

    /**
     * Checks if two names match (fuzzy matching)
     */
    private boolean namesMatch(String name1, String name2) {
        String norm1 = normalizeName(name1);
        String norm2 = normalizeName(name2);
        return norm1.equals(norm2) || norm1.contains(norm2) || norm2.contains(norm1);
    }

    /**
     * Checks if two amounts match (within tolerance)
     */
    private boolean amountsMatch(BigDecimal amount1, BigDecimal amount2) {
        if (amount1 == null && amount2 == null) return true;
        if (amount1 == null || amount2 == null) return false;
        
        // Allow 0.01 tolerance for rounding differences
        BigDecimal diff = amount1.subtract(amount2).abs();
        return diff.compareTo(new BigDecimal("0.01")) <= 0;
    }

    /**
     * Parses BigDecimal from JsonNode
     */
    private BigDecimal parseBigDecimal(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            logger.warn("Failed to parse BigDecimal: {}", node.asText());
            return null;
        }
    }

    /**
     * Gets text value from JsonNode
     */
    private String getTextValue(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) return null;
        return field.asText();
    }

    /**
     * Formats amount for display
     */
    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "$0";
        return String.format("$%,.2f", amount);
    }

    // Data classes
    
    public static class ScheduleFData {
        public String sequenceNum;
        public String businessName;
        public String principalProduct;
        public String ein;
        public boolean materiallyParticipated = false;
        public BigDecimal grossIncome;
        public BigDecimal totalExpenses;
        public BigDecimal netProfitLoss;
    }

    public static class Form8582Data {
        public List<PassiveActivity8582> passiveActivities = new ArrayList<>();
    }

    public static class PassiveActivity8582 {
        public String activityName;
        public BigDecimal currentYearLoss;
        public BigDecimal overallLoss;
    }

    public static class ValidationResult {
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        
        public void addError(String error) {
            errors.add(error);
        }
        
        public void addWarning(String warning) {
            warnings.add(warning);
        }
        
        public boolean isPass() {
            return errors.isEmpty();
        }
        
        public List<String> getErrors() {
            return errors;
        }
        
        public List<String> getWarnings() {
            return warnings;
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Validation Result: ").append(isPass() ? "PASS" : "FAIL").append("\n");
            if (!errors.isEmpty()) {
                sb.append("\nErrors:\n");
                errors.forEach(e -> sb.append("  - ").append(e).append("\n"));
            }
            if (!warnings.isEmpty()) {
                sb.append("\nWarnings:\n");
                warnings.forEach(w -> sb.append("  - ").append(w).append("\n"));
            }
            return sb.toString();
        }
    }
}