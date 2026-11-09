package com.aurora.appserver.payment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodic compensation. The first run shortly after startup is the restart recovery: there is
 * no in-memory state, so it resumes whatever the database says is due.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "aurora.payment.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentJobScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentJobScheduler.class);

    private final PaymentJobService jobService;

    public PaymentJobScheduler(PaymentJobService jobService) {
        this.jobService = jobService;
    }

    @Scheduled(
            initialDelayString = "${aurora.payment.jobs.initial-delay-ms:5000}",
            fixedDelayString = "${aurora.payment.jobs.interval-ms:30000}")
    public void run() {
        try {
            jobService.runCycle();
        } catch (RuntimeException ex) {
            log.warn("Payment job cycle failed error={}", ex.getClass().getSimpleName());
        }
    }
}
