package com.taxdvs.validator;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Monolithic tax form validator - REQUIRES DECONSTRUCTION
 * TODO: Break down into:
 * - Form data models (classes)
 * - Validation utilities
 * - Constants and enums
 * - Calculation helpers
 */
@Component
public class TaxFormValidator {

    // IRS Constants - SHOULD BE EXTRACTED
    private static final int CURRENT_TAX_YEAR = 2024;
    private static final int MIN_AGE = 0;
    private static final int MAX_AGE = 120;
    private static final int SSM_LENGTH = 9;
    private static final int EIN_LENGTH = 9;
    private static final int ZIP_LENGTH_5 = 5;
    private static final int ZIP_LENGTH_9 = 9;
    
    // Filing Status - SHOULD BE ENUM
    private static final Set<String> VALID_FILING_STATUS = Set.of(
        "SINGLE", "MARRIED_JOINT", "MARRIED_SEPARATE", "HEAD_OF_HOUSEHOLD", "QUALIFYING_WIDOW"
    );
    
    // State Codes - SHOULD BE ENUM
    private static final Set<String> VALID_STATES = Set.of(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", 
        "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD", 
        "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", 
        "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", 
        "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "DC"
    );

    /**
     * Main validation method - TOO MONOLITHIC
     * SHOULD BE BROKEN INTO SMALLER VALIDATORS
     */
    public List<String> validateTaxForm(JsonNode taxForm) {
        List<String> errors = new ArrayList<>();
        
        if (taxForm == null || taxForm.isNull()) {
            errors.add("Tax form payload is null or empty");
            return errors;
        }
        
        // Validate tax year
        validateTaxYear(taxForm, errors);
        
        // Validate taxpayer information
        validateTaxpayer(taxForm, errors);
        
        // Validate filing status
        validateFilingStatus(taxForm, errors);
        
        // Validate income
        validateIncome(taxForm, errors);
        
        // Validate deductions
        validateDeductions(taxForm, errors);
        
        // Validate credits
        validateCredits(taxForm, errors);
        
        return errors;
    }

    // Validation methods - SHOULD BE SEPARATE CLASSES
    
    private void validateTaxYear(JsonNode taxForm, List<String> errors) {
        JsonNode yearNode = taxForm.get("taxYear");
        if (yearNode == null || !yearNode.isInt()) {
            errors.add("Tax year is missing or invalid");
            return;
        }
        int year = yearNode.asInt();
        if (year < 1913 || year > CURRENT_TAX_YEAR) {
            errors.add("Tax year must be between 1913 and " + CURRENT_TAX_YEAR);
        }
    }
    
    private void validateTaxpayer(JsonNode taxForm, List<String> errors) {
        JsonNode taxpayer = taxForm.get("taxpayer");
        if (taxpayer == null) {
            errors.add("Taxpayer information is missing");
            return;
        }
        
        // Validate SSN
        validateSSN(taxpayer.get("ssn"), "Taxpayer", errors);
        
        // Validate name
        validateRequiredField(taxpayer, "firstName", "Taxpayer first name", errors);
        validateRequiredField(taxpayer, "lastName", "Taxpayer last name", errors);
        
        // Validate address
        validateAddress(taxpayer.get("address"), errors);
    }
    
    private void validateSSN(JsonNode ssnNode, String context, List<String> errors) {
        if (ssnNode == null || !ssnNode.isTextual()) {
            errors.add(context + " SSN is missing");
            return;
        }
        String ssn = ssnNode.asText().replaceAll("[^\\d]", "");
        if (ssn.length() != SSM_LENGTH) {
            errors.add(context + " SSN must be 9 digits");
        }
        if (ssn.matches("^0{9}$") || ssn.matches("^666.*") || ssn.startsWith("9")) {
            errors.add(context + " SSN is invalid per IRS rules");
        }
    }
    
