package com.taxdvs.validation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Youth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class TaxDataValidationService {

    private static final Pattern SSN_PATTERN = Pattern.compile("^\\d{3}-\\d{2}-\\d{4}$");
    private static final Pattern EIN_PATTERN = Pattern.compile("^\\d{2}-\\d{7}$");
    private static final Pattern ZIP_PATTERN = Pattern.compile("^\\d{5}(-\\d{4})?$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\d{3}-\\d{3}-\\d{4}$");
    private static final DateTimeFormatter DATE_FORMATTEl‰Ý DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    // IRS Standard Filing Statuses
    private static final List<String> VALID_FILING_STATUSES = List.of(
        "SINGLE", "MARRIED_JOINT", "MARRIED_SEPARATE", "HEAD_OF_HOUSEHOLD", "QUALIFYING_WIDOW"
    );

    public ValidationResult validateTaxPayload(JsonNode payload) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (payload == null || payload.isNull()) {
            errors.add("Payload cannot be null or empty");
            return new ValidationResult(false, errors, warnings);
        }

        // Validate Taxpayer Information
        validateTaxpayerInfo(errors, warnings, payload.get("taxpayer"));

        // Validate Spouse Information (if applicable)
        if (payload.has("spouse") && !payload.get("spouse").isNull()) {
            validateSpouseInfo(errors, warnings, payload.get("spouse"), payload.get("filingStatus"));
        }

        // Validate Filing Status
        validateFilingStatus(errors, payload.get("filingStatus"), payload.has("spouse"));

        // Validate Income Information
        validateIncome(errors, warnings, payload.get("income"));

        // Validate Deductions
        validateDeductions(errors, warnings, payload.get("deductions"));

        // Validate Dependents
        validateDependents(errors, warnings, payload.get("dependents"));

        // Validate Tax Year
        validateTaxYear(errors, payload.get("taxYear"));

        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    private void validateTaxpayerInfo(List<String> errors, List<String> warnings, JsonNode taxpayer) {
        if (taxpayer == null || taxpayer.isNull()) {
            errors.add("Taxpayer information is required");
            return;
        }

        // Validate SSN
        validateSSN(errors, taxpayer.get("ssn"), "taxpayer");

        // Validate Name
        validateName(errors, taxpayer.get("firstName"), "firstName", "taxpayer");
        validateName(errors, taxpayer.get("lastName"), "lastName", "taxpayer");

        // Validate Date of Birth
        validateDateOfBirth(errors, warnings, taxpayer.get("dateOfBirth"), "taxpayer");

        // Validate Address
        validateAddress(errors, taxpayer.get("address"));
    }

    private void validateSpouseInfo(List<String> errors, List<String> warnings, JsonNode spouse, JsonNode filingStatus) {
        if (spouse == null || spouse.isNull()) {
            return;
        }

        // Validate SSN
        validateSSN(errors, spouse.get("ssn"), "spouse");

        // Validate Name
        validateName(errors, spouse.get("firstName"), "firstName", "spouse");
        validateName(errors, spouse.get("lastName"), "lastName", "spouse");

        // Validate Date of Birth
        validateDateOfBirth(errors, warnings, spouse.get("dateOfBirth"), "spouse");

        // Validate spouse is only provided for married filing statuses
        if (filingStatus != null && !filingStatus.isNull() && filingStatus.isTextual()) {
            String status = filingStatus.asText();
            if (!"MARRIED_JOINT".equals(status) && !"MARRIED_SEPARATE".equals(status)) {
                errors.add("Spouse information should only be provided for married filing statuses");
            }
        }
    }

    private void validateSSN(List<String> errors, JsonNode ssnNode, String context) {
        if (ssnNode == null || ssnNode.isNull() || !ssnNode.isTextual()) {
            errors.add(context + " SSN is required");
            return;
        }

        String ssn = ssnNode.asText().trim();

        // Validate format (3 digits - 2 digits - 4 digits)
        if (!SSN_PATTERN.matcher(ssn).matches()) {
            errors.add(context + " SSN must be in format XXX-XX-XXXX");
            return;
        }

        // IRS Validation: First part cannot be 000, 666, or 900-999
        String firstPart = ssn.substring(0, 3);
        if ("000".equals(firstPart) || "666".equals(firstPart)) {
            errors.add(context + " SSN is invalid (first part cannot be 000 or 666)");
        }

        int firstNum = Integer.parseInt(firstPart);
        if (firstNum >= 900 && firstNum <= 999) {
            errors.add(context + " SSN is invalid (first part cannot be between 900-999)");
        }

        // Second part cannot be 00
        String secondPart = ssn.substring(4, 6);
        if ("00".equals(secondPart)) {
            errors.add(context + " SSN is invalid (second part cannot be 00)");
        }

        // Third part cannot be 0000
        String thirdPart = ssn.substring(7, 11);
        if ("0000".equals(thirdPart)) {
            errors.add(context + " SSN is invalid (third part cannot be 0000)");
        }
    }

    private void validateName(List<String> errors, JsonNode nameNode, String field, String context) {
        if (nameNode == null || nameNode.isNull() || !nameNode.isTextual()) {
            errors.add(context + " " + field + " is required");
            return;
        }

        String name = nameNode.asText().trim();
        if (name.isEmpty()) {
            errors.add(context + " " + field + " cannot be empty");
            return;
        }

        // Name must be between 1 and 50 characters
        if (name.length() > 50) {
            errors.add(context + " " + field + " must be 50 characters or less");
        }
    }

    private void validateDateOfBirth(List<String> errors, List<String> warnings, JsonNode dobNode, String context) {
        if (dobNode == null || dobNode.isNull() || !dobNode.isTextual()) {
            errors.add(context + " dateOfBirth is required");
            return;
        }

        String dobStr = dobNode.asText().trim();
        LocalDate dob;

        try {
            dob = LocalDate.parse(dobStr, DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            errors.add(context + " dateOfBirth must be in format YYYY-MM-DD");
            return;
        }

        // Date of birth cannot be in the future
        if (dob.isAfter(LocalDate.now())) {
            errors.add(context + " dateOfBirth cannot be in the future");
        }

        // Warn if taxpayer is over 120 years old
        int age = LocalDate.now().getYear() - dob.getYear();
        if (age > 120) {
            warnings.add(context + " age exceeds 120 years, please verify");
        }
    }

    private void validateAddress(List<String> errors, JsonNode address) {
        if (address == null || address.isNull()) {
            errors.add("Taxpayer address is required");
            return;
        }

        // Validate street address
        JsonNode street = address.get("street");
        if (street == null || street.isNull() || !street.isTextual() || street.asText().trim().isEmpty()) {
            errors.add("Taxpayer address street is required");
        }

        // Validate city
        JsonNode city = address.get("city");
        if (city == null || city.isNull() || !city.isTextual() || city.asText().trim().isEmpty()) {
            errors.add("Taxpayer address city is required");
        }

        // Validate state
        JsonNode state = address.get("state");
        if (state == null || state.isNull() || !state.isTextual()) {
            errors.add("Taxpayer address state is required");
        } else {
            String stateCode = state.asText().trim();
            // Validate state is 2 letter code
            if (stateCode.length() != 2) {
                errors.add("Taxpayer address state must be a 2-letter code");
            }
        }

        // Validate zip code
        JsonNode zip = address.get("zip");
        if (zip == null || zip.isNull() || !zip.isTextual()) {
            errors.add("Taxpayer address zip code is required");
        } else {
            String zipCode = zip.asText().trim();
            if (!ZIP_PATTERN.matcher(zipCode).matches()) {
                errors.add("Taxpayer address zip code must be in format XXXXX or XXXXX-XXXX");
            }
        }
    }

    private void validateFilingStatus(List<String> errors, JsonNode filingStatus, boolean hasSpouse) {
        if (filingStatus == null || filingStatus.isNull() || !filingStatus.isTextual()) {
            errors.add("Filing status is required");
            return;
        }

        String status = filingStatus.asText().trim();
        if (!VALID_FILING_STATUSES.contains(status)) {
            errors.add("Invalid filing status. Must be one of: " + VALID_FILING_STATUSES);
        }

        // Validate spouse information is provided for married filing joint
        if ("MARRIED_JOINT".equals(status) && !hasSpouse) {
            errors.add("Spouse information is required for Married Filing Jointly status");
        }
    }

    private void validateIncome(List<String> errors, List<String> warnings, JsonNode income) {
        if (income == null || income.isNull()) {
            errors.add("Income information is required");
            return;
        }

        // Validate wages
        validateAmount(errors, warnings, income.get("wages"), "wages", true);

        // Validate interest
        validateAmount(errors, warnings, income.get("interest"), "interest", false);

        // Validate dividends
        validateAmount(errors, warnings, income.get("dividends"), "dividends", false);

        // Validate capital gains
        validateAmount(errors, warnings, income.get("capitalGains"), "capitalGains", false);

        // Validate business income
        validateAmount(errors, warnings, income.get("businessIncome"), "businessIncome", false);

        // Validate retirement income
        validateAmount(errors, warnings, income.get("retirementIncome"), "retirementIncome", false);

        // Validate other income
        validateAmount(errors, warnings, income.get("otherIncome"), "otherIncome", false);
    }

    private void validateDeductions(List<String> errors, List<String> warnings, JsonNode deductions) {
        if (deductions == null || deductions.isNull()) {
            return; // Deductions are optional
        }

        // Validate mortgage interest
        validateAmount(errors, warnings, deductions.get("mortgageInterest"), "mortgageInterest", false);

        // Validate state and local taxes
        validateAmount(errors, warnings, deductions.get("stateLocalTaxes"), "stateLocalTaxes", false);

        // Validate charitable contributions
        validateAmount(errors, warnings, deductions.get("charitableContributions"), "charitableContributions", false);

        // Validate medical expenses
        validateAmount(errors, warnings, deductions.get("medicalExpenses"), "medicalExpenses", false);

        // Validate other deductions
        validateAmount(errors, warnings, deductions.get("otherDeductions"), "otherDeductions", false);
    }

    private void validateDependents(List<String> errors, List<String> warnings, JsonNode dependents) {
        if (dependents == null || dependents.isNull() || !dependents.isArray()) {
            return; // Dependents are optional
        }

        if (dependents.size() > 20) {
            warnings.add("Number of dependents exceeds 20, please verify");
        }

        for (int i = 0; i < dependents.size(); i++) {
            JsonNode dependent = dependents.get(i);
            String context = "Dependent #" + (i + 1);

            // Validate SSN
            validateSSN(errors, dependent.get("ssn"), context);

            // Validate name
            validateName(errors, dependent.get("firstName"), "firstName", context);
            validateName(errors, dependent.get("lastName"), "lastName", context);

            // Validate date of birth
            validateDateOfBirth(errors, warnings, dependent.get("dateOfBirth"), context);

            // Validate relationship
            JsonNode relationship = dependent.get("relationship");
            if (relationship == null || relationship.isNull() || !relationship.isTextual() || relationship.asText().trim().isEmpty()) {
                errors.add(context + " relationship is required");
            }
        }
    }

    private void validateAmount(List<String> errors, List<String> warnings, JsonNode amountNode, String fieldName, boolean required) {
        if (amountNode == null || amountNode.isNull()) {
            if (required) {
                errors.add(fieldName + " is required");
            }
            return;
        }

        if (!amountNode.isNumber()) {
            errors.add(fieldName + " must be a number");
            return;
        }

        double amount = amountNode.asDouble();

        // Amount cannot be negative (except for capital gains which can be losses)
        if (amount < 0 && !"capitalGains".equals(fieldName) && !"businessIncome".equals(fieldName)) {
            errors.add(fieldName + " cannot be negative");
        }

        // Warn if amount is exceptionally large
        if (amount > 10_000_000) {
            warnings.add(fieldName + " exceeds $10 million, please verify");
        }

        // Validate decimal places (max 2 for currency)
        String amountStr = String.valueOf(amount);
        if (amountStr.contains(".")) {
            int decimalPlaces = amountStr.length() - amountStr.indexOf(".") - 1;
            if (decimalPlaces > 2) {
                errors.add(fieldName + " cannot have more than 2 decimal places");
            }
        }
    }

    private void validateTaxYear(List<String> errors, JsonNode taxYearNode) {
        if (taxYearNode == null || taxYearNode.isNull()) {
            errors.add("Tax year is required");
            return;
        }

        if (!taxYearNode.isInt()) {
            errors.add("Tax year must be a valid year");
            return;
        }

        int taxYear = taxYearNode.asInt();
        int currentYear = LocalDate.now().getYear();

        // Tax year must be within reasonable range
        if (taxYear < 1950) {
            errors.add("Tax year cannot be before 1950");
        }

        // Tax year cannot be in the future
        if (taxYear > currentYear) {
            errors.add("Tax year cannot be in the future");
        }

        // Tax year should not be more than 7 years in the past (IRS audit limit)
        if (taxYear < currentYear - 7) {
            errors.add("Tax year is more than 7 years old, amendment may not be possible");
        }
    }

    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;
        private final List<String> warnings;

        public ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
            this.valid = valid;
            this.errors = errors;
            this.warnings = warnings;
        }

        public boolean isValid() {
            return valid;
        }

        public List<String> getErrors() {
            return errors;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }
}