package com.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Service
@Slf4j
public class TaxFormValidator {

    public Map<String, Object> validateTaxForm(JsonNode payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (payload == null || payload.isNull()) {
            errors.add("Payload is null or empty");
            result.put("valid", false);
            result.put("errors", errors);
            return result;
        }

        // Validate required fields
        validateRequiredFields(payload, errors);

        // Validate data types
        validateDataTypes(payload, errors);

        // Validate IRS specific rules
        validateIRSRules(payload, errors, warnings);

        // Validate cross-field dependencies
        validateCrossFieldDependencies(payload, errors);

        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        result.put("warnings", warnings);
        result.put("timestamp", new Date());

        return result;
    }

    private void validateRequiredFields(JsonNode payload, List<String> errors) {
        String[] requiredFields = {"formType", "taxYear", "taxpayerIdentification"};

        for (String field : requiredFields) {
            if (!payload.has(field) || payload.get(field).isNull()) {
                errors.add("Required field missing: " + field);
            }
        }
    }

    private void validateDataTypes(JsonNode payload, List<String> errors) {
        if (payload.has("taxYear") && !payload.get("taxYear").isInt()) {
            errors.add("Invalid data type for taxYear: must be integer");
        }

        if (payload.has("income") && !payload.get("income").isNumber()) {
            errors.add("Invalid data type for income: must be number");
        }

        if (payload.has("filingStatus") && !payload.get("filingStatus").isTextual()) {
            errors.add("Invalid data type for filingStatus: must be string");
        }
    }

    private void validateIRSRules(JsonNode payload, List<String> errors, List<String> warnings) {
        // Validate tax Year
        if (payload.has("taxYear")) {
            int taxYear = payload.get("taxYear").asInt();
            if (taxYear < 1913 || taxYear > 2025) {
                errors.add("Tax year out of valid range: " + taxYear);
            }
        }

        // Validate SSN/EIN format
        if (payload.has("taxpayerIdentification")) {
            JsonNode taxpayerId = payload.get("taxpayerIdentification");
            if (taxpayerId.has("ssn")) {
                String ssn = taxpayerId.get("ssn").asText();
                if (!ssn.matches("^\\d{3}-\\d{2}-\\d{4}$")) {
                    errors.add("Invalid SSN format: must be XXX-XX-XXXX");
                }
            }
        }

        // Validate filing status
        if (payload.has("filingStatus")) {
            String status = payload.get("filingStatus").asText();
            Set<String> validStatuses = Set.of("SINGLE", "MARRIED_JOINT", "MARRIED_SEPARATE", "HEAD_OF_HOUSEHOLD", "QUALIFYING_WIDOWER");
            if (!validStatuses.contains(status)) {
                errors.add("Invalid filing status: " + status);
            }
        }

        // Validate income ranges
        if (payload.has("income")) {
            double income = payload.get("income").asDouble();
            if (income < 0) {
                errors.add("Income cannot be negative");
            }
            if (income > 1000000000) {
                warnings.add("Unusually high income reported: " + income);
            }
        }

        // Validate deductions
        if (payload.has("deductions")) {
            double deductions = payload.get("deductions").asDouble();
            if (deductions < 0) {
                errors.add("Deductions cannot be negative");
            }
        }
    }

    private void validateCrossFieldDependencies(JsonNode payload, List<String> errors) {
        // Validate deductions do not exceed income
        if (payload.has("income") && payload.has("deductions")) {
            double income = payload.get("income").asDouble();
            double deductions = payload.get("deductions").asDouble();
            if (deductions > income) {
                errors.add("Deductions cannot exceed income");
            }
        }

        // Validate dependents for Head of Household
        if (payload.has("filingStatus") && "HEAD_OF_HOUSEHOLD".equals(payload.get("filingStatus").asText())) {
            if (!payload.has("dependents") || payload.get("dependents").size() == 0) {
                errors.add("Head of Household requires at least one dependent");
            }
        }
    }
}