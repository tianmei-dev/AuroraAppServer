package com.aurora.appserver.payment.web;

import com.aurora.appserver.auth.security.SessionPrincipal;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.api.ApiResponse;
import com.aurora.appserver.common.exception.BusinessException;
import com.aurora.appserver.payment.service.PaymentOrderService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** App and user always come from the authenticated session, never from the request. */
@RestController
@RequestMapping("/api/v1/payment/orders")
public class PaymentOrderController {

    private final PaymentOrderService paymentOrderService;

    public PaymentOrderController(PaymentOrderService paymentOrderService) {
        this.paymentOrderService = paymentOrderService;
    }

    @PostMapping
    public ApiResponse<PaymentOrderResponse> create(
            Authentication authentication,
            @Valid @RequestBody CreatePaymentOrderRequest request
    ) {
        SessionPrincipal principal = requirePrincipal(authentication);
        return ApiResponse.success(PaymentOrderResponse.from(paymentOrderService.create(
                principal.appId(), principal.userId(), request.planCode(), request.clientRequestId())));
    }

    @GetMapping("/{orderNo}")
    public ApiResponse<PaymentOrderResponse> get(
            Authentication authentication,
            @PathVariable("orderNo") String orderNo
    ) {
        SessionPrincipal principal = requirePrincipal(authentication);
        return ApiResponse.success(PaymentOrderResponse.from(
                paymentOrderService.get(principal.appId(), principal.userId(), orderNo)));
    }

    private static SessionPrincipal requirePrincipal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) {
            throw new BusinessException(ApiErrorCode.UNAUTHORIZED);
        }
        return principal;
    }
}
