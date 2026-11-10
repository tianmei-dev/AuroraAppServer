package com.aurora.appserver.payment.alert;

import com.aurora.appserver.payment.config.PaymentProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * POSTs alert JSON to aurora.payment.alert.webhook-url (JDK HttpClient, no extra dependency).
 * Disabled unless the URL is https. The URL may embed a token, so it is never logged.
 */
@Component
public class WebhookPaymentAlertSender implements PaymentAlertSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookPaymentAlertSender.class);

    private final PaymentProperties.Alert config;
    private final boolean enabled;
    private volatile HttpClient client;

    public WebhookPaymentAlertSender(PaymentProperties properties) {
        this.config = properties.getAlert();
        boolean configured = config.isWebhookConfigured();
        boolean https = config.getWebhookUrl().toLowerCase().startsWith("https://");
        this.enabled = configured && https;
        if (configured && !https) {
            log.warn("Payment alert webhook ignored: URL must use https");
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void send(String jsonPayload) throws IOException, InterruptedException {
        if (!enabled) {
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.getWebhookUrl()))
                .timeout(config.getTimeout())
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<Void> response = client().send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("webhook status " + response.statusCode());
        }
    }

    private HttpClient client() {
        HttpClient current = client;
        if (current == null) {
            synchronized (this) {
                if (client == null) {
                    client = HttpClient.newBuilder()
                            .connectTimeout(config.getTimeout())
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
                }
                current = client;
            }
        }
        return current;
    }
}
