package com.aurora.appserver.membership.service;

import com.aurora.appserver.auth.domain.UserEntity;
import com.aurora.appserver.auth.repository.UserRepository;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import com.aurora.appserver.membership.domain.GrantSourceType;
import com.aurora.appserver.membership.domain.MembershipEntitlementEntity;
import com.aurora.appserver.membership.domain.MembershipGrantEntity;
import com.aurora.appserver.membership.domain.MembershipPlanEntity;
import com.aurora.appserver.membership.domain.MembershipStatus;
import com.aurora.appserver.membership.domain.PlanCode;
import com.aurora.appserver.membership.domain.PlanStatus;
import com.aurora.appserver.membership.repository.MembershipEntitlementRepository;
import com.aurora.appserver.membership.repository.MembershipGrantRepository;
import com.aurora.appserver.membership.repository.MembershipPlanRepository;
import com.aurora.appserver.membership.service.MembershipEntitlementCalculator.ComputedEntitlement;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Server-authoritative membership grant / revoke / status.
 * No public HTTP grant/revoke APIs — callers are internal services (future payment) and tests.
 */
@Service
public class MembershipService {

    private static final Logger log = LoggerFactory.getLogger(MembershipService.class);

    private final MembershipPlanRepository planRepository;
    private final MembershipGrantRepository grantRepository;
    private final MembershipEntitlementRepository entitlementRepository;
    private final UserRepository userRepository;
    private final Clock clock;
    private final Environment environment;
    private final MembershipService self;

    public MembershipService(
            MembershipPlanRepository planRepository,
            MembershipGrantRepository grantRepository,
            MembershipEntitlementRepository entitlementRepository,
            UserRepository userRepository,
            Clock clock,
            Environment environment,
            @Lazy MembershipService self
    ) {
        this.planRepository = planRepository;
        this.grantRepository = grantRepository;
        this.entitlementRepository = entitlementRepository;
        this.userRepository = userRepository;
        this.clock = clock;
        this.environment = environment;
        this.self = self;
    }

    public record GrantCommand(
            Long appId,
            Long userId,
            PlanCode planCode,
            GrantSourceType sourceType,
            String sourceRef,
            String orderId
    ) {
    }

    public record GrantResult(
            Long grantId,
            boolean created,
            MembershipStatusView status
    ) {
    }

    public record MembershipStatusView(
            MembershipStatus status,
            String planType,
            List<String> contributingPlanTypes,
            boolean lifetime,
            Instant membershipStartedAt,
            Instant membershipExpiresAt,
            Instant serverTime,
            int activeGrantCount
    ) {
    }

    public GrantResult grant(GrantCommand command) {
        validateGrantCommand(command);
        requireUserInApp(command.appId(), command.userId());
        rejectTestSourceInProduction(command.sourceType());
        ensureEntitlementRowQuietly(command.appId(), command.userId());
        return executeWithLockRetry(() -> {
            try {
                return self.grantInTx(command);
            } catch (DataIntegrityViolationException ex) {
                log.info("Concurrent idempotent membership grant recovered appId={} sourceRef={}",
                        command.appId(), command.sourceRef());
                return self.readIdempotentGrantResult(command);
            }
        });
    }

