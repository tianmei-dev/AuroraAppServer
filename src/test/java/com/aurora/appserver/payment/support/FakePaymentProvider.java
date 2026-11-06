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
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Test-only provider (src/test, "test" profile). Never shipped in the production jar and
 * never performs network I/O. Not evidence of real Yusen protocol behaviour.
 */
@Component
@Profile("test")
public class FakePaymentProvider implements PaymentProvider {

    public static final String FAKE_PID = "fake-pid-0001";
    public static final String FAKE_KEY = "fake-key-for-tests-only";
    public static final String CHECKOUT_BASE = "https://fake-pay.invalid/checkout";

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

    @Override
    public PaymentLink buildCheckoutLink(CheckoutRequest request) {
        return new PaymentLink(PaymentLink.PayKind.URL,
                CHECKOUT_BASE + "?out_trade_no=" + request.orderNo()
                        + "&money=" + FenAmounts.toYuanString(request.amountFen()));
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
        return PaymentQueryResult.notConfirmed();
    }
}
