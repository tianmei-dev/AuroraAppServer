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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Yusen unified checkout via submit.php without {@code type}, so the user picks the payment
 * method on Yusen's hosted cashier page (merchant doc "页面跳转支付": type 不传会跳转到收银台支付).
 *
 * <p>Order query follows doc "[API]查询单个订单" (GET api.php?act=order). Outbound HTTP goes through
 * {@link YusenHttpTransport}; the default transport refuses to connect unless explicitly enabled.
 */
@Component
public class YusenPaymentProvider implements PaymentProvider {

    static final String SUBMIT_PATH = "/submit.php";
    static final String API_PATH = "/api.php";
    static final String TRADE_SUCCESS = "TRADE_SUCCESS";
    static final int NAME_MAX_BYTES = 127;

    private final PaymentProperties.Yusen config;
    private final YusenHttpTransport transport;
    private final ObjectMapper objectMapper;

    @Autowired
    public YusenPaymentProvider(PaymentProperties properties, YusenHttpTransport transport, ObjectMapper objectMapper) {
        this.config = properties.getYusen();
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    /** For pure unit tests: real transport (network disabled unless configured). */
    public YusenPaymentProvider(PaymentProperties properties) {
        this(properties, new JdkYusenHttpTransport(properties), new ObjectMapper());
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

    /**
     * Mapping: code=1 & status=1 -> PAID; code=1 & status=0 -> NOT_CONFIRMED; anything else
     * (code!=1 such as "order not found", HTTP error, bad JSON, timeout) -> inconclusive.
     */
    @Override
    public PaymentQueryResult queryOrder(String orderNo) {
        if (!isConfigured()) {
            return PaymentQueryResult.inconclusive(PaymentQueryResult.Outcome.ERROR, null, null, "not_configured");
        }
        URI uri = URI.create(gatewayBase() + API_PATH
                + "?act=order&pid=" + encode(config.getPid())
                + "&key=" + encode(config.getKey())
                + "&out_trade_no=" + encode(orderNo));

        YusenHttpTransport.Response response;
        try {
            response = transport.get(uri, config.getRequestTimeout());
        } catch (HttpTimeoutException ex) {
            return PaymentQueryResult.inconclusive(PaymentQueryResult.Outcome.TIMEOUT, null, null, "timeout");
        } catch (IOException ex) {
            return PaymentQueryResult.inconclusive(PaymentQueryResult.Outcome.ERROR, null, null, "io_error");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return PaymentQueryResult.inconclusive(PaymentQueryResult.Outcome.ERROR, null, null, "interrupted");
        } catch (RuntimeException ex) {
            // Never propagate the message: transports may embed the request URI (with key).
            return PaymentQueryResult.inconclusive(PaymentQueryResult.Outcome.ERROR, null, null,
                    "transport_" + ex.getClass().getSimpleName());
        }

        if (response.status() != 200) {
            return PaymentQueryResult.inconclusive(
                    PaymentQueryResult.Outcome.ERROR, response.status(), null, "http_status");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(response.body() == null ? "" : response.body());
        } catch (IOException ex) {
            return PaymentQueryResult.inconclusive(PaymentQueryResult.Outcome.ERROR, 200, null, "invalid_json");
        }
        if (node == null || !node.isObject()) {
            return PaymentQueryResult.inconclusive(PaymentQueryResult.Outcome.ERROR, 200, null, "invalid_json");
        }

        String code = text(node, "code");
        String msg = redact(text(node, "msg"));
        if (!"1".equals(code)) {
            return PaymentQueryResult.inconclusive(PaymentQueryResult.Outcome.ERROR, 200, code, msg);
        }

        String status = text(node, "status");
        PaymentQueryResult.Outcome outcome;
        if ("1".equals(status)) {
            outcome = PaymentQueryResult.Outcome.PAID;
        } else if ("0".equals(status)) {
            outcome = PaymentQueryResult.Outcome.NOT_CONFIRMED;
        } else {
            return PaymentQueryResult.inconclusive(PaymentQueryResult.Outcome.ERROR, 200, code, "unknown_status");
        }
        String amountRaw = text(node, "money");
        return new PaymentQueryResult(
                outcome,
                text(node, "out_trade_no"),
                text(node, "pid"),
                text(node, "trade_no"),
                text(node, "api_trade_no"),
                amountRaw,
                FenAmounts.parseYuanToFen(amountRaw),
                text(node, "type"),
                text(node, "endtime"),
                200,
                code,
                msg);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private String redact(String msg) {
        if (msg == null) {
            return null;
        }
        String key = config.getKey();
        return key == null || key.isEmpty() ? msg : msg.replace(key, "***");
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
