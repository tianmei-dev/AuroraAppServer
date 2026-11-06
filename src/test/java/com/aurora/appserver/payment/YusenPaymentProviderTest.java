package com.aurora.appserver.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aurora.appserver.payment.config.PaymentProperties;
import com.aurora.appserver.payment.provider.CheckoutRequest;
import com.aurora.appserver.payment.provider.PaymentLink;
import com.aurora.appserver.payment.provider.PaymentNotificationRejectedException;
import com.aurora.appserver.payment.provider.VerifiedNotification;
import com.aurora.appserver.payment.provider.yusen.YusenPaymentProvider;
import com.aurora.appserver.payment.provider.yusen.YusenSigner;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure unit tests with a fictional merchant. No network, no real credentials. */
class YusenPaymentProviderTest {

    private static final String KEY = "fictional-merchant-key-0001";
    private static final String NOTIFY = "https://api.example.test/api/v1/payment/notify/yusen";
    private static final String RETURN = "https://api.example.test/api/v1/payment/return/yusen?from=app&x=1";

    private static YusenPaymentProvider provider() {
        PaymentProperties properties = new PaymentProperties();
        properties.getYusen().setGatewayBaseUrl("https://gateway.example.test/");
        properties.getYusen().setPid("1001");
        properties.getYusen().setKey(KEY);
        properties.getYusen().setNotifyUrl(NOTIFY);
        properties.getYusen().setReturnUrl(RETURN);
        return new YusenPaymentProvider(properties);
    }

    @Test
    void checkoutLinkTargetsSubmitPhpWithoutTypeAndWithoutKey() {
        PaymentLink link = provider().buildCheckoutLink(
                new CheckoutRequest("CV20261106131500ABCDEFGH2345", 1990, "年卡"));

        assertThat(link.kind()).isEqualTo(PaymentLink.PayKind.URL);
        URI uri = URI.create(link.value());
        assertThat(uri.getScheme() + "://" + uri.getHost() + uri.getPath())
                .isEqualTo("https://gateway.example.test/submit.php");

        Map<String, String> query = parseQuery(uri.getRawQuery());
        assertThat(query).doesNotContainKey("type");
        assertThat(query).doesNotContainKey("key");
        assertThat(link.value()).doesNotContain(KEY);
        assertThat(query).containsEntry("pid", "1001")
                .containsEntry("out_trade_no", "CV20261106131500ABCDEFGH2345")
                .containsEntry("money", "19.90")
                .containsEntry("name", "年卡")
                .containsEntry("notify_url", NOTIFY)
                .containsEntry("return_url", RETURN)
                .containsEntry("sign_type", "MD5");
    }

    @Test
    void urlEncodingIsSeparateFromSignatureComputation() {
        PaymentLink link = provider().buildCheckoutLink(
                new CheckoutRequest("CV20261106131500ABCDEFGH2345", 1990, "年卡"));
        String raw = URI.create(link.value()).getRawQuery();

        assertThat(raw).contains("name=%E5%B9%B4%E5%8D%A1");
        assertThat(raw).contains("notify_url=https%3A%2F%2Fapi.example.test%2Fapi%2Fv1%2Fpayment%2Fnotify%2Fyusen");
        assertThat(raw).contains("return_url=https%3A%2F%2Fapi.example.test%2Fapi%2Fv1%2Fpayment%2Freturn%2Fyusen%3Ffrom%3Dapp%26x%3D1");
        assertThat(raw).doesNotContain("+");

        Map<String, String> decoded = parseQuery(raw);
        String expectedSign = YusenSigner.sign(decoded, KEY);
        assertThat(decoded.get("sign")).isEqualTo(expectedSign);
        assertThat(YusenSigner.verify(decoded, KEY)).isTrue();
    }

    @Test
    void sameOrderProducesDeterministicLink() {
        CheckoutRequest request = new CheckoutRequest("CV20261106131500ABCDEFGH2345", 590, "月卡");
        assertThat(provider().buildCheckoutLink(request).value())
                .isEqualTo(provider().buildCheckoutLink(request).value());
    }

    @Test
    void paymentLinkToStringRedactsSignedUrl() {
        PaymentLink link = provider().buildCheckoutLink(new CheckoutRequest("CV1", 590, "月卡"));
        assertThat(link.toString()).doesNotContain("submit.php").doesNotContain("sign=");
    }

    @Test
    void subjectIsTruncatedToDocumentedByteLimitOnCodePointBoundary() {
        String longName = "会".repeat(60);
        PaymentLink link = provider().buildCheckoutLink(new CheckoutRequest("CV1", 590, longName));
        String name = parseQuery(URI.create(link.value()).getRawQuery()).get("name");
        assertThat(name.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(127);
        assertThat(name).isEqualTo("会".repeat(42));
    }

    @Test
    void verifyNotificationAcceptsValidAndRejectsPidOrSignature() {
        YusenPaymentProvider provider = provider();
        Map<String, String> params = new HashMap<>();
        params.put("pid", "1001");
        params.put("trade_no", "Y2026110600001");
        params.put("out_trade_no", "CV20261106131500ABCDEFGH2345");
        params.put("type", "wxpay");
        params.put("name", "年卡");
        params.put("money", "19.90");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("sign", YusenSigner.sign(params, KEY));
        params.put("sign_type", "MD5");

        VerifiedNotification verified = provider.verifyNotification(params);
        assertThat(verified.tradeSuccess()).isTrue();
        assertThat(verified.amountFen()).contains(1990);
        assertThat(verified.orderNo()).isEqualTo("CV20261106131500ABCDEFGH2345");

        Map<String, String> wrongPid = new HashMap<>(params);
        wrongPid.put("pid", "9999");
        assertThatThrownBy(() -> provider.verifyNotification(wrongPid))
                .isInstanceOf(PaymentNotificationRejectedException.class)
                .extracting(e -> ((PaymentNotificationRejectedException) e).getReason())
                .isEqualTo(PaymentNotificationRejectedException.Reason.PID_MISMATCH);

        Map<String, String> tampered = new HashMap<>(params);
        tampered.put("money", "0.01");
        assertThatThrownBy(() -> provider.verifyNotification(tampered))
                .isInstanceOf(PaymentNotificationRejectedException.class)
                .extracting(e -> ((PaymentNotificationRejectedException) e).getReason())
                .isEqualTo(PaymentNotificationRejectedException.Reason.SIGN_INVALID);
    }

    @Test
    void queryIsNotEnabledAndUnconfiguredProviderRefusesToBuildLinks() {
        assertThatThrownBy(() -> provider().queryOrder("CV1"))
                .isInstanceOf(UnsupportedOperationException.class);

        YusenPaymentProvider unconfigured = new YusenPaymentProvider(new PaymentProperties());
        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.buildCheckoutLink(new CheckoutRequest("CV1", 590, "月卡")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void propertiesToStringNeverPrintsKey() {
        PaymentProperties properties = new PaymentProperties();
        properties.getYusen().setKey(KEY);
        assertThat(properties.getYusen().toString()).doesNotContain(KEY).contains("key=***");
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }
}