    @Transactional
    public GrantResult grantInTx(GrantCommand command) {
        MembershipPlanEntity plan = planRepository.findByAppIdAndPlanCode(command.appId(), command.planCode())
                .orElseThrow(() -> new BusinessException(ApiErrorCode.MEMBERSHIP_PLAN_NOT_FOUND));
        if (plan.getStatus() != PlanStatus.ACTIVE) {
            throw new BusinessException(ApiErrorCode.MEMBERSHIP_PLAN_DISABLED);
        }

        lockEntitlement(command.appId(), command.userId());

        String sourceRef = command.sourceRef().trim();
        var existing = grantRepository.findByAppIdAndSourceTypeAndSourceRef(
                command.appId(), command.sourceType(), sourceRef);
        if (existing.isPresent()) {
            log.info("Idempotent membership grant hit appId={} sourceType={} sourceRef={}",
                    command.appId(), command.sourceType(), sourceRef);
            return new GrantResult(existing.get().getId(), false, statusInTx(command.appId(), command.userId()));
        }

        Instant now = clock.instant();
        MembershipGrantEntity grant = MembershipGrantEntity.create(
                command.appId(),
                command.userId(),
                plan,
                command.sourceType(),
                sourceRef,
                command.orderId(),
                now
        );
        grantRepository.saveAndFlush(grant);
        refreshEntitlement(command.appId(), command.userId());
        log.info("Membership granted appId={} userId={} plan={} sourceType={} sourceRef={} grantId={}",
                command.appId(), command.userId(), plan.getPlanCode(), command.sourceType(),
                sourceRef, grant.getId());
        return new GrantResult(grant.getId(), true, statusInTx(command.appId(), command.userId()));
    }

    @Transactional(readOnly = true)
    public GrantResult readIdempotentGrantResult(GrantCommand command) {
        MembershipGrantEntity grant = grantRepository
                .findByAppIdAndSourceTypeAndSourceRef(
                        command.appId(), command.sourceType(), command.sourceRef().trim())
                .orElseThrow(() -> new BusinessException(ApiErrorCode.INTERNAL_ERROR));
        return new GrantResult(grant.getId(), false, statusInTx(command.appId(), command.userId()));
    }

