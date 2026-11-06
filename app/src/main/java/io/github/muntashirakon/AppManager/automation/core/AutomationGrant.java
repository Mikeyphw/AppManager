// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.core;

import java.util.regex.Pattern;

/** User-approved, revocable grant snapshot; persistence and consent UI come later. */
public final class AutomationGrant {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    public final int uid;
    public final String packageName;
    public final String signingCertSha256;
    public final String operation;
    public final String profileId;
    public final long expiresAtMillis;

    public AutomationGrant(int verifiedUid, String packageName, String signingCertSha256, String operation,
                           String profileId, long expiresAtMillis) {
        if (verifiedUid < 0 || packageName == null || packageName.isEmpty()
                || signingCertSha256 == null || !SHA256.matcher(signingCertSha256).matches()
                || (!AutomationRequest.QUERY_CAPABILITIES.equals(operation)
                && !AutomationRequest.APPLY_PROFILE.equals(operation))
                || expiresAtMillis <= 0) {
            throw new IllegalArgumentException("invalid grant");
        }
        if (AutomationRequest.APPLY_PROFILE.equals(operation)) {
            if (profileId == null || profileId.isEmpty() || "*".equals(profileId))
                throw new IllegalArgumentException("profile permission must be narrowly scoped");
        } else if (profileId != null) {
            throw new IllegalArgumentException("capability query has no profile scope");
        }
        this.uid = verifiedUid;
        this.packageName = packageName;
        this.signingCertSha256 = signingCertSha256;
        this.operation = operation;
        this.profileId = profileId;
        this.expiresAtMillis = expiresAtMillis;
    }
}
