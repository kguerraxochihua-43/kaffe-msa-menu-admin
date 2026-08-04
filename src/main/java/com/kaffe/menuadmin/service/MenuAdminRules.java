package com.kaffe.menuadmin.service;

import com.kaffe.common.exception.BadRequestException;

import java.util.Map;

final class MenuAdminRules {

    private MenuAdminRules() {
    }

    static void validateCategory(Map<String, Object> request, boolean creating) {
        text(request, "name", 100, creating);
        text(request, "description", 1000, false);
        nonNegative(request, "sortOrder");
    }

    static void validateProduct(Map<String, Object> request, boolean creating) {
        text(request, "name", 140, creating);
        text(request, "description", 2000, false);
        text(request, "imageUrl", 1000, false);
        nonNegative(request, "basePrice");
        nonNegative(request, "sortOrder");
    }

    static void validateAddonGroup(Map<String, Object> request, boolean creating) {
        text(request, "name", 120, creating);
        text(request, "description", 1000, false);
        nonNegative(request, "minSelection");
        nonNegative(request, "maxSelection");
        nonNegative(request, "sortOrder");

        Integer min = integer(request.get("minSelection"));
        Integer max = integer(request.get("maxSelection"));
        Boolean required = bool(request.get("required"));
        if (min != null && max != null && max < min) {
            throw new BadRequestException("maxSelection must be greater than or equal to minSelection");
        }
        if (Boolean.TRUE.equals(required) && min != null && min < 1) {
            throw new BadRequestException("A required addon group must select at least one option");
        }
    }

    static void validateAddon(Map<String, Object> request, boolean creating) {
        text(request, "name", 120, creating);
        text(request, "description", 1000, false);
        nonNegative(request, "price");
        nonNegative(request, "sortOrder");
    }

    static void validateLocationConfiguration(Map<String, Object> request) {
        nonNegative(request, "priceOverride");
        nonNegative(request, "sortOrder");
    }

    private static void text(Map<String, Object> request, String key, int maxLength, boolean required) {
        if (!request.containsKey(key) || request.get(key) == null) {
            if (required) {
                throw new BadRequestException(key + " is required");
            }
            return;
        }
        String value = String.valueOf(request.get(key)).trim();
        if (required && value.isEmpty()) {
            throw new BadRequestException(key + " is required");
        }
        if (value.length() > maxLength) {
            throw new BadRequestException(key + " must not exceed " + maxLength + " characters");
        }
    }

    private static void nonNegative(Map<String, Object> request, String key) {
        if (!request.containsKey(key) || request.get(key) == null) {
            return;
        }
        Integer value = integer(request.get(key));
        if (value == null || value < 0) {
            throw new BadRequestException(key + " must be a non-negative integer");
        }
    }

    private static Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            if (!Double.isFinite(decimal)
                    || decimal != Math.rint(decimal)
                    || decimal < Integer.MIN_VALUE
                    || decimal > Integer.MAX_VALUE) {
                return null;
            }
            return number.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Boolean bool(Object value) {
        return value instanceof Boolean booleanValue ? booleanValue : null;
    }
}
