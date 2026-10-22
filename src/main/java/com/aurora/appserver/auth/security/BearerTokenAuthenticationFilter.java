package com.aurora.appserver.auth.security;

import com.aurora.appserver.app.context.AppContext;
import com.aurora.appserver.app.service.AppRegistryService;
import com.aurora.appserver.auth.service.AuthSessionService;
import com.aurora.appserver.auth.web.AuthController;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.api.ApiResponse;
import com.aurora.appserver.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private final AuthSessionService authSessionService;
    private final AppRegistryService appRegistryService;
    private final ObjectMapper objectMapper;

    public BearerTokenAuthenticationFilter(
            AuthSessionService authSessionService,
            AppRegistryService appRegistryService,
            ObjectMapper objectMapper
    ) {
        this.authSessionService = authSessionService;
        this.appRegistryService = appRegistryService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health")
                || path.equals("/api/v1/auth/wechat/login")
                || path.equals("/api/v1/trial/device/activate");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String rawToken = authorization.substring("Bearer ".length()).trim();
        if (!StringUtils.hasText(rawToken)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String appCode = request.getHeader(AuthController.APP_CODE_HEADER);
            AppContext app = appRegistryService.requireActiveApp(appCode);
            SessionPrincipal principal = authSessionService.authenticate(rawToken, app);
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (BusinessException ex) {
            SecurityContextHolder.clearContext();
            writeError(response, ex.getErrorCode());
        }
    }

    private void writeError(HttpServletResponse response, ApiErrorCode errorCode) throws IOException {
        int status = switch (errorCode) {
            case APP_NOT_FOUND -> HttpServletResponse.SC_NOT_FOUND;
            case APP_DISABLED, FORBIDDEN, APP_CONTEXT_MISMATCH -> HttpServletResponse.SC_FORBIDDEN;
            case APP_CONTEXT_REQUIRED, VALIDATION_FAILED, BAD_REQUEST -> HttpServletResponse.SC_BAD_REQUEST;
            case SESSION_EXPIRED, SESSION_REVOKED, UNAUTHORIZED -> HttpServletResponse.SC_UNAUTHORIZED;
            default -> HttpServletResponse.SC_UNAUTHORIZED;
        };
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(errorCode));
    }
}
