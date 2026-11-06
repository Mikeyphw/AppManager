// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AutomationIdentityPolicyTest {
    private static final String PACKAGE = "net.dinglisch.android.taskerm";
    private static final String SIGNER = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    @Test public void rejectsMissingWrongAndAmbiguousUidPackageBindings() {
        assertNull(AutomationIdentityPolicy.verifiedCaller(10001, null, PACKAGE, SIGNER));
        assertNull(AutomationIdentityPolicy.verifiedCaller(10001, new String[0], PACKAGE, SIGNER));
        assertNull(AutomationIdentityPolicy.verifiedCaller(10001,
                new String[]{PACKAGE, "com.example.other"}, PACKAGE, SIGNER));
        assertNull(AutomationIdentityPolicy.verifiedCaller(10001,
                new String[]{"com.example.other"}, PACKAGE, SIGNER));
        assertNull(AutomationIdentityPolicy.verifiedCaller(-1, new String[]{PACKAGE}, PACKAGE, SIGNER));
        assertNull(AutomationIdentityPolicy.verifiedCaller(10001, new String[]{PACKAGE}, PACKAGE, null));
        assertNull(AutomationIdentityPolicy.verifiedCaller(10001, new String[]{PACKAGE}, PACKAGE, "bad"));
    }
    @Test public void verifiedIdentityUsesExactUidAndDigest() {
        AutomationCaller caller = AutomationIdentityPolicy.verifiedCaller(10001,
                new String[]{PACKAGE}, PACKAGE, SIGNER);
        assertNotNull(caller);
        assertEquals(10001, caller.uid);
        assertEquals(PACKAGE, caller.packageName);
        assertEquals(SIGNER, caller.signingCertSha256);
    }
}
