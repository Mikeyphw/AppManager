// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.core;

/** Non-sensitive, structured response model. Never includes keys or private app data. */
public final class AutomationResult {
    public enum Status { DENIED, QUEUED, SUCCESS, FAILED }
    public final String requestId;
    public final Status status;
    public final String code;
    public final String jobId;

    private AutomationResult(String requestId, Status status, String code, String jobId) {
        this.requestId = requestId;
        this.status = status;
        this.code = code;
        this.jobId = jobId;
    }

    public static AutomationResult denied(String requestId) {
        return new AutomationResult(requestId, Status.DENIED, "AUTH_REQUIRED", null);
    }

    public static AutomationResult duplicate(String requestId) {
        return new AutomationResult(requestId, Status.DENIED, "DUPLICATE_REQUEST", null);
    }

    public static AutomationResult queued(String requestId, String jobId) {
        if (jobId == null || jobId.isEmpty() || jobId.length() > 128)
            throw new IllegalArgumentException("invalid job id");
        return new AutomationResult(requestId, Status.QUEUED, "QUEUED", jobId);
    }

    public static AutomationResult success(String requestId) {
        return new AutomationResult(requestId, Status.SUCCESS, "OK", null);
    }

    public static AutomationResult failed(String requestId) {
        return new AutomationResult(requestId, Status.FAILED, "OPERATION_FAILED", null);
    }
}
