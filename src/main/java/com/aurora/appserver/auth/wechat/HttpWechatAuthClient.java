package com.aurora.appserver.auth.wechat;

import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Production WeChat OAuth client. Never logs app secret, code, or access_token.
 * Tests override with a {@code @Primary} mock/stub bean.
 */
@Component
public class HttpWechatAuthClient implements WechatAuthClient {

    private static final Logger log = LoggerFactory.getLogger(HttpWechatAuthClient.class);
    private static final String TOKEN_URL = "https://api.weixin.qq.com/sns/oauth2/access_token";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public HttpWechatAuthClient(ObjectMapper objectMapper) {
        this(buildRestClient(), objectMapper);
    }

    HttpWechatAuthClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    private static RestClient buildRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public WechatUserInfo exchangeCode(String wechatAppId, String wechatAppSecret, String code) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(ApiErrorCode.WECHAT_CODE_INVALID);
        }
        try {
            String body = restClient.get()
                    .uri(TOKEN_URL + "?appid={appid}&secret={secret}&code={code}&grant_type=authorization_code",
                            wechatAppId, wechatAppSecret, code.trim())
                    .retrieve()
                    .body(String.class);

            if (!StringUtils.hasText(body)) {
                log.warn("WeChat auth returned empty body");
                throw new BusinessException(ApiErrorCode.WECHAT_AUTH_FAILED);
            }

            JsonNode json = objectMapper.readTree(body);
            if (json.hasNonNull("errcode") && json.get("errcode").asInt() != 0) {
                int errcode = json.get("errcode").asInt();
                log.warn("WeChat auth failed with errcode={}", errcode);
                if (isInvalidCode(errcode)) {
                    throw new BusinessException(ApiErrorCode.WECHAT_CODE_INVALID);
                }
                throw new BusinessException(ApiErrorCode.WECHAT_AUTH_FAILED);
            }

            String openid = text(json, "openid");
            if (!StringUtils.hasText(openid)) {
                log.warn("WeChat auth response missing openid");
                throw new BusinessException(ApiErrorCode.WECHAT_AUTH_FAILED);
            }
            String unionid = text(json, "unionid");
            return new WechatUserInfo(openid, StringUtils.hasText(unionid) ? unionid : null);
        } catch (BusinessException ex) {
            throw ex;
        } catch (RestClientException ex) {
            log.warn("WeChat auth HTTP failure: {}", ex.getClass().getSimpleName());
            throw new BusinessException(ApiErrorCode.WECHAT_AUTH_FAILED);
        } catch (Exception ex) {
            log.warn("WeChat auth parse failure: {}", ex.getClass().getSimpleName());
            throw new BusinessException(ApiErrorCode.WECHAT_AUTH_FAILED);
        }
    }

    private static boolean isInvalidCode(int errcode) {
        // Common invalid / used / expired code errors from WeChat OAuth.
        return errcode == 40029 || errcode == 40163 || errcode == 42001 || errcode == 40055;
    }

    private static String text(JsonNode json, String field) {
        JsonNode node = json.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
