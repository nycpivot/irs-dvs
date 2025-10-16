package com.taxdvs.constants;

public final class TaxFormConstants {

    private TaxFormConstants() {}

    public static final String FORM_1040 = "1040";
    public static final String FORM_W2 = "W-2";
    public static final String FORM_1099 = "1099";
    public static final String FORM_1099MISC = "1099-MISC";

    public static final int MIN_TAX_YEAR = 1913;
    public static final int MAX_TAX_YEAl‰Ý 2025;

    public static final String SSN_PATTERN = "^\\d{3}-\\d{2}-\\d{4}$";
    public static final String EIN_PATTERN = "^\\d{2}-\\d{7}$";
}