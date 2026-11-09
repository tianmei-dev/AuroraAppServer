package com.aurora.appserver.payment.support;

import com.aurora.appserver.payment.provider.yusen.YusenHttpTransport;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Test-only replacement for the Yusen HTTP transport. Answers api.php?act=order with bodies in
 * the documented JSON shape; never opens a socket. State is static for restart simulations.
 */
@Component
@Primary
@Profile("test")
public class StubYusenHttpTransport implements YusenHttpTransport {

    public record Stub(int status, String body, boolean timeout, long delayMs) {
    }

    private static final Map<String, Stub> STUBS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> LAST_QUERY = new ConcurrentHashMap<>();

    public static void reset() {
        STUBS.clear();
        CALLS.clear();
        LAST_QUERY.clear();
    }

    public static void respond(String orderNo, int status, String body) {
        STUBS.put(orderNo, new Stub(status, body, false, 0));
    }

    public static void respondSlowly(String orderNo, String body, long delayMs) {
        STUBS.put(orderNo, new Stub(200, body, false, delayMs));
    }

    public static void timeout(String orderNo) {
        STUBS.put(orderNo, new Stub(0, null, true, 0));
    }

    public static void paid(String orderNo, String money, String pid) {
        respond(orderNo, 200, paidJson(orderNo, money, pid));
    }

    /** Field names and types follow doc "[API]查询单个订单". */
    public static String paidJson(String orderNo, String money, String pid) {
        return """
                {"code":1,"msg":"查询订单号成功！","trade_no":"Y%s","out_trade_no":"%s",\
                "api_trade_no":"WX%s","type":"wxpay","pid":%s,"addtime":"2026-11-06 21:00:00",\
                "endtime":"2026-11-06 21:01:00","name":"会员","money":"%s","status":1,"param":"","buyer":""}
                """.formatted(orderNo, orderNo, orderNo, pid, money).trim();
    }

    public static String unpaidJson(String orderNo, String money) {
        return """
                {"code":1,"msg":"查询订单号成功！","trade_no":"Y%s","out_trade_no":"%s","type":"wxpay",\
                "pid":1001,"money":"%s","status":0}
                """.formatted(orderNo, orderNo, money).trim();
    }

    public static int calls(String orderNo) {
        AtomicInteger count = CALLS.get(orderNo);
        return count == null ? 0 : count.get();
    }

    public static Map<String, String> lastQuery(String orderNo) {
        return LAST_QUERY.get(orderNo);
    }

    @Override
    public Response get(URI uri, Duration timeout) throws IOException, InterruptedException {
        Map<String, String> query = parse(uri.getRawQuery());
        String orderNo = query.getOrDefault("out_trade_no", "");
        CALLS.computeIfAbsent(orderNo, k -> new AtomicInteger()).incrementAndGet();
        LAST_QUERY.put(orderNo, query);
        Stub stub = STUBS.get(orderNo);
        if (stub == null) {
            return new Response(200, unpaidJson(orderNo, "0.00"));
        }
        if (stub.delayMs() > 0) {
            Thread.sleep(stub.delayMs());
        }
        if (stub.timeout()) {
            throw new HttpTimeoutException("request timed out");
        }
        return new Response(stub.status(), stub.body());
    }

    private static Map<String, String> parse(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
