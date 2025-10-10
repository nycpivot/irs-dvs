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
public class ScheduleF8582Validator {

    /**
     * Validates Schedule F matching with Form 8582 based on IRS rules:
     * 1. Business names must match between Schedule F and Form 8582
     * 2. Loss amounts must match between Schedule F and Form 8582
     * 3. Loss calculation: Line 9 (Income) - Line 33 (Expenses) = Net Loss
     * 4. Only non-materially participated activities with losses should appear on 8582
     * 5. Passive losses are limited per IRC Section 469
     */
    public ValidationResult validate(JsonNode payload) {
        ValidationResult result = ValidationResult.builder()
                .passed(true)
                .errors(new ArrayList<>())
                .warnings(new ArrayList<>())
                .details(new ArrayList<>())
                .build();

        try {
            // Extract Schedule F forms
            List<ScheduleFData> scheduleFList = extractScheduleFData(payload);
            
            // Extract Form 8582 data
            List<Form8582Data> form8582List = extractForm8582Data(payload);

            // Validate each Schedule F against Form 8582
            for (ScheduleFData schedF : scheduleFList) {
                // Calculate actual loss from Line 9 - Line 33
                BigDecimal calculatedLoss = schedF.grossIncome.subtract(schedF.totalExpenses);
                
                // Check if this is a loss activity
                if (calculatedLoss.compareTo(BigDecimal.ZERO) < 0) {
                    // Validate net loss reporting
                    if (schedF.netProfitLoss.compareTo(BigDecimal.ZERO) == 0) {
                        result.getErrors().add(String.format(
                                "Schedule F '%s' NetFarmProfitLossAmt shows $0 but calculated loss is %s. " +
                                "Line 9 (%s) - Line 33 (%s) = %s",
                                schedF.businessName, 
                                formatCurrency(calculatedLoss),
                                formatCurrency(schedF.grossIncome),
                                formatCurrency(schedF.totalExpenses),
                                formatCurrency(calculatedLoss)
                        ));
                        result.setPassed(false);
                    }
                    
                    // Check if non-materially participated (passive)
                    if (!schedF.materiallyParticipated) {
                        // Find matching Form 8582 entry
                        Optional<Form8582Data> matching8582 = form8582List.stream()
                                .filter(f => f.activityName.equalsIgnoreCase(schedF.businessName))
                                .findFirst();

                        if (matching8582.isPresent()) {
                            Form8582Data form8582 = matching8582.get();

                            // Validate loss amount matches
                            BigDecimal absCalculatedLoss = calculatedLoss.abs();
                            if (absCalculatedLoss.compareTo(form8582.currentYearLoss) != 0) {
                                result.getErrors().add(String.format(
                                        "Loss amount mismatch for '%s': Schedule F calculated loss %s, " +
                                        "Form 8582 shows %s",
                                        schedF.businessName,
                                        formatCurrency(absCalculatedLoss),
                                        formatCurrency(form8582.currentYearLoss)
                                  ));
                                result.setPassed(false);
                            } else {
                                result.getDetails().add(String.format(
                                        "Validated '%s': Schedule F loss %s matches Form 8582",
                                        schedF.businessName,
                                        formatCurrency(absCalculatedLoss)
                                ));
                            }
                        } else {
                            result.getErrors().add(String.format(
                                    "Schedule F '%s' has passive loss %s but not found on Form 8582",
                                    schedF.businessName,
                                    formatCurrency(calculatedLoss.abs())
                            ));
                            result.setPassed(false);
                        }
                    } else {
                        // Materially participated with loss - should NOT be on 8582
                        boolean foundOn8582 = form8582List.stream()
                                .anyMatch(f -> f.activityName.equalsIgnoreCase(schedF.businessName));
                        
                        if (foundOn8582) {
                            result.getWarnings().add(String.format(
                                    "Schedule F '%s' is materially participated but appears on Form 8582. " +
                                    "Material participation losses are not subject to passive loss limitations.",
                                    schedF.businessName
                            ));
                        }
                    }
                } else if (calculatedLoss.compareTo(BigDecimal.ZERO) > 0) {
                    // Profit - should NOT be on 8582
                    boolean foundOn8582 = form8582List.stream()
                            .anyMatch(f -> f.activityName.equalsIgnoreCase(schedF.businessName));

                    if (foundOn8582) {
                        result.getErrors().add(String.format(
                                "Schedule F '%s' has profit %s but appears on Form 8582. " +
                                "Only losses should be reported on Form 8582.",
                                schedF.businessName,
                                formatCurrency(calculatedLoss)
                        ));
                        result.setPassed(false);
                    } else {
                        result.getDetails().add(String.format(
                                "Schedule F '%s' has profit %s - correctly excluded from Form 8582",
                                schedF.businessName,
                                formatCurrency(calculatedLoss)
                        ));
                    }
                }
            }

            // Check for orphaned 8582 entries not matching any Schedule F
            for (Form8582Data form8582 : form8582List) {
                boolean matchesSchedF = scheduleFList.stream()
                        .anyMatch(s -> s.businessName.equalsIgnoreCase(form8582.activityName));

                if (!matchesSchedF) {
                    result.getWarnings().add(String.format(
                            "Form 8582 activity '%s' with loss %s does not match any Schedule F",
                            form8582.activityName,
                            formatCurrency(form8582.currentYearLoss)
                    ));
                }
            }

        } catch (Exception e) {
            result.setPassed(false);
            result.getErrors().add("Validation error: " + e.getMessage());
        }

        return result;
    }

