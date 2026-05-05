package com.viyangle.study_tour.utils;

import java.util.regex.Pattern;

public final class PhoneValidator {

    private static final Pattern CN_MOBILE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    private PhoneValidator() {
    }

    public static boolean isValidChineseMainlandMobile(String phone) {
        if (phone == null) {
            return false;
        }
        return CN_MOBILE_PATTERN.matcher(phone.trim()).matches();
    }
}