    public MembershipStatusView revoke(
            Long appId,
            GrantSourceType sourceType,
            String sourceRef,
            String reason
    ) {
        if (appId == null || sourceType == null || !StringUtils.hasText(sourceRef)) {
            throw new BusinessException(ApiErrorCode.MEMBERSHIP_GRANT_SOURCE_INVALID);
        }
        String ref = sourceRef.trim();
        MembershipGrantEntity probe = grantRepository
                .findByAppIdAndSourceTypeAndSourceRef(appId, sourceType, ref)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.MEMBERSHIP_GRANT_NOT_FOUND));
        ensureEntitlementRowQuietly(appId, probe.getUserId());
        return executeWithLockRetry(() -> self.revokeInTx(appId, sourceType, ref, reason));
    }

    @Transactional
    public MembershipStatusView revokeInTx(
            Long appId,
            GrantSourceType sourceType,
            String sourceRef,
            String reason
    ) {
        MembershipGrantEntity grant = grantRepository
                .findByAppIdAndSourceTypeAndSourceRef(appId, sourceType, sourceRef)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.MEMBERSHIP_GRANT_NOT_FOUND));

        lockEntitlement(appId, grant.getUserId());

        grant = grantRepository
                .findByAppIdAndSourceTypeAndSourceRef(appId, sourceType, sourceRef)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.MEMBERSHIP_GRANT_NOT_FOUND));

        if (!grant.isRevoked()) {
            grant.revoke(clock.instant(), truncateReason(reason));
            grantRepository.saveAndFlush(grant);
            log.info("Membership revoked appId={} userId={} sourceType={} sourceRef={} grantId={}",
                    appId, grant.getUserId(), sourceType, sourceRef, grant.getId());
        } else {
            log.info("Idempotent membership revoke hit appId={} sourceType={} sourceRef={}",
                    appId, sourceType, sourceRef);
        }

        refreshEntitlement(appId, grant.getUserId());
        return statusInTx(appId, grant.getUserId());
    }

    @Transactional(readOnly = true)
    public MembershipStatusView getStatus(Long appId, Long userId) {
        requireUserInApp(appId, userId);
        return statusInTx(appId, userId);
    }

    private MembershipStatusView statusInTx(Long appId, Long userId) {
        List<MembershipGrantEntity> grants =
                grantRepository.findByAppIdAndUserIdOrderByGrantedAtAscIdAsc(appId, userId);
        Instant now = clock.instant();
        ComputedEntitlement computed = MembershipEntitlementCalculator.compute(grants, now);
        return toView(computed, now);
    }

    private void refreshEntitlement(Long appId, Long userId) {
        List<MembershipGrantEntity> grants =
                grantRepository.findByAppIdAndUserIdOrderByGrantedAtAscIdAsc(appId, userId);
        ComputedEntitlement computed = MembershipEntitlementCalculator.compute(grants, clock.instant());
        MembershipEntitlementEntity entitlement = entitlementRepository.findForUpdate(appId, userId)
                .orElseGet(() -> MembershipEntitlementEntity.empty(appId, userId));
        entitlement.apply(computed.lifetime(), computed.startedAt(), computed.expiresAt());
        entitlementRepository.saveAndFlush(entitlement);
    }

    /** Locks the per-user entitlement row. Caller must ensure the row exists first. */
    private MembershipEntitlementEntity lockEntitlement(Long appId, Long userId) {
        return entitlementRepository.findForUpdate(appId, userId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.INTERNAL_ERROR));
    }

    private void ensureEntitlementRowQuietly(Long appId, Long userId) {
        try {
            self.ensureEntitlementRow(appId, userId);
        } catch (DataIntegrityViolationException ex) {
            log.info("Concurrent entitlement ensure recovered appId={} userId={}", appId, userId);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureEntitlementRow(Long appId, Long userId) {
        if (entitlementRepository.findByAppIdAndUserId(appId, userId).isPresent()) {
            return;
        }
        entitlementRepository.saveAndFlush(MembershipEntitlementEntity.empty(appId, userId));
    }

    private <T> T executeWithLockRetry(java.util.function.Supplier<T> action) {
        int attempt = 0;
        while (true) {
            try {
                return action.get();
            } catch (PessimisticLockingFailureException ex) {
                attempt++;
                if (attempt >= 5) {
                    throw ex;
                }
                log.warn("Membership lock conflict, retrying attempt={}", attempt);
                try {
                    Thread.sleep(20L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }

    private void requireUserInApp(Long appId, Long userId) {
        UserEntity user = userRepository.findByAppIdAndId(appId, userId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.NOT_FOUND, "User not found in app"));
        if (!user.getAppId().equals(appId)) {
            throw new BusinessException(ApiErrorCode.FORBIDDEN);
        }
    }

    private void validateGrantCommand(GrantCommand command) {
        if (command == null
                || command.appId() == null
                || command.userId() == null
                || command.planCode() == null
                || command.sourceType() == null
                || !StringUtils.hasText(command.sourceRef())) {
            throw new BusinessException(ApiErrorCode.MEMBERSHIP_GRANT_SOURCE_INVALID);
        }
        if (command.sourceRef().trim().length() > 128) {
            throw new BusinessException(ApiErrorCode.MEMBERSHIP_GRANT_SOURCE_INVALID);
        }
    }

    private void rejectTestSourceInProduction(GrantSourceType sourceType) {
        if (sourceType != GrantSourceType.TEST) {
            return;
        }
        boolean nonProd = environment.acceptsProfiles(Profiles.of("test", "local", "dev"));
        if (!nonProd) {
            throw new BusinessException(ApiErrorCode.MEMBERSHIP_TEST_SOURCE_FORBIDDEN);
        }
    }

    private static MembershipStatusView toView(ComputedEntitlement computed, Instant serverTime) {
        return new MembershipStatusView(
                computed.status(),
                MembershipEntitlementCalculator.singlePlanTypeOrNull(
                        computed.contributingPlanTypes(), computed.lifetime()),
                MembershipEntitlementCalculator.planTypeNames(computed.contributingPlanTypes()),
                computed.lifetime(),
                computed.startedAt(),
                computed.expiresAt(),
                serverTime,
                computed.activeGrantCount()
        );
    }

    private static String truncateReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return null;
        }
        String trimmed = reason.trim();
        return trimmed.length() <= 255 ? trimmed : trimmed.substring(0, 255);
    }
}
