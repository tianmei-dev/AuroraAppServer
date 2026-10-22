package com.aurora.appserver.trial.web;

import com.aurora.appserver.common.api.ApiResponse;
import com.aurora.appserver.trial.service.DeviceTrialService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trial/device")
public class DeviceTrialController {

    public static final String APP_CODE_HEADER = "X-App-Code";

    private final DeviceTrialService deviceTrialService;

    public DeviceTrialController(DeviceTrialService deviceTrialService) {
        this.deviceTrialService = deviceTrialService;
    }

    @PostMapping("/activate")
    public ApiResponse<DeviceTrialResponse> activate(
            @RequestHeader(value = APP_CODE_HEADER, required = false) String appCode,
            @Valid @RequestBody DeviceTrialActivateRequest request,
            HttpServletRequest httpRequest
    ) {
        DeviceTrialService.TrialView view = deviceTrialService.activate(
                appCode,
                request.deviceKey(),
                clientIp(httpRequest)
        );
        return ApiResponse.success(new DeviceTrialResponse(
                view.status(),
                view.trialStartedAt(),
                view.trialExpiresAt(),
                view.serverTime(),
                view.remainingSeconds()
        ));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
