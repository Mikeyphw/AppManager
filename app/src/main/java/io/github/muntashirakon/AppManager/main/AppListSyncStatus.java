// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.main;

import androidx.annotation.NonNull;

/** Immutable status of an awaited, authoritative package database reconciliation. */
public final class AppListSyncStatus {
    public final boolean running;
    public final boolean success;
    public final boolean partial;
    public final boolean filtered;
    public final String reason;
    public final String error;
    public final int sourceCount;
    public final int visibleCount;
    public final int scannedUsers;
    public final int skippedUsers;
    public final long elapsedMs;

    private AppListSyncStatus(boolean running, boolean success, boolean filtered,
                              String reason, String error, int sourceCount, int visibleCount,
                              int scannedUsers, int skippedUsers, long elapsedMs) {
        this.running = running;
        this.success = success;
        this.filtered = filtered;
        this.reason = reason;
        this.error = error;
        this.sourceCount = sourceCount;
        this.visibleCount = visibleCount;
        this.scannedUsers = scannedUsers;
        this.skippedUsers = skippedUsers;
        this.partial = skippedUsers > 0;
        this.elapsedMs = elapsedMs;
    }

    @NonNull public static AppListSyncStatus running(@NonNull String reason) {
        return new AppListSyncStatus(true, false, false, reason, "", 0, 0, 0, 0, 0);
    }

    @NonNull public static AppListSyncStatus failure(@NonNull String reason, @NonNull String error) {
        return new AppListSyncStatus(false, false, false, reason, error, 0, 0, 0, 0, 0);
    }

    @NonNull public static AppListSyncStatus success(@NonNull String reason, int sources,
                                                      int scanned, int skipped, long elapsed) {
        return new AppListSyncStatus(false, true, false, reason, "", sources, 0, scanned,
                skipped, elapsed);
    }

    @NonNull public AppListSyncStatus withVisibleCount(int visible, boolean filtered) {
        return new AppListSyncStatus(running, success, filtered, reason, error, sourceCount,
                visible, scannedUsers, skippedUsers, elapsedMs);
    }
}
