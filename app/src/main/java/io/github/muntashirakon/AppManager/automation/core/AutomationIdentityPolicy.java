// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.core;

import java.util.Objects;

/** Android UID-to-package validation policy, independently unit-testable. */
public final class AutomationIdentityPolicy {
    private AutomationIdentityPolicy() { }

    /** Fail closed on shared UID or missing UID-to-package mapping. */
    public static boolean uniquelyOwned(int uid, String[] uidPackages, String claimedPackage) {
        return uid > 0 && uidPackages != null && uidPackages.length == 1
                && claimedPackage != null && claimedPackage.equals(uidPackages[0]);
    }

    /** Digest MUST come from the actual Android PackageManager certificate. */
    public static AutomationCaller verifiedCaller(int uid, String[] uidPackages,
                                                  String claimedPackage, String actualSignerSha256) {
        if (!uniquelyOwned(uid, uidPackages, claimedPackage)) return null;
        try {
            return new AutomationCaller(uid, claimedPackage, Objects.requireNonNull(actualSignerSha256));
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return null;
        }
    }
}
