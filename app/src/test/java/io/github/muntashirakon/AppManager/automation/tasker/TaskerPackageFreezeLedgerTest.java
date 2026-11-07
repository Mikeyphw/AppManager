// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.pm.PackageManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class TaskerPackageFreezeLedgerTest {
    private static final String PKG = "com.example.amt04b2b";
    private TaskerPackageFreezeLedger journal() {
        Context ctx = RuntimeEnvironment.getApplication();
        return new TaskerPackageFreezeLedger(ctx);
    }

    @Test public void recordSurvivesNewInstanceAndRestoresExactDefault() throws Exception {
        TaskerPackageFreezeLedger a = journal();
        TaskerPackageFreezeLedger.Entry prior = a.find(PKG);
        if (prior != null) a.forgetMatching(PKG, prior);
        TaskerPackageFreezeLedger.Entry entry = new TaskerPackageFreezeLedger.Entry(
                12345, 13579L, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        a.record(PKG, entry);
        TaskerPackageFreezeLedger.Entry recovered = journal().find(PKG);
        assertNotNull(recovered);
        assertTrue(recovered.matches(12345, 13579L));
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, recovered.previousState);
        journal().forgetMatching(PKG, recovered);
        assertNull(journal().find(PKG));
    }

    @Test public void cannotReplaceExistingRecordOrForgetDifferentIdentity() throws Exception {
        final String pkg = "com.example.amt04b2b_guard";
        TaskerPackageFreezeLedger ledger = journal();
        TaskerPackageFreezeLedger.Entry old = ledger.find(pkg);
        if (old != null) ledger.forgetMatching(pkg, old);
        TaskerPackageFreezeLedger.Entry saved = new TaskerPackageFreezeLedger.Entry(2000, 4000,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        ledger.record(pkg, saved);
        try {
            ledger.record(pkg, new TaskerPackageFreezeLedger.Entry(3000, 4000, 0));
            fail("must not overwrite saved state");
        } catch (IOException expected) { /* expected */ }
        try {
            ledger.forgetMatching(pkg, new TaskerPackageFreezeLedger.Entry(3000, 4000, 1));
            fail("must not clear another package installation's snapshot");
        } catch (IOException expected) { /* expected */ }
        assertTrue(journal().find(pkg).sameAs(saved));
        ledger.forgetMatching(pkg, saved);
    }

    @Test public void disabledUserIsNeverSavedAsOwnedFreeze() throws Exception {
        try {
            journal().record("com.example.amt04b2b_invalid",
                    new TaskerPackageFreezeLedger.Entry(123, 456,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER));
            fail("must not claim external disabled-user state");
        } catch (IOException expected) { /* expected */ }
    }
}
