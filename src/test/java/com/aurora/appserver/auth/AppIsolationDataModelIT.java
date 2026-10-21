package com.aurora.appserver.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aurora.appserver.auth.domain.AuthSessionEntity;
import com.aurora.appserver.auth.domain.UserEntity;
import com.aurora.appserver.auth.domain.UserStatus;
import com.aurora.appserver.auth.domain.WechatIdentityEntity;
import com.aurora.appserver.auth.repository.AuthSessionRepository;
import com.aurora.appserver.auth.repository.UserRepository;
import com.aurora.appserver.auth.repository.WechatIdentityRepository;
import com.aurora.appserver.support.ExternalMySqlSupport;
import com.aurora.appserver.trial.domain.DeviceTrialEntity;
import com.aurora.appserver.trial.repository.DeviceTrialRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AppIsolationDataModelIT {

    private static final String WECHAT_APPID = "wx_test_appid";

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        ExternalMySqlSupport.registerDatasource(registry);
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WechatIdentityRepository wechatIdentityRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private DeviceTrialRepository deviceTrialRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long appAId;
    private Long appBId;

    @BeforeEach
    void setUpApps() {
        appAId = jdbcTemplate.queryForObject(
                "SELECT id FROM apps WHERE app_code = ?",
                Long.class,
                "calculator_vault");
        assertThat(appAId).isNotNull();

        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM apps WHERE app_code = ?",
                Integer.class,
                "speaker_cleaner");
        if (exists != null && exists == 0) {
            jdbcTemplate.update(
                    "INSERT INTO apps (app_code, app_name, platform, package_name, status) VALUES (?, ?, ?, ?, ?)",
                    "speaker_cleaner",
                    "Speaker Cleaner",
                    "ANDROID",
                    "com.aurora.speakercleaner",
                    "ACTIVE");
        }
        appBId = jdbcTemplate.queryForObject(
                "SELECT id FROM apps WHERE app_code = ?",
                Long.class,
                "speaker_cleaner");
        assertThat(appBId).isNotEqualTo(appAId);
    }

    @Test
    void appsCanCreateUsersIndependently() {
        UserEntity userA = userRepository.saveAndFlush(UserEntity.create(appAId, UserStatus.ACTIVE));
        UserEntity userB = userRepository.saveAndFlush(UserEntity.create(appBId, UserStatus.ACTIVE));

        assertThat(userA.getId()).isNotNull();
        assertThat(userB.getId()).isNotNull();
        assertThat(userA.getAppId()).isEqualTo(appAId);
        assertThat(userB.getAppId()).isEqualTo(appBId);

        assertThat(userRepository.findByAppIdAndId(appAId, userA.getId())).isPresent();
        assertThat(userRepository.findByAppIdAndId(appBId, userA.getId())).isEmpty();
        assertThat(userRepository.findByAppIdAndId(appAId, userB.getId())).isEmpty();
        assertThat(userRepository.countByAppId(appAId)).isGreaterThanOrEqualTo(1);
        assertThat(userRepository.countByAppId(appBId)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void sameWechatOpenIdCanBindToDifferentApps() {
        UserEntity userA = userRepository.saveAndFlush(UserEntity.create(appAId, UserStatus.ACTIVE));
        UserEntity userB = userRepository.saveAndFlush(UserEntity.create(appBId, UserStatus.ACTIVE));

        String openid = "openid_shared_across_apps";
        WechatIdentityEntity identityA = wechatIdentityRepository.saveAndFlush(
                WechatIdentityEntity.create(appAId, userA.getId(), WECHAT_APPID, openid, null));
        WechatIdentityEntity identityB = wechatIdentityRepository.saveAndFlush(
                WechatIdentityEntity.create(appBId, userB.getId(), WECHAT_APPID, openid, "union_optional"));

        assertThat(identityA.getId()).isNotEqualTo(identityB.getId());
        assertThat(identityA.getUserId()).isEqualTo(userA.getId());
        assertThat(identityB.getUserId()).isEqualTo(userB.getId());
        assertThat(identityB.getUnionid()).isEqualTo("union_optional");

        assertThat(wechatIdentityRepository.findByAppIdAndWechatAppidAndOpenid(appAId, WECHAT_APPID, openid))
                .isPresent()
                .get()
                .extracting(WechatIdentityEntity::getUserId)
                .isEqualTo(userA.getId());
        assertThat(wechatIdentityRepository.findByAppIdAndWechatAppidAndOpenid(appBId, WECHAT_APPID, openid))
                .isPresent()
                .get()
                .extracting(WechatIdentityEntity::getUserId)
                .isEqualTo(userB.getId());
    }

    @Test
    void duplicateWechatIdentityWithinSameAppIsRejected() {
        UserEntity user = userRepository.saveAndFlush(UserEntity.create(appAId, UserStatus.ACTIVE));
        String openid = "openid_dup_same_app";
        wechatIdentityRepository.saveAndFlush(
                WechatIdentityEntity.create(appAId, user.getId(), WECHAT_APPID, openid, null));

        UserEntity anotherUser = userRepository.saveAndFlush(UserEntity.create(appAId, UserStatus.ACTIVE));
        assertConstraintViolation(() -> wechatIdentityRepository.saveAndFlush(
                WechatIdentityEntity.create(appAId, anotherUser.getId(), WECHAT_APPID, openid, null)));
    }

    @Test
    void crossAppWechatIdentityBindingIsRejectedByDatabase() {
        UserEntity userA = userRepository.saveAndFlush(UserEntity.create(appAId, UserStatus.ACTIVE));

        assertConstraintViolation(() -> wechatIdentityRepository.saveAndFlush(
                WechatIdentityEntity.create(appBId, userA.getId(), WECHAT_APPID, "openid_cross_app", null)));
    }

    @Test
    void crossAppAuthSessionAssociationIsRejectedByDatabase() {
        UserEntity userA = userRepository.saveAndFlush(UserEntity.create(appAId, UserStatus.ACTIVE));
        Instant expires = Instant.now().plus(1, ChronoUnit.DAYS);

        assertConstraintViolation(() -> authSessionRepository.saveAndFlush(
                AuthSessionEntity.create(appBId, userA.getId(), "hash_cross_app_session", expires)));
    }

    @Test
    void sameDeviceKeyHashCanHaveIndependentTrialsPerApp() {
        Instant now = Instant.now();
        Instant expires = now.plus(3, ChronoUnit.DAYS);
        String deviceHash = "device_hash_shared";

        DeviceTrialEntity trialA = deviceTrialRepository.saveAndFlush(
                DeviceTrialEntity.create(appAId, deviceHash, now, now, expires));
        DeviceTrialEntity trialB = deviceTrialRepository.saveAndFlush(
                DeviceTrialEntity.create(appBId, deviceHash, now, now, expires));

        assertThat(trialA.getId()).isNotEqualTo(trialB.getId());
        assertThat(deviceTrialRepository.findByAppIdAndDeviceKeyHash(appAId, deviceHash)).isPresent();
        assertThat(deviceTrialRepository.findByAppIdAndDeviceKeyHash(appBId, deviceHash)).isPresent();
        assertThat(deviceTrialRepository.existsByAppIdAndDeviceKeyHash(appAId, deviceHash)).isTrue();
    }

    @Test
    void sameAppCannotCreateDuplicateDeviceTrial() {
        Instant now = Instant.now();
        Instant expires = now.plus(3, ChronoUnit.DAYS);
        String deviceHash = "device_hash_dup";

        deviceTrialRepository.saveAndFlush(
                DeviceTrialEntity.create(appAId, deviceHash, now, now, expires));

        assertConstraintViolation(() -> deviceTrialRepository.saveAndFlush(
                DeviceTrialEntity.create(appAId, deviceHash, now, now, expires)));
    }

    @Test
    void unknownAppIdCannotCreateRelatedRecords() {
        long unknownAppId = 9_999_999_999L;

        assertConstraintViolation(() -> userRepository.saveAndFlush(
                UserEntity.create(unknownAppId, UserStatus.ACTIVE)));

        assertConstraintViolation(() -> jdbcTemplate.update(
                """
                INSERT INTO device_trials
                (app_id, device_key_hash, first_seen_at, trial_started_at, trial_expires_at)
                VALUES (?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
                """,
                unknownAppId,
                "device_unknown_app"));
    }

    @Test
    void tokenHashUniqueConstraintIsEnforced() {
        UserEntity userA = userRepository.saveAndFlush(UserEntity.create(appAId, UserStatus.ACTIVE));
        UserEntity userB = userRepository.saveAndFlush(UserEntity.create(appBId, UserStatus.ACTIVE));
        Instant expires = Instant.now().plus(1, ChronoUnit.DAYS);
        String sharedHash = "shared_token_hash_value";

        authSessionRepository.saveAndFlush(
                AuthSessionEntity.create(appAId, userA.getId(), sharedHash, expires));

        assertConstraintViolation(() -> authSessionRepository.saveAndFlush(
                AuthSessionEntity.create(appBId, userB.getId(), sharedHash, expires)));
    }

    @Test
    void repositoryQueriesAreAppScoped() {
        UserEntity userA = userRepository.saveAndFlush(UserEntity.create(appAId, UserStatus.ACTIVE));
        authSessionRepository.saveAndFlush(
                AuthSessionEntity.create(
                        appAId,
                        userA.getId(),
                        "scoped_token_hash",
                        Instant.now().plus(1, ChronoUnit.HOURS)));

        assertThat(authSessionRepository.findByAppIdAndTokenHash(appAId, "scoped_token_hash")).isPresent();
        assertThat(authSessionRepository.findByAppIdAndTokenHash(appBId, "scoped_token_hash")).isEmpty();
        assertThat(authSessionRepository.countByAppIdAndUserId(appAId, userA.getId())).isEqualTo(1);
        assertThat(authSessionRepository.countByAppIdAndUserId(appBId, userA.getId())).isZero();
    }

    private static void assertConstraintViolation(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfAny(DataIntegrityViolationException.class, UncategorizedSQLException.class);
    }
}
