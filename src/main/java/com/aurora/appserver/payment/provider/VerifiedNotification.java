package com.aurora.appserver.payment.provider;

import java.util.Optional;

/** Notification whose merchant id and signature have been verified. Still requires order checks. */
public record VerifiedNotification(
        String orderNo,
        String providerTradeNo,
        String amountRaw,
        Optional<Integer> amountFen,
        String channelRaw,
        String tradeStatus,
        boolean tradeSuccess
) {
}
