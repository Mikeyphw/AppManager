// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.os.Bundle;
import android.os.UserHandleHidden;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

import io.github.muntashirakon.AppManager.backup.BackupUtils;
import io.github.muntashirakon.AppManager.db.entity.Backup;

/** AMT-05A read-only inventory: scoped to the queried package and current Android user.
 * Returns bounded metadata only; never exposes archive paths, keys or payload data.
 * Invoked after the existing Tasker Package Query capability has been validated.
 */
public final class TaskerBackupInventory {
    private static final int MAX_ITEMS = 24;
    private static final int MAX_LABEL_LENGTH = 80;

    private TaskerBackupInventory() { }

    public static Bundle query(String pkg) {
        if (!TaskerPackageQueryWire.validPackage(pkg)) return unavailable("invalid_package");
        try {
            return summarize(pkg, UserHandleHidden.myUserId(),
                    BackupUtils.getBackupMetadataFromDbNoLockValidate(pkg));
        } catch (RuntimeException failure) {
            // A failed database/storage read is never represented as zero backups.
            return unavailable("inventory_unavailable");
        }
    }

    public static Bundle unavailable(String reason) {
        Bundle out = new Bundle();
        out.putString("%am_backup_status", reason);
        out.putString("%am_backup_count", "");
        out.putString("%am_backup_latest_ms", "");
        out.putString("%am_backups_json", "{}");
        return out;
    }

    /** Pure bounded summarizer; useful for JVM and real emulator instrumentation. */
    static Bundle summarize(String pkg, int user, List<Backup> candidates) {
        if (!TaskerPackageQueryWire.validPackage(pkg) || candidates == null || user < 0)
            return unavailable("invalid_inventory_request");
        int count = 0;
        long latestMs = 0L;
        JSONArray entries = new JSONArray();
        try {
            for (Backup item : candidates) {
                if (item == null || !pkg.equals(item.packageName) || item.userId != user) continue;
                if (count == Integer.MAX_VALUE) return unavailable("inventory_capacity_exceeded");
                ++count;
                if (item.backupTime > latestMs) latestMs = item.backupTime;
                if (entries.length() >= MAX_ITEMS) continue;
                JSONObject entry = new JSONObject();
                String name = item.backupName == null ? "" : item.backupName;
                if (name.length() > MAX_LABEL_LENGTH) name = name.substring(0, MAX_LABEL_LENGTH);
                entry.put("name", name);
                entry.put("created_ms", item.backupTime);
                entry.put("flags", item.flags);
                entry.put("version_code", item.versionCode);
                entries.put(entry);
            }
            JSONObject summary = new JSONObject();
            summary.put("package", pkg);
            summary.put("count", count);
            summary.put("returned", entries.length());
            summary.put("truncated", count > entries.length());
            summary.put("backups", entries);
            Bundle out = new Bundle();
            out.putString("%am_backup_status", "available");
            out.putString("%am_backup_count", Integer.toString(count));
            out.putString("%am_backup_latest_ms", Long.toString(latestMs));
            out.putString("%am_backups_json", summary.toString());
            return out;
        } catch (JSONException | RuntimeException failure) {
            return unavailable("inventory_serialization_failed");
        }
    }
}
