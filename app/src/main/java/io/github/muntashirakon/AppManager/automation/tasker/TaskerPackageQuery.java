// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import org.json.JSONException;
import org.json.JSONObject;

/** App Manager's existing PackageManager view; no privileged operation or shell. */
public final class TaskerPackageQuery {
    private TaskerPackageQuery() { }

    private static Bundle result(String pkg, boolean success, boolean installed,
                                 String versionName, String versionCode, String uid,
                                 String installer, String enabled, String system, String error) {
        Bundle vars = new Bundle();
        vars.putString("%am_success", Boolean.toString(success));
        vars.putString("%am_status", success ? "success" : "failed");
        vars.putString("%am_error", error);
        vars.putString("%am_package", pkg == null ? "" : pkg);
        vars.putString("%am_installed", Boolean.toString(installed));
        vars.putString("%am_version_name", versionName);
        vars.putString("%am_version_code", versionCode);
        vars.putString("%am_uid", uid);
        vars.putString("%am_installer", installer);
        vars.putString("%am_enabled", enabled);
        vars.putString("%am_system", system);
        // Always clear new fields: Tasker variables must never retain stale inventory.
        vars.putString("%am_backup_status", "not_queried");
        vars.putString("%am_backup_count", "");
        vars.putString("%am_backup_latest_ms", "");
        vars.putString("%am_backups_json", "{}");
        try {
            JSONObject json = new JSONObject();
            json.put("success", success);
            json.put("package", pkg == null ? "" : pkg);
            json.put("installed", installed);
            json.put("versionName", versionName);
            json.put("versionCode", versionCode);
            json.put("uid", uid);
            json.put("installer", installer);
            json.put("enabled", enabled);
            json.put("system", system);
            json.put("error", error);
            vars.putString("%am_json", json.toString());
        } catch (JSONException e) {
            vars.putString("%am_json", "{}");
            vars.putString("%am_success", "false");
            vars.putString("%am_status", "failed");
            vars.putString("%am_error", "json_serialization_failed");
        }
        return vars;
    }

    /** Fail closed with all output keys cleared (never leave stale Tasker variables). */
    public static Bundle failure(String error) {
        return result("", false, false, "", "", "", "", "", "", error);
    }

    /** A missing package is a *successful negative query*, not a failed action. */
    public static Bundle query(Context context, String pkg) {
        if (!TaskerPackageQueryWire.validPackage(pkg)) {
            return result(pkg, false, false, "", "", "", "", "", "", 
                    pkg != null && pkg.startsWith("%") ? "unresolved_tasker_variable" : "invalid_package");
        }
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = Build.VERSION.SDK_INT >= 33
                    ? pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
                    : pm.getPackageInfo(pkg, 0);
            ApplicationInfo ai = info.applicationInfo;
            if (ai == null) return result(pkg, false, false, "", "", "", "", "", "", "package_info_unavailable");
            String installer = "";
            try {
                if (Build.VERSION.SDK_INT >= 30) installer = pm.getInstallSourceInfo(pkg).getInstallingPackageName();
                else installer = pm.getInstallerPackageName(pkg);
            } catch (PackageManager.NameNotFoundException | SecurityException ignored) {
                // Installed package metadata is still valid if installer provenance is unavailable.
            }
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            boolean enabled = ai.enabled;
            try {
                int state = pm.getApplicationEnabledSetting(pkg);
                if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) enabled = true;
                if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                        state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                        state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)
                    enabled = false;
            } catch (IllegalArgumentException | SecurityException ignored) {
                // The visible ApplicationInfo flag is still the conservative fallback.
            }
            Bundle vars = result(pkg, true, true, info.versionName == null ? "" : info.versionName,
                    Long.toString(code), Integer.toString(ai.uid), installer == null ? "" : installer,
                    Boolean.toString(enabled), Boolean.toString((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0), "");
            // Optional inventory failure never falsifies the original package lookup.
            vars.putAll(TaskerBackupInventory.query(pkg));
            return vars;
        } catch (PackageManager.NameNotFoundException missing) {
            return result(pkg, true, false, "", "", "", "", "", "", "");
        } catch (SecurityException denied) {
            return result(pkg, false, false, "", "", "", "", "", "", "package_query_denied");
        } catch (RuntimeException failure) {
            return result(pkg, false, false, "", "", "", "", "", "", "package_query_failed");
        }
    }
}
