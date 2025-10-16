package com.taxdvs.enums;

public enum FormType {
    FORM_1040("1040", "U.S. Individual Income Tax Return"),
    FORM_W2("W-2", "Wage and Tax Statement"),
    FORM_1099("1099", "Miscellaneous Income"),
    FORM_1099_MISC("1099-MISC", "Miscellaneous Information");

    private final String code;
    private final String description;

    FormType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}