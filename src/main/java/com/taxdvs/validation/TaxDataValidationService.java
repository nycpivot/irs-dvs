package com.taxdvs.validation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class TaxDataValidationService {

    /**
     * Validates tax data payload based on IRS rules and business logic
     * @param payload JsonNode containing tax data
     * @return Map containing validation results
     */
    public Map<String, Object> validateTaxData(JsonNode payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Validate required fields
        validateRequiredFields(payload, errors);

        // Validate SSN/EIN format
        validateTaxpayerIdentifier(payload, errors);

        // Validate filing status
        validateFilingStatus(payload, errors);

        // Validate income amounts
        validateIncomeAmounts(payload, errors, warnings);

        // Validate deductions
        validateDeductions(payload, errors, warnings);

        // Validate dependents
        validateDependents(payload, errors, warnings);

        // Validate tax year
        validateTaxYear(payload, errors);

        // Validate credits
        validateCredits(payload, errors, warnings);

        // Calculate total tax liability
        calculateTaxLiability(payload, result, errors);

        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        result.put("warnings", warnings);

        return result;
    }

    private void validateRequiredFields(JsonNode payload, List<String> errors) {
        String[] requiredFields = {"taxpayerId", "filingStatus", "taxYear", "firstName", "lastName"};
        for (String field : requiredFields) {
            if (!payload.has(field) || payload.get(field).isNull() || payload.get(field).asText().isBlank()) {
                errors.add("Required field missing or empty: " + field);
            }
        }
    }

    private void validateTaxpayerIdentifier(JsonNode payload, List<String> errors) {
        if (payload.has("taxpayerId")) {
            String taxpayerId = payload.get("taxpayerId").asText();
            String taxpayerType = payload.has("taxpayerType") ? payload.get("taxpayerType").asText() : "individual";

            if ("individual".equalsIgnoreCase(taxpayerType)) {
                // SSN format: XXX-XX-XXXX or 9 digits
                if (!taxpayerId.matches("^\\d{3}-\\d{2}-\\d{4}$|^\\d{9}$")) {
                    errors.add("Invalid SSN format. Expected: XXX-XX-XXXX or 9 digits");
                }
                // IRS rule: SSN cannot be all zeros in any group
                String cleanSSN = taxpayerId.replaceAll("-", "");
                if (cleanSSN.startsWith("000") || cleanSSN.substring(3, 5).equals("00") || cleanSSN.endsWith("0000")) {
                    errors.add("Invalid SSN: cannot contain all zeros in any group");
                }
                // IRS rule: SSN cannot start with 999
                if (cleanSSN.startsWith("999")) {
                    errors.add("Invalid SSN: cannot start with 999");
                }
            } else if ("business".equalsIgnoreCase(taxpayerType)) {
                // EIN format: XX-XXXXXXX or 9 digits
                if (!taxpayerId.matches("^\\d{2}-\\d{7}$|^\\d{9}$")) {
                    errors.add("Invalid EIN format. Expected: XX-XXXXXXX or 9 digits");
                }
            }
        }
    }

    private void validateFilingStatus(JsonNode payload, List<String> errors) {
        if (payload.has("filingStatus")) {
            String filingStatus = payload.get("filingStatus").asText().toUpperCase();
            Set<String> validStatuses = Set.of("SINGLE", "MARRIED_FILING_JOINTLY", "MARRIED_FILING_SEPARATELY", "HEAD_OF_HOUSEHOLD", "QUALIFYING_WIDOWER");
            if (!validStatuses.contains(filingStatus)) {
                errors.add("Invalid filing status: " + filingStatus + ". Valid values: " + validStatuses);
            }
        }
    }

    private void validateIncomeAmounts(JsonNode payload, List<String> errors, List<String> warnings) {
        if (payload.has("income")) {
            JsonNode income = payload.get("income");
            
            // Validate wages
            if (income.has("wages")) {
                double wages = income.get("wages").asDouble();
                if (wages < 0) {
                    errors.add("Wages cannot be negative");
                }
                // IRS rule: Wages over $10M require additional scrutiny
                if (wages > 10000000) {
                    warnings.add("Wages exceed $10M - may require additional documentation");
                }
            }
            
            // Validate interest income
            if (income.has("interest")) {
                double interest = income.get("interest").asDouble();
                if (interest < 0) {
                    errors.add("Interest income cannot be negative");
                }
                // IRS rule: Interest over $1500 requires Schedule B
                if (interest > 1500 && !payload.has("scheduleB")) {
                    warnings.add("Interest income over $1500 requires Schedule B");
                }
            }
            
            // Validate dividends
            if (income.has("dividends")) {
                double dividends = income.get("dividends").asDouble();
                if (dividends < 0) {
                    errors.add("Dividends cannot be negative");
                }
            }
            
            // Validate capital gains
            if (income.has("capitalGains")) {
                double capitalGains = income.get("capitalGains").asDouble();
                // Capital gains can be negative (losses) but limited to $3,000
                if (capitalGains < -3000) {
                    errors.add("Capital losses limited to $3,000 per year");
                }
            }
        }
    }

    private void validateDeductions(JsonNode payload, List<String> errors, List<String> warnings) {
        if (payload.has("deductions")) {
            JsonNode deductions = payload.get("deductions");
            String deductionType = deductions.has("type") ? deductions.get("type").asText() : "standard";
            
            if ("standard".equalsIgnoreCase(deductionType)) {
                // Validate standard deduction amount based on filing status (2023 values)
                if (deductions.has("amount")) {
                    double amount = deductions.get("amount").asDouble();
                    String filingStatus = payload.has("filingStatus") ? payload.get("filingStatus").asText().toUpperCase() : "SINGLE";
                    
                    double expectedStandardDeduction = switch (filingStatus) {
                        case "SINGLE", "MARRIED_FILING_SEPARATELY" -> 13850;
                        case "MARRIED_FILING_JOINTLY", "QUALIFYING_WIDOWER" -> 27700;
                        case "HEAD_OF_HOUSEHOLD" -> 20800;
                        default -> 13850;
                    };
                    
                    if (amount != expectedStandardDeduction) {
                        warnings.add("Standard deduction amount (" + amount + ") does not match expected value (" + expectedStandardDeduction + ") for filing status " + filingStatus);
                    }
                }
            } else if ("itemized".equalsIgnoreCase(deductionType)) {
                // Itemized deductions require Schedule A
                if (!payload.has("scheduleA")) {
                    warnings.add("Itemized deductions require Schedule A");
                }
                
                if (deductions.has("items")) {
                    JsonNode items = deductions.get("items");
                    
                    // Validate medical expenses (must exceed 7.5% of AGI)
                    if (items.has("medicalExpenses") && payload.has("adjustedGrossIncome")) {
                        double medical = items.get("medicalExpenses").asDouble();
                        double agi = payload.get("adjustedGrossIncome").asDouble();
                        if (medical < agi * 0.075) {
                            warnings.add("Medical expenses must exceed 7.5% of AGI to be deductible");
                        }
                    }
                    
                    // Validate SALT deduction limit ($10,000)
                    if (items.has("stateAndLocalTaxes")) {
                        double salt = items.get("stateAndLocalTaxes").asDouble();
                        if (salt > 10000) {
                            errors.add("State and local tax deduction limited to $10,000");
                        }
                    }
                    
                    // Validate mortgage interest limit ($750,000 loan balance)
                    if (items.has("mortgageInterest")) {
                        double mortgageInterest = items.get("mortgageInterest").asDouble();
                        if (mortgageInterest < 0) {
                            errors.add("Mortgage interest cannot be negative");
                        }
                    }
                }
            }
        }
    }

    private void validateDependents(JsonNode payload, List<String> errors, List<String> warnings) {
        if (payload.has("dependents") && payload.get("dependents").isArray()) {
            JsonNode dependents = payload.get("dependents");
            Set<String> ssns = new HashSet<>();
            
            for (int i = 0; i < dependents.size(); i++) {
                JsonNode dependent = dependents.get(i);
                
                // Validate required fields
                if (!dependent.has("ssn") || !dependent.has("firstName") || !dependent.has("lastName") || !dependent.has("relationship")) {
                    errors.add("Dependent " + (i + 1) + " missing required fields (ssn, firstName, lastName, relationship)");
                    continue;
                }
                
                String ssn = dependent.get("ssn").asText();
                
                // Check for duplicate SSNs
                if (ssns.contains(ssn)) {
                    errors.add("Duplicate dependent SSN: " + ssn);
                }
                ssns.add(ssn);
                
                // Validate SSN format
                if (!ssn.matches("^\\d{3}-\\d{2}-\\d{4}$|^\\d{9}$")) {
                    errors.add("Invalid SSN format for dependent " + (i + 1));
                }
                
                // Validate relationship
                String relationship = dependent.get("relationship").asText().toUpperCase();
                Set<String> validRelationships = Set.of("SON", "DAUGHTER", "STEPCHILD", "FOSTER", "SIBLING", "STEPSIBLING", "HALFSIBLING", "OTHER");
                if (!validRelationships.contains(relationship)) {
                    errors.add("Invalid relationship for dependent " + (i + 1) + ": " + relationship);
                }
            }
            
            // IRS rule: Child Tax Credit limited to children under 17
            if (payload.has("credits") && payload.get("credits").has("childTaxCredit")) {
                long childrenUnder17 = 0;
                for (int i = 0; i < dependents.size(); i++) {
                    JsonNode dependent = dependents.get(i);
                    if (dependent.has("age") && dependent.get("age").asInt() < 17) {
                        childrenUnder17++;
                    }
                }
                if (childrenUnder17 == 0) {
                    warnings.add("Child Tax Credit claimed but no dependents under age 17");
                }
            }
        }
    }

    private void validateTaxYear(JsonNode payload, List<String> errors) {
        if (payload.has("taxYear")) {
            int taxYear = payload.get("taxYear").asInt();
            int currentYear = Calendar.getInstance().get(Calendar.YEAR);
            
            // IRS rule: Cannot file for future years
            if (taxYear > currentYear) {
                errors.add("Cannot file tax return for future year: " + taxYear);
            }
            
            // IRS rule: Generally cannot file for years more than 3 years old
            if (taxYear < currentYear - 3) {
                errors.add("Tax year " + taxYear + " is beyond the standard filing period");
            }
        }
    }

    private void validateCredits(JsonNode payload, List<String> errors, List<String> warnings) {
        if (payload.has("credits")) {
            JsonNode credits = payload.get("credits");
            
            // Validate Child Tax Credit
            if (credits.has("childTaxCredit")) {
                double childTaxCredit = credits.get("childTaxCredit").asDouble();
                if (childTaxCredit < 0) {
                    errors.add("Child Tax Credit cannot be negative");
                }
                // IRS rule: $2,000 per child under 17
                if (childTaxCredit % 2000 != 0) {
                    warnings.add("Child Tax Credit should be in multiples of $2,000");
                }
            }
            
            // Validate Earned Income Tax Credit
            if (credits.has("earnedIncomeCredit")) {
                double eitc = credits.get("earnedIncomeCredit").asDouble();
                if (eitc < 0) {
                    errors.add("Earned Income Credit cannot be negative");
                }
                // IRS rule: EITC has income limits
                if (payload.has("adjustedGrossIncome")) {
                    double agi = payload.get("adjustedGrossIncome").asDouble();
                    // Simplified check - actual limits vary by filing status and number of children
                    if (agi > 60000 && eitc > 0) {
                        warnings.add("Earned Income Credit may be limited or ineligible based on AGI");
                    }
                }
            }
            
            // Validate Education Credits
            if (credits.has("americanOpportunityCredit")) {
                double aoc = credits.get("americanOpportunityCredit").asDouble();
                if (aoc < 0) {
                    errors.add("American Opportunity Credit cannot be negative");
                }
                // IRS rule: Max $2,500 per student
                if (aoc > 2500) {
                    errors.add("American Opportunity Credit exceeds maximum of $2,500 per student");
                }
            }
            
            if (credits.has("lifetimeLearningCredit")) {
                double llc = credits.get("lifetimeLearningCredit").asDouble();
                if (llc < 0) {
                    errors.add("Lifetime Learning Credit cannot be negative");
                }
                // IRS rule: Max $2,000 per return
                if (llc > 2000) {
                    errors.add("Lifetime Learning Credit exceeds maximum of $2,000");
                }
            }
            
            // IRS rule: Cannot claim both AOC and LLC for the same student
            if (credits.has("americanOpportunityCredit") && credits.has("lifetimeLearningCredit")) {
                if (credits.get("americanOpportunityCredit").asDouble() > 0 && credits.get("lifetimeLearningCredit").asDouble() > 0) {
                    warnings.add("Cannot claim both American Opportunity and Lifetime Learning Credits for the same student");
                }
            }
        }
    }

    private void calculateTaxLiability(JsonNode payload, Map<String, Object> result, List<String> errors) {
        try {
            // Calculate Adjusted Gross Income (AGI)
            double agi = 0;
            if (payload.has("adjustedGrossIncome")) {
                agi = payload.get("adjustedGrossIncome").asDouble();
            } else if (payload.has("income")) {
                JsonNode income = payload.get("income");
                double totalIncome = 0;
                
                if (income.has("wages")) totalIncome += income.get("wages").asDouble();
                if (income.has("interest")) totalIncome += income.get("interest").asDouble();
                if (income.has("dividends")) totalIncome += income.get("dividends").asDouble();
                if (income.has("capitalGains")) totalIncome += income.get("capitalGains").asDouble();
                
                agi = totalIncome;
            }
            
            // Calculate taxable income
            double deduction = 0;
            if (payload.has("deductions") && payload.get("deductions").has("amount")) {
                deduction = payload.get("deductions").get("amount").asDouble();
            }
            
            double taxableIncome = Math.max(0, agi - deduction);
            
            // Calculate tax based on 2023 tax brackets (simplified)
            double tax = calculateTaxByBracket(taxableIncome, payload);
            
            // Subtract credits
            double totalCredits = 0;
            if (payload.has("credits")) {
                JsonNode credits = payload.get("credits");
                if (credits.has("childTaxCredit")) totalCredits += credits.get("childTaxCredit").asDouble();
                if (credits.has("earnedIncomeCredit")) totalCredits += credits.get("earnedIncomeCredit").asDouble();
                if (credits.has("americanOpportunityCredit")) totalCredits += credits.get("americanOpportunityCredit").asDouble();
                if (credits.has("lifetimeLearningCredit")) totalCredits += credits.get("lifetimeLearningCredit").asDouble();
            }
            
            double taxLiability = Math.max(0, tax - totalCredits);
            
            // Add withholding and payments
            double withholding = 0;
            if (payload.has("withholding")) {
                withholding = payload.get("withholding").asDouble();
            }
            
            double balanceDue = taxLiability - withholding;
            
            result.put("adjustedGrossIncome", Math.round(agi * 100) / 100.0);
            result.put("taxableIncome", Math.round(taxableIncome * 100) / 100.0);
            result.put("taxBeforeCredits", Math.round(tax * 100) / 100.0);
            result.put("totalCredits", Math.round(totalCredits * 100) / 100.0);
            result.put("taxLiability", Math.round(taxLiability * 100) / 100.0);
            result.put("withholding", Math.round(withholding * 100) / 100.0);
            result.put("balanceDue", Math.round(balanceDue * 100) / 100.0);
            result.put("refund", Math.round(Math.max(0, -balanceDue) * 100) / 100.0);
        } catch (Exception e) {
            errors.add("Error calculating tax liability: " + e.getMessage());
        }
    }

    private double calculateTaxByBracket(double taxableIncome, JsonNode payload) {
        String filingStatus = payload.has("filingStatus") ? payload.get("filingStatus").asText().toUpperCase() : "SINGLE";
        
        // 2023 Tax Brackets (simplified for Single filers)
        if ("SINGLE".equals(filingStatus)) {
            if (taxableIncome <= 11000) {
                return taxableIncome * 0.10;
            } else if (taxableIncome <= 44725) {
                return 1100 + (taxableIncome - 11000) * 0.12;
            } else if (taxableIncome <= 95375) {
                return 5057 + (taxableIncome - 44725) * 0.22;
            } else if (taxableIncome <= 182100) {
                return 16200 + (taxableIncome - 95375) * 0.24;
            } else if (taxableIncome <= 231250) {
                return 37024 + (taxableIncome - 182100) * 0.32;
            } else if (taxableIncome <= 578125) {
                return 52752 + (taxableIncome - 231250) * 0.35;
            } else {
                return 174238 + (taxableIncome - 578125) * 0.37;
            }
        } else if ("MARRIED_FILING_JOINTLY".equals(filingStatus)) {
            if (taxableIncome <= 22000) {
                return taxableIncome * 0.10;
            } else if (taxableIncome <= 89450) {
                return 2200 + (taxableIncome - 22000) * 0.12;
            } else if (taxableIncome <= 190750) {
                return 10114 + (taxableIncome - 89450) * 0.22;
            } else if (taxableIncome <= 364200) {
                return 32400 + (taxableIncome - 190750) * 0.24;
            } else if (taxableIncome <= 462500) {
                return 74048 + (taxableIncome - 364200) * 0.32;
            } else if (taxableIncome <= 693250) {
                return 105504 + (taxableIncome - 462500) * 0.35;
            } else {
                return 186269 + (taxableIncome - 693250) * 0.37;
            }
        }
        
        // Default to single brackets for other filing statuses
        return taxableIncome * 0.22; // Simplified
    }
}