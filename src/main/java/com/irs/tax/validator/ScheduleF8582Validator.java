package com.irs.tax.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.Builder;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@SpringBootApplication
@Service
public class ScheduleF8582Validator {

    public static void main(String[] args) {
        SpringApplication.run(ScheduleF8582Validator.class, args);
    }

    /**
     * Validates Schedule F matching with Form 8582
     * Business Rules:
     * 1. Business names must match between Schedule F and Form 8582
     * 2. Loss amounts on Form 8582 must equal Schedule F Line 9 - Line 33
     * 3. Schedule F NetFarmProfitLossAmt should reflect actual loss (negative), not zero
     * 4. Only non-materially participating activities should appear on 8582
     */
    public ValidationResult validate(JsonNode payload) {
        ValidationResult result = ValidationResult.builder()
                .valid(true)
                .errors(new ArrayList<>())
                .warnings(new ArrayList<>())
                .build();

        try {
            // Extract Schedule F activities
            List<ScheduleFActivity> scheduleFActivities = extractScheduleFActivities(payload);

            // Extract Form 8582 activities
            List<Form8582Activity> form8582Activities = extractForm8582Activities(payload);

            // Validate business name matching
            validateBusinessNameMatching(scheduleFActivities, form8582Activities, result);

            // Validate loss calculations
            validateLossCalculations(scheduleFActivities, form8582Activities, result);

            // Validate NetFarmProfitLossAmt reporting
            validateNetFarmProfitLossReporting(scheduleFActivities, result);

            // Validate material participation exclusion
            validateMaterialParticipationExclusion(scheduleFActivities, form8582Activities, result);

            result.setValid(result.getErrors().isEmpty());

        } catch (Exception e) {
            result.setValid(false);
            result.getErrors().add("Validation exception: " + e.getMessage());
        }

        return result;
    }

    private List<ScheduleFActivity> extractScheduleFActivities(JsonNode payload) {
        List<ScheduleFActivity> activities = new ArrayList<>();
        JsonNode forms = payload.at("/body/forms");

        if (forms != null && forms.isArray()) {
            for (JsonNode form : forms) {
                String formNum = form.at("/formNum").asText("");
                if ("IRS1040ScheduleF".equals(formNum)) {
                    ScheduleFActivity activity = parseScheduleF(form);
                    if (activity != null) {
                        activities.add(activity);
                    }
                }
            }
        }

        return activities;
    }

    private ScheduleFActivity parseScheduleF(JsonNode form) {
        String businessName = null;
        BigDecimal grossIncome = BigDecimal.ZERO;
        BigDecimal totalExpenses = BigDecimal.ZERO;
        BigDecimal netProfitLoss = BigDecimal.ZERO;
        boolean materiallyParticipated = false;
        String sequenceNum = form.at("/sequenceNum").asText("");

        JsonNode lineItems = form.at("/lineItems");
        if (lineItems != null && lineItems.isArray()) {
            for (JsonNode item : lineItems) {
                String lineName = item.at("/lineNameTxt").asText("");

                if (lineName.contains("/FarmProprietorName/BusinessNameLine1Txt")) {
                    businessName = item.at("/perReturnValueTxt").asText("").trim();
                } else if (lineName.contains("/MateriallyParticipatedInd")) {
                    materiallyParticipated = "true".equalsIgnoreCase(item.at("/perReturnValueTxt").asText("false"));
                } else if (lineName.contains("/FarmIncomeCashMethodGrp/GrossIncomeAmt")) {
                    grossIncome = new BigDecimal(item.at("/perReturnValueTxt").asText("0"));
                } else if (lineName.contains("/FarmExpensesGrp/TotalExpensesAmt")) {
                    totalExpenses = new BigDecimal(item.at("/perReturnValueTxt").asText("0"));
                } else if (lineName.contains("/FarmExpensesGrp/NetFarmProfitLossAmt")) {
                    netProfitLoss = new BigDecimal(item.at("/perReturnValueTxt").asText("0"));
                }
            }
        }

        if (businessName != null && !businessName.isBlank()) {
            return ScheduleFActivity.builder()
                    .businessName(businessName)
                    .sequenceNum(sequenceNum)
                    .grossIncome(grossIncome)
                    .totalExpenses(totalExpenses)
                    .netProfitLoss(netProfitLoss)
                    .materiallyParticipated(materiallyParticipated)
                    .build();
        }

        return null;
    }

