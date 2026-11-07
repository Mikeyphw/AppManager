// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.UserHandleHidden;

import java.io.IOException;

import io.github.muntashirakon.AppManager.compat.ManifestCompat;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.self.SelfPermissions;

/** No shell, no privilege escalation: delegates to App Manager's existing PM compat layer. */
public final class TaskerPackageManageBackend {
    private static final Object MUTATION_LOCK = new Object();

    private TaskerPackageManageBackend() { }

    private static Bundle result(String pkg, String operation, boolean success,
                                 String previous, String current, String error) {
        Bundle vars = new Bundle();
        vars.putString("%am_success", Boolean.toString(success));
        vars.putString("%am_status", success
                ? (TaskerPackageManageWire.FORCE_STOP.equals(operation) ? "dispatched" : "success")
                : "failed");
        vars.putString("%am_verified", Boolean.toString(success
                && !TaskerPackageManageWire.FORCE_STOP.equals(operation)));
        vars.putString("%am_error", error);
        vars.putString("%am_package", pkg == null ? "" : pkg);
        vars.putString("%am_operation", operation == null ? "" : operation);
        vars.putString("%am_previous_state", previous);
        vars.putString("%am_new_state", current);
        vars.putString("%am_restore_recorded", "");
        return vars;
    }

    public static Bundle failure(String error) { return result("", "", false, "", "", error); }
    private static boolean isFreezeOperation(String operation) {
        return TaskerPackageManageWire.FREEZE.equals(operation)
                || TaskerPackageManageWire.UNFREEZE.equals(operation);
    }

    private static Bundle restoreFlag(Bundle vars, String saved) {
        vars.putString("%am_restore_recorded", saved);
        return vars;
    }

