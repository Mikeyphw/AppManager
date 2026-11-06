// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.core;

import java.util.regex.Pattern;

/**
 * Identity supplied ONLY by a trusted Android caller-verification bridge.
 * A package name, UID or digest copied from Intent extras is NOT an attestation.
 * The eventual bridge must validate Binder UID + package membership + signing
 * certificate via PackageManager (with explicit handling of shared UIDs).
 */
public final class AutomationCaller {
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*(\\.[A-Za-z_][A-Za-z_0-9]*)+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    public final int uid;
    public final String packageName;
    public final String signingCertSha256;

    public AutomationCaller(int verifiedUid, String verifiedPackage, String verifiedCertSha256) {
        if (verifiedUid < 0 || verifiedPackage == null || !PACKAGE.matcher(verifiedPackage).matches()
                || verifiedCertSha256 == null || !SHA256.matcher(verifiedCertSha256).matches()) {
            throw new IllegalArgumentException("invalid verified caller identity");
        }
        uid = verifiedUid;
        packageName = verifiedPackage;
        signingCertSha256 = verifiedCertSha256;
    }
}
