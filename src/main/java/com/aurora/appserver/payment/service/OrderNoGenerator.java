package com.aurora.appserver.payment.service;

import com.aurora.appserver.payment.config.PaymentProperties;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Order number: PREFIX + yyyyMMddHHmmss (UTC) + 12 random base32 characters.
 * Alphanumeric only and at most 32 characters (Yusen out_trade_no limits are undocumented).
 */
@Component
public class OrderNoGenerator {

    static final int MAX_LENGTH = 32;
    private static final int RANDOM_CHARS = 12;
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final Pattern PREFIX_PATTERN = Pattern.compile("^[A-Z]{2,4}$");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final SecureRandom random = new SecureRandom();
    private final String prefix;

    public OrderNoGenerator(PaymentProperties properties) {
        String configured = properties.getOrderNoPrefix();
        if (configured == null || !PREFIX_PATTERN.matcher(configured).matches()) {
            throw new IllegalStateException("aurora.payment.order-no-prefix must be 2-4 uppercase letters");
        }
        this.prefix = configured;
    }

    public String next(Instant now) {
        StringBuilder sb = new StringBuilder(MAX_LENGTH);
        sb.append(prefix).append(TIME.format(now));
        for (int i = 0; i < RANDOM_CHARS; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
