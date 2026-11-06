// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;

/** Deny-by-default evaluator. No legacy authorization key is accepted here. */
public final class AutomationPolicy {
    private AutomationPolicy() { }

    public static boolean permits(AutomationCaller caller, AutomationRequest request,
                                  Collection<AutomationGrant> grants, long nowMillis) {
        if (caller == null || request == null || grants == null || nowMillis <= 0) return false;
        for (AutomationGrant grant : grants) {
            if (grant == null || nowMillis >= grant.expiresAtMillis) continue;
            if (caller.uid != grant.uid || !caller.packageName.equals(grant.packageName)) continue;
            if (!MessageDigest.isEqual(caller.signingCertSha256.getBytes(StandardCharsets.US_ASCII),
                    grant.signingCertSha256.getBytes(StandardCharsets.US_ASCII))) continue;
            if (!request.operation.equals(grant.operation)) continue;
            if (AutomationRequest.APPLY_PROFILE.equals(request.operation)
                    && !request.profileId.equals(grant.profileId)) continue;
            return true;
        }
        return false;
    }
}
