package com.aurora.appserver.payment.provider.yusen;

import com.aurora.appserver.payment.config.PaymentProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Default transport. Refuses every request unless aurora.payment.yusen.network-enabled=true,
 * so no environment reaches the real gateway by accident.
 */
@Component
public class JdkYusenHttpTransport implements YusenHttpTransport {

    private final PaymentProperties.Yusen config;
    private volatile HttpClient client;

    public JdkYusenHttpTransport(PaymentProperties properties) {
        this.config = properties.getYusen();
    }

    @Override
    public Response get(URI uri, Duration timeout) throws IOException, InterruptedException {
        if (!config.isNetworkEnabled()) {
            throw new IllegalStateException("Yusen network access is disabled");
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
        HttpResponse<String> response = client().send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(response.statusCode(), response.body());
    }

    private HttpClient client() {
        HttpClient current = client;
        if (current == null) {
            synchronized (this) {
                if (client == null) {
                    client = HttpClient.newBuilder()
                            .connectTimeout(config.getConnectTimeout())
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
                }
                current = client;
            }
        }
        return current;
    }
}
