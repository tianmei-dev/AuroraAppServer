package com.aurora.appserver.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aurora.appserver.common.money.FenAmounts;
import com.aurora.appserver.payment.config.PaymentProperties;
import com.aurora.appserver.payment.service.OrderNoGenerator;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentPrimitivesTest {

    @Test
    void fenToYuanIsExact() {
        assertThat(FenAmounts.toYuanString(590)).isEqualTo("5.90");
        assertThat(FenAmounts.toYuanString(1990)).isEqualTo("19.90");
        assertThat(FenAmounts.toYuanString(2990)).isEqualTo("29.90");
        assertThat(FenAmounts.toYuanString(1)).isEqualTo("0.01");
    }

    @Test
    void yuanParsingIsStrict() {
        assertThat(FenAmounts.parseYuanToFen("19.90")).contains(1990);
        assertThat(FenAmounts.parseYuanToFen("19.9")).contains(1990);
        assertThat(FenAmounts.parseYuanToFen("19")).contains(1900);
        assertThat(FenAmounts.parseYuanToFen("19.901")).isEmpty();
        assertThat(FenAmounts.parseYuanToFen("-1.00")).isEmpty();
        assertThat(FenAmounts.parseYuanToFen("1e3")).isEmpty();
        assertThat(FenAmounts.parseYuanToFen(null)).isEmpty();
    }

    @Test
    void orderNoIsAlphanumericAtMost32AndUnique() {
        OrderNoGenerator generator = new OrderNoGenerator(new PaymentProperties());
        Instant now = Instant.parse("2026-11-06T13:15:00Z");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String orderNo = generator.next(now);
            assertThat(orderNo).matches("^CV20261106131500[A-Z2-7]{12}$").hasSize(28);
            assertThat(seen.add(orderNo)).isTrue();
        }
    }

    @Test
    void invalidOrderNoPrefixFailsFast() {
        PaymentProperties properties = new PaymentProperties();
        properties.setOrderNoPrefix("cv-");
        assertThatThrownBy(() -> new OrderNoGenerator(properties)).isInstanceOf(IllegalStateException.class);
    }
}
