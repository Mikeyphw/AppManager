// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.apk.installer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import android.net.Uri;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import io.github.muntashirakon.AppManager.apk.ApkSource;
import io.github.muntashirakon.AppManager.apk.CachedApkSource;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class OriginalApkDeletionTest {
    @Test public void acceptsOnlyPackageArchiveNames() {
        assertTrue(OriginalApkDeletion.isApkArchiveName("Something.APK"));
        assertTrue(OriginalApkDeletion.isApkArchiveName("bundle.apkm"));
        assertTrue(OriginalApkDeletion.isApkArchiveName("bundle.apks"));
        assertTrue(OriginalApkDeletion.isApkArchiveName("bundle.xapk"));
        assertFalse(OriginalApkDeletion.isApkArchiveName("bundle.zip"));
        assertFalse(OriginalApkDeletion.isApkArchiveName("bundle.apk.old"));
        assertFalse(OriginalApkDeletion.isApkArchiveName(null));
    }

    @Test public void refusesProtectedAndTraversalPaths() {
        assertTrue(OriginalApkDeletion.isSafeExternalFilePath("/storage/emulated/0/Download/test.apk"));
        assertFalse(OriginalApkDeletion.isSafeExternalFilePath("/data/app/test.apk"));
        assertFalse(OriginalApkDeletion.isSafeExternalFilePath("/system/app/test.apk"));
        assertFalse(OriginalApkDeletion.isSafeExternalFilePath("/storage/"));
    }

    @Test public void appManagerProviderOnlyAllowsSharedExternalPaths() throws Exception {
        String authority = io.github.muntashirakon.AppManager.fm.FmProvider.AUTHORITY;
        ApkQueueItem external = makeItem("content://" + authority
                + "/storage/emulated/0/Download/test.apk", true);
        ApkQueueItem internal = makeItem("content://" + authority + "/data/app/base.apk", true);
        assertTrue(OriginalApkDeletion.isEligible(external));
        assertFalse(OriginalApkDeletion.isEligible(internal));
    }

    @Test public void originalUriSurvivesCacheCopiesAndJson() throws Exception {
        Uri uri = Uri.parse("content://example.documents/document/sample.apk");
        CachedApkSource original = (CachedApkSource) ApkSource.getCachedApkSource(uri, null);
        CachedApkSource copy = (CachedApkSource) original.toCachedSource();
        assertTrue(uri.equals(copy.getOriginalUri()));
        CachedApkSource decoded = (CachedApkSource) ApkSource.DESERIALIZER.deserialize(copy.serializeToJson());
        assertTrue(uri.equals(decoded.getOriginalUri()));
    }

    @Test public void sameSourceQueueDefersDeletionUntilLastItem() throws Exception {
        ApkQueueItem first = makeItem("file:///storage/emulated/0/Download/test.apk", true);
        ApkQueueItem later = makeItem("file:///storage/emulated/0/Download/test.apk", true);
        ApkQueueItem another = makeItem("file:///storage/emulated/0/Download/other.apk", true);
        assertTrue(OriginalApkDeletion.hasLaterSource(first, java.util.Arrays.asList(another, later)));
        assertFalse(OriginalApkDeletion.hasLaterSource(later, java.util.Arrays.asList(another)));
    }

    @Test public void successAndExplicitOptInAreRequired() throws Exception {
        ApkQueueItem item = makeItem("file:///storage/emulated/0/Download/test.apk", true);
        InstallerOptions options = InstallerOptions.getDefault();
        options.setDeleteApkAfterInstall(true);
        assertTrue(OriginalApkDeletion.shouldDelete(PackageInstallerCompat.STATUS_SUCCESS, options, item));
        assertFalse(OriginalApkDeletion.shouldDelete(PackageInstallerCompat.STATUS_FAILURE_INVALID, options, item));
        options.setDeleteApkAfterInstall(false);
        assertFalse(OriginalApkDeletion.shouldDelete(PackageInstallerCompat.STATUS_SUCCESS, options, item));
    }

    @Test public void neverDeletesInstalledAppOrIneligibleSource() throws Exception {
        InstallerOptions options = InstallerOptions.getDefault();
        options.setDeleteApkAfterInstall(true);
        ApkQueueItem item = makeItem("file:///data/app/installed/base.apk", true);
        assertFalse(OriginalApkDeletion.shouldDelete(PackageInstallerCompat.STATUS_SUCCESS, options, item));
        item = makeItem("file:///storage/emulated/0/Download/a.apk", false);
        assertFalse(OriginalApkDeletion.shouldDelete(PackageInstallerCompat.STATUS_SUCCESS, options, item));
    }

    @Test public void duplicateUriAliasesAreRecognized() throws Exception {
        ApkQueueItem file = makeItem("file:///storage/emulated/0/Download/example.apk", true);
        ApkQueueItem ownProvider = makeItem("content://" +
                io.github.muntashirakon.AppManager.fm.FmProvider.AUTHORITY
                + "/storage/emulated/0/Download/example.apk", true);
        assertTrue(OriginalApkDeletion.hasLaterSource(file, java.util.Arrays.asList(ownProvider)));
    }

    @Test public void privilegedPathsStillRejectPrivateStorage() {
        assertFalse(OriginalApkDeletion.isSafeExternalFilePath(
                "/storage/emulated/0/Android/data/app/example.apk"));
        assertFalse(OriginalApkDeletion.isSafeExternalFilePath(
                "/storage/emulated/0/Android/obb/app/example.apk"));
    }

    @Test public void readableContentProviderDeletesOnlyWhenItsNameIsApk() throws Exception {
        DeleteProvider provider = new DeleteProvider();
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(
                "test.apk.deletion", provider);
        ApkQueueItem item = makeItem("content://test.apk.deletion/example", true);
        assertTrue(OriginalApkDeletion.deleteOriginal(
                org.robolectric.RuntimeEnvironment.getApplication(), item));
        assertTrue(provider.deletes == 1);
        provider.name = "not-an-apk.txt";
        assertFalse(OriginalApkDeletion.deleteOriginal(
                org.robolectric.RuntimeEnvironment.getApplication(), item));
        assertTrue(provider.deletes == 1);
        provider.name = "example.apk";
        provider.deny = true;
        assertFalse(OriginalApkDeletion.deleteOriginal(
                org.robolectric.RuntimeEnvironment.getApplication(), item));
        assertTrue(provider.deletes == 2);
    }

    private static final class DeleteProvider extends android.content.ContentProvider {
        int deletes = 0;
        String name = "example.apk";
        boolean deny;
        @Override public boolean onCreate() { return true; }
        @Override public android.database.Cursor query(android.net.Uri uri, String[] projection,
                String selection, String[] selectionArgs, String sortOrder) {
            android.database.MatrixCursor cursor = new android.database.MatrixCursor(
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME});
            cursor.addRow(new Object[]{name});
            return cursor;
        }
        @Override public String getType(android.net.Uri uri) {
            return "application/vnd.android.package-archive";
        }
        @Override public android.net.Uri insert(android.net.Uri uri, android.content.ContentValues values) {
            return null;
        }
        @Override public int delete(android.net.Uri uri, String selection, String[] selectionArgs) {
            ++deletes;
            if (deny) throw new SecurityException("read-only content");
            return 1;
        }
        @Override public int update(android.net.Uri uri, android.content.ContentValues values,
                String selection, String[] selectionArgs) { return 0; }
    }

    private ApkQueueItem makeItem(String uri, boolean eligible) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("install_existing", false);
        obj.put("delete_source_eligible", eligible);
        obj.put("apk_source", ApkSource.getCachedApkSource(Uri.parse(uri),
                "application/vnd.android.package-archive").serializeToJson());
        return ApkQueueItem.DESERIALIZER.deserialize(obj);
    }
}
