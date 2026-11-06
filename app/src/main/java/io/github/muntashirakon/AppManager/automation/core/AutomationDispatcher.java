// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.core;

import java.util.Collection;

/**
 * Internal-only dispatcher. No exported component is registered by AMT-01.
 * The future Android bridge must obtain caller identity from the OS, read
 * approved grants from private storage, and bind execution to existing
 * App Manager services. Never use Intent-provided caller/credential data.
 */
public final class AutomationDispatcher {
    public interface GrantProvider {
        Collection<AutomationGrant> grantsFor(AutomationCaller verifiedCaller);
    }

    public interface OperationExecutor {
        AutomationResult execute(AutomationRequest approvedRequest);
    }

    /** Must atomically reserve caller/request ID before any operation. Durable storage required for export. */
    public interface ReplayGuard {
        boolean reserve(AutomationCaller verifiedCaller, AutomationRequest request);
    }

    private final GrantProvider grants;
    private final ReplayGuard replayGuard;
    private final OperationExecutor executor;

    public AutomationDispatcher(GrantProvider grants, ReplayGuard replayGuard, OperationExecutor executor) {
        if (grants == null || replayGuard == null || executor == null)
            throw new IllegalArgumentException("missing dependencies");
        this.grants = grants;
        this.replayGuard = replayGuard;
        this.executor = executor;
    }

    public AutomationResult dispatch(AutomationCaller verifiedCaller, AutomationRequest request,
                                     long nowMillis) {
        if (request == null) return AutomationResult.denied(null);
        try {
            // No operation is even queued until this single authorization gate passes.
            if (!AutomationPolicy.permits(verifiedCaller, request,
                    verifiedCaller != null ? grants.grantsFor(verifiedCaller) : null, nowMillis)) {
                return AutomationResult.denied(request.requestId);
            }
            if (!replayGuard.reserve(verifiedCaller, request)) {
                return AutomationResult.duplicate(request.requestId);
            }
            AutomationResult result = executor.execute(request);
            if (result == null || !request.requestId.equals(result.requestId))
                return AutomationResult.failed(request.requestId);
            return result;
        } catch (RuntimeException ignored) {
            // Do not leak credentials, file paths or exception messages to callers.
            return AutomationResult.failed(request.requestId);
        }
    }
}
