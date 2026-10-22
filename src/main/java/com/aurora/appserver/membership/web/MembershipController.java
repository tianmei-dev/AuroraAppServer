package com.aurora.appserver.membership.web;

import com.aurora.appserver.auth.security.SessionPrincipal;
import com.aurora.appserver.common.api.ApiErrorCode;
import com.aurora.appserver.common.api.ApiResponse;
import com.aurora.appserver.common.exception.BusinessException;
import com.aurora.appserver.membership.service.MembershipService;
import com.aurora.appserver.membership.service.MembershipService.MembershipStatusView;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/membership")
public class MembershipController {

    private final MembershipService membershipService;

    public MembershipController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @GetMapping("/me")
    public ApiResponse<MembershipMeResponse> me(Authentication authentication) {
        SessionPrincipal principal = requirePrincipal(authentication);
        MembershipStatusView view = membershipService.getStatus(principal.appId(), principal.userId());
        return ApiResponse.success(new MembershipMeResponse(
                view.status().name(),
                view.planType(),
                view.contributingPlanTypes(),
                view.lifetime(),
                view.membershipStartedAt(),
                view.membershipExpiresAt(),
                view.serverTime(),
                view.activeGrantCount()
        ));
    }

    private static SessionPrincipal requirePrincipal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) {
            throw new BusinessException(ApiErrorCode.UNAUTHORIZED);
        }
        return principal;
    }
}
