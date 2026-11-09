package com.aurora.appserver.fixture.payment;

import com.aurora.appserver.common.money.FenAmounts;
import com.aurora.appserver.fixture.i343d1.I343d1SafetyGate;
import com.aurora.appserver.payment.domain.PaymentEnvironment;
import com.aurora.appserver.payment.domain.PaymentOrderEntity;
import com.aurora.appserver.payment.domain.PaymentProviderCode;
import com.aurora.appserver.payment.provider.PaymentProvider;
import com.aurora.appserver.payment.provider.PaymentProviderRegistry;
import com.aurora.appserver.payment.config.PaymentProperties;
import com.aurora.appserver.payment.repository.PaymentOrderRepository;
import com.aurora.appserver.payment.service.PaymentJobService;
import com.aurora.appserver.payment.service.PaymentNotificationService;
import com.aurora.appserver.payment.support.FakePaymentProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fake payment control for the isolated 8081 environment ONLY (fixture build, s32it profile).
 * Every request must come from the loopback interface without proxy headers and carry the
 * per-start control token written to .local/s32it/payment-control.token. Requests are handled
 * here and never reach the application's security chain or controllers.
 *
 * <pre>
 * POST /internal/fixture/fake-payment/orders/{orderNo}/behavior?mode=PAID|UNPAID|TIMEOUT|ERROR|THROW[&amp;amountFen=][&amp;delayMs=]
 * POST /internal/fixture/fake-payment/orders/{orderNo}/notify[?tradeStatus=TRADE_SUCCESS][&amp;amountFen=]
 * GET  /internal/fixture/fake-payment/orders/{orderNo}
 * POST /internal/fixture/payment-jobs/run
 * </pre>
 */
public class FixturePaymentControlFilter extends OncePerRequestFilter {

    public static final String PREFIX = "/internal/fixture/";
    public static final String TOKEN_HEADER = "X-Fixture-Control-Token";
    public static final String TOKEN_FILE = ".local/s32it/payment-control.token";

    private static final Logger log = LoggerFactory.getLogger(FixturePaymentControlFilter.class);
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");
    private static final Pattern ORDER_PATH =
            Pattern.compile("^/internal/fixture/fake-payment/orders/([A-Z0-9]{1,32})(/behavior|/notify)?$");

    private final PaymentOrderRepository orderRepository;
    private final PaymentNotificationService notificationService;
    private final PaymentJobService jobService;
    private final ObjectMapper objectMapper;
    private final byte[] token;

    public FixturePaymentControlFilter(
            I343d1SafetyGate safetyGate,
            PaymentProperties properties,
            PaymentProviderRegistry providerRegistry,
            PaymentOrderRepository orderRepository,
            PaymentNotificationService notificationService,
            PaymentJobService jobService,
            ObjectMapper objectMapper
    ) throws IOException {
        safetyGate.assertSafeToRun();
        Optional<PaymentProvider> active = providerRegistry.active();
        if (properties.getEnvironment() != PaymentEnvironment.TEST
                || active.isEmpty() || active.get().code() != PaymentProviderCode.FAKE) {
            throw new IllegalStateException(
                    "Fixture payment control requires aurora.payment.provider=FAKE and environment=TEST");
        }
        this.orderRepository = orderRepository;
        this.notificationService = notificationService;
        this.jobService = jobService;
        this.objectMapper = objectMapper;

        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String tokenText = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        this.token = tokenText.getBytes(StandardCharsets.US_ASCII);
        Path file = Path.of(TOKEN_FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, tokenText, StandardCharsets.US_ASCII);
        log.info("Fixture payment control enabled (loopback + token). Token file: {}", file.toAbsolutePath());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!LOOPBACK.contains(request.getRemoteAddr()) || request.getHeader("X-Forwarded-For") != null) {
            write(response, 403, Map.of("error", "loopback_only"));
            return;
        }
        String provided = request.getHeader(TOKEN_HEADER);
        if (provided == null || !MessageDigest.isEqual(token, provided.getBytes(StandardCharsets.US_ASCII))) {
            write(response, 401, Map.of("error", "control_token_required"));
            return;
        }

        String path = request.getRequestURI();
        String method = request.getMethod();
        try {
            if ("POST".equals(method) && "/internal/fixture/payment-jobs/run".equals(path)) {
                write(response, 200, Map.of("report", jobService.runCycle().toString()));
                return;
            }
            Matcher m = ORDER_PATH.matcher(path);
            if (!m.matches()) {
                write(response, 404, Map.of("error", "unknown_fixture_endpoint"));
                return;
            }
            String orderNo = m.group(1);
            String action = m.group(2);
            PaymentOrderEntity order = orderRepository.findByOrderNo(orderNo)
                    .filter(o -> o.getProvider() == PaymentProviderCode.FAKE
                            && o.getEnvironment() == PaymentEnvironment.TEST)
                    .orElse(null);
            if (order == null) {
                write(response, 404, Map.of("error", "fake_test_order_not_found"));
                return;
            }

            if ("GET".equals(method) && action == null) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("orderNo", orderNo);
                body.put("orderStatus", order.getStatus().name());
                FakePaymentProvider.Behavior behavior = FakePaymentProvider.behavior(orderNo);
                body.put("fakeMode", behavior == null ? "UNPAID(default)" : behavior.mode().name());
                body.put("fakeQueryCount", FakePaymentProvider.queryCount(orderNo));
                write(response, 200, body);
            } else if ("POST".equals(method) && "/behavior".equals(action)) {
                FakePaymentProvider.Mode mode = FakePaymentProvider.Mode.valueOf(
                        required(request, "mode").toUpperCase());
                int amountFen = intParam(request, "amountFen", order.getAmountFen());
                long delayMs = Math.min(30_000L, intParam(request, "delayMs", 0));
                FakePaymentProvider.configure(orderNo, mode, amountFen, delayMs);
                log.info("Fixture fake behavior orderNo={} mode={} amountFen={} delayMs={}",
                        orderNo, mode, amountFen, delayMs);
                write(response, 200, Map.of("orderNo", orderNo, "mode", mode.name(), "amountFen", amountFen));
            } else if ("POST".equals(method) && "/notify".equals(action)) {
                String tradeStatus = Optional.ofNullable(request.getParameter("tradeStatus")).orElse("TRADE_SUCCESS");
                int amountFen = intParam(request, "amountFen", order.getAmountFen());
                Map<String, String> params = FakePaymentProvider.signedNotification(
                        orderNo, FenAmounts.toYuanString(amountFen), tradeStatus);
                String ack = notificationService.handle(PaymentProviderCode.FAKE, params, "fixture-loopback");
                write(response, 200, Map.of("orderNo", orderNo, "ack", ack));
            } else {
                write(response, 405, Map.of("error", "method_not_allowed"));
            }
        } catch (IllegalArgumentException ex) {
            write(response, 400, Map.of("error", "bad_request"));
        }
    }

    private static String required(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name);
        }
        return value;
    }

    private static int intParam(HttpServletRequest request, String name, int defaultValue) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        int parsed = Integer.parseInt(value.trim());
        if (parsed < 0) {
            throw new IllegalArgumentException(name);
        }
        return parsed;
    }

    private void write(HttpServletResponse response, int status, Map<String, ?> body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