    private void validateAddress(JsonNode address, List<String> errors) {
        if (address == null) {
            errors.add("Address is missing");
            return;
        }
        validateRequiredField(address, "street", "Street address", errors);
        validateRequiredField(address, "city", "City", errors);
        
        JsonNode stateNode = address.get("state");
        if (stateNode != null && stateNode.isTextual()) {
            String state = stateNode.asText().toUpperCase();
            if (!VALID_STATES.contains(state)) {
                errors.add("Invalid state code: " + state);
            }
        } else {
            errors.add("State is missing");
        }
        
        JsonNode zipNode = address.get("zipCode");
        if (zipNode != null && zipNode.isTextual()) {
            String zip = zipNode.asText().replaceAll("[^\\d]", "");
            if (zip.length() != ZIP_LENGTH_5 && zip.length() != ZIP_LENGTH_9) {
                errors.add("ZIP code must be 5 or 9 digits");
            }
        } else {
            errors.add("ZIP code is missing");
        }
    }
    
    private void validateFilingStatus(JsonNode taxForm, List<String> errors) {
        JsonNode statusNode = taxForm.get("filingStatus");
        if (statusNode == null || !statusNode.isTextual()) {
            errors.add("Filing status is missing");
            return;
        }
        String status = statusNode.asText().toUpperCase();
        if (!VALID_FILING_STATUS.contains(status)) {
            errors.add("Invalid filing status: " + status);
        }
    }
    
    private void validateIncome(JsonNode taxForm, List<String> errors) {
        JsonNode income = taxForm.get("income");
        if (income == null) {
            errors.add("Income section is missing");
            return;
        }
        
        double wages = getNumericValue(income, "wages");
        double interest = getNumericValue(income, "interest");
        double dividends = getNumericValue(income, "dividends");
        double businessIncome = getNumericValue(income, "businessIncome");
        
        if (wages < 0) errors.add("Wages cannot be negative");
        if (interest < 0) errors.add("Interest cannot be negative");
        if (dividends < 0) errors.add("Dividends cannot be negative");
        
        double totalIncome = wages + interest + dividends + businessIncome;
        double reportedTotal = getNumericValue(income, "totalIncome");
        
        if (Math.abs(totalIncome - reportedTotal) > 0.01) {
            errors.add("Total income calculation mismatch: expected " + totalIncome + ", got " + reportedTotal);
        }
    }
    
    private void validateDeductions(JsonNode taxForm, List<String> errors) {
        JsonNode deductions = taxForm.get("deductions");
        if (deductions == null) return;
        
        double standardDeduction = getNumericValue(deductions, "standardDeduction");
        double itemizedDeduction = getNumericValue(deductions, "itemizedDeduction");
        
        if (standardDeduction > 0 && itemizedDeduction > 0) {
            errors.add("Cannot claim both standard and itemized deductions");
        }
        
        if (standardDeduction < 0) errors.add("Standard deduction cannot be negative");
        if (itemizedDeduction < 0) errors.add("Itemized deduction cannot be negative");
    }
    
    private void validateCredits(JsonNode taxForm, List<String> errors) {
        JsonNode credits = taxForm.get("credits");
        if (credits == null) return;
        
        double childTaxCredit = getNumericValue(credits, "childTaxCredit");
        double earnedIncomeCredit = getNumericValue(credits, "earnedIncomeCredit");
        
        if (childTaxCredit < 0) errors.add("Child tax credit cannot be negative");
        if (earnedIncomeCredit < 0) errors.add("Earned income credit cannot be negative");
    }
    
    // Utility methods - SHOULD BE IN UTILITY CLASS
    
    private void validateRequiredField(JsonNode parent, String fieldName, String displayName, List<String> errors) {
        JsonNode field = parent.get(fieldName);
        if (field == null || field.isNull() || (field.isTextual() && field.asText().trim().isEmpty())) {
            errors.add(displayName + " is required");
        }
    }
    
    private double getNumericValue(JsonNode parent, String fieldName) {
        JsonNode node = parent.get(fieldName);
        if (node == null || node.isNull()) return 0.0;
        if (node.isNumber()) return node.asDouble();
        if (node.isTextual()) {
            try {
                return Double.parseDouble(node.asText().replaceAll("[^\\d.-]", ""));
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }
}