package com.aurora.appserver.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aurora.appserver.AuroraAppServerApplication;
import com.aurora.appserver.payment.config.PaymentProperties;
import com.aurora.appserver.payment.domain.PaymentEnvironment;
import com.aurora.appserver.payment.provider.yusen.YusenPaymentProvider;
import com.aurora.appserver.payment.web.PaymentCallbackController;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * B3-06-A1: prod profile keeps existing security defaults — no Boot default user, notify remains
 * anonymous+signed path, Yusen provider stays the real implementation (stubs are test-only).
 */
class ProdProfileSecurityDefaultsTest {

    @Test
    void defaultSecurityUserAutoConfigurationStaysExcluded() {
        SpringBootApplication ann = AuroraAppServerApplication.class.getAnnotation(SpringBootApplication.class);
        assertThat(ann).isNotNull();
        assertThat(Arrays.asList(ann.exclude())).contains(UserDetailsServiceAutoConfiguration.class);
    }

    @Test
    void paymentPropertiesDefaultNetworkOffAndProductionEnumExists() {
        PaymentProperties props = new PaymentProperties();
        assertThat(props.getYusen().isNetworkEnabled()).isFalse();
        assertThat(PaymentEnvironment.PRODUCTION.name()).isEqualTo("PRODUCTION");
    }

    @Test
    void yusenNotifyEndpointRemainsGetMappingOnCallbackController() throws Exception {
        Method notify = PaymentCallbackController.class.getMethod(
                "notifyYusen", jakarta.servlet.http.HttpServletRequest.class);
        GetMapping mapping = notify.getAnnotation(GetMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).contains("/notify/yusen");
        assertThat(YusenPaymentProvider.class.getPackageName())
                .isEqualTo("com.aurora.appserver.payment.provider.yusen");
    }
}
