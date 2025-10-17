package com.irs.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
@Component
public class TaxFormValidator {

    public List<String> validateTaxForm(JsonNode taxForm) {
        List<String> errors = new ArrayList<>();

        if (taxForm == null || taxForm.isNull()) {
            errors.add("Tax form payload is null or empty");
            return errors;
        }

        validateTaxpayerIdentification(taxForm, errors);
        validateFilingStatus(taxForm, errors);
        validateIncomeSections(taxForm, errors);
        validateDeductions(taxForm, errors);
        validateCredits(taxForm, errors);
        validateTaxCalculations(taxForm, errors);
        validateDependents(taxForm, errors);

        return errors;
    }

    private void validateTaxpayerIdentification(JsonNode taxForm, List<String> errors) {
        JsonNode ssn = taxForm.get("ssn");
        if (ssn == null || ssn.isNull() || ssn.asText().isBlank()) {
            errors.add("SSN is required");
        } else {
            String ssnValue = ssn.asText().replaceAll("[^\\d]", "");
            if (ssnValue.length() != 9) {
                errors.add("SSN must be 9 digits");
            }
            if (ssnValue.startsWith("9") && ssnValue.matches("^9\\d{8}$")) {
                errors.add("Invalid SSN format: cannot start with 9");
            }
        }

        JsonNode name = taxForm.get("name");
        if (name == null || name.isNull() || name.asText().isBlank()) {
            errors.add("Taxpayer name is required");
        }
    }

    private void validateFilingStatus(JsonNode taxForm, List<String> errors) {
        JsonNode filingStatus = taxForm.get("filingStatus");
        Set<String> validStatuses = Set.of("SINGLE", "MARRIED_JOINT", "MARRIED_SEPARATE", "HEAD_OF_HOUSEHOLD", "QUALIFYING_WIDOW");

        if (filingStatus == null || filingStatus.isNull()) {
            errors.add("Filing status is required");
        } else if (!validStatuses.contains(filingStatus.asText().toUpperCase())) {
            errors.add("Invalid filing status. Must be one of: " + validStatuses);
        }
    }

    private void validateIncomeSections(JsonNode taxForm, List<String> errors) {
        JsonNode income = taxForm.get("income");
        if (income == null || income.isNull()) {
            errors.add("Income section is required");
            return;
        }

        validateAmountField(income, "wages", errors, false);
        validateAmountField(income, "interest", errors, false);
        validateAmountField(income, "dividends", errors, false);
        validateAmountField(income, "businessIncome", errors, false);
        validateAmountField(income, "capitalGains", errors, false);

        JsonNode totalIncome = income.get("totalIncome");
        if (totalIncome == null || totalIncome.isNull()) {
            errors.add("Total income is required");
        }
    }

    private void validateDeductions(JsonNode taxForm, List<String> errors) {
        JsonNode deductions = taxForm.get("deductions");
        if (deductions == null || deductions.isNull()) {
            return;
        }

        JsonNode deductionType = deductions.get("type");
        if (deductionType != null && !deductionType.isNull()) {
            String type = deductionType.asText().toUpperCase();
            if (!type.equals("STANDARD") && !type.equals("ITEMIZED")) {
                errors.add("Deduction type must be STANDARD or ITEMIZED");
            }
        }

        validateAmountField(deductions, "amount", errors, true);
    }

    private void validateCredits(JsonNode taxForm, List<String> errors) {
        JsonNode credits = taxForm.get("credits");
        if (credits == null || credits.isNull()) {
            return;
        }

        validateAmountField(credits, "childTaxCredit", errors, false);
        validateAmountField(credits, "earnedIncomeCredit", errors, false);
        validateAmountField(credits, "educationCredit", errors, false);
    }

    private void validateTaxCalculations(JsonNode taxForm, List<String> errors) {
        JsonNode taxCalc = taxForm.get("taxCalculation");
        if (taxCalc == null || taxCalc.isNull()) {
            errors.add("Tax calculation section is required");
            return;
        }

        validateAmountField(taxCalc, "taxableIncome", errors, true);
        validateAmountField(taxCalc, "totalTax", errors, true);
        validateAmountField(taxCalc, "withholding", errors, false);
        validateAmountField(taxCalc, "refundOrAmountDue", errors, false);

        JsonNode taxableIncome = taxCalc.get("taxableIncome");
        if (taxableIncome != null && !taxableIncome.isNull() && taxableIncome.asDouble() < 0) {
            errors.add("Taxable income cannot be negative");
        }
    }

    private void validateDependents(JsonNode taxForm, List<String> errors) {
        JsonNode dependents = taxForm.get("dependents");
        if (dependents == null || !dependents.isArray()) {
            return;
        }

        for (int i = 0; i < dependents.size(); i++) {
            JsonNode dependent = dependents.get(i);
            JsonNode depSsn = dependent.get("ssn");
            if (depSsn == null || depSsn.isNull() || depSsn.asText().isBlank()) {
                errors.add("Dependent #" + (i + 1) + " SSN is required");
            }

            JsonNode depName = dependent.get("name");
            if (depName == null || depName.isNull() || depName.asText().isBlank()) {
                errors.add("Dependent #" + (i + 1) + " name is required");
            }

            JsonNode relationship = dependent.get("relationship");
            if (relationship == null || relationship.isNull() || relationship.asText().isBlank()) {
                errors.add("Dependent #" + (i + 1) + " relationship is required");
            }
        }
    }

    private void validateAmountField(JsonNode parent, String fieldName, List<String> errors, boolean required) {
        JsonNode field = parent.get(fieldName);
        if (field == null || field.isNull()) {
            if (required) {
                errors.add(fieldName + " is required");
            }
            return;
        }

        if (!field.isNumber()) {
            errors.add(fieldName + " must be a numeric value");
        }
    }
}