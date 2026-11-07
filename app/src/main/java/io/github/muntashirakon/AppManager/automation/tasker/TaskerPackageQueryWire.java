// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

import java.util.regex.Pattern;

/** AMT-04A: read-only, separately authorized Tasker package query contract. */
public final class TaskerPackageQueryWire {
    public static final int VERSION = 1;
    public static final String KEY_VERSION = "am.pkgquery.version";
    public static final String KEY_TOKEN = "am.pkgquery.access";
    public static final String KEY_PACKAGE = "am.pkgquery.package";
    public static final String KEY_DYNAMIC = "am.pkgquery.dynamic";
    private static final Pattern PACKAGE = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");

    private TaskerPackageQueryWire() { }

    public static boolean validPackage(@Nullable String pkg) {
        return pkg != null && pkg.length() <= 255 && PACKAGE.matcher(pkg).matches();
    }

    /** A single Tasker substitution variable, not an arbitrary expression. */
    public static boolean validVariable(@Nullable String text) {
        return TaskerPluginWire.validVariableReference(text);
    }

    public static Bundle saved(String pkg, String token, boolean dynamic) {
        if (token == null || token.length() != 43 ||
                !(dynamic ? validVariable(pkg) : validPackage(pkg))) {
            throw new IllegalArgumentException("invalid package query configuration");
        }
        Bundle config = new Bundle();
        config.putInt(KEY_VERSION, VERSION);
        config.putString(KEY_PACKAGE, pkg);
        config.putString(KEY_TOKEN, token);
        if (dynamic) config.putBoolean(KEY_DYNAMIC, true);
        return config;
    }

    /** The host replaces KEY_PACKAGE on fire when dynamic=true. */
    public static boolean validConfiguration(@Nullable Bundle config) {
        if (config == null || config.size() < 3 || config.size() > 4) return false;
        for (String key : config.keySet()) {
            if (!KEY_VERSION.equals(key) && !KEY_TOKEN.equals(key) &&
                    !KEY_PACKAGE.equals(key) && !KEY_DYNAMIC.equals(key)) return false;
        }
        if (!(config.get(KEY_VERSION) instanceof Integer) ||
                config.getInt(KEY_VERSION, -1) != VERSION ||
                !(config.get(KEY_TOKEN) instanceof String) ||
                !(config.get(KEY_PACKAGE) instanceof String)) return false;
        if (config.containsKey(KEY_DYNAMIC) &&
                (!(config.get(KEY_DYNAMIC) instanceof Boolean) ||
                        !config.getBoolean(KEY_DYNAMIC, false))) return false;
        String token = config.getString(KEY_TOKEN);
        String pkg = config.getString(KEY_PACKAGE);
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return false;
        return validPackage(pkg) || (config.getBoolean(KEY_DYNAMIC, false) && validVariable(pkg));
    }

    public static Intent editResult(Bundle config, String blurb) {
        Intent out = new Intent();
        out.putExtra(TaskerPluginWire.EXTRA_BUNDLE, config);
        out.putExtra(TaskerPluginWire.EXTRA_BLURB, blurb);
        if (config.getBoolean(KEY_DYNAMIC, false)) {
            out.putExtra(TaskerPluginWire.EXTRA_VARIABLE_REPLACE_KEYS, KEY_PACKAGE);
        }
        out.putExtra(TaskerPluginWire.EXTRA_RELEVANT, new String[] {
                "%am_success\nQuery completed",
                "%am_status\nResult status", "%am_error\nError code",
                "%am_package\nPackage ID", "%am_installed\nInstalled",
                "%am_version_name\nVersion name", "%am_version_code\nVersion code",
                "%am_uid\nUID", "%am_installer\nInstaller package",
                "%am_enabled\nEnabled", "%am_system\nSystem app",
                "%am_json\nStructured package metadata JSON",
                "%am_backup_status\nBackup inventory availability",
                "%am_backup_count\nBackups for the current Android user",
                "%am_backup_latest_ms\nLatest backup timestamp (milliseconds)",
                "%am_backups_json\nBounded backup metadata only (no file paths)",
                "%am_restore_request_id\nLast internal-data restore request ID",
                "%am_restore_request_status\nNone, queued, running, native_completed, failed, unknown or unavailable",
                "%am_restore_request_error\nNon-sensitive restore error code",
                "%am_restore_request_updated_ms\nLast restore status timestamp",
                "%am_restore_request_verified\nAlways false: native completion cannot prove restored app behavior",
                "%am_backup_request_name\nLatest Tasker backup request name",
                "%am_backup_request_type\nLatest backup request type: apk or data",
                "%am_backup_request_status\nQueued, running, succeeded, failed, unverified or unknown",
                "%am_backup_request_error\nBackup request failure/uncertainty code",
                "%am_backup_request_verified\nTrue only after native completion plus matching backup metadata",
                "%am_backup_request_updated_ms\nReceipt updated time (milliseconds)",
                "%am_backup_request_pending_count\nNon-expired queued or running APK requests for this package",
                "%am_backup_request_history_json\nLatest 12 current-user APK backup receipts (no file paths)"
        });
        return out;
    }
}
