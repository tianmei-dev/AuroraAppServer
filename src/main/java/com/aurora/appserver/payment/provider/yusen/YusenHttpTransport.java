package com.aurora.appserver.payment.provider.yusen;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/**
 * Outbound HTTP for Yusen server-to-server calls. The URI contains the merchant key
 * (doc "[API]查询单个订单"), so implementations must never log it or put it in exception messages.
 */
public interface YusenHttpTransport {

    Response get(URI uri, Duration timeout) throws IOException, InterruptedException;

    record Response(int status, String body) {
    }
}