    private List<ScheduleFData> extractScheduleFData(JsonNode payload) {
        List<ScheduleFData> result = new ArrayList<>();
        
        JsonNode forms = payload.at("/body/forms");
        if (forms != null && forms.isArray()) {
            for (JsonNode form : forms) {
                String formNum = form.at("/formNum").asText("");
                
                if ("IRS1040ScheduleF".equals(formNum)) {
                    ScheduleFData data = ScheduleFData.builder()
                            .businessName(extractBusinessName(form))
                            .ein(extractText(form, "/lineItems", "/IRS1040ScheduleF/EIN"))
                            .grossIncome(extractAmount(form, "/lineItems", 
                                  "/IRS1040ScheduleF/FarmIncomeCashMethodGrp/GrossIncomeAmt"))
                            .totalExpenses(extractAmount(form, "/lineItems",
                                  "/IRS1040ScheduleF/FarmExpensesGrp/TotalExpensesAmt"))
                            .netProfitLoss(extractAmount(form, "/lineItems",
                                  "/IRS1040ScheduleF/FarmExpensesGrp/NetFarmProfitLossAmt"))
                            .materiallyParticipated(extractBoolean(form, "/lineItems",
                                  "/IRS1040ScheduleF/MateriallyParticipatedInd"))
                            .build();
                    
                    result.add(data);
                }
            }
        }
        
        return result;
    }

    private List<Form8582Data> extractForm8582Data(JsonNode payload) {
        List<Form8582Data> result = new ArrayList<>();
        
        JsonNode forms = payload.at("/body/forms");
        if (forms != null && forms.isArray()) {
            for (JsonNode form : forms) {
                String formNum = form.at("/formNum").asText("");
                
                if ("IRS8582".equals(formNum)) {
                    JsonNode worksheetGrp = findNode(form, "/lineItems", 
                            "/IRS8582/ParentWrkshtPassiveGrp");
                    
                    if (worksheetGrp != null) {
                        JsonNode activities = findNode(worksheetGrp, "/lineItems", 
                                "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp");
                        
                        if (activities != null && activities.isArray()) {
                            for (JsonNode activity : activities) {
                                Form8582Data data = Form8582Data.builder()
                                        .activityName(extractText(activity, "/lineItems", 
                                                "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/NonParticipateActivityNm"))
                                        .currentYearLoss(extractAmount(activity, "/lineItems",
                                                "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/CurrentYearNetLossAmt"))
                                        .priorYearUnallowed(extractAmount(activity, "/lineItems",
                                                "/IRS8582/ParentWrkshtPassiveGrp/WrkshtPassiveGrp/OverallLossAmt"))
                                        .build();
                                
                                result.add(data);
                            }
                        }
                    }
                }
            }
        }
        
        return result;
    }

    private String extractBusinessName(JsonNode form) {
        JsonNode nameNode = findNode(form, "/lineItems", 
                "/IRS1040ScheduleF/FarmProprietorName/BusinessNameLine1Txt");
        return nameNode != null ? nameNode.asText("") : "";
    }

    private String extractText(JsonNode parent, String... paths) {
        JsonNode node = findNode(parent, paths);
        return node != null ? node.asText("") : "";
    }

    private BigDecimal extractAmount(JsonNode parent, String... paths) {
        JsonNode node = findNode(parent, paths);
        if (node != null) {
            try {
                return new BigDecimal(node.asText("0"));
            } catch (NumberFormatException e) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    private boolean extractBoolean(JsonNode parent, String... paths) {
        JsonNode node = findNode(parent, paths);
        if (node != null) {
            String value = node.asText("false").toLowerCase();
            return "true".equals(value) || "x".equals(value);
        }
        return false;
    }

    private JsonNode findNode(JsonNode parent, String... paths) {
        if (parent == null || paths.length == 0) {
            return null;
        }
        
        for (String path : paths) {
            if (path.startsWith("/")) {
                JsonNode node = parent.at(path);
                if (node != null && !node.isMissingNode()) {
                    parent = node;
                } else {
                    // Search in arrays
                    JsonNode found = searchInArray(parent, path);
                    if (found != null) {
                        parent = found;
                    } else {
                        return null;
                    }
                }
            }
        }
        
        return parent;
    }

    private JsonNode searchInArray(JsonNode parent, String targetPath) {
        if (parent.isArray()) {
            for (JsonNode item : parent) {
                JsonNode found = item.at(targetPath);
                if (found != null && !found.isMissingNode()) {
                    return found;
                }
                // Recursive search
                for (Iterator<JsonNode> it = item.elements(); it.hasNext(); ) {
                    JsonNode result = searchInArray(it.next(), targetPath);
                    if (result != null) {
                        return result;
                    }
                }
            }
        }
        return null;
    }

    private String formatCurrency(BigDecimal amount) {
        return String.format("$%,.2f", amount);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
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
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Form8582Data {
        private String activityName;
        private BigDecimal currentYearLoss;
        private BigDecimal priorYearUnallowed;
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