    private List<Form8582Activity> extractForm8582Activities(JsonNode payload) {
        List<Form8582Activity> activities = new ArrayList<>();
        JsonNode forms = payload.at("/body/forms");

        if (forms != null && forms.isArray()) {
            for (JsonNode form : forms) {
                String formNum = form.at("/formNum").asText("");
                if ("IRS8582".equals(formNum)) {
                    activities.addAll(parseForm8582(form));
                }
            }
        }

        return activities;
    }

    private List<Form8582Activity> parseForm8582(JsonNode form) {
        List<Form8582Activity> activities = new ArrayList<>();
        JsonNode lineItems = form.at("/lineItems");

        if (lineItems != null && lineItems.isArray()) {
            for (JsonNode item : lineItems) {
                String lineName = item.at("/lineNameTxt").asText("");

                if (lineName.contains("/ParentWrkshtPassiveGrp/WrkshtPassiveGrp")) {
                    Form8582Activity activity = parse8582Activity(item);
                    if (activity != null) {
                        activities.add(activity);
                    }
                }
            }
        }

        return activities;
    }

    private Form8582Activity parse8582Activity(JsonNode wrkshtGrp) {
        String activityName = null;
        BigDecimal currentYearLoss = BigDecimal.ZERO;
        BigDecimal priorYearLoss = BigDecimal.ZERO;

        JsonNode lineItems = wrkshtGrp.at("/lineItems");
        if (lineItems != null && lineItems.isArray()) {
            for (JsonNode item : lineItems) {
                String lineName = item.at("/lineNameTxt").asText("");

                if (lineName.contains("/NonParticipateActivityNm")) {
                    activityName = item.at("/perReturnValueTxt").asText("").trim();
                } else if (lineName.contains("/CurrentYearNetLossAmt")) {
                    currentYearLoss = new BigDecimal(item.at("/perReturnValueTxt").asText("0"));
                } else if (lineName.contains("/OverallLossAmt")) {
                    priorYearLoss = new BigDecimal(item.at("/perReturnValueTxt").asText("0"));
                }
            }
        }

        if (activityName != null && !activityName.isBlank()) {
            return Form8582Activity.builder()
                    .activityName(activityName)
                    .currentYearLoss(currentYearLoss)
                    .priorYearLoss(priorYearLoss)
                    .build();
        }

        return null;
    }

