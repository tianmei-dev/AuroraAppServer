package com.aurora.appserver.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aurora.appserver.config.isolation.PaystagingEnvironmentGuard;
import com.aurora.appserver.config.isolation.ProfileSelectionGuard;
import com.aurora.appserver.config.isolation.ProfileSelectionRules;
import com.aurora.appserver.config.isolation.S32itEnvironmentGuard;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;

/** B3-03-A3: exactly one explicit, known profile whose own configuration file was loaded. */
class ProfileSelectionRulesTest {

    private static StandardEnvironment env(String[] activeProfiles, String... loadedSourceNames) {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles(activeProfiles);
        for (String name : loadedSourceNames) {
            env.getPropertySources().addLast(new MapPropertySource(name, Map.of("x", "y")));
        }
        return env;
    }

    private static String classpathSource(String file) {
        return "Config resource 'class path resource [" + file + "]' via location 'optional:classpath:/'";
    }

    private static void guard(StandardEnvironment env) {
        new ProfileSelectionGuard().postProcessEnvironment(env, null);
    }

    // A3-U01 -------------------------------------------------------------------------------------
    @Test
    void u01_noActiveProfileIsRefused() {
        StandardEnvironment env = env(new String[0], classpathSource("application.yml"));
        assertThatThrownBy(() -> guard(env)).hasMessageContaining("no Spring profile is active");
    }

    @Test
    void u01_defaultProfilesAreNotAccepted() {
        for (String fallback : new String[] {"local", "paystaging", "s32it", "test"}) {
            StandardEnvironment env = env(new String[0], classpathSource("application-" + fallback + ".yml"));
            env.setDefaultProfiles(fallback);
            assertThatThrownBy(() -> guard(env)).hasMessageContaining("no Spring profile is active");
        }
    }

    // A3-U02 -------------------------------------------------------------------------------------
    @Test
    void u02_eachKnownProfileWithItsOwnConfigurationPasses() {
        for (String profile : ProfileSelectionRules.KNOWN_PROFILES) {
            StandardEnvironment env = env(new String[] {profile},
                    classpathSource("application-" + profile + ".yml"), classpathSource("application.yml"));
            assertThatCode(() -> guard(env)).as(profile).doesNotThrowAnyException();
        }
        StandardEnvironment fileBased = env(new String[] {"local"},
                "Config resource 'file [config\\application-local.yml]' via location 'optional:file:./config/'");
        assertThatCode(() -> guard(fileBased)).doesNotThrowAnyException();
        StandardEnvironment properties = env(new String[] {"local"},
                "Config resource 'file [./application-local.properties]' via location 'optional:file:./'");
        assertThatCode(() -> guard(properties)).doesNotThrowAnyException();
        StandardEnvironment multiDocument = env(new String[] {"paystaging"},
                classpathSource("application-paystaging.yml") + " (document #0)");
        assertThatCode(() -> guard(multiDocument)).doesNotThrowAnyException();
    }

    // A3-U03 -------------------------------------------------------------------------------------
    @Test
    void u03_knownProfileWithoutItsConfigurationIsRefused() {
        String[][] cases = {
                {"local"},
                {"local", classpathSource("application.yml")},
                {"local", classpathSource("application-local.yml.example")},
                {"local", classpathSource("application-localx.yml")},
                {"local", classpathSource("application-paystaging.yml")},
                {"test", classpathSource("application.yml")},
                {"s32it", classpathSource("application.yml")},
                {"paystaging", classpathSource("application-local.yml")},
        };
        for (String[] c : cases) {
            String[] sources = java.util.Arrays.copyOfRange(c, 1, c.length);
            StandardEnvironment env = env(new String[] {c[0]}, sources);
            assertThatThrownBy(() -> guard(env)).as(String.join(" | ", c))
                    .hasMessageContaining("profile '" + c[0] + "' is active but no application-" + c[0]);
        }
    }

    // A3-U04 -------------------------------------------------------------------------------------
    @Test
    void u04_unknownOrMisspelledProfilesAreRefused() {
        for (String wrong : new String[] {"PAYSTAGING", "Paystaging", "LOCAL", "prod", "production", "staging",
                "default", "paystaging-copy", "s32"}) {
            StandardEnvironment env = env(new String[] {wrong}, classpathSource("application-" + wrong + ".yml"));
            assertThatThrownBy(() -> guard(env)).as(wrong).hasMessageContaining("unknown profile '" + wrong + "'");
        }
    }

    // A3-U05 -------------------------------------------------------------------------------------
    @Test
    void u05_profileCombinationsAreRefusedEvenWithAllFilesLoaded() {
        String[][] combos = {
                {"local", "paystaging"}, {"paystaging", "local"}, {"paystaging", "s32it"}, {"paystaging", "test"},
                {"local", "s32it"}, {"s32it", "local"}, {"s32it", "test"}, {"test", "local"},
                {"paystaging", "foo"}, {"local", "paystaging", "s32it"},
        };
        for (String[] combo : combos) {
            String[] sources = java.util.Arrays.stream(combo)
                    .map(p -> classpathSource("application-" + p + ".yml")).toArray(String[]::new);
            StandardEnvironment env = env(combo, sources);
            assertThatThrownBy(() -> guard(env)).as(String.join(",", combo))
                    .hasMessageContaining("exactly one profile must be active");
        }
    }

    // A3-U06 -------------------------------------------------------------------------------------
    @Test
    void u06_guardIsRegisteredAndOrderedAfterConfigDataAndBeforeProfileGuards() {
        List<String> registered = SpringFactoriesLoader.forDefaultResourceLocation()
                .load(EnvironmentPostProcessor.class, SpringFactoriesLoader.ArgumentResolver.none(),
                        SpringFactoriesLoader.FailureHandler.logging(
                                org.apache.commons.logging.LogFactory.getLog(getClass())))
                .stream().map(p -> p.getClass().getName()).toList();
        assertThat(registered).contains(ProfileSelectionGuard.class.getName(),
                S32itEnvironmentGuard.class.getName(), PaystagingEnvironmentGuard.class.getName());

        int order = new ProfileSelectionGuard().getOrder();
        assertThat(order).isGreaterThan(ConfigDataEnvironmentPostProcessor.ORDER);
        assertThat(order).isLessThan(new S32itEnvironmentGuard().getOrder());
        assertThat(order).isLessThan(new PaystagingEnvironmentGuard().getOrder());
        assertThat(new PaystagingEnvironmentGuard().getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }
}
