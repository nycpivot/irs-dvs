package com.taxdvs.enums;

public enum FilingStatus {
    SINGLE("Single"),
    MARRIED_JOINT("Married Filing Jointly"),
    MARRIED_SEPARATE("Married Filing Separately"),
    HEAD_OF_HOUSEHOLD("Head of Household"),
    QUALIFYING_WIDOWER("Qualifying Widow(er)");

    private final String description;

    FilingStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}