    private void validateBusinessNameMatching(List<ScheduleFActivity> scheduleFActivities,
                                                   List<Form8582Activity> form8582Activities,
                                                   ValidationResult result) {
        for (Form8582Activity form8582Activity : form8582Activities) {
            boolean found = false;
            for (ScheduleFActivity scheduleFActivity : scheduleFActivities) {
                if (normalizeBusinessName(scheduleFActivity.getBusinessName())
                        .equals(normalizeBusinessName(form8582Activity.getActivityName()))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                result.getErrors().add(
                    String.format("Form 8582 activity '%s' not found in Schedule F",
                        form8582Activity.getActivityName())
                );
            }
        }
    }

    private void validateLossCalculations(List<ScheduleFActivity> scheduleFActivities,
                                                  List<Form8582Activity> form8582Activities,
                                                  ValidationResult result) {
        for (Form8582Activity form8582Activity : form8582Activities) {
            for (ScheduleFActivity scheduleFActivity : scheduleFActivities) {
                if (normalizeBusinessName(scheduleFActivity.getBusinessName())
                        .equals(normalizeBusinessName(form8582Activity.getActivityName()))) {

                    // Calculate expected loss: Line 9 - Line 33
                    BigDecimal calculatedLoss = scheduleFActivity.getGrossIncome()
                            .subtract(scheduleFActivity.getTotalExpenses());

                    // Form 8582 should show loss as positive
                    BigDecimal expected8582Loss = calculatedLoss.negate();

                    if (calculatedLoss.compareTo(BigDecimal.ZERO) < 0) {
                        // This is a loss, validate it matches Form 8582
                        if (form8582Activity.getCurrentYearLoss().compareTo(expected8582Loss) != 0) {
                            result.getErrors().add(
                                String.format(
                                    "Activity '%s': Form 8582 loss (%s) does not match calculated loss (%s). " +
                                            "Expected: Line 9 (%s) - Line 33 (%s) = %s",
                                    scheduleFActivity.getBusinessName(),
                                    form8582Activity.getCurrentYearLoss(),
                                    expected8582Loss,
                                    scheduleFActivity.getGrossIncome(),
                                    scheduleFActivity.getTotalExpenses(),
                                    calculatedLoss
                                )
                            );
                        }
                    }
                    break;
                }
            }
        }
    }

    private void validateNetFarmProfitLossReporting(List<ScheduleFActivity> scheduleFActivities,
                                                            ValidationResult result) {
        for (ScheduleFActivity activity : scheduleFActivities) {
            BigDecimal calculatedLoss = activity.getGrossIncome()
                    .subtract(activity.getTotalExpenses());

            // If there's a loss (negative), NetFarmProfitLossAmt should reflect it
            if (calculatedLoss.compareTo(BigDecimal.ZERO) < 0) {
                if (activity.getNetProfitLoss().compareTo(BigDecimal.ZERO) == 0) {
                    result.getErrors().add(
                        String.format(
                            "Schedule F activity '%s': NetFarmProfitLossAmt shows $0 but should show %s. " +
                                    "Per IRS rules, losses must be reported as negative values.",
                            activity.getBusinessName(),
                            calculatedLoss
                        )
                    );
                } else if (activity.getNetProfitLoss().compareTo(calculatedLoss) != 0) {
                    result.getWarnings().add(
                        String.format(
                            "Schedule F activity '%s': NetFarmProfitLossAmt (%s) does not match calculated loss (%s)",
                            activity.getBusinessName(),
                            activity.getNetProfitLoss(),
                            calculatedLoss
                        )
                    );
                }
            }
        }
    }

    private void validateMaterialParticipationExclusion(List<ScheduleFActivity> scheduleFActivities,
                                                                List<Form8582Activity> form8582Activities,
                                                                ValidationResult result) {
        // Activities with material participation should NOT appear on Form 8582
        for (ScheduleFActivity activity : scheduleFActivities) {
            if (activity.isMateriallyParticipated()) {
                for (Form8582Activity form8582Activity : form8582Activities) {
                    if (normalizeBusinessName(activity.getBusinessName())
                            .equals(normalizeBusinessName(form8582Activity.getActivityName()))) {
                        result.getErrors().add(
                            String.format(
                                "Activity '%s' has material participation but appears on Form 8582. " +
                                        "Per IRS rules, materially participating activities are not passive.",
                                activity.getBusinessName()
                            )
                        );
                        break;
                    }
                }
            }
        }

        // Activities on Form 8582 should NOT have material participation
        for (Form8582Activity form8582Activity : form8582Activities) {
            for (ScheduleFActivity activity : scheduleFActivities) {
                if (normalizeBusinessName(activity.getBusinessName())
                        .equals(normalizeBusinessName(form8582Activity.getActivityName()))) {
                    if (activity.isMateriallyParticipated()) {
                        result.getWarnings().add(
                            String.format(
                                "Activity '%s' on Form 8582 shows material participation in Schedule F. " +
                                        "This may indicate a data inconsistency.",
                                form8582Activity.getActivityName()
                            )
                        );
                    }
                    break;
                }
            }
        }
    }

    private String normalizeBusinessName(String name) {
        if (name == null) {
            return "";
        }
        return name.toUpperCase().trim().replaceAll("\\s+", " ");
    }

    @Data
    @Builder
    public static class ScheduleFActivity {
        private String businessName;
        private String sequenceNum;
        private BigDecimal grossIncome;
        private BigDecimal totalExpenses;
        private BigDecimal netProfitLoss;
        private boolean materiallyParticipated;
    }

    @Data
    @Builder
    public static class Form8582Activity {
        private String activityName;
        private BigDecimal currentYearLoss;
        private BigDecimal priorYearLoss;
    }

    @Data
    @Builder
    public static class ValidationResult {
        private boolean valid;
        private List<String> errors;
        private List<String> warnings;
    }
}
