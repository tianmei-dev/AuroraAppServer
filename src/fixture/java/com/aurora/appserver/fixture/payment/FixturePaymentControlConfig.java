package com.aurora.appserver.fixture.payment;

import com.aurora.appserver.fixture.i343d1.I343d1SafetyGate;
import com.aurora.appserver.payment.config.PaymentProperties;
import com.aurora.appserver.payment.provider.PaymentProviderRegistry;
import com.aurora.appserver.payment.repository.PaymentOrderRepository;
import com.aurora.appserver.payment.service.PaymentJobService;
import com.aurora.appserver.payment.service.PaymentNotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;

/**
 * Registers the fake payment control filter ahead of every other filter (including Spring
 * Security) in the standing s32it server. Not active for one-shot fixture CLI runs.
 */
@Configuration
@Profile("s32it")
@ConditionalOnExpression("'${i343d1.command:}'.isEmpty()")
public class FixturePaymentControlConfig {

    @Bean
    FilterRegistrationBean<FixturePaymentControlFilter> fixturePaymentControlFilter(
            I343d1SafetyGate safetyGate,
            PaymentProperties properties,
            PaymentProviderRegistry providerRegistry,
            PaymentOrderRepository orderRepository,
            PaymentNotificationService notificationService,
            PaymentJobService jobService,
            ObjectMapper objectMapper
    ) throws IOException {
        FixturePaymentControlFilter filter = new FixturePaymentControlFilter(
                safetyGate, properties, providerRegistry, orderRepository, notificationService, jobService, objectMapper);
        FilterRegistrationBean<FixturePaymentControlFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(FixturePaymentControlFilter.PREFIX + "*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("fixturePaymentControlFilter");
        return registration;
    }
}
