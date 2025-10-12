package com.irs.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Validator for Schedule F to Form 8582 matching
 * 
 * Business Rules:
 * 1. Match business names between Schedule F and Form 8582
 * 2. Validate loss calculation: Line 9 (Gross Income) - Line 33 (Total Expenses) = Line 34 (Net Profit/Loss)
 * 3. Ensure Schedule F Line 34 matches Form 8582 reported losses
 * 4. Verify passive activity treatment for non-material participation farms
 *
 * IRS Rules (Form 8582 Instructions):
 * - Net loss = excess of current year deductions over current year income
 * - Passive activities without material participation must report losses on Schedule F
 * - Form 8582 Part V is used for passive trade/business activities
 */
@Component
public class ScheduleF8582Validator {

    private static final String SCHEDULE_F_PATH = "/IRS1040ScheduleF";
    private static final String FORM_8582_PATH = "/IRS8582";
    private static final String FARM_INCOME_PATH = "/FarmIncomeCashMethodGrp/GrossIncomeAmt";
    private static final String FARM_EXPENSES_PATH = "/FarmExpensesGrp/TotalExpensesAmt";
    private static final String NET_FARM_PROFIT_LOSS_PATH = "/FarmExpensesGrp/NetFarmProfitLossAmt";
    private static final String BUSINESS_NAME_PATH = "/FarmProprietorName/BusinessNameLine1Txt";
    private static final String MATERIAL_PARTICIPATION_PATH = "/MateriallyParticipatedInd";
    private static final String WORKSHEET_PASSIVE_GRP_PATH = "/ParentWrkshtPassiveGrp/WrkshtPassiveGrp";
    private static final String NON_PARTICIPATE_ACTIVITY_NAME = "/NonParticipateActivityNm";
    private static final String CURRENT_YEAR_NET_LOSS = "/CurrentYearNetLossAmt";

    /**
     * Validates the entire tax return payload for Schedule F to Form 8582 matching
     */
    public ValidationResult validate(JsonNode payload) {
        ValidationResult.ValidationResultBuilder resultBuilder = ValidationResult.builder();
        List<ValidationError> errors = new ArrayList<>();

        try {
            // Extract forms from payload
            JsonNode body = payload.get("body");
            if (body == null || !body.has("forms")) {
                return resultBuilder
                        .valid(false)
                        .errors(List.of(ValidationError.builder()
                                .errorCode("MISSING_BODY")
                                .message("Payload missing body or forms")
                                .severity("CRITICAL")
                                .build()))
                        .build();
            }

            JsonNode forms = body.get("forms");
            List<JsonNode> scheduleFForms = extractScheduleFForms(forms);
            Optional<JsonNode> form8582 = extractForm8582(forms);

            if (scheduleFForms.isEmpty()) {
                errors.add(ValidationError.builder()
                        .errorCode("NO_SCHEDULEF_FOUND")
                        .message("No Schedule F forms found in payload")
                        .severity("WARNING")
                        .build());
            }

            if (form8582.isEmpty()) {
                errors.add(ValidationError.builder()
                        .errorCode("NO_8582_FOUND")
                        .message("Form 8582 not found in payload")
                        .severity("WARNING")
                        .build());
            }

            // Validate each Schedule F
            for (JsonNode scheduleF : scheduleFForms) {
                errors.addAll(validateScheduleF(scheduleF, form8582));
            }

            boolean isValid = errors.stream()
                    .noneMatch(e -> "CRITICAL".equals(e.getSeverity()) || "ERROR".equals(e.getSeverity()));

            return resultBuilder
                    .valid(isValid)
                    .errors(errors)
                    .totalScheduleFForms(scheduleFForms.size())
                    .build();

        } catch (Exception e) {
            return resultBuilder
                    .valid(false)
                    .errors(List.of(ValidationError.builder()
                            .errorCode("VALIDATION_ERROR")
                            .message("Validation failed: " + e.getMessage())
                            .severity("CRITICAL")
                            .build()))
                    .build();
        }
    }

