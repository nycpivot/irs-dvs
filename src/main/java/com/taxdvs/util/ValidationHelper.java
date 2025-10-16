package com.taxdvs.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Pattern;

public final class ValidationHelper {

    private static final Pattern SSN_PATTERN = Pattern.compile("^\\d{3}-\\d{2}-\\d{4}$");
    private static final Pattern EIN_PATTERN = Pattern.compile("^\\d{2}-\\d{7}$");

    private ValidationHelper() {}

    public static boolean isValidSSN(String ssn) {
        return ssn != null && SSN_PATTERN.matcher(ssn).matches();
    }

    public static boolean isValidEIN(String ein) {
        return ein != null && EIN_PATTERN.matcher(ein).matches();
    }

    public static boolean isNullOrEmpty(JsonNode node) {
        return node == null || node.isNull() || (node.isTextual() && node.asText().trim().isEmpty());
    }

    public static boolean isValidTaxYear(int year) {
        return year >= 1913 && year <= 2025;
    }

    public static boolean isValidAmount(double amount) {
        return amount >= 0 && amount <= 1000000000;
    }
}