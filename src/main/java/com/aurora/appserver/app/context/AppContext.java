package com.aurora.appserver.app.context;

import com.aurora.appserver.app.domain.AppStatus;
import com.aurora.appserver.app.domain.Platform;

/**
 * Explicit application context for a single request/operation.
 * Prefer passing this as a method parameter rather than relying on ThreadLocal.
 *
 * Important: appCode / packageName from the client only identify which app
 * configuration to load. They do NOT authenticate the caller.
 */
public record AppContext(
        Long appId,
        String appCode,
        String appName,
        Platform platform,
        String packageName,
        AppStatus status
) {

    public boolean isActive() {
        return status == AppStatus.ACTIVE;
    }
}
