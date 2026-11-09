package com.aurora.appserver.payment.support;

import com.aurora.appserver.common.money.FenAmounts;
import com.aurora.appserver.payment.domain.PaymentProviderCode;
import com.aurora.appserver.payment.provider.CheckoutRequest;
import com.aurora.appserver.payment.provider.PaymentLink;
import com.aurora.appserver.payment.provider.PaymentNotificationRejectedException;
import com.aurora.appserver.payment.provider.PaymentNotificationRejectedException.Reason;
import com.aurora.appserver.payment.provider.PaymentProvider;
import com.aurora.appserver.payment.provider.PaymentQueryResult;
import com.aurora.appserver.payment.provider.VerifiedNotification;
import com.aurora.appserver.payment.provider.yusen.YusenSigner;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fake provider for automated tests ("test") and the isolated 8081 environment ("s32it").
 * Lives in src/fixture, so it is never packaged in the production bootJar, and never performs
 * network I/O. Query behaviour is programmable per order; state is static so a second application
 * context in the same JVM (restart simulation) sees the same behaviour.
 * Not evidence of real Yusen protocol behaviour.
 */
@Component
@Profile({"test", "s32it"})
public class FakePaymentProvider implements PaymentProvider {

    public static final String FAKE_PID = "fake-pid-0001";
    public static final String FAKE_KEY = "fake-key-for-tests-only";

    public enum Mode {
        UNPAID,
        PAID,
        TIMEOUT,
        ERROR,
        THROW
    }

    public record Behavior(Mode mode, Integer amountFen, String pid, String orderNoOverride, long delayMs) {
    }

    private static final Map<String, Behavior> BEHAVIORS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> QUERY_COUNTS = new ConcurrentHashMap<>();

    public static void reset() {
        BEHAVIORS.clear();
        QUERY_COUNTS.clear();
    }

    public static void unpaid(String orderNo) {
        BEHAVIORS.put(orderNo, new Behavior(Mode.UNPAID, null, null, null, 0));
    }

    public static void paid(String orderNo, int amountFen) {
        BEHAVIORS.put(orderNo, new Behavior(Mode.PAID, amountFen, FAKE_PID, null, 0));
    }

    public static void paidWith(String orderNo, int amountFen, String pid, String reportedOrderNo) {
        BEHAVIORS.put(orderNo, new Behavior(Mode.PAID, amountFen, pid, reportedOrderNo, 0));
    }

    public static void mode(String orderNo, Mode mode) {
        BEHAVIORS.put(orderNo, new Behavior(mode, null, null, null, 0));
    }

    public static void configure(String orderNo, Mode mode, Integer amountFen, long delayMs) {
        BEHAVIORS.put(orderNo, new Behavior(mode, amountFen, FAKE_PID, null, Math.max(0, delayMs)));
    }

    public static Behavior behavior(String orderNo) {
        return BEHAVIORS.get(orderNo);
    }

    public static int queryCount(String orderNo) {
        AtomicInteger count = QUERY_COUNTS.get(orderNo);
        return count == null ? 0 : count.get();
    }

    /** Notification parameters signed with the fake key, mirroring Yusen's notify fields. */
    public static Map<String, String> signedNotification(String orderNo, String money, String tradeStatus) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", FAKE_PID);
        params.put("trade_no", "FAKE-T-" + orderNo);
        params.put("out_trade_no", orderNo);
        params.put("type", "wxpay");
        params.put("name", "会员");
        params.put("money", money);
        params.put("trade_status", tradeStatus);
        params.put("sign", YusenSigner.sign(params, FAKE_KEY));
        params.put("sign_type", "MD5");
        return params;
    }

    @Override
    public PaymentProviderCode code() {
        return PaymentProviderCode.FAKE;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public String merchantPid() {
        return FAKE_PID;
    }

    /** No fake website: clients receive kind=TEST and show a test-payment hint instead of a link. */
    @Override
    public PaymentLink buildCheckoutLink(CheckoutRequest request) {
        return PaymentLink.testPayment();
    }

    @Override
    public VerifiedNotification verifyNotification(Map<String, String> params) {
        if (!FAKE_PID.equals(params.get("pid"))) {
            throw new PaymentNotificationRejectedException(Reason.PID_MISMATCH);
        }
        if (!YusenSigner.verify(params, FAKE_KEY)) {
            throw new PaymentNotificationRejectedException(Reason.SIGN_INVALID);
        }
        String amountRaw = params.get("money");
        String status = params.get("trade_status");
        return new VerifiedNotification(
                params.get("out_trade_no"),
                params.get("trade_no"),
                amountRaw,
                FenAmounts.parseYuanToFen(amountRaw),
                params.get("type"),
                status,
                "TRADE_SUCCESS".equals(status));
    }

    @Override
    public PaymentQueryResult queryOrder(String orderNo) {
        QUERY_COUNTS.computeIfAbsent(orderNo, k -> new AtomicInteger()).incrementAndGet();
        Behavior behavior = BEHAVIORS.getOrDefault(orderNo, new Behavior(Mode.UNPAID, null, null, null, 0));
        if (behavior.delayMs() > 0) {
            try {
                Thread.sleep(behavior.delayMs());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        return switch (behavior.mode()) {
            case UNPAID -> PaymentQueryResult.notConfirmed(orderNo);
            case TIMEOUT -> PaymentQueryResult.inconclusive(PaymentQueryResult.Outcome.TIMEOUT, null, null, "timeout");
            case ERROR -> PaymentQueryResult.inconclusive(PaymentQueryResult.Outcome.ERROR, 500, null, "http_status");
            case THROW -> throw new IllegalStateException("fake provider failure");
            case PAID -> new PaymentQueryResult(
                    PaymentQueryResult.Outcome.PAID,
                    behavior.orderNoOverride() != null ? behavior.orderNoOverride() : orderNo,
                    behavior.pid(),
                    "FAKE-T-" + orderNo,
                    "FAKE-UP-" + orderNo,
                    FenAmounts.toYuanString(behavior.amountFen()),
                    Optional.of(behavior.amountFen()),
                    "wxpay",
                    "2026-11-06 21:00:00",
                    200,
                    "1",
                    "ok");
        };
    }
}
