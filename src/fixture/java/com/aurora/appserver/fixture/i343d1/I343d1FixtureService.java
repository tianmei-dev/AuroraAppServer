package com.aurora.appserver.fixture.i343d1;

import com.aurora.appserver.app.service.AppRegistryService;
import com.aurora.appserver.auth.config.AuroraAuthProperties;
import com.aurora.appserver.auth.domain.AuthSessionEntity;
import com.aurora.appserver.auth.domain.UserEntity;
import com.aurora.appserver.auth.domain.UserStatus;
import com.aurora.appserver.auth.domain.WechatIdentityEntity;
import com.aurora.appserver.auth.repository.AuthSessionRepository;
import com.aurora.appserver.auth.repository.UserRepository;
import com.aurora.appserver.auth.repository.WechatIdentityRepository;
import com.aurora.appserver.auth.service.AuthSessionService;
import com.aurora.appserver.auth.service.SessionTokenGenerator;
import com.aurora.appserver.auth.service.TokenHasher;
import com.aurora.appserver.membership.domain.GrantSourceType;
import com.aurora.appserver.membership.domain.PlanCode;
import com.aurora.appserver.membership.service.MembershipService;
import com.aurora.appserver.membership.service.MembershipService.GrantCommand;
import com.aurora.appserver.membership.service.MembershipService.MembershipStatusView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class I343d1FixtureService {

    private static final Logger log = LoggerFactory.getLogger(I343d1FixtureService.class);

    private final I343d1SafetyGate safetyGate;
    private final AppRegistryService appRegistryService;
    private final UserRepository userRepository;
    private final WechatIdentityRepository wechatIdentityRepository;
    private final AuthSessionRepository authSessionRepository;
    private final AuthSessionService authSessionService;
    private final SessionTokenGenerator sessionTokenGenerator;
    private final TokenHasher tokenHasher;
    private final MembershipService membershipService;
    private final AuroraAuthProperties authProperties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final I343d1MutableClock mutableClock;

    public I343d1FixtureService(
            I343d1SafetyGate safetyGate,
            AppRegistryService appRegistryService,
            UserRepository userRepository,
            WechatIdentityRepository wechatIdentityRepository,
            AuthSessionRepository authSessionRepository,
            AuthSessionService authSessionService,
            SessionTokenGenerator sessionTokenGenerator,
            TokenHasher tokenHasher,
            MembershipService membershipService,
            AuroraAuthProperties authProperties,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            @Autowired(required = false) I343d1MutableClock mutableClock
    ) {
        this.safetyGate = safetyGate;
        this.appRegistryService = appRegistryService;
        this.userRepository = userRepository;
        this.wechatIdentityRepository = wechatIdentityRepository;
        this.authSessionRepository = authSessionRepository;
        this.authSessionService = authSessionService;
        this.sessionTokenGenerator = sessionTokenGenerator;
        this.tokenHasher = tokenHasher;
        this.membershipService = membershipService;
        this.authProperties = authProperties;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.mutableClock = mutableClock;
    }

    public void seed() throws Exception {
        safetyGate.assertSafeToRun();
        requireMutableClock();
        cleanFixtureRows();
        ensureOtherApp();

        Long vaultAppId = appRegistryService.requireActiveApp(I343d1Constants.APP_CODE).appId();
        Long otherAppId = appRegistryService.requireActiveApp(I343d1Constants.OTHER_APP_CODE).appId();
        String wechatAppId = requireWechatAppId(I343d1Constants.APP_CODE);
        String otherWechatAppId = "wx_i343d1_other";

        Map<String, AccountSeed> seeds = new LinkedHashMap<>();

        AccountSeed none = createAccount(vaultAppId, wechatAppId, "none");
        seeds.put("none", none);

        AccountSeed monthly = createAccount(vaultAppId, wechatAppId, "monthly");
        grant(vaultAppId, monthly.userId(), PlanCode.MONTHLY, "monthly", "g1");
        seeds.put("monthly", monthly);

        AccountSeed yearly = createAccount(vaultAppId, wechatAppId, "yearly");
        grant(vaultAppId, yearly.userId(), PlanCode.YEARLY, "yearly", "g1");
        seeds.put("yearly", yearly);

        AccountSeed lifetime = createAccount(vaultAppId, wechatAppId, "lifetime");
        grant(vaultAppId, lifetime.userId(), PlanCode.LIFETIME, "lifetime", "g1");
        seeds.put("lifetime", lifetime);

        AccountSeed stack = createAccount(vaultAppId, wechatAppId, "stack");
        grant(vaultAppId, stack.userId(), PlanCode.MONTHLY, "stack", "monthly");
        grant(vaultAppId, stack.userId(), PlanCode.YEARLY, "stack", "yearly");
        seeds.put("stack", stack);

        AccountSeed expired = createAccount(vaultAppId, wechatAppId, "expired");
        mutableClock.setInstant(Instant.parse(I343d1Constants.EXPIRED_GRANT_INSTANT));
        try {
            grant(vaultAppId, expired.userId(), PlanCode.MONTHLY, "expired", "g1");
        } finally {
            mutableClock.resetToSystemUtc();
        }
        seeds.put("expired", expired);

        AccountSeed revoked = createAccount(vaultAppId, wechatAppId, "revoked");
        grant(vaultAppId, revoked.userId(), PlanCode.MONTHLY, "revoked", "g1");
        membershipService.revoke(
                vaultAppId,
                GrantSourceType.ADMIN,
                I343d1Constants.sourceRef("revoked", "g1"),
                "i343d1-fixture-revoke");
        seeds.put("revoked", revoked);

        AccountSeed other = createAccount(otherAppId, otherWechatAppId, "other-app-none");
        seeds.put("other-app-none", other);

        String expiredSessionToken = issueExpiredSession(vaultAppId, monthly.userId());
        String revokedSessionToken = issueThenRevokeSession(monthly);

        writeOutputs(seeds, expiredSessionToken, revokedSessionToken, monthly.userId());
        logSeedSummary(seeds);
    }

    public void clean() {
        safetyGate.assertSafeToRun();
        int removed = cleanFixtureRows();
        deleteTokenFiles();
        log.info("I3.4.3-D1 clean done fixtureUsersRemoved={}", removed);
    }

    private void requireMutableClock() {
        if (mutableClock == null) {
            throw new IllegalStateException(
                    "I3.4.3-D1 fixture requires i343d1.command so MutableClock is registered");
        }
    }

    private String requireWechatAppId(String appCode) {
        AuroraAuthProperties.AppCredentials credentials =
                authProperties.getWechat().getApps().get(appCode);
        if (credentials == null || credentials.getAppId() == null || credentials.getAppId().isBlank()) {
            throw new IllegalStateException("Missing wechat app-id for " + appCode + " under s32it");
        }
        return credentials.getAppId();
    }

    private void ensureOtherApp() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM apps WHERE app_code = ?",
                Integer.class,
                I343d1Constants.OTHER_APP_CODE);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO apps (app_code, app_name, platform, package_name, status) VALUES (?, ?, ?, ?, ?)",
                I343d1Constants.OTHER_APP_CODE,
                "I343D1 Other App",
                "ANDROID",
                I343d1Constants.OTHER_PACKAGE,
                "ACTIVE");
        log.info("Created fixture app_code={}", I343d1Constants.OTHER_APP_CODE);
    }

    private AccountSeed createAccount(Long appId, String wechatAppId, String label) {
        String openid = I343d1Constants.openid(label);
        if (!I343d1Constants.isFixtureOpenid(openid)) {
            throw new IllegalStateException("Refusing non-fixture openid");
        }
        UserEntity user = userRepository.saveAndFlush(UserEntity.create(appId, UserStatus.ACTIVE));
        wechatIdentityRepository.saveAndFlush(WechatIdentityEntity.create(
                appId, user.getId(), wechatAppId, openid, null));
        AuthSessionService.IssuedSession issued = authSessionService.issueSession(user);
        log.info("Seeded account label={} userId={} sessionId={} openid={}",
                label, user.getId(), issued.session().getId(), openid);
        return new AccountSeed(label, openid, user.getId(), appId, issued.rawToken(), issued.session().getId());
    }

    private void grant(Long appId, Long userId, PlanCode planCode, String label, String suffix) {
        String ref = I343d1Constants.sourceRef(label, suffix);
        if (!I343d1Constants.isFixtureSourceRef(ref)) {
            throw new IllegalStateException("Refusing non-fixture sourceRef");
        }
        MembershipStatusView status = membershipService.grant(new GrantCommand(
                appId,
                userId,
                planCode,
                GrantSourceType.ADMIN,
                ref,
                null
        )).status();
        log.info("Granted label={} plan={} status={} lifetime={} expiresPresent={}",
                label,
                planCode,
                status.status(),
                status.lifetime(),
                status.membershipExpiresAt() != null);
    }

    private String issueExpiredSession(Long appId, Long userId) {
        String raw = sessionTokenGenerator.generateRawToken();
        String hash = tokenHasher.sha256Hex(raw);
        AuthSessionEntity session = AuthSessionEntity.create(
                appId, userId, hash, Instant.parse("2020-06-01T00:00:00Z"));
        authSessionRepository.saveAndFlush(session);
        log.info("Issued already-expired fixture sessionId={} userId={}", session.getId(), userId);
        return raw;
    }

    private String issueThenRevokeSession(AccountSeed account) {
        UserEntity user = userRepository.findByAppIdAndId(account.appId(), account.userId())
                .orElseThrow();
        AuthSessionService.IssuedSession issued = authSessionService.issueSession(user);
        AuthSessionEntity session = authSessionRepository
                .findByAppIdAndId(account.appId(), issued.session().getId())
                .orElseThrow();
        session.revoke(Instant.now());
        authSessionRepository.saveAndFlush(session);
        log.info("Issued then revoked fixture sessionId={} userId={}", session.getId(), account.userId());
        return issued.rawToken();
    }

    /**
     * Deletes only i343d1-* identities/users/grants/sessions/entitlements.
     *
     * @return number of fixture user ids removed
     */
    @Transactional
    int cleanFixtureRows() {
        List<Long> userIds = jdbcTemplate.queryForList(
                """
                        SELECT DISTINCT user_id FROM wechat_identities
                        WHERE openid LIKE ?
                        """,
                Long.class,
                I343d1Constants.PREFIX + "%");

        int grantByRef = jdbcTemplate.update(
                "DELETE FROM membership_grants WHERE source_ref LIKE ? AND source_type = 'ADMIN'",
                I343d1Constants.PREFIX + "%");

        if (!userIds.isEmpty()) {
            String in = String.join(",", userIds.stream().map(String::valueOf).toList());
            jdbcTemplate.update("DELETE FROM auth_sessions WHERE user_id IN (" + in + ")");
            jdbcTemplate.update("DELETE FROM membership_grants WHERE user_id IN (" + in + ")");
            jdbcTemplate.update("DELETE FROM membership_entitlements WHERE user_id IN (" + in + ")");
            jdbcTemplate.update("DELETE FROM wechat_identities WHERE user_id IN (" + in + ")");
            jdbcTemplate.update("DELETE FROM users WHERE id IN (" + in + ")");
        }

        Long otherAppId = jdbcTemplate.query(
                "SELECT id FROM apps WHERE app_code = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                I343d1Constants.OTHER_APP_CODE);
        if (otherAppId != null) {
            Integer userCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE app_id = ?", Integer.class, otherAppId);
            Integer planCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM membership_plans WHERE app_id = ?", Integer.class, otherAppId);
            if ((userCount == null || userCount == 0) && (planCount == null || planCount == 0)) {
                jdbcTemplate.update("DELETE FROM apps WHERE id = ?", otherAppId);
            }
        }

        log.info("Cleaned fixture users={} grantsDeletedByRefAlso={}", userIds.size(), grantByRef);
        return userIds.size();
    }

    private void writeOutputs(
            Map<String, AccountSeed> seeds,
            String expiredSessionToken,
            String revokedSessionToken,
            Long monthlyUserId
    ) throws Exception {
        Path tokenPath = Path.of(I343d1Constants.TOKEN_FILE);
        Path metaPath = Path.of(I343d1Constants.META_FILE);
        Files.createDirectories(tokenPath.getParent());

        ObjectNode root = objectMapper.createObjectNode();
        root.put("generatedAt", Instant.now().toString());
        root.put("baseUrl", "http://127.0.0.1:8081");
        root.put("appCode", I343d1Constants.APP_CODE);
        root.put("warning", "SECRET — gitignored; do not paste into reports or commit");

        ArrayNode accounts = root.putArray("accounts");
        ObjectNode metaRoot = objectMapper.createObjectNode();
        metaRoot.put("generatedAt", Instant.now().toString());
        metaRoot.put("baseUrl", "http://127.0.0.1:8081");
        metaRoot.put("appCode", I343d1Constants.APP_CODE);
        ArrayNode metaAccounts = metaRoot.putArray("accounts");

        for (AccountSeed seed : seeds.values()) {
            ObjectNode a = accounts.addObject();
            a.put("label", seed.label());
            a.put("openid", seed.openid());
            a.put("userId", seed.userId());
            a.put("appId", seed.appId());
            a.put("sessionId", seed.sessionId());
            a.put("accessToken", seed.accessToken());
            a.put("expectedStatus", expectedStatus(seed.label()));

            ObjectNode m = metaAccounts.addObject();
            m.put("label", seed.label());
            m.put("openid", seed.openid());
            m.put("userId", seed.userId());
            m.put("appId", seed.appId());
            m.put("sessionId", seed.sessionId());
            m.put("expectedStatus", expectedStatus(seed.label()));
            m.put("hasToken", true);
        }

        ObjectNode expSess = accounts.addObject();
        expSess.put("label", "session-expired");
        expSess.put("userId", monthlyUserId);
        expSess.put("accessToken", expiredSessionToken);
        expSess.put("expectedHttp", 401);
        expSess.put("expectedCode", 40101);

        ObjectNode revSess = accounts.addObject();
        revSess.put("label", "session-revoked");
        revSess.put("userId", monthlyUserId);
        revSess.put("accessToken", revokedSessionToken);
        revSess.put("expectedHttp", 401);
        revSess.put("expectedCode", 40102);

        ObjectNode metaExp = metaAccounts.addObject();
        metaExp.put("label", "session-expired");
        metaExp.put("userId", monthlyUserId);
        metaExp.put("expectedHttp", 401);
        metaExp.put("expectedCode", 40101);
        metaExp.put("hasToken", true);

        ObjectNode metaRev = metaAccounts.addObject();
        metaRev.put("label", "session-revoked");
        metaRev.put("userId", monthlyUserId);
        metaRev.put("expectedHttp", 401);
        metaRev.put("expectedCode", 40102);
        metaRev.put("hasToken", true);

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tokenPath.toFile(), root);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(metaPath.toFile(), metaRoot);
        log.info("Wrote token file (gitignored) and meta file (no secrets in logs)");
    }

    private static String expectedStatus(String label) {
        return switch (label) {
            case "none", "other-app-none" -> "NONE";
            case "monthly", "yearly", "lifetime", "stack" -> "ACTIVE";
            case "expired" -> "EXPIRED";
            case "revoked" -> "REVOKED";
            default -> "UNKNOWN";
        };
    }

    private void logSeedSummary(Map<String, AccountSeed> seeds) {
        for (AccountSeed seed : seeds.values()) {
            MembershipStatusView view = membershipService.getStatus(seed.appId(), seed.userId());
            log.info(
                    "Summary label={} userId={} status={} planType={} lifetime={} activeGrants={}",
                    seed.label(),
                    seed.userId(),
                    view.status(),
                    view.planType(),
                    view.lifetime(),
                    view.activeGrantCount());
        }
    }

    private void deleteTokenFiles() {
        try {
            Files.deleteIfExists(Path.of(I343d1Constants.TOKEN_FILE));
            Files.deleteIfExists(Path.of(I343d1Constants.META_FILE));
        } catch (Exception ex) {
            log.warn("Could not delete token/meta files: {}", ex.toString());
        }
    }

    private record AccountSeed(
            String label,
            String openid,
            Long userId,
            Long appId,
            String accessToken,
            Long sessionId
    ) {
    }
}