    /**
     * Validates a single Schedule F form
     */
    private List<ValidationError> validateScheduleF(JsonNode scheduleF, Optional<JsonNode> form8582) {
        List<ValidationError> errors = new ArrayList<>();

        // Extract business name
        String businessName = extractTextValue(scheduleF, BUSINESS_NAME_PATH);
        if (businessName == null || businessName.isBlank()) {
            errors.add(ValidationError.builder()
                    .errorCode("MISSING_BUSINESS_NAME")
                    .message("Schedule F missing business name")
                    .severity("ERROR")
                    .build());
            return errors;
        }

        // Extract financial data
        BigDecimal grossIncome = extractAmount(scheduleF, FARM_INCOME_PATH);
        BigDecimal totalExpenses = extractAmount(scheduleF, FARM_EXPENSES_PATH);
        BigDecimal reportedNetProfitLoss = extractAmount(scheduleF, NET_FARM_PROFIT_LOSS_PATH);
        boolean materialParticipation = extractBoolean(scheduleF, MATERIAL_PARTICIPATION_PATH);

        // Rule 1: Calculate expected net profit/loss (Line 9 - Line 33)
        BigDecimal calculatedNetProfitLoss = grossIncome.subtract(totalExpenses);

        // Rule 2: Validate calculation matches reported value
        if (calculatedNetProfitLoss.compareTo(reportedNetProfitLoss) != 0) {
            errors.add(ValidationError.builder()
                    .errorCode("NET_PROFIT_LOSS_MISMATCH")
                    .message(String.format(
                            "Schedule F '%s': Line 34 (%s) does not match calculated value (Line 9 %s - Line 33 %s = %s)",
                            businessName,
                            reportedNetPÏfitLoss,
                            grossIncome,
                            totalExpenses,
                            calculatedNetProfitLoss
                    ))
                    .severity("ERROR")
                    .businessName(businessName)
                    .expectedValue(calculatedNetPÏfitLoss.toString())
                    .actualValue(reportedNetProfitLoss.toString())
                    .build());
        }

        // Rule 3: For passive activities (non-material participation) with losses, validate against Form 8582
        if (!materialParticipation && calculatedNetPÏfitLoss.compareTo(BigDecimal.ZERO) < 0) {
            // This is a passive activity with a loss
            if (reportedNetPÏfitLoss.compareTo(BigDecimal.ZERO) == 0) {
                errors.add(ValidationError.builder()
                        .errorCode("PASSIVE_LOSS_NOT_REPORTED")
                        .message(String.format(
                                "Schedule F '%s': Passive activity loss not reported on Line 34. " +
                                        "Calculated loss of %s should be reported, not $0.",
                                businessName,
                                calculatedNetProfitLoss
                        ))
                        .severity("ERROR")
                        .businessName(businessName)
                        .expectedValue(calculatedNetPÏfitLoss.toString())
                        .actualValue("0")
                        .build());
            }

            // Validate against Form 8582
            if (form8582.isPresent()) {
                errors.addAll(validateAgainstForm8582(businessName, calculatedNetProfitLoss, form8582.get()));
            }
        }

        return errors;
    }

    /**
     * Validates Schedule F loss against Form 8582
     */
    private List<ValidationError> validateAgainstForm8582(String businessName, BigDecimal calculatedLoss, JsonNode form8582) {
        List<ValidationError> errors = new ArrayList<>();

        // Find matching activity in Form 8582
        JsonNode worksheetPassiveGrp = form8582.at(WORKSHEET_PASSIVE_GRP_PATH);
        
        if (worksheetPassiveGrp != null && worksheetPassiveGrp.isArray()) {
            boolean foundMatch = false;
            
            for (JsonNode activity : worksheetPassiveGrp) {
                String activityName = extractTextValue(activity, NON_PARTICIPATE_ACTIVITY_NAME);
                
                if (businessName.equalsIgnoreCase(activityName)) {
                    foundMatch = true;
                    BigDecimal form8582Loss = extractAmount(activity, CURRENT_YEAR_NET_LOSS);
                    
                    // Compare absolute values (losses are positive in 8582)
                    BigDecimal expectedLoss = calculatedLoss.abs();
                    
                    if (form8582Loss.compareTo(expectedLoss) != 0) {
                        errors.add(ValidationError.builder()
                                .errorCode("FORM_8582_MISMATCH")
                                .message(String.format(
                                        "Form 8582 loss for '%s' (%s) does not match Schedule F calculated loss (%s)",
                                        businessName,
                                        form8582Loss,
                                        expectedLoss
                                ))
                                .severity("ERROR")
                                .businessName(businessName)
                                .expectedValue(expectedLoss.toString())
                                .actualValue(form8582Loss.toString())
                                .build());
                    }
                    break;
                }
            }
            
            if (!foundMatch) {
                errors.add(ValidationError.builder()
                        .errorCode("BUSINESS_NAME_NOT_FOUND_IN_8582")
                        .message(String.format(
                                "Schedule F business '%s' not found in Form 8582 passive activities",
                                businessName
                        ))
                        .severity("WARNING")
                        .businessName(businessName)
                        .build());
            }
        }
        
        return errors;
    }

