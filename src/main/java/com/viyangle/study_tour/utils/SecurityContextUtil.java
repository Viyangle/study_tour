package com.viyangle.study_tour.utils;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

public final class SecurityContextUtil {

    private SecurityContextUtil() {
    }

    public static Long currentAccountId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object details = authentication.getDetails();
        if (!(details instanceof Map<?, ?> detailMap)) {
            return null;
        }

        Object accountId = detailMap.get("accountId");
        if (accountId instanceof Long value) {
            return value;
        }
        if (accountId instanceof Integer value) {
            return value.longValue();
        }
        if (accountId instanceof String value && !value.isBlank()) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static String currentRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object details = authentication.getDetails();
        if (!(details instanceof Map<?, ?> detailMap)) {
            return null;
        }

        Object role = detailMap.get("role");
        return role == null ? null : role.toString();
    }
}
