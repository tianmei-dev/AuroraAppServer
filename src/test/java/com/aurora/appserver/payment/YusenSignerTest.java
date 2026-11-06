package com.aurora.appserver.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.aurora.appserver.payment.provider.yusen.YusenSigner;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Golden vectors were computed independently (PowerShell MD5) from the doc's algorithm. */
class YusenSignerTest {

    private static final String KEY = "fictional-merchant-key-0001";

    @Test
    void goldenVectorWithChineseNameAndUrls() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", "1001");
        params.put("out_trade_no", "CV20261106131500ABCDEFGH2345");
        params.put("notify_url", "https://api.example.test/api/v1/payment/notify/yusen");
        params.put("return_url", "https://api.example.test/api/v1/payment/return/yusen");
        params.put("name", "年卡");
        params.put("money", "19.90");

        assertThat(YusenSigner.canonicalString(params)).isEqualTo(
                "money=19.90&name=年卡&notify_url=https://api.example.test/api/v1/payment/notify/yusen"
                        + "&out_trade_no=CV20261106131500ABCDEFGH2345&pid=1001"
                        + "&return_url=https://api.example.test/api/v1/payment/return/yusen");
        assertThat(YusenSigner.sign(params, KEY)).isEqualTo("939d51b00fee0da57ac98bcc27e6e437");
    }

    @Test
    void excludesSignSignTypeAndEmptyValuesAndSortsByAscii() {
        Map<String, String> params = new HashMap<>();
        params.put("pid", "1001");
        params.put("b", "2");
        params.put("a", "1");
        params.put("name", "VIP");
        params.put("money", "5.90");
        params.put("out_trade_no", "CV1");
        params.put("param", "");
        params.put("type", null);
        params.put("sign", "should-be-ignored");
        params.put("sign_type", "MD5");

        assertThat(YusenSigner.canonicalString(params))
                .isEqualTo("a=1&b=2&money=5.90&name=VIP&out_trade_no=CV1&pid=1001");
        assertThat(YusenSigner.sign(params, KEY)).isEqualTo("c83b0ad4001cb27dcc60550fb11d2b4a");
    }

    @Test
    void verifyAcceptsUppercaseSignatureAndRejectsTampering() {
        Map<String, String> params = new HashMap<>();
        params.put("pid", "1001");
        params.put("out_trade_no", "CV1");
        params.put("money", "5.90");
        params.put("sign", YusenSigner.sign(params, KEY).toUpperCase());
        assertThat(YusenSigner.verify(params, KEY)).isTrue();

        params.put("money", "0.01");
        assertThat(YusenSigner.verify(params, KEY)).isFalse();
    }

    @Test
    void verifyRejectsMissingSignatureAndWrongKey() {
        Map<String, String> params = new HashMap<>();
        params.put("pid", "1001");
        assertThat(YusenSigner.verify(params, KEY)).isFalse();

        params.put("sign", YusenSigner.sign(params, KEY));
        assertThat(YusenSigner.verify(params, "another-fictional-key")).isFalse();
    }
}
