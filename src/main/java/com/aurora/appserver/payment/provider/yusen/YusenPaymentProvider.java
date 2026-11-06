package com.aurora.appserver.payment.provider.yusen;

import com.aurora.appserver.common.money.FenAmounts;
import com.aurora.appserver.payment.config.PaymentProperties;
import com.aurora.appserver.payment.domain.PaymentProviderCode;
import com.aurora.appserver.payment.provider.CheckoutRequest;
import com.aurora.appserver.payment.provider.PaymentLink;
import com.aurora.appserver.payment.provider.PaymentNotificationRejectedException;
import com.aurora.appserver.payment.provider.PaymentNotificationRejectedException.Reason;
import com.aurora.appserver.payment.provider.PaymentProvider;
import com.aurora.appserver.payment.provider.PaymentQueryResult;
import com.aurora.appserver.payment.provider.VerifiedNotification;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Yusen unified checkout via submit.php without {@code type}, so the user picks the payment
 * method on Yusen's hosted cashier page (merchant doc "页面跳转支付": type 不传会跳转到收银台支付).
 *
 * <p>Network calls (order query) are not enabled yet; link building and notification
 * verification are pure computations.
 */
@Component
public class YusenPaymentProvider implements PaymentProvider {

    static final String SUBMIT_PATH = "/submit.php";
    static final String TRADE_SUCCESS = "TRADE_SUCCESS";
    static final int NAME_MAX_BYTES = 127;

    private final PaymentProperties.Yusen config;

    public YusenPaymentProvider(PaymentProperties properties) {
        this.config = properties.getYusen();
    }

    @Override
    public PaymentProviderCode code() {
        return PaymentProviderCode.YUSEN;
    }

    @Override
    public boolean isConfigured() {
        return config.isComplete();
    }

    @Override
    public String merchantPid() {
        return config.getPid();
    }

    @Override
    public PaymentLink buildCheckoutLink(CheckoutRequest request) {
        requireConfigured();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", config.getPid());
        params.put("out_trade_no", request.orderNo());
        params.put("notify_url", config.getNotifyUrl());
        params.put("return_url", config.getReturnUrl());
        params.put("name", truncateUtf8(request.subject(), NAME_MAX_BYTES));
        params.put("money", FenAmounts.toYuanString(request.amountFen()));

        String sign = YusenSigner.sign(params, config.getKey());

        Map<String, String> query = new TreeMap<>(params);
        query.put(YusenSigner.SIGN, sign);
        query.put(YusenSigner.SIGN_TYPE, "MD5");

        String queryString = query.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        return new PaymentLink(PaymentLink.PayKind.URL, gatewayBase() + SUBMIT_PATH + "?" + queryString);
    }

    @Override
    public VerifiedNotification verifyNotification(Map<String, String> params) {
        requireConfigured();
        if (params == null) {
            throw new PaymentNotificationRejectedException(Reason.MISSING_FIELDS);
        }
        if (!config.getPid().equals(params.get("pid"))) {
            throw new PaymentNotificationRejectedException(Reason.PID_MISMATCH);
        }
        if (!YusenSigner.verify(params, config.getKey())) {
            throw new PaymentNotificationRejectedException(Reason.SIGN_INVALID);
        }
        String orderNo = params.get("out_trade_no");
        String tradeStatus = params.get("trade_status");
        if (isBlank(orderNo) || isBlank(tradeStatus)) {
            throw new PaymentNotificationRejectedException(Reason.MISSING_FIELDS);
        }
        String amountRaw = params.get("money");
        return new VerifiedNotification(
                orderNo,
                params.get("trade_no"),
                amountRaw,
                FenAmounts.parseYuanToFen(amountRaw),
                params.get("type"),
                tradeStatus,
                TRADE_SUCCESS.equals(tradeStatus));
    }

    @Override
    public PaymentQueryResult queryOrder(String orderNo) {
        throw new UnsupportedOperationException("Yusen order query is not enabled");
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("Yusen payment is not configured");
        }
    }

    private String gatewayBase() {
        String base = config.getGatewayBaseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /** URL-encodes a raw value. The signature is always computed over raw values, never these. */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String truncateUtf8(String value, int maxBytes) {
        if (value == null) {
            return "";
        }
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return value;
        }
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            int cpBytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + cpBytes > maxBytes) {
                break;
            }
            out.appendCodePoint(cp);
            bytes += cpBytes;
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
