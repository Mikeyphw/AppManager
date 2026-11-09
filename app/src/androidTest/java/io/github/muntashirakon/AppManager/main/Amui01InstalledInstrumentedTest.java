// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.main;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.lifecycle.Observer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.muntashirakon.AppManager.db.entity.App;
import io.github.muntashirakon.AppManager.misc.AdvancedSearchView;
import io.github.muntashirakon.AppManager.db.utils.AppDb;
import io.github.muntashirakon.AppManager.types.PackageChangeReceiver;
import io.github.muntashirakon.AppManager.utils.PackageUtils;

/** Run only after CI installs the disposable AMT06C fixture; never touches user app data. */
@RunWith(AndroidJUnit4.class)
public class Amui01InstalledInstrumentedTest {
    private static final String FIXTURE = "dev.mikeyphw.amt06c.fixture";

    @Test public void testUidlessPackageUriIsRecognized() {
        Intent intent = new Intent(Intent.ACTION_PACKAGE_ADDED, Uri.parse("package:" + FIXTURE));
        assertArrayEquals(new String[]{FIXTURE}, PackageChangeReceiver.packagesFromPackageIntent(intent));
        intent.putExtra(Intent.EXTRA_UID, -1);
        assertArrayEquals(new String[]{FIXTURE}, PackageChangeReceiver.packagesFromPackageIntent(intent));
    }

    @Test public void testMissingUidAndUriRequestsFallback() {
        assertNull(PackageChangeReceiver.packagesFromPackageIntent(new Intent(Intent.ACTION_PACKAGE_ADDED)));
    }

    @Test public void testSnapshotDoesNotAliasMutableMetadata() {
        ApplicationItem original = new ApplicationItem();
        original.packageName = FIXTURE;
        original.label = "original label";
        original.versionCode = 7;
        original.userIds = new int[]{0, 10};
        ApplicationItem snapshot = original.snapshotForList();
        original.label = "updated label";
        original.versionCode = 8;
        original.userIds[0] = 42;
        assertEquals("original label", snapshot.label);
        assertEquals(7, snapshot.versionCode);
        assertArrayEquals(new int[]{0, 10}, snapshot.userIds);
    }

    @Test public void testAuthoritativeSyncFindsRealInstalledApk() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNotNull(context.getPackageManager().getPackageInfo(FIXTURE, 0));
        AppDb.SyncReport scan = new AppDb().updateApplicationsChecked(context);
        assertTrue("No authoritative package enumeration", scan.scannedUsers > 0);
        boolean inDatabase = false;
        for (App app : new AppDb().getAllApplications(FIXTURE)) {
            inDatabase |= app.isInstalled;
        }
        assertTrue("Installed fixture missing from reconciled database", inDatabase);
        boolean inCache = false;
        for (ApplicationItem item : PackageUtils.getCachedInstalledOrBackedUpApplicationsFromDb(context)) {
            inCache |= FIXTURE.equals(item.packageName) && item.isInstalled;
        }
        assertTrue("Installed fixture missing from refreshed list", inCache);
    }

    @Test public void testManualRefreshCompletesAfterVisiblePublication() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MainViewModel model = new MainViewModel((Application) context.getApplicationContext());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AppListSyncStatus> receipt = new AtomicReference<>();
        AtomicReference<List<ApplicationItem>> visible = new AtomicReference<>();
        Observer<AppListSyncStatus> status = next -> {
            if (next != null && !next.running && "instrumentation".equals(next.reason)) {
                receipt.set(next);
                latch.countDown();
            }
        };
        Observer<List<ApplicationItem>> list = visible::set;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            model.getRefreshState().observeForever(status);
            model.getApplicationItems().observeForever(list);
            // Rapid search changes during a pending startup sync must not
            // overwrite the last active filter or its authoritative result.
            model.setSearchQuery("not-a-real-fixture-2026", AdvancedSearchView.SEARCH_TYPE_CONTAINS);
            model.setSearchQuery(FIXTURE, AdvancedSearchView.SEARCH_TYPE_CONTAINS);
            model.refreshApplications("instrumentation");
        });
        try {
            assertTrue("Authoritative refresh timeout", latch.await(120, TimeUnit.SECONDS));
            assertTrue("Refresh reported failure: " + receipt.get().error, receipt.get().success);
            assertNotNull("List was not published", visible.get());
            assertEquals("Status precedes visible publication", visible.get().size(), receipt.get().visibleCount);
            assertTrue("Active search filter was ignored", receipt.get().filtered);
            assertEquals("Newest search query lost during refresh", FIXTURE, model.getSearchQuery());
            for (ApplicationItem item : visible.get()) {
                assertEquals("Stale/unfiltered item leaked into search results", FIXTURE, item.packageName);
            }
            boolean present = false;
            for (ApplicationItem item : visible.get()) {
                present |= FIXTURE.equals(item.packageName) && item.isInstalled;
            }
            assertTrue("Installed fixture hidden by unexpected list state", present);
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                model.getRefreshState().removeObserver(status);
                model.getApplicationItems().removeObserver(list);
            });
        }
    }
}
