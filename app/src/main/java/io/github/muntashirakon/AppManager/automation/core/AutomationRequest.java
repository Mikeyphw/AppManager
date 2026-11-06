// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.core;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable, validated v1 request. Never build directly from an untrusted Intent. */
public final class AutomationRequest {
    public static final int VERSION = 1;
    public static final String QUERY_CAPABILITIES = "capabilities.query";
    public static final String APPLY_PROFILE = "profile.apply";
    private static final Pattern ID = Pattern.compile("[a-f0-9]{32}");
    private static final Pattern PROFILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _.-]{0,127}");
    public final int version;
    public final String requestId;
    public final String operation;
    public final String profileId;
    public final String state;

    private AutomationRequest(int version, String requestId, String operation, String profileId, String state) {
        if (version != VERSION) throw new IllegalArgumentException("unsupported protocol version");
        if (requestId == null || !ID.matcher(requestId).matches())
            throw new IllegalArgumentException("invalid request identifier");
        if (!QUERY_CAPABILITIES.equals(operation) && !APPLY_PROFILE.equals(operation))
            throw new IllegalArgumentException("unsupported operation");
        if (APPLY_PROFILE.equals(operation)) {
            if (profileId == null || !PROFILE.matcher(profileId).matches() || "..".equals(profileId))
                throw new IllegalArgumentException("invalid profile identifier");
            if (!"on".equals(state) && !"off".equals(state))
                throw new IllegalArgumentException("invalid profile state");
        } else if (profileId != null || state != null) {
            throw new IllegalArgumentException("unexpected arguments");
        }
        this.version = version;
        this.requestId = requestId;
        this.operation = Objects.requireNonNull(operation);
        this.profileId = profileId;
        this.state = state;
    }

    /** Bridge must first reject unknown fields and obtain a verified caller. */
    public static AutomationRequest fromFields(int version, String id, String operation,
                                               String profileId, String state) {
        return new AutomationRequest(version, id, operation, profileId, state);
    }

    public static AutomationRequest capabilities(String id) {
        return new AutomationRequest(VERSION, id, QUERY_CAPABILITIES, null, null);
    }

    public static AutomationRequest applyProfile(String id, String profileId, String state) {
        return new AutomationRequest(VERSION, id, APPLY_PROFILE, profileId, state);
    }
}
