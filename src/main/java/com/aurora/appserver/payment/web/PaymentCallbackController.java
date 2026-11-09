package com.aurora.appserver.payment.web;

import com.aurora.appserver.payment.domain.PaymentProviderCode;
import com.aurora.appserver.payment.service.PaymentNotificationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous Yusen endpoints. Trust comes only from signature + merchant checks and the follow-up
 * provider query; the browser return page never grants or confirms anything.
 */
@RestController
@RequestMapping("/api/v1/payment")
public class PaymentCallbackController {

    private static final String RETURN_PAGE = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>支付结果处理中</title></head>
            <body style="font-family:sans-serif;text-align:center;padding:48px 24px;">
            <h2>支付结果处理中</h2>
            <p>请返回 App 查看支付结果。</p>
            <p style="color:#888;font-size:14px;">会员开通以服务器确认为准，可能需要几秒钟。</p>
            </body></html>
            """;

    private final PaymentNotificationService notificationService;

    public PaymentCallbackController(PaymentNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** Yusen async notification (doc "支付结果通知": GET, reply "success"). */
    @GetMapping(value = "/notify/yusen", produces = MediaType.TEXT_PLAIN_VALUE)
    public String notifyYusen(HttpServletRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (values != null && values.length > 0) {
                params.put(name, values[0]);
            }
        });
        return notificationService.handle(PaymentProviderCode.YUSEN, params, request.getRemoteAddr());
    }

    /** Browser return_url: static hint only. Query parameters are ignored and never echoed. */
    @GetMapping(value = "/return/yusen")
    public ResponseEntity<String> returnYusen() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .body(RETURN_PAGE);
    }
}
