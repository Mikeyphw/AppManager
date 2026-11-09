// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.main;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import io.github.muntashirakon.AppManager.db.entity.App;
import io.github.muntashirakon.AppManager.db.utils.AppDb;
import io.github.muntashirakon.AppManager.utils.PackageUtils;

/** CI runs this only AFTER uninstalling the dedicated disposable fixture. */
@RunWith(AndroidJUnit4.class)
public class Amui01RemovedInstrumentedTest {
    private static final String FIXTURE = "dev.mikeyphw.amt06c.fixture";

    @Test public void testUninstallReconcilesWithoutStaleInstalledRows() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppDb.SyncReport scan = new AppDb().updateApplicationsChecked(context);
        assertTrue(scan.scannedUsers > 0);
        for (App app : new AppDb().getAllApplications(FIXTURE)) {
            assertFalse("Removed APK remains installed in DB", app.isInstalled);
        }
        PackageUtils.getCachedInstalledOrBackedUpApplicationsFromDb(context).forEach(item -> {
            if (FIXTURE.equals(item.packageName)) {
                assertFalse("Removed APK remains installed in list", item.isInstalled);
            }
        });
    }
}
