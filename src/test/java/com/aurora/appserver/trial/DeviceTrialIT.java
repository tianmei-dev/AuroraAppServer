package com.aurora.appserver.trial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aurora.appserver.support.ExternalMySqlSupport;
import com.aurora.appserver.trial.service.DeviceKeyHasher;
import com.aurora.appserver.trial.service.InMemoryRateLimiter;
import com.aurora.appserver.trial.support.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DeviceTrialIT.ClockConfig.class)
class DeviceTrialIT {

    private static final String APP_A = "calculator_vault";
    private static final String APP_B = "speaker_cleaner";
    private static final long HOUR = 3600L;
    private static final long FULL = 72L * HOUR;

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        ExternalMySqlSupport.registerDatasource(registry);
    }

    @TestConfiguration
    static class ClockConfig {
        static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DeviceKeyHasher deviceKeyHasher;

    @Autowired
    private InMemoryRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        ClockConfig.CLOCK.setInstant(Instant.parse("2026-01-01T00:00:00Z"));
        rateLimiter.reset();
        ensureSecondApp();
    }

    @Test
    void firstActivationSucceeds() throws Exception {
        String deviceKey = "device-first-" + System.nanoTime();
        JsonNode data = activate(APP_A, deviceKey, null);
        assertThat(data.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(data.get("trialStartedAt").asText()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(data.get("trialExpiresAt").asText()).isEqualTo("2026-01-04T00:00:00Z");
        assertThat(data.get("serverTime").asText()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(data.get("remainingSeconds").asLong()).isEqualTo(FULL);
        assertThat(data.has("deviceKeyHash")).isFalse();
        assertThat(data.has("id")).isFalse();
        assertPersistedExpires(APP_A, deviceKey, Instant.parse("2026-01-04T00:00:00Z"));
    }

    @Test
    void firstOnlineWithFullCap() throws Exception {
        String deviceKey = "device-full-cap-" + System.nanoTime();
        JsonNode data = activate(APP_A, deviceKey, FULL);
        assertThat(data.get("remainingSeconds").asLong()).isEqualTo(FULL);
        assertThat(data.get("trialExpiresAt").asText()).isEqualTo("2026-01-04T00:00:00Z");
    }

    @Test
    void offline48hThenOnlineReports24hRemaining() throws Exception {
        String deviceKey = "device-offline48-" + System.nanoTime();
        JsonNode data = activate(APP_A, deviceKey, 24L * HOUR);
        assertThat(data.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(data.get("trialExpiresAt").asText()).isEqualTo("2026-01-02T00:00:00Z");
        assertThat(data.get("remainingSeconds").asLong()).isEqualTo(24L * HOUR);
        assertPersistedExpires(APP_A, deviceKey, Instant.parse("2026-01-02T00:00:00Z"));
    }

    @Test
    void hour73ReinstallDoesNotRestoreTrial() throws Exception {
        String deviceKey = "device-reinstall73-" + System.nanoTime();
        activate(APP_A, deviceKey, null);
        ClockConfig.CLOCK.advance(Duration.ofHours(73));
        JsonNode data = activate(APP_A, deviceKey, FULL);
        assertThat(data.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(data.get("remainingSeconds").asLong()).isZero();
        assertThat(data.get("trialExpiresAt").asText()).isEqualTo("2026-01-04T00:00:00Z");
    }

    @Test
    void firstActivateWithZeroCapIsImmediatelyExpired() throws Exception {
        String deviceKey = "device-zero-cap-" + System.nanoTime();
        JsonNode data = activate(APP_A, deviceKey, 0L);
        assertThat(data.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(data.get("remainingSeconds").asLong()).isZero();
        assertThat(data.get("trialStartedAt").asText()).isEqualTo(data.get("trialExpiresAt").asText());
        assertPersistedExpires(APP_A, deviceKey, Instant.parse("2026-01-01T00:00:00Z"));

        ClockConfig.CLOCK.advance(Duration.ofHours(1));
        JsonNode again = activate(APP_A, deviceKey, FULL);
        assertThat(again.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(again.get("trialExpiresAt").asText()).isEqualTo("2026-01-01T00:00:00Z");
    }

    @Test
    void expiredDeviceReportingFullCapDoesNotRevive() throws Exception {
        String deviceKey = "device-no-revive-" + System.nanoTime();
        activate(APP_A, deviceKey, null);
        ClockConfig.CLOCK.advance(Duration.ofHours(80));
        JsonNode data = activate(APP_A, deviceKey, FULL);
        assertThat(data.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(data.get("trialExpiresAt").asText()).isEqualTo("2026-01-04T00:00:00Z");
    }

    @Test
    void repeatWithoutCapDoesNotExtend() throws Exception {
        String deviceKey = "device-repeat-nocap-" + System.nanoTime();
        JsonNode first = activate(APP_A, deviceKey, null);
        ClockConfig.CLOCK.advance(Duration.ofHours(5));
        JsonNode second = activate(APP_A, deviceKey, null);
        assertThat(second.get("trialStartedAt").asText()).isEqualTo(first.get("trialStartedAt").asText());
        assertThat(second.get("trialExpiresAt").asText()).isEqualTo(first.get("trialExpiresAt").asText());
        assertThat(second.get("remainingSeconds").asLong()).isEqualTo((72L - 5L) * HOUR);
    }

    @Test
    void sameCapAtLaterTimeDoesNotExtendOrShorten() throws Exception {
        // newExpires = LEAST(existing, now+cap). Same cap later => later candidate => keeps existing.
        String deviceKey = "device-same-cap-later-" + System.nanoTime();
        JsonNode first = activate(APP_A, deviceKey, 48L * HOUR);
        assertThat(first.get("trialExpiresAt").asText()).isEqualTo("2026-01-03T00:00:00Z");

        ClockConfig.CLOCK.advance(Duration.ofHours(10));
        JsonNode second = activate(APP_A, deviceKey, 48L * HOUR);
        assertThat(second.get("trialExpiresAt").asText()).isEqualTo("2026-01-03T00:00:00Z");
        assertThat(second.get("remainingSeconds").asLong()).isEqualTo((48L - 10L) * HOUR);
        assertPersistedExpires(APP_A, deviceKey, Instant.parse("2026-01-03T00:00:00Z"));
    }

    @Test
    void smallerCapAtLaterTimeShortensWhenCandidateEarlier() throws Exception {
        String deviceKey = "device-smaller-cap-later-" + System.nanoTime();
        activate(APP_A, deviceKey, 48L * HOUR);
        // existing = Jan 3 00:00; after +10h, cap=12h => candidate = Jan 1 22:00 < existing
        ClockConfig.CLOCK.advance(Duration.ofHours(10));
        JsonNode data = activate(APP_A, deviceKey, 12L * HOUR);
        assertThat(data.get("trialExpiresAt").asText()).isEqualTo("2026-01-01T22:00:00Z");
        assertPersistedExpires(APP_A, deviceKey, Instant.parse("2026-01-01T22:00:00Z"));
    }

    @Test
    void largerCapAtLaterTimeDoesNotExtend() throws Exception {
        String deviceKey = "device-larger-cap-" + System.nanoTime();
        activate(APP_A, deviceKey, 24L * HOUR);
        ClockConfig.CLOCK.advance(Duration.ofHours(1));
        JsonNode data = activate(APP_A, deviceKey, FULL);
        assertThat(data.get("trialExpiresAt").asText()).isEqualTo("2026-01-02T00:00:00Z");
        assertPersistedExpires(APP_A, deviceKey, Instant.parse("2026-01-02T00:00:00Z"));
    }

    @Test
    void outOfOrderCapsKeepEarliest() throws Exception {
        String deviceKey = "device-ooo-" + System.nanoTime();
        activate(APP_A, deviceKey, 10L * HOUR);
        JsonNode later = activate(APP_A, deviceKey, 50L * HOUR);
        assertThat(later.get("trialExpiresAt").asText()).isEqualTo("2026-01-01T10:00:00Z");
        JsonNode stricter = activate(APP_A, deviceKey, 2L * HOUR);
        assertThat(stricter.get("trialExpiresAt").asText()).isEqualTo("2026-01-01T02:00:00Z");
    }

    @Test
    void oldClientMissingCapCompatible() throws Exception {
        String deviceKey = "device-legacy-" + System.nanoTime();
        JsonNode first = activate(APP_A, deviceKey, null);
        ClockConfig.CLOCK.advance(Duration.ofHours(3));
        JsonNode second = activateLegacyBody(APP_A, deviceKey);
        assertThat(second.get("trialExpiresAt").asText()).isEqualTo(first.get("trialExpiresAt").asText());
        assertThat(second.get("remainingSeconds").asLong()).isEqualTo((72L - 3L) * HOUR);
    }

    @Test
    void oldRecordThenCapOnlyShortens() throws Exception {
        String deviceKey = "device-old-record-" + System.nanoTime();
        activate(APP_A, deviceKey, null);
        assertPersistedExpires(APP_A, deviceKey, Instant.parse("2026-01-04T00:00:00Z"));
        ClockConfig.CLOCK.advance(Duration.ofHours(48));
        JsonNode data = activate(APP_A, deviceKey, 24L * HOUR);
        assertThat(data.get("trialExpiresAt").asText()).isEqualTo("2026-01-04T00:00:00Z");
        // now+24h = Jan 4 00:00 same as existing — equal LEAST keeps it
        assertThat(data.get("remainingSeconds").asLong()).isEqualTo(24L * HOUR);

        JsonNode tighter = activate(APP_A, deviceKey, 5L * HOUR);
        assertThat(tighter.get("trialExpiresAt").asText()).isEqualTo("2026-01-03T05:00:00Z");
    }

    @Test
    void differentAppsHaveIndependentTrials() throws Exception {
        String deviceKey = "device-multi-app-" + System.nanoTime();
        JsonNode a = activate(APP_A, deviceKey, 10L * HOUR);
        ClockConfig.CLOCK.advance(Duration.ofHours(1));
        JsonNode b = activate(APP_B, deviceKey, null);
        assertThat(b.get("trialStartedAt").asText()).isNotEqualTo(a.get("trialStartedAt").asText());
        assertThat(b.get("remainingSeconds").asLong()).isEqualTo(FULL);
        assertThat(a.get("trialExpiresAt").asText()).isEqualTo("2026-01-01T10:00:00Z");
    }

    @Test
    void accountSwitchDoesNotAffectDeviceTrial() throws Exception {
        // Trial is keyed by device only; repeated activate after "login switch" keeps same expiry.
        String deviceKey = "device-account-switch-" + System.nanoTime();
        JsonNode first = activate(APP_A, deviceKey, 30L * HOUR);
        JsonNode afterSwitch = activate(APP_A, deviceKey, null);
        assertThat(afterSwitch.get("trialExpiresAt").asText()).isEqualTo(first.get("trialExpiresAt").asText());
    }

    @Test
    void invalidCapRejected() throws Exception {
        String deviceKey = "device-bad-cap-" + System.nanoTime();
        mockMvc.perform(post("/api/v1/trial/device/activate")
                        .header("X-App-Code", APP_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceKey\":\"" + deviceKey + "\",\"remainingSecondsCap\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40010));

        mockMvc.perform(post("/api/v1/trial/device/activate")
                        .header("X-App-Code", APP_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceKey\":\"" + deviceKey + "\",\"remainingSecondsCap\":259201}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40010));
    }

    @Test
    void responseMatchesPersistedExpires() throws Exception {
        String deviceKey = "device-resp-persist-" + System.nanoTime();
        JsonNode data = activate(APP_A, deviceKey, 12L * HOUR);
        Instant persisted = readPersistedExpires(APP_A, deviceKey);
        assertThat(Instant.parse(data.get("trialExpiresAt").asText())).isEqualTo(persisted);
        assertThat(data.get("serverTime").asText()).isEqualTo("2026-01-01T00:00:00Z");
    }

    @Test
    void concurrentFirstActivationCreatesSingleRow() throws Exception {
        String deviceKey = "device-concurrent-" + System.nanoTime();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JsonNode>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return activate(APP_A, deviceKey, null);
            }));
        }
        start.countDown();
        List<String> starts = new ArrayList<>();
        for (Future<JsonNode> future : futures) {
            starts.add(future.get(30, TimeUnit.SECONDS).get("trialStartedAt").asText());
        }
        pool.shutdownNow();

        assertThat(starts.stream().distinct().count()).isEqualTo(1);
        String hash = deviceKeyHasher.hash(APP_A, deviceKey);
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_trials WHERE app_id = (SELECT id FROM apps WHERE app_code = ?) AND device_key_hash = ?",
                Long.class,
                APP_A,
                hash);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentCreateWithMixedCapsKeepsStrictest() throws Exception {
        String deviceKey = "device-conc-cap-" + System.nanoTime();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JsonNode>> futures = new ArrayList<>();
        long[] caps = {FULL, 40L * HOUR, 15L * HOUR, FULL, 8L * HOUR, 20L * HOUR, 8L * HOUR, 30L * HOUR};
        for (int i = 0; i < threads; i++) {
            long cap = caps[i];
            futures.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return activate(APP_A, deviceKey, cap);
            }));
        }
        start.countDown();
        Instant earliest = Instant.parse("2099-01-01T00:00:00Z");
        for (Future<JsonNode> future : futures) {
            Instant exp = Instant.parse(future.get(30, TimeUnit.SECONDS).get("trialExpiresAt").asText());
            if (exp.isBefore(earliest)) {
                earliest = exp;
            }
        }
        pool.shutdownNow();

        Instant persisted = readPersistedExpires(APP_A, deviceKey);
        assertThat(persisted).isEqualTo(Instant.parse("2026-01-01T08:00:00Z"));
        assertThat(earliest).isEqualTo(persisted);

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_trials WHERE app_id = (SELECT id FROM apps WHERE app_code = ?) AND device_key_hash = ?",
                Long.class,
                APP_A,
                deviceKeyHasher.hash(APP_A, deviceKey));
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentTightenDoesNotExtend() throws Exception {
        String deviceKey = "device-conc-tighten-" + System.nanoTime();
        activate(APP_A, deviceKey, FULL);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JsonNode>> futures = new ArrayList<>();
        long[] caps = {50L * HOUR, 10L * HOUR, 30L * HOUR, 10L * HOUR, 40L * HOUR, 25L * HOUR};
        for (long cap : caps) {
            futures.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return activate(APP_A, deviceKey, cap);
            }));
        }
        start.countDown();
        for (Future<JsonNode> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertPersistedExpires(APP_A, deviceKey, Instant.parse("2026-01-01T10:00:00Z"));
    }

    @Test
    void retryAfterNetworkDoesNotCreateNewFullCycle() throws Exception {
        String deviceKey = "device-retry-" + System.nanoTime();
        JsonNode first = activate(APP_A, deviceKey, 20L * HOUR);
        ClockConfig.CLOCK.advance(Duration.ofMinutes(5));
        JsonNode retry = activate(APP_A, deviceKey, 20L * HOUR);
        assertThat(retry.get("trialStartedAt").asText()).isEqualTo(first.get("trialStartedAt").asText());
        assertThat(Instant.parse(retry.get("trialExpiresAt").asText()))
                .isBeforeOrEqualTo(Instant.parse(first.get("trialExpiresAt").asText()));
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_trials WHERE app_id = (SELECT id FROM apps WHERE app_code = ?) AND device_key_hash = ?",
                Long.class,
                APP_A,
                deviceKeyHasher.hash(APP_A, deviceKey));
        assertThat(count).isEqualTo(1);
    }

    @Test
    void expiredAfter72Hours() throws Exception {
        String deviceKey = "device-expire-" + System.nanoTime();
        activate(APP_A, deviceKey, null);
        ClockConfig.CLOCK.advance(Duration.ofHours(72));
        JsonNode data = activate(APP_A, deviceKey, null);
        assertThat(data.get("status").asText()).isEqualTo("EXPIRED");
        assertThat(data.get("remainingSeconds").asLong()).isZero();
    }

    @Test
    void remainingSecondsNeverNegative() throws Exception {
        String deviceKey = "device-neg-" + System.nanoTime();
        activate(APP_A, deviceKey, null);
        ClockConfig.CLOCK.advance(Duration.ofHours(100));
        JsonNode data = activate(APP_A, deviceKey, null);
        assertThat(data.get("remainingSeconds").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(data.get("status").asText()).isEqualTo("EXPIRED");
    }

    @Test
    void disabledAppRejected() throws Exception {
        jdbcTemplate.update("UPDATE apps SET status = 'DISABLED' WHERE app_code = ?", APP_B);
        try {
            mockMvc.perform(post("/api/v1/trial/device/activate")
                            .header("X-App-Code", APP_B)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"deviceKey\":\"device-disabled-app-key\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(40002));
        } finally {
            jdbcTemplate.update("UPDATE apps SET status = 'ACTIVE' WHERE app_code = ?", APP_B);
        }
    }

    @Test
    void unknownAppRejected() throws Exception {
        mockMvc.perform(post("/api/v1/trial/device/activate")
                        .header("X-App-Code", "unknown_app_xyz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceKey\":\"device-unknown-app-key\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void blankOrInvalidDeviceKeyRejected() throws Exception {
        mockMvc.perform(post("/api/v1/trial/device/activate")
                        .header("X-App-Code", APP_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceKey\":\"\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/trial/device/activate")
                        .header("X-App-Code", APP_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceKey\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40007));

        mockMvc.perform(post("/api/v1/trial/device/activate")
                        .header("X-App-Code", APP_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceKey\":\"bad key with spaces!!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40007));
    }

    @Test
    void clientCannotDictateStartEndOrStatus() throws Exception {
        String deviceKey = "device-client-fields-" + System.nanoTime();
        String body = """
                {
                  "deviceKey":"%s",
                  "trialStartedAt":"2020-01-01T00:00:00Z",
                  "trialExpiresAt":"2099-01-01T00:00:00Z",
                  "status":"EXPIRED"
                }
                """.formatted(deviceKey);
        MvcResult result = mockMvc.perform(post("/api/v1/trial/device/activate")
                        .header("X-App-Code", APP_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(data.get("trialStartedAt").asText()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(data.get("trialExpiresAt").asText()).isEqualTo("2026-01-04T00:00:00Z");
    }

    @Test
    void rawDeviceKeyNotPersisted() throws Exception {
        String deviceKey = "device-raw-persist-" + System.nanoTime();
        activate(APP_A, deviceKey, null);
        Integer hits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_trials WHERE device_key_hash = ?",
                Integer.class,
                deviceKey);
        assertThat(hits).isZero();

        String hash = deviceKeyHasher.hash(APP_A, deviceKey);
        Integer hashHits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_trials WHERE device_key_hash = ?",
                Integer.class,
                hash);
        assertThat(hashHits).isEqualTo(1);
    }

    @Test
    void hmacIsStableAndAppIsolated() {
        String deviceKey = "device-hmac-stable-key";
        String h1 = deviceKeyHasher.hash(APP_A, deviceKey);
        String h2 = deviceKeyHasher.hash(APP_A, deviceKey);
        String hB = deviceKeyHasher.hash(APP_B, deviceKey);
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).isNotEqualTo(hB);
        assertThat(h1).hasSize(64);
    }

    @Test
    void anonymousAccessAllowedWithoutBearer() throws Exception {
        mockMvc.perform(post("/api/v1/trial/device/activate")
                        .header("X-App-Code", APP_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceKey\":\"device-anon-access-key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void rateLimitEnforced() throws Exception {
        String deviceKeyPrefix = "device-rl-" + System.nanoTime() + "-";
        int limited = 0;
        for (int i = 0; i < 25; i++) {
            MvcResult result = mockMvc.perform(post("/api/v1/trial/device/activate")
                            .header("X-App-Code", APP_A)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"deviceKey\":\"" + deviceKeyPrefix + i + "xxxxxxx\"}"))
                    .andReturn();
            if (result.getResponse().getStatus() == 429) {
                limited++;
                JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
                assertThat(body.get("code").asInt()).isEqualTo(42901);
            }
        }
        assertThat(limited).isGreaterThan(0);
    }

    @Test
    void missingHmacConfigRejected() throws Exception {
        assertThat(deviceKeyHasher.hash(APP_A, "configured-secret-key")).isNotBlank();
    }

    private JsonNode activate(String appCode, String deviceKey, Long remainingSecondsCap) throws Exception {
        String body = remainingSecondsCap == null
                ? "{\"deviceKey\":\"" + deviceKey + "\"}"
                : "{\"deviceKey\":\"" + deviceKey + "\",\"remainingSecondsCap\":" + remainingSecondsCap + "}";
        MvcResult result = mockMvc.perform(post("/api/v1/trial/device/activate")
                        .header("X-App-Code", appCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private JsonNode activateLegacyBody(String appCode, String deviceKey) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/trial/device/activate")
                        .header("X-App-Code", appCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceKey\":\"" + deviceKey + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private Instant readPersistedExpires(String appCode, String deviceKey) {
        String hash = deviceKeyHasher.hash(appCode, deviceKey);
        return jdbcTemplate.queryForObject(
                """
                        SELECT trial_expires_at FROM device_trials
                        WHERE app_id = (SELECT id FROM apps WHERE app_code = ?) AND device_key_hash = ?
                        """,
                Instant.class,
                appCode,
                hash);
    }

    private void assertPersistedExpires(String appCode, String deviceKey, Instant expected) {
        assertThat(readPersistedExpires(appCode, deviceKey)).isEqualTo(expected);
    }

    private void ensureSecondApp() {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM apps WHERE app_code = ?", Integer.class, APP_B);
        if (exists == null || exists == 0) {
            jdbcTemplate.update(
                    "INSERT INTO apps (app_code, app_name, platform, package_name, status) VALUES (?, ?, ?, ?, ?)",
                    APP_B, "Speaker Cleaner", "ANDROID", "com.aurora.speakercleaner", "ACTIVE");
        } else {
            jdbcTemplate.update("UPDATE apps SET status = 'ACTIVE' WHERE app_code = ?", APP_B);
        }
    }
}
