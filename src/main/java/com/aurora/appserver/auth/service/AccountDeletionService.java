package com.aurora.appserver.auth.service;

import com.aurora.appserver.auth.domain.UserEntity;
import com.aurora.appserver.auth.domain.UserStatus;
import com.aurora.appserver.auth.repository.AuthSessionRepository;
import com.aurora.appserver.auth.repository.UserRepository;
import com.aurora.appserver.auth.repository.WechatIdentityRepository;
import com.aurora.appserver.auth.security.SessionPrincipal;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import com.aurora.appserver.membership.service.MembershipService;
import com.aurora.appserver.payment.domain.PaymentAnomaly;
import com.aurora.appserver.payment.domain.PaymentOrderEntity;
import com.aurora.appserver.payment.domain.PaymentOrderStatus;
import com.aurora.appserver.payment.repository.PaymentOrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User-initiated account closure within a single app scope.
 *
 * <p>WeChat bindings and sessions are physically deleted. The {@code users} row is retained as a
 * {@link UserStatus#DISABLED} tombstone because payment orders and membership grant ledgers keep
 * non-null FKs to {@code users(app_id, id)} and must not be deleted while retention rules are
 * unsettled. {@code device_trials} are untouched.
 *
 * <p>Unexpired pending orders and PAID-but-unfulfilled orders block deletion (no Yusen cancel).
 * After deletion, late valid payments keep the order row and remain blocked from membership grant.
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);
    private static final String REVOKE_REASON = "ACCOUNT_DELETED";

    private final UserRepository userRepository;
    private final WechatIdentityRepository wechatIdentityRepository;
    private final AuthSessionRepository authSessionRepository;
    private final MembershipService membershipService;
    private final PaymentOrderRepository paymentOrderRepository;
    private final Clock clock;

    public AccountDeletionService(
            UserRepository userRepository,
            WechatIdentityRepository wechatIdentityRepository,
            AuthSessionRepository authSessionRepository,
            MembershipService membershipService,
            PaymentOrderRepository paymentOrderRepository,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.wechatIdentityRepository = wechatIdentityRepository;
        this.authSessionRepository = authSessionRepository;
        this.membershipService = membershipService;
        this.paymentOrderRepository = paymentOrderRepository;
        this.clock = clock;
    }

    @Transactional
    public void deleteCurrentAccount(SessionPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ApiErrorCode.UNAUTHORIZED);
        }
        Long appId = principal.appId();
        Long userId = principal.userId();

        UserEntity user = userRepository.findForUpdate(appId, userId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.UNAUTHORIZED));

        Instant now = clock.instant();
        List<PaymentOrderEntity> orders = paymentOrderRepository.findByAppIdAndUserId(appId, userId);
        rejectIfBlockingOrders(orders, now);

        membershipService.revokeAllForAccountDeletion(appId, userId, REVOKE_REASON);

        wechatIdentityRepository.deleteByAppIdAndUserId(appId, userId);
        authSessionRepository.deleteByAppIdAndUserId(appId, userId);

        tagRetainedOrdersForLatePaymentBlock(orders);

        if (user.getStatus() != UserStatus.DISABLED) {
            user.disable();
            userRepository.saveAndFlush(user);
        }

        log.info("Account deleted appId={} userId={} (user row retained as DISABLED tombstone)",
                appId, userId);
    }

    private static void rejectIfBlockingOrders(List<PaymentOrderEntity> orders, Instant now) {
        for (PaymentOrderEntity order : orders) {
            if (order.getStatus() == PaymentOrderStatus.PAID) {
                throw new BusinessException(ApiErrorCode.ACCOUNT_DELETE_PAID_UNFULFILLED);
            }
        }
        for (PaymentOrderEntity order : orders) {
            if (order.getStatus() == PaymentOrderStatus.PENDING_PAYMENT && now.isBefore(order.getExpiresAt())) {
                throw new BusinessException(ApiErrorCode.ACCOUNT_DELETE_PENDING_ORDER);
            }
        }
    }

    /**
     * Mark retained EXPIRED / past-due PENDING / FULFILLED rows so a late Yusen payment cannot
     * grant membership to the closed account. Does not cancel upstream checkout.
     */
    private void tagRetainedOrdersForLatePaymentBlock(List<PaymentOrderEntity> orders) {
        for (PaymentOrderEntity order : orders) {
            if (order.getStatus() == PaymentOrderStatus.PAID) {
                continue;
            }
            if (!order.isAccountDeletedAnomaly()) {
                order.markAnomaly(PaymentAnomaly.ACCOUNT_DELETED);
                paymentOrderRepository.save(order);
            }
        }
        paymentOrderRepository.flush();
    }
}
