// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.apk.installer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import io.github.muntashirakon.AppManager.apk.ApkSource;
import io.github.muntashirakon.AppManager.apk.CachedApkSource;
import io.github.muntashirakon.AppManager.fm.FmProvider;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.io.Paths;

/** Deletes only an explicitly user-selected *original* archive after install success. */
public final class OriginalApkDeletion {
    private static final String TAG = "OriginalApkDeletion";

    private OriginalApkDeletion() {}

    public static boolean isEligible(@NonNull ApkQueueItem item) {
        if (item.isInstallExisting() || !item.isDeleteSourceEligible()) return false;
        ApkSource source = item.getApkSource();
        if (!(source instanceof CachedApkSource)) return false;
        Uri uri = normalizedOriginalUri(((CachedApkSource) source).getOriginalUri());
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (ContentResolver.SCHEME_FILE.equals(scheme)) {
            try {
                File target = new File(uri.getPath()).getCanonicalFile();
                return isSafeExternalFilePath(target.getPath()) && isApkArchiveName(target.getName());
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }
        return ContentResolver.SCHEME_CONTENT.equals(scheme);
    }

    static boolean hasLaterSource(@NonNull ApkQueueItem current,
                                  @NonNull Iterable<ApkQueueItem> laterItems) {
        if (!(current.getApkSource() instanceof CachedApkSource)) return false;
        Uri original = ((CachedApkSource) current.getApkSource()).getOriginalUri();
        for (ApkQueueItem later : laterItems) {
            if (!later.isDeleteSourceEligible()
                    || !(later.getApkSource() instanceof CachedApkSource)) continue;
            if (sameOriginalSource(original, ((CachedApkSource) later.getApkSource()).getOriginalUri())) {
                return true;
            }
        }
        return false;
    }

    static boolean sameOriginalSource(@NonNull Uri first, @NonNull Uri second) {
        Uri a = normalizedOriginalUri(first);
        Uri b = normalizedOriginalUri(second);
        if (a == null || b == null) return false;
        if (ContentResolver.SCHEME_FILE.equals(a.getScheme())
                && ContentResolver.SCHEME_FILE.equals(b.getScheme())) {
            try {
                return new File(a.getPath()).getCanonicalFile().equals(
                        new File(b.getPath()).getCanonicalFile());
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }
        return a.equals(b);
    }

    static boolean shouldDelete(int result, @NonNull InstallerOptions options,
                                @NonNull ApkQueueItem item) {
        return result == PackageInstallerCompat.STATUS_SUCCESS
                && options.isDeleteApkAfterInstall() && isEligible(item);
    }

    /**
     * Never touches installation staging cache or installed application sourceDir.
     * Explicitly avoids root permissions and refuses file paths outside shared storage.
     */
    public static boolean deleteOriginal(@NonNull Context context, @NonNull ApkQueueItem item) {
        if (!isEligible(item)) return false;
        Uri uri = normalizedOriginalUri(((CachedApkSource) item.getApkSource()).getOriginalUri());
        if (uri == null) return false;
        try {
            if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
                File source = new File(uri.getPath()).getCanonicalFile();
                if (!isSafeExternalFilePath(source.getPath()) || !isApkArchiveName(source.getName()))
                    return false;
                // Existing AppManager file backend supports its configured privilege mode.
                // Path.delete() may recurse for directories, so reject non-files and symlinks.
                io.github.muntashirakon.io.Path target = Paths.get(source);
                if (!target.isFile() || target.isDirectory() || target.isSymbolicLink()) return false;
                return target.delete();
            }
            if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
                // A provider may represent an arbitrary sensitive file. Verify its name first.
                if (!isApkArchiveName(queryDisplayName(context, uri))) return false;
                ContentResolver resolver = context.getContentResolver();
                if (DocumentsContract.isDocumentUri(context, uri)) {
                    return DocumentsContract.deleteDocument(resolver, uri);
                }
                return resolver.delete(uri, null, null) > 0;
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Original APK deletion denied for " + uri, e);
        }
        return false;
    }

    @Nullable
    private static Uri normalizedOriginalUri(@NonNull Uri source) {
        if (ContentResolver.SCHEME_CONTENT.equals(source.getScheme())
                && FmProvider.AUTHORITY.equals(source.getAuthority())) {
            return FmProvider.getOriginalFileUriForInstallerCleanup(source);
        }
        return source;
    }

    @Nullable
    private static String queryDisplayName(@NonNull Context context, @NonNull Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Source document name is not readable", e);
        }
        return null;
    }

    static boolean isApkArchiveName(@Nullable String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".apk") || lower.endsWith(".apks")
                || lower.endsWith(".apkm") || lower.endsWith(".xapk");
    }

    static boolean isSafeExternalFilePath(@Nullable String canonicalPath) {
        if (canonicalPath == null || !canonicalPath.startsWith("/storage/")
                || canonicalPath.length() <= "/storage/".length()) return false;
        // Privileged file-manager backends must not delete app-private external files.
        return !canonicalPath.contains("/Android/data/")
                && !canonicalPath.contains("/Android/obb/");
    }
}