    /** Disable-only freeze: never emulate App Manager's suspend/hide methods. */
    private static Bundle executeFreeze(Context ctx, String pkg, String operation, int user,
                                        ApplicationInfo application) throws IOException,
                                        PackageManager.NameNotFoundException {
        boolean freezing = TaskerPackageManageWire.FREEZE.equals(operation);
        if (!SelfPermissions.checkSelfOrRemotePermission(Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE))
            return restoreFlag(result(pkg, operation, false, "", "", "backend_permission_required"), "unknown");
        // Both fields must still identify the same installed package on restoration.
        PackageInfo identity = ctx.getPackageManager().getPackageInfo(pkg, 0);
        TaskerPackageFreezeLedger ledger = new TaskerPackageFreezeLedger(ctx);
        TaskerPackageFreezeLedger.Entry saved = ledger.find(pkg);
        int current = PackageManagerCompat.getApplicationEnabledSetting(pkg, user);
        if (saved != null && !saved.matches(application.uid, identity.firstInstallTime))
            return restoreFlag(result(pkg, operation, false, "", Integer.toString(current),
                    "freeze_identity_changed"), "true");
        if (freezing) {
            if (saved == null) {
                if (current == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
                    return restoreFlag(result(pkg, operation, false, "", Integer.toString(current),
                            "already_disabled_without_snapshot"), "false");
                // Durable snapshot must precede any state mutation, including if the
                // process dies between the write and Binder call.
                saved = new TaskerPackageFreezeLedger.Entry(application.uid,
                        identity.firstInstallTime, current);
                ledger.record(pkg, saved);
            } else if (current != saved.previousState
                    && current != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                return restoreFlag(result(pkg, operation, false,
                        Integer.toString(saved.previousState), Integer.toString(current),
                        "freeze_state_conflict"), "true");
            }
            if (current != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
                PackageManagerCompat.setApplicationEnabledSetting(pkg,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0, user);
            int after = PackageManagerCompat.getApplicationEnabledSetting(pkg, user);
            return restoreFlag(result(pkg, operation,
                    after == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                    Integer.toString(saved.previousState), Integer.toString(after),
                    after == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                            ? "" : "freeze_state_unverified"), "true");
        }
        // Unfreeze NEVER treats an arbitrary disabled application as our own.
        if (saved == null)
            return restoreFlag(result(pkg, operation, false, "", Integer.toString(current),
                    "restore_record_missing"), "false");
        if (current != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                && current != saved.previousState)
            return restoreFlag(result(pkg, operation, false,
                    Integer.toString(saved.previousState), Integer.toString(current),
                    "restore_state_conflict"), "true");
        if (current == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
            PackageManagerCompat.setApplicationEnabledSetting(pkg, saved.previousState, 0, user);
        int after = PackageManagerCompat.getApplicationEnabledSetting(pkg, user);
        if (after != saved.previousState)
            return restoreFlag(result(pkg, operation, false,
                    Integer.toString(saved.previousState), Integer.toString(after),
                    "restore_state_unverified"), "true");
        // If the process crashed after a successful restore but before cleanup,
        // an identical readback allows the stale record to be finalized safely.
        ledger.forgetMatching(pkg, saved);
        return restoreFlag(result(pkg, operation, true,
                Integer.toString(saved.previousState), Integer.toString(after), ""), "false");
    }


    public static Bundle execute(Context ctx, String pkg, String operation) {
        if (!TaskerPackageQueryWire.validPackage(pkg) || !TaskerPackageManageWire.validOperation(operation))
            return failure("invalid_operation");
        if (pkg.equals(ctx.getPackageName())) return result(pkg, operation, false, "", "", "self_target_denied");
        if (!TaskerPackageManageWire.ENABLE.equals(operation)
                && (TaskerPluginWire.TASKER_PACKAGE.equals(pkg)
                        || TaskerPluginWire.LOCALE_PACKAGE.equals(pkg)))
            return result(pkg, operation, false, "", "", "tasker_target_denied");
        synchronized (MUTATION_LOCK) {
            final int user = UserHandleHidden.myUserId();
            final int requested = TaskerPackageManageWire.ENABLE.equals(operation)
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
            try {
                ApplicationInfo application = ctx.getPackageManager().getApplicationInfo(pkg, 0);
                if ((application.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0)
                    return result(pkg, operation, false, "", "", "system_package_denied");
                if (TaskerPackageManageWire.FORCE_STOP.equals(operation)) {
                    // The existing App Manager backend must have this privilege already.
                    // Android exposes no reliable readback to prove a process was killed;
                    // report dispatch separately from a confirmed package-state change.
                    if (!SelfPermissions.checkSelfOrRemotePermission(ManifestCompat.permission.FORCE_STOP_PACKAGES))
                        return result(pkg, operation, false, "", "", "backend_permission_required");
                    PackageManagerCompat.forceStopPackage(pkg, user);
                    return result(pkg, operation, true, "", "", "");
                }
                if (TaskerPackageManageWire.FREEZE.equals(operation)
                        || TaskerPackageManageWire.UNFREEZE.equals(operation)) {
                    try { return executeFreeze(ctx, pkg, operation, user, application); }
                    catch (IOException error) {
                        return restoreFlag(result(pkg, operation, false, "", "",
                                "freeze_ledger_unavailable"), "unknown");
                    }
                }
                // A saved freeze cannot be bypassed by older Enable/Disable actions.
                try {
                    if (new TaskerPackageFreezeLedger(ctx).find(pkg) != null)
                        return result(pkg, operation, false, "", "", "managed_freeze_pending");
                } catch (IOException error) {
                    return result(pkg, operation, false, "", "", "freeze_ledger_unavailable");
                }
                if (!SelfPermissions.checkSelfOrRemotePermission(Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE))
                    return result(pkg, operation, false, "", "", "backend_permission_required");
                int before = PackageManagerCompat.getApplicationEnabledSetting(pkg, user);
                if (before == requested) return result(pkg, operation, true,
                        Integer.toString(before), Integer.toString(before), "");
                PackageManagerCompat.setApplicationEnabledSetting(pkg, requested, 0, user);
                int after = PackageManagerCompat.getApplicationEnabledSetting(pkg, user);
                if (after != requested) return result(pkg, operation, false,
                        Integer.toString(before), Integer.toString(after), "state_unverified");
                return result(pkg, operation, true,
                        Integer.toString(before), Integer.toString(after), "");
            } catch (PackageManager.NameNotFoundException missing) {
                return restoreFlag(result(pkg, operation, false, "", "", "package_missing"),
                        isFreezeOperation(operation) ? "unknown" : "");
            } catch (SecurityException denied) {
                return restoreFlag(result(pkg, operation, false, "", "", "backend_permission_denied"),
                        isFreezeOperation(operation) ? "unknown" : "");
            } catch (RuntimeException e) {
                // A failed Binder operation may have changed state; never assert rollback.
                return restoreFlag(result(pkg, operation, false, "", "", "state_unknown_after_error"),
                        isFreezeOperation(operation) ? "unknown" : "");
            }
        }
    }
}