    /**
     * Extracts all Schedule F forms from the forms array
     */
    private List<JsonNode> extractScheduleFForms(JsonNode forms) {
        List<JsonNode> scheduleFForms = new ArrayList<>();
        if (forms != null && forms.isArray()) {
            for (JsonNode form : forms) {
                if (form.has("formNum") && "IRS1040ScheduleF".equals(form.get("formNum").asText())) {
                    scheduleFForms.add(form);
                }
            }
        }
        return scheduleFForms;
    }

    /**
     * Extracts Form 8582 from the forms array
     */
    private Optional<JsonNode> extractForm8582(JsonNode forms) {
        if (forms != null && forms.isArray()) {
            for (JsonNode form : forms) {
                if (form.has("formNum") && "IRS8582".equals(form.get("formNum").asText())) {
                    return Optional.of(form);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Extracts a text value from a JsonNode using a path
     */
    private String extractTextValue(JsonNode node, String path) {
        JsonNode targetNode = navigateToNode(node, path);
        if (targetNode != null && !targetNode.isNull()) {
            if (targetNode.isTextual()) {
                return targetNode.asText();
            }
            // Check for lineItems structure
            if (targetNode.has("lineItems") && targetNode.get("lineItems").isArray()) {
                for (JsonNode lineItem : targetNode.get("lineItems")) {
                    if (lineItem.has("perReturnValueTxt")) {
                        return lineItem.get("perReturnValueTxt").asText();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Extracts a numeric amount from a JsonNode using a path
     */
    private BigDecimal extractAmount(JsonNode node, String path) {
        JsonNode targetNode = navigateToNode(node, path);
        if (targetNode != null && !targetNode.isNull()) {
            if (targetNode.isNumber()) {
                return new BigDecimal(targetNode.asText());
            }
            // Check for lineItems structure
            if (targetNode.has("lineItems") && targetNode.get("lineItems").isArray()) {
                for (JsonNode lineItem : targetNode.get("lineItems")) {
                    if (lineItem.has("perReturnValueTxt")) {
                        try {
                            return new BigDecimal(lineItem.get("perReturnValueTxt").asText());
                        } catch (NumberFormatException e) {
                            // Ignore and continue
                        }
                    }
                }
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * Extracts a boolean value from a JsonNode using a path
     */
    private boolean extractBoolean(JsonNode node, String path) {
        JsonNode targetNode = navigateToNode(node, path);
        if (targetNode != null && !targetNode.isNull()) {
            if (targetNode.isBoolean()) {
                return targetNode.asBoolean();
            }
            // Check for lineItems structure
            if (targetNode.has("lineItems") && targetNode.get("lineItems").isArray()) {
                for (JsonNode lineItem : targetNode.get("lineItems")) {
                    if (lineItem.has("perReturnValueTxt")) {
                        String value = lineItem.get("perReturnValueTxt").asText();
                        return "true".equalsIgnoreCase(value);
                    }
                }
            }
        }
        return false;
    }

    /**
     * Navigates to a node using a slash-delimited path
     */
    private JsonNode navigateToNode(JsonNode root, String path) {
        if (path == null || path.isBlank()) {
            return root;
        }
        
        JsonNode current = root;
        String[] parts = path.split("/");
        
        for (String part : parts) {
            if (part.isBlank()) continue;
            
            // Check for lineItems array
            if (current.has("lineItems") && current.get("lineItems").isArray()) {
                for (JsonNode lineItem : current.get("lineItems")) {
                    if (lineItem.has("lineNameTxt") && lineItem.get("lineNameTxt").asText().endsWith(part)) {
                        return lineItem;
                    }
                }
            }
            
            current = current.get(part);
            if (current == null || current.isNull()) {
                return null;
            }
        }
        
        return current;
    }

    /**
     * Validation result class
     */
    @Data
    @Builder
    public static class ValidationResult {
        private boolean valid;
        private List<ValidationError> errors;
        private int totalScheduleFForms;
    }

    /**
     * Validation error class
     */
    @Data
    @Builder
    public static class ValidationError {
        private String errorCode;
        private String message;
        private String severity; // CRITICAL, ERROR, WARNING, INFO
        private String businessName;
        private String expectedValue;
        private String actualValue;
    }
}
