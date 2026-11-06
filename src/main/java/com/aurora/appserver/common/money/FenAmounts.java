package com.aurora.appserver.common.money;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/** Exact fen <-> yuan string conversion. Never use floating point for money. */
public final class FenAmounts {

    private static final Pattern YUAN_PATTERN = Pattern.compile("^\\d{1,9}(\\.\\d{1,2})?$");

    private FenAmounts() {
    }

    /** 590 -> "5.90", 1 -> "0.01". */
    public static String toYuanString(int fen) {
        if (fen < 0) {
            throw new IllegalArgumentException("Amount must not be negative");
        }
        return BigDecimal.valueOf(fen, 2).toPlainString();
    }

    /** Strict parse of a yuan string with at most two decimals; anything else is empty. */
    public static Optional<Integer> parseYuanToFen(String yuan) {
        if (yuan == null || !YUAN_PATTERN.matcher(yuan).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(yuan).movePointRight(2).intValueExact());
        } catch (ArithmeticException ex) {
            return Optional.empty();
        }
    }
}
