package com.aurora.appserver.payment.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.aurora.appserver.payment.config.PaymentProperties;
import com.aurora.appserver.payment.domain.PaymentEnvironment;
import com.aurora.appserver.trial.support.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** I3.5.5-B1 T16: alerting never sends without a webhook, never throws, never leaks secrets. */
class PaymentAlertServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-10T13:00:00Z");

    private static class RecordingSender implements PaymentAlertSender {
        final boolean enabled;
        final List<String> payloads = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch latch;
        final boolean fail;

        RecordingSender(boolean enabled, int expected, boolean fail) {
            this.enabled = enabled;
            this.latch = new CountDownLatch(expected);
            this.fail = fail;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void send(String jsonPayload) throws Exception {
            calls.incrementAndGet();
            payloads.add(jsonPayload);
            latch.countDown();
            if (fail) {
                throw new java.io.IOException("simulated webhook failure");
            }
        }
    }

    private static PaymentAlertService service(PaymentAlertSender sender, MutableClock clock) {
        PaymentProperties properties = new PaymentProperties();
        properties.setEnvironment(PaymentEnvironment.STAGING);
        return new PaymentAlertService(sender, properties, new ObjectMapper(), clock);
    }

    @Test
    void t16_noWebhookMeansNothingIsSent() throws Exception {
        RecordingSender sender = new RecordingSender(false, 1, false);
        PaymentAlertService alerts = service(sender, new MutableClock(T0));
        alerts.alert(PaymentAlertType.QUERY_MISMATCH, "CV1", "anomaly=QUERY_AMOUNT_MISMATCH");
        assertThat(sender.latch.await(300, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(sender.calls.get()).isZero();
    }

    @Test
    void t16_payloadIsStructuredAndContainsOnlyTheGivenNonSecretFields() throws Exception {
        RecordingSender sender = new RecordingSender(true, 1, false);
        PaymentAlertService alerts = service(sender, new MutableClock(T0));
        alerts.alert(PaymentAlertType.NOTIFY_AMOUNT_MISMATCH, "CV20261110130000AAAAAAAAAAAA", "amountRaw=0.01 expectedFen=590");

        assertThat(sender.latch.await(5, TimeUnit.SECONDS)).isTrue();
        JsonNode json = new ObjectMapper().readTree(sender.payloads.get(0));
        assertThat(json.get("type").asText()).isEqualTo("NOTIFY_AMOUNT_MISMATCH");
        assertThat(json.get("severity").asText()).isEqualTo("ERROR");
        assertThat(json.get("orderNo").asText()).isEqualTo("CV20261110130000AAAAAAAAAAAA");
        assertThat(json.get("occurredAt").asText()).isEqualTo(T0.toString());
        assertThat(json.get("environment").asText()).isEqualTo("STAGING");
        assertThat(json.get("detail").asText()).isEqualTo("amountRaw=0.01 expectedFen=590");
        assertThat(json.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("source", "category", "type", "severity", "orderNo", "occurredAt",
                        "environment", "detail");
    }

    @Test
    void t16_webhookFailureNeverPropagates() throws Exception {
        RecordingSender sender = new RecordingSender(true, 1, true);
        PaymentAlertService alerts = service(sender, new MutableClock(T0));
        assertThatCode(() -> alerts.alert(PaymentAlertType.FULFILLMENT_FAILING, "CV2", "attempts=5"))
                .doesNotThrowAnyException();
        assertThat(sender.latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void t16_brokenSenderAndSaturationNeverPropagate() {
        PaymentAlertSender exploding = new PaymentAlertSender() {
            @Override
            public boolean isEnabled() {
                throw new IllegalStateException("broken");
            }

            @Override
            public void send(String jsonPayload) {
            }
        };
        PaymentAlertService alerts = service(exploding, new MutableClock(T0));
        assertThatCode(() -> alerts.alert(PaymentAlertType.LATE_PAYMENT, "CV3", null)).doesNotThrowAnyException();

        RecordingSender slow = new RecordingSender(true, 1, false) {
            @Override
            public void send(String jsonPayload) throws Exception {
                Thread.sleep(2000);
            }
        };
        MutableClock clock = new MutableClock(T0);
        PaymentAlertService flooded = service(slow, clock);
        assertThatCode(() -> {
            for (int i = 0; i < 300; i++) {
                flooded.alert(PaymentAlertType.QUERY_MISMATCH, "CV-" + i, "flood");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void t16_sameTypeAndOrderIsPushedOncePerWindow() throws Exception {
        RecordingSender sender = new RecordingSender(true, 2, false);
        MutableClock clock = new MutableClock(T0);
        PaymentAlertService alerts = service(sender, clock);
        alerts.alert(PaymentAlertType.QUERY_MISMATCH, "CV4", "first");
        alerts.alert(PaymentAlertType.QUERY_MISMATCH, "CV4", "duplicate within window");
        clock.advance(Duration.ofMinutes(11));
        alerts.alert(PaymentAlertType.QUERY_MISMATCH, "CV4", "after window");

        assertThat(sender.latch.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);
        assertThat(sender.calls.get()).isEqualTo(2);
    }

    @Test
    void t16_webhookSenderIsDisabledUnlessHttpsUrlConfigured() {
        PaymentProperties none = new PaymentProperties();
        assertThat(new WebhookPaymentAlertSender(none).isEnabled()).isFalse();

        PaymentProperties http = new PaymentProperties();
        http.getAlert().setWebhookUrl("http://hooks.example.com/abc");
        assertThat(new WebhookPaymentAlertSender(http).isEnabled()).isFalse();

        PaymentProperties https = new PaymentProperties();
        https.getAlert().setWebhookUrl("https://hooks.example.com/abc");
        assertThat(new WebhookPaymentAlertSender(https).isEnabled()).isTrue();
        assertThat(https.getAlert().toString()).doesNotContain("hooks.example.com");
    }
}
