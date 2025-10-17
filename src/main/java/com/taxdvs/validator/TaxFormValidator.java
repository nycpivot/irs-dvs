package com.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class TaxFormValidator {

    private static final Pattern SSN_PATTERN = Pattern.compile("^\\d{3}-\\d{2}-\\d{4}$");
    private static final Pattern EIN_PATTERN = Pattern.compile("^\\d{2}-\\d{7}$");
    private static final Pattern ZIP_PATTERN = Pattern.compile("^\\d{5}(-\\d{4})?$");

    public List<String> validateTaxForm(JsonNode taxForm) {
        List<String> errors = new ArrayList<>();

        if (taxForm == null || taxForm.isNull()) {
            errors.add("Tax form payload is null or empty");
            return errors;
        }

        validateFormType(taxForm, errors);
        validateTaxYear(taxForm, errors);
        validateTaxpayerInfo(taxForm, errors);
        validateIncome(taxForm, errors);
        validateDeductions(taxForm, errors);
        validateCredits(taxForm, errors);
        validateTaxCalculations(taxForm, errors);

        return errors;
    }

    private void validateFormType(JsonNode taxForm, List<String> errors) {
        JsonNode formType = taxForm.get("formType");
        if (formType == null || !formType.isTextual()) {
            errors.add("Form type is missing or invalid");
            return;
        }
        String type = formType.asText();
        if (!type.matches("^(1040|1099|W-2|W-4|1120|Schedule[A-E])$")) {
            errors.add("Invalid form type: " + type);
        }
    }

    private void validateTaxYear(JsonNode taxForm, List<String> errors) {
        JsonNode taxYear = taxForm.get("taxYear");
        if (taxYear == null || !taxYear.isInt()) {
            errors.add("Tax year is missing or invalid");
            return;
        }
        int year = taxYear.asInt();
        if (year < 1913 || year > 2025) {
            errors.add("Tax year out of valid range: " + year);
        }
    }

    private void validateTaxpayerInfo(JsonNode taxForm, List<String> errors) {
        JsonNode taxpayer = taxForm.get("taxpayer");
        if (taxpayer == null || !taxpayer.isObject()) {
            errors.add("Taxpayer information is missing");
            return;
        }

        validateSSN(taxpayer.get("ssn"), errors, "Taxpayer");
        validateName(taxpayer.get("firstName"), errors, "First name");
        validateName(taxpayer.get("lastName"), errors, "Last name");
        validateAddress(taxpayer.get("address"), errors);

        JsonNode spouse = taxForm.get("spouse");
        if (spouse != null && spouse.isObject()) {
            validateSSN(spouse.get("ssn"), errors, "Spouse");
            validateName(spouse.get("firstName"), errors, "Spouse first name");
            validateName(spouse.get("lastName"), errors, "Spouse last name");
        }
    }

    private void validateSSN(JsonNode ssn, List<String> errors, String context) {
        if (ssn == null || !ssn.isTextual()) {
            errors.add(context + " SSN is missing");
            return;
        }
        String ssnValue = ssn.asText();
        if (!SSN_PATTERN.matcher(ssnValue).matches()) {
            errors.add(context + " SSN format invalid: " + ssnValue);
        }
        if (ssnValue.startsWith("666") || ssnValue.startsWith("900") || ssnValue.startsWith("000")) {
            errors.add(context + " SSN contains invalid area number: " + ssnValue);
        }
    }

    private void validateName(JsonNode name, List<String> errors, String field) {
        if (name == null || !name.isTextual() || name.asText().trim().isEmpty()) {
            errors.add(field + " is missing or empty");
        }
    }

    private void validateAddress(JsonNode address, List<String> errors) {
        if (address == null || !address.isObject()) {
            errors.add("Address information is missing");
            return;
        }
        if (address.get("street") == null || !address.get("street").isTextual()) {
            errors.add("Street address is missing");
        }
        if (address.get("city") == null || !address.get("city").isTextual()) {
            errors.add("City is missing");
        }
        if (address.get("state") == null || !address.get("state").isTextual()) {
            errors.add("State is missing");
        }
        JsonNode zip = address.get("zip");
        if (zip == null || !zip.isTextual() || !ZIP_PATTERN.matcher(zip.asText()).matches()) {
            errors.add("ZIP code is missing or invalid");
        }
    }

    private void validateIncome(JsonNode taxForm, List<String> errors) {
        JsonNode income = taxForm.get("income");
        if (income == null || !income.isObject()) {
            errors.add("Income section is missing");
            return;
        }

        validateAmount(income.get("wages"), errors, "Wages", false);
        validateAmount(income.get("interest"), errors, "Interest", false);
        validateAmount(income.get("dividends"), errors, "Dividends", false);
        validateAmount(income.get("capitalGains"), errors, "Capital gains", true);
        validateAmount(income.get("businessIncome"), errors, "Business income", true);

        double totalIncome = calculateTotal(income, "wages", "interest", "dividends", "capitalGains", "businessIncome");
        JsonNode agi = income.get("adjustedGrossIncome");
        if (agi != null && agi.isNumber()) {
            double agiValue = agi.asDouble();
            if (Math.abs(agiValue - totalIncome) > 0.01) {
                errors.add("AGI mismatch: expected " + totalIncome + ", found " + agiValue);
            }
        }
    }

    private void validateDeductions(JsonNode taxForm, List<String> errors) {
        JsonNode deductions = taxForm.get("deductions");
        if (deductions == null || !deductions.isObject()) {
            return;
        }

        JsonNode filingStatus = taxForm.get("filingStatus");
        String status = filingStatus != null ? filingStatus.asText() : "Single";

        JsonNode standardDeduction = deductions.get("standardDeduction");
        if (standardDeduction != null && standardDeduction.isNumber()) {
            double stdDed = standardDeduction.asDouble();
            double expected = getStandardDeduction(status);
            if (Math.abs(stdDed - expected) > 0.01) {
                errors.add("Standard deduction mismatch for " + status + ": expected " + expected + ", found " + stdDed);
            }
        }

        validateAmount(deductions.get("mortgageInterest"), errors, "Mortgage interest", false);
        validateAmount(deductions.get("charitableContributions"), errors, "Charitable contributions", false);
        validateAmount(deductions.get("stateLocalTaxes"), errors, "State/local taxes", false);

        JsonNode salt = deductions.get("stateLocalTaxes");
        if (salt != null && salt.isNumber() && salt.asDouble() > 10000) {
            errors.add("SALT deduction exceeds $10,000 cap: " + salt.asDouble());
        }
    }

    private void validateCredits(JsonNode taxForm, List<String> errors) {
        JsonNode credits = taxForm.get("credits");
        if (credits == null || !credits.isObject()) {
            return;
        }

        validateAmount(credits.get("childTaxCredit"), errors, "Child tax credit", false);
        validateAmount(credits.get("earnedIncomeCredit"), errors, "Earned income credit", false);
        validateAmount(credits.get("educationCredit"), errors, "Education credit", false);
    }

    private void validateTaxCalculations(JsonNode taxForm, List<String> errors) {
        JsonNode taxSummary = taxForm.get("taxSummary");
        if (taxSummary == null || !taxSummary.isObject()) {
            return;
        }

        validateAmount(taxSummary.get("totalTax"), errors, "Total tax", false);
        validateAmount(taxSummary.get("withholding"), errors, "Withholding", false);
        validateAmount(taxSummary.get("refund"), errors, "Refund", true);
        validateAmount(taxSummary.get("amountOwed"), errors, "Amount owed", false);

        JsonNode refund = taxSummary.get("refund");
        JsonNode owed = taxSummary.get("amountOwed");
        if (refund != null && refund.isNumber() && refund.asDouble() > 0 &&
            owed != null && owed.isNumber() && owed.asDouble() > 0) {
            errors.add("Cannot have both refund and amount owed");
        }
    }

    private void validateAmount(JsonNode amount, List<String> errors, String field, boolean allowNegative) {
        if (amount == null) {
            return;
        }
        if (!amount.isNumber()) {
            errors.add(field + " must be a number");
            return;
        }
        double value = amount.asDouble();
        if (!allowNegative && value < 0) {
            errors.add(field + " cannot be negative: " + value);
        }
    }

    private double calculateTotal(JsonNode object, String... fields) {
        double total = 0.0;
        for (String field : fields) {
            JsonNode node = object.get(field);
            if (node != null && node.isNumber()) {
                total += node.asDouble();
            }
        }
        return total;
    }

    private double getStandardDeduction(String filingStatus) {
        return switch (filingStatus) {
            case "Single" -> 13900.0;
            case "MarriedFilingJointly" -> 27700.0;
            case "MarriedFilingSeparately" -> 13850.0;
            case "HeadOfHousehold" -> 20800.0;
            default -> 13900.0;
        };
    }
}