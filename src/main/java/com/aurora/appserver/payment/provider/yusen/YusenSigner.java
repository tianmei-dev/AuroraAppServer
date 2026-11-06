package com.aurora.appserver.payment.provider.yusen;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Yusen (EPay-style) MD5 signature, per the merchant doc "MD5签名算法":
 * sort by parameter name (ASCII), drop sign / sign_type / empty values, join as k=v with '&'
 * using raw (not URL-encoded) values, append the merchant key, MD5, lowercase hex.
 */
public final class YusenSigner {

    static final String SIGN = "sign";
    static final String SIGN_TYPE = "sign_type";

    private YusenSigner() {
    }

    public static String canonicalString(Map<String, String> params) {
        return new TreeMap<>(params).entrySet().stream()
                .filter(e -> !SIGN.equals(e.getKey()) && !SIGN_TYPE.equals(e.getKey()))
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
    }

    public static String sign(Map<String, String> params, String merchantKey) {
        if (merchantKey == null || merchantKey.isEmpty()) {
            throw new IllegalStateException("Merchant key is not configured");
        }
        return md5Hex(canonicalString(params) + merchantKey);
    }

    /** Constant-time comparison against the expected signature. */
    public static boolean verify(Map<String, String> params, String merchantKey) {
        String provided = params.get(SIGN);
        if (provided == null || provided.isEmpty()) {
            return false;
        }
        String expected = sign(params, merchantKey);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                provided.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    private static String md5Hex(String input) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md5.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 not available", ex);
        }
    }
}
