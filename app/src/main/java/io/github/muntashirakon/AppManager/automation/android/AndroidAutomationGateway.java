// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.android;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;

import io.github.muntashirakon.AppManager.automation.core.AutomationCaller;
import io.github.muntashirakon.AppManager.automation.core.AutomationDispatcher;
import io.github.muntashirakon.AppManager.automation.core.AutomationResult;

/**
 * Internal-only integration point. Must be invoked directly from an inbound
 * Binder IPC transaction, NEVER from an Activity/receiver/forwarded Intent.
 * No component is exported by this milestone, and no privileged executor is
 * installed by default. Future endpoints must obtain explicit user consent.
 */
public final class AndroidAutomationGateway {
    private final AndroidCallerVerifier identity;
    private final AutomationDispatcher dispatcher;

    public AndroidAutomationGateway(@NonNull Context context,
                                    @NonNull AutomationDispatcher.OperationExecutor executor) {
        identity = new AndroidCallerVerifier(context);
        dispatcher = new AutomationDispatcher(new AndroidGrantRepository(context),
                new AndroidReplayGuard(context), executor);
    }

    @NonNull
    public AutomationResult dispatchFromBinder(@NonNull Bundle payload) {
        try {
            AndroidAutomationIntentParser.Parsed parsed = AndroidAutomationIntentParser.parse(payload);
            AutomationCaller caller = identity.verifyBinderCaller(parsed.claimedPackage);
            if (caller == null) return AutomationResult.denied(parsed.request.requestId);
            return dispatcher.dispatch(caller, parsed.request, System.currentTimeMillis());
        } catch (RuntimeException ignored) {
            // No untrusted exception details or identities in external results.
            return AutomationResult.denied(null);
        }
    }
}
