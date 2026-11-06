// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.android;

import android.os.Bundle;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import io.github.muntashirakon.AppManager.automation.core.AutomationRequest;

/** Validates untrusted protocol payloads; the claimed package is NOT identity proof. */
public final class AndroidAutomationIntentParser {
    private static final String VERSION = "am.version";
    private static final String REQUEST_ID = "am.request_id";
    private static final String OPERATION = "am.operation";
    private static final String PACKAGE = "am.package";
    private static final String PROFILE = "am.profile_id";
    private static final String STATE = "am.state";
    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
            VERSION, REQUEST_ID, OPERATION, PACKAGE, PROFILE, STATE));

    private AndroidAutomationIntentParser() { }

    public static final class Parsed {
        @NonNull public final String claimedPackage;
        @NonNull public final AutomationRequest request;
        private Parsed(String claimedPackage, AutomationRequest request) {
            this.claimedPackage = claimedPackage;
            this.request = request;
        }
    }

    @NonNull
    public static Parsed parse(@NonNull Bundle payload) {
        if (payload == null || payload.keySet().size() > ALLOWED.size())
            throw new IllegalArgumentException("invalid automation payload");
        for (String key : payload.keySet()) {
            if (!ALLOWED.contains(key)) throw new IllegalArgumentException("unsupported field");
        }
        Object version = payload.get(VERSION);
        if (!(version instanceof Integer)) throw new IllegalArgumentException("invalid version type");
        String callerPackage = payload.getString(PACKAGE);
        if (callerPackage == null || callerPackage.isEmpty())
            throw new IllegalArgumentException("missing package claim");
        AutomationRequest request = AutomationRequest.fromFields((Integer) version,
                payload.getString(REQUEST_ID), payload.getString(OPERATION),
                payload.getString(PROFILE), payload.getString(STATE));
        return new Parsed(callerPackage, request);
    }
}
