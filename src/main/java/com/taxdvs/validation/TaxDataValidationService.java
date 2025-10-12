package com.taxdvs.validation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class TaxDataValidationService {

    private static final Pattern SSN_PATTERN = Pattern.compile("^\\d{3}-\\d{2}-\\d{4}$");
    private static final Pattern EIN_PATTERN = Pattern.compile("^\\d{2}-\\d{7}$");
    private static final Pattern ZIP_PATTERN = Pattern.compile("^\\d{5}(-\\d{4})?$");
    private static final Set<String> VALID_STATES = Set.of(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
        "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
        "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
        "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
        "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY",
        "DC"
    );
    private static final Set<String> VALID_FILING_STATUS = Set.of(
        "SINGLE", "MARRIED_JOINT", "MARRIED_SEPARATE", "HEAD_OF_HOUSEHOLD", "QUALIFYING_WIDOW"
    );
    private static final Set<String> VALID_INCOME_TYPES = Set.of(
        "WAGES", "INTEREST", "DIVIDENDS", "CAPITAL_GAINS", "BUSINESS_INCOME",
        "RENTAL_INCOME", "ROYALTIES", "FARM_INCOME", "UNEMPLOYMENT",
        "SOCIAL_SECURITY", "PENSION", "OTHER_INCOME"
    );
    private static final Set<String> VALID_DEDUCTION_TYPES = Set.of(
        "STANDARD", "ITEMIZED"
    );
    private static final Map<Integer, BigDecimal> STANDARD_DEDUCTION_2023 = Map.of(
        0, new BigDecimal("13850"), // SINGLE
        1, new BigDecimal("27700"), // MARRIED_JOINT
        2, new BigDecimal("13850"), // MARRIED_SEPARATE
        3, new BigDecimal("20800"), // HEAD_OF_HOUSEHOLD
        4, new BigDecimal("27700")  // QUALIFYING_WIDOW
    );

    public Map<String, Object> validateTaxData(JsonNode payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Validate required fields
        if (!payload.has("taxpayer")) {
            errors.add("Missing required field: taxpayer");
        } else {
            validateTaxpayer(payload.get("taxpayer"), errors, warnings);
        }

        if (!payload.has("taxYear")) {
            errors.add("Missing required field: taxYear");
        } else {
            validateTaxYear(payload.get("taxYear"), errors);
        }

        if (!payload.has("filingStatus")) {
            errors.add("Missing required field: filingStatus");
        } else {
            validateFilingStatus(payload.get("filingStatus"), errors);
        }

        // Validate income
        if (payload.has("income")) {
            validateIncome(payload.get("income"), errors, warnings);
        }

        // Validate deductions
        if (payload.has("deductions")) {
            validateDeductions(payload.get("deductions"), payload.get("filingStatus").textValue(), errors, warnings);
        }

        // Validate credits
        if (payload.has("credits")) {
            validateCredits(payload.get("credits"), errors, warnings);
        }

        // Validate dependents
        if (payload.has("dependents")) {
            validateDependents(payload.get("dependents"), errors, warnings);
        }

        // Validate withholdings
        if (payload.has("withholdings")) {
            validateWithholdings(payload.get("withholdings"), errors, warnings);
        }

        // Cross-field validations
        performCrossFieldValidations(payload, errors, warnings);

        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        result.put("warnings", warnings);

        return result;
    }

    private void validateTaxpayer(JsonNode taxpayer, List<String> errors, List<String> warnings) {
        // Validate SSN or EIN
        if (!taxpayer.has("ssn") && !taxpayer.has("ein")) {
            errors.add("Taxpayer must have either SSN or EIN");
        }

        if (taxpayer.has("ssn")) {
            String ssn = taxpayer.get("ssn").textValue();
            if (!SSN_PATTERN.matcher(ssn).matches()) {
                errors.add("Invalid SSN format. Must be XXX-XX-XXXX");
            }
            // IRS rule: SSN cannot be all zeros or have all zeros in any group
            if (ssn.equals("000-00-0000") || ssn.startsWith("000-") || ssn.contains("-00-") || ssn.endsWith("-0000")) {
                errors.add("Invalid SSN: Cannot contain all zeros in any group");
            }
            // IRS rule: SSN cannot start with 999
            if (ssn.startsWith("999")) {
                errors.add("Invalid SSN: Cannot start with 999");
            }
        }

        if (taxpayer.has("ein")) {
            String ein = taxpayer.get("ein").textValue();
            if (!EIN_PATTERN.matcher(ein).matches()) {
                errors.add("Invalid EIN format. Must be XX-XXXXXXX");
            }
        }

        // Validate name
        if (!taxpayer.has("firstName") || taxpayer.get("firstName").textValue().isBlank()) {
            errors.add("Taxpayer first name is required");
        }
        if (!taxpayer.has("lastName") || taxpayer.get("lastName").textValue().isBlank()) {
            errors.add("Taxpayer last name is required");
        }

        // Validate address
        if (taxpayer.has("address")) {
            JsonNode address = taxpayer.get("address");
            if (!address.has("street") || address.get("street").textValue().isBlank()) {
                errors.add("Street address is required");
            }
            if (!address.has("city") || address.get("city").textValue().isBlank()) {
                errors.add("City is required");
            }
            if (!address.has("state") || !VALID_STATES.contains(address.get("state").textValue())) {
                errors.add("Invalid or missing state code");
            }
            if (address.has("zip")) {
                String zip = address.get("zip").textValue();
                if (!ZIP_PATTERN.matcher(zip).matches()) {
                    errors.add("Invalid ZIP code format. Must be XXXXX or XXXXX-XXXX");
                }
            } else {
                errors.add("ZIP code is required");
            }
        } else {
            errors.add("Taxpayer address is required");
        }

        // Validate date of birth
        if (taxpayer.has("dateOfBirth")) {
            try {
                LocalDate dob = LocalDate.parse(taxpayer.get("dateOfBirth").textValue());
                if (dob.isAfter(LocalDate.now())) {
                    errors.add("Date of birth cannot be in the future");
                }
                if (dob.isBefore(LocalDate.now().minusYears(150))) {
                    errors.add("Date of birth is unreasonably old");
                }
            } catch (Exception e) {
                errors.add("Invalid date of birth format. Use YYYY-MM-DD");
            }
        }
    }

    private void validateTaxYear(JsonNode taxYearNode, List<String> errors) {
        int taxYear = taxYearNode.asInt();
        int currentYear = Year.now().getValue();
        
        if (taxYear < 1913) { // First year of modern income tax
            errors.add("Tax year cannot be before 1913");
        }
        if (taxYear > currentYear) {
            errors.add("Tax year cannot be in the future");
        }
    }

    private void validateFilingStatus(JsonNode filingStatusNode, List<String> errors) {
        String filingStatus = filingStatusNode.textValue();
        if (!VALID_FILING_STATUS.contains(filingStatus)) {
            errors.add("Invalid filing status. Must be one of: " + VALID_FILING_STATUS);
        }
    }

    private void validateIncome(JsonNode income, List<String> errors, List<String> warnings) {
        if (!income.isArray()) {
            errors.add("Income must be an array");
            return;
        }

        BigDecimal totalIncome = BigDecimal.ZERO;
        for (JsonNode incomeItem : income) {
            if (!incomeItem.has("type")) {
                errors.add("Income item missing type");
                continue;
            }
            String type = incomeItem.get("type").textValue();
            if (!VALID_INCOME_TYPES.contains(type)) {
                errors.add("Invalid income type: " + type);
            }

            if (!incomeItem.has("amount")) {
                errors.add("Income item missing amount");
                continue;
            }

            BigDecimal amount = new BigDecimal(incomeItem.get("amount").asText());
            if (amount.compareTo(BigDecimal.ZERO) < 0) {
                errors.add("Income amount cannot be negative for type: " + type);
            }

            // IRS rule: Warn if income exceeds reasonable thresholds
            if (amount.compareTo(new BigDecimal("100000000")) > 0) {
                warnings.add("Unusually high income amount for type: " + type + ". Please verify.");
            }

            totalIncome = totalIncome.add(amount);

            // Validate specific income type rules
            if (type.equals("CAPITAL_GAINS") && incomeItem.has("holdingPeriod")) {
                String holdingPeriod = incomeItem.get("holdingPeriod").textValue();
                if (!holdingPeriod.equals("SHORT_TERM") && !holdingPeriod.equals("LONG_TERM")) {
                    errors.add("Invalid capital gains holding period. Must be SHORT_TERM OR LONG_TERM");
                }
            }
        }

        if (totalIncome.compareTo(BigDecimal.ZERO) == 0) {
            warnings.add("Total income is zero. Please verify.");
        }
    }

    private void validateDeductions(JsonNode deductions, String filingStatus, List<String> errors, List<String> warnings) {
        if (!deductions.has("type")) {
            errors.add("Deduction type is required");
            return;
        }

        String deductionType = deductions.get("type").textValue();
        if (!VALID_DEDUCTION_TYPES.contains(deductionType)) {
            errors.add("Invalid deduction type. Must be STANDARD or ITEMIZED");
            return;
        }

        if (deductionType.equals("STANDARD")) {
            if (deductions.has("amount")) {
                BigDecimal amount = new BigDecimal(deductions.get("amount").asText());
                BigDecimal expected = getStandardDeduction(filingStatus);
                if (expected != null && amount.compareTo(expected) != 0) {
                    warnings.add("Standard deduction amount does not match expected value for filing status: " + filingStatus);
                }
            }
        } else if (deductionType.equals("ITEMIZED")) {
            if (!deductions.has("items") || !deductions.get("items").isArray()) {
                errors.add("Itemized deductions must include an array of items");
                return;
            }

            BigDecimal totalItemized = BigDecimal.ZERO;
            for (JsonNode item : deductions.get("items")) {
                if (!item.has("category") || !item.has("amount")) {
                    errors.add("Each itemized deduction must have category and amount");
                    continue;
                }
                BigDecimal amount = new BigDecimal(item.get("amount").asText());
                if (amount.compareTo(BigDecimal.ZERO) < 0) {
                    errors.add("Deduction amount cannot be negative");
                }
                totalItemized = totalItemized.add(amount);
            }

            // IRS rule: Warn if itemized deductions are less than standard deduction
            BigDecimal standardDeduction = getStandardDeduction(filingStatus);
            if (standardDeduction != null && totalItemized.compareTo(standardDeduction) < 0) {
                warnings.add("Itemized deductions are less than standard deduction. Consider using standard deduction.");
            }
        }
    }

    private void validateCredits(JsonNode credits, List<String> errors, List<String> warnings) {
        if (!credits.isArray()) {
            errors.add("Credits must be an array");
            return;
        }

        for (JsonNode credit : credits) {
            if (!credit.has("type") || !credit.has("amount")) {
                errors.add("Each credit must have type and amount");
                continue;
            }

            BigDecimal amount = new BigDecimal(credit.get("amount").asText());
            if (amount.compareTo(BigDecimal.ZERO) < 0) {
                errors.add("Credit amount cannot be negative");
            }

            String creditType = credit.get("type").textValue();
            // Validate specific credit types
            if (creditType.equals("CHILD_TAX_CREDIT")) {
                // IRS rule: Child Tax Credit is $2,000 per child for 2023
                if (amount.remainder(new BigDecimal("2000")).compareTo(BigDecimal.ZERO) != 0) {
                    warnings.add("Child Tax Credit should be a multiple of $2,000");
                }
            } else if (creditType.equals("EARNED_INCOME_CREDIT")) {
                // IRS rule: EIC requires earned income
                if (!credit.has("earnedIncome")) {
                    warnings.add("Earned Income Credit requires earned income information");
                }
            }
        }
    }

    private void validateDependents(JsonNode dependents, List<String> errors, List<String> warnings) {
        if (!dependents.isArray()) {
            errors.add("Dependents must be an array");
            return;
        }

        Set<String> ssns = new HashSet<>();
        for (JsonNode dependent : dependents) {
            if (!dependent.has("ssn")) {
                errors.add("Dependent must have SSN");
                continue;
            }

            String ssn = dependent.get("ssn").textValue();
            if (!SSN_PATTERN.matcher(ssn).matches()) {
                errors.add("Invalid dependent SSN format"));
            }

            // IRS rule: Each dependent SSN must be unique
            if (ssns.contains(ssn)) {
                errors.add("Duplicate dependent SSN: " + ssn);
            }
            ssns.add(ssn);

            if (!dependent.has("firstName") || !dependent.has("lastName")) {
                errors.add("Dependent must have first and last name");
            }

            if (!dependent.has("relationship")) {
                errors.add("Dependent must have relationship");
            }

            if (dependent.has("dateOfBirth")) {
                try {
                    LocalDate dob = LocalDate.parse(dependent.get("dateOfBirth").textValue());
                    // IRS rule: Dependent must be under 19 or under 24 if full-time student
                    int age = LocalDate.now().getYear() - dob.getYear();
                    if (age >= 19) {
                        warnings.add("Dependent is 19 or older. Verify eligibility (full-time student under 24 or permanently disabled)");
                    }
                } catch (Exception e) {
                    errors.add("Invalid dependent date of birth format");
                }
            }
        }
    }

    private void validateWithholdings(JsonNode withholdings, List<String> errors, List<String> warnings) {
        if (!withholdings.isArray()) {
            errors.add("Withholdings must be an array");
            return;
        }

        for (JsonNode withholding : withholdings) {
            if (!withholding.has("type") || !withholding.has("amount")) {
                errors.add("Each withholding must have type and amount");
                continue;
            }

            BigDecimal amount = new BigDecimal(withholding.get("amount").asText());
            if (amount.compareTo(BigDecimal.ZERO) < 0) {
                errors.add("Withholding amount cannot be negative");
            }
        }
    }

    private void performCrossFieldValidations(JsonNode payload, List<String> errors, List<String> warnings) {
        // Validate filing status vs. spouse information
        if (payload.has("filingStatus")) {
            String filingStatus = payload.get("filingStatus").textValue();
            boolean hasSpouse = payload.has("spouse");

            if ((filingStatus.equals("MARRIED_JOINT") || filingStatus.equals("MARRIED_SEPARATE")) && !hasSpouse) {
                errors.add("Married filing status requires spouse information");
            }

            if ((filingStatus.equals("SINGLE") || filingStatus.equals("HEAD_OF_HOUSEHOLD")) && hasSpouse) {
                warnings.add("Spouse information provided but filing status is " + filingStatus);
            }
        }

        // Validate income vs. withholdings
        if (payload.has("income") && payload.has("withholdings")) {
            BigDecimal totalIncome = BigDecimal.ZERO;
            for (JsonNode incomeItem : payload.get("income")) {
                if (incomeItem.has("amount")) {
                    totalIncome = totalIncome.add(new BigDecimal(incomeItem.get("amount").asText()));
                }
            }

            BigDecimal totalWithholdings = BigDecimal.ZERO;
            for (JsonNode withholding : payload.get("withholdings")) {
                if (withholding.has("amount")) {
                    totalWithholdings = totalWithholdings.add(new BigDecimal(withholding.get("amount").asText()));
                }
            }

            // IRS rule: Warn if withholdings exceed income
            if (totalWithholdings.compareTo(totalIncome) > 0) {
                warnings.add("Total withholdings exceed total income. Please verify.");
            }
        }

        // Validate Head of Household requires dependents
        if (payload.has("filingStatus") && payload.get("filingStatus").textValue().equals("HEAD_OF_HOUSEHOLD")) {
            if (!payload.has("dependents") || payload.get("dependents").size() == 0) {
                errors.add("Head of Household filing status requires at least one dependent");
            }
        }
    }

    private BigDecimal getStandardDeduction(String filingStatus) {
        return switch (filingStatus) {
            case "SINGLE" -> STANDARD_DEDUCTION_2023.get(0);
            case "MARRIED_JOINT" -> STANDARD_DEDUCTION_2023.get(1);
            case "MARRIED_SEPARATE" -> STANDARD_DEEUCTOON_2023.get(2);
            case "HEAD_OF_HOUSEHOLD" -> STANDARD_DEEUCTOON_2023.get(3);
            case "QUALIFYING_WIDOW" -> STANDARD_DEDUCTION_2023.get(4);
            default -> null;
        };
    }
}