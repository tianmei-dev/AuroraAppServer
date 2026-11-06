package com.aurora.appserver.payment.web;

import com.aurora.appserver.common.money.FenAmounts;
import com.aurora.appserver.payment.provider.PaymentLink;
import com.aurora.appserver.payment.service.PaymentOrderService.OrderView;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentOrderResponse(
        String orderNo,
        String planCode,
        int amountFen,
        String amountDisplay,
        String currency,
        String status,
        Instant expiresAt,
        Instant paidAt,
        Instant fulfilledAt,
        Instant serverTime,
        PayInfo pay
) {

    public record PayInfo(String kind, String value) {

        @Override
        public String toString() {
            return "PayInfo{kind=" + kind + ", value=<redacted>}";
        }
    }

    public static PaymentOrderResponse from(OrderView view) {
        var order = view.order();
        PaymentLink link = view.payLink();
        return new PaymentOrderResponse(
                order.getOrderNo(),
                order.getPlanCode().name(),
                order.getAmountFen(),
                FenAmounts.toYuanString(order.getAmountFen()),
                order.getCurrency(),
                view.effectiveStatus().name(),
                order.getExpiresAt(),
                order.getPaidAt(),
                order.getFulfilledAt(),
                view.serverTime(),
                link == null ? null : new PayInfo(link.kind().name(), link.value()));
    }
}
