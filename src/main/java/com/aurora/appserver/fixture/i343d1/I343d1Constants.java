package com.aurora.appserver.fixture.i343d1;

/**
 * I3.4.3-D1 isolation fixture markers. Only rows keyed by these prefixes may be created/cleaned.
 */
public final class I343d1Constants {

    public static final String PREFIX = "i343d1-";
    public static final String APP_CODE = "calculator_vault";
    /** Synthetic second app for cross-app isolation checks (created only if missing). */
    public static final String OTHER_APP_CODE = "i343d1_other_app";
    public static final String OTHER_PACKAGE = "com.aurora.i343d1.other";

    public static final String PROP_COMMAND = "i343d1.command";
    public static final String TOKEN_FILE = "build/i343d1-sessions.local.json";
    public static final String META_FILE = "build/i343d1-accounts.meta.json";

    /** Past instant for EXPIRED membership grants (CLI Clock only; standing server uses system UTC). */
    public static final String EXPIRED_GRANT_INSTANT = "2020-01-15T00:00:00Z";

    public static final String[] ACCOUNT_LABELS = {
            "none",
            "monthly",
            "yearly",
            "lifetime",
            "stack",
            "expired",
            "revoked",
            "other-app-none"
    };

    private I343d1Constants() {
    }

    public static String openid(String label) {
        return PREFIX + label;
    }

    public static String sourceRef(String label, String suffix) {
        return PREFIX + label + "-" + suffix;
    }

    public static boolean isFixtureOpenid(String openid) {
        return openid != null && openid.startsWith(PREFIX);
    }

    public static boolean isFixtureSourceRef(String sourceRef) {
        return sourceRef != null && sourceRef.startsWith(PREFIX);
    }
}
