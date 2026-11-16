package com.aurora.appserver.config.isolation;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * The server must be started with exactly one explicitly activated, known profile whose own
 * configuration file was actually loaded. Otherwise a missing, misspelled or combined profile
 * would silently fall back to application.yml defaults and skip the per-environment isolation
 * guards. Default profiles (spring.profiles.default) are deliberately not accepted.
 */
public final class ProfileSelectionRules {

    public static final List<String> KNOWN_PROFILES = List.of("local", "s32it", "paystaging", "prod", "test");

    private ProfileSelectionRules() {
    }

    public static void verify(ConfigurableEnvironment environment) {
        String[] active = environment.getActiveProfiles();
        if (active.length == 0) {
            throw refusal("no Spring profile is active. Set SPRING_PROFILES_ACTIVE (or --spring.profiles.active) "
                    + "to exactly one of " + KNOWN_PROFILES + "; default profiles are not accepted");
        }
        if (active.length > 1) {
            throw refusal("exactly one profile must be active, got " + Arrays.toString(active));
        }
        String profile = active[0];
        if (!KNOWN_PROFILES.contains(profile)) {
            throw refusal("unknown profile '" + profile + "' (allowed: " + KNOWN_PROFILES + ")");
        }
        if (!profileConfigurationLoaded(environment, profile)) {
            throw refusal("profile '" + profile + "' is active but no application-" + profile
                    + " configuration file was loaded");
        }
    }

    /** Matches Spring Boot config-data source names such as "... [application-local.yml]' via ...". */
    static boolean profileConfigurationLoaded(ConfigurableEnvironment environment, String profile) {
        Pattern file = Pattern.compile("[\\[/\\\\]application-" + Pattern.quote(profile) + "\\.(yml|yaml|properties)]");
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (file.matcher(source.getName()).find()) {
                return true;
            }
        }
        return false;
    }

    private static IllegalStateException refusal(String reason) {
        return new IllegalStateException("Refusing to start: " + reason);
    }
}
