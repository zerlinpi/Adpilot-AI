package com.adpilot.common.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class DecimalUtils {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public static BigDecimal safeDivide(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }

    public static BigDecimal toPercent(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }

    public static double toDouble(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }

    public static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
