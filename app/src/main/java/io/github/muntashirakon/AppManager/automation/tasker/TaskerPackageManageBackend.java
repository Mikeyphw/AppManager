// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.UserHandleHidden;

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
        vars.putString("%am_status", success ? "success" : "failed");
        vars.putString("%am_error", error);
        vars.putString("%am_package", pkg == null ? "" : pkg);
        vars.putString("%am_operation", operation == null ? "" : operation);
        vars.putString("%am_previous_state", previous);
        vars.putString("%am_new_state", current);
        return vars;
    }

    public static Bundle failure(String error) { return result("", "", false, "", "", error); }

    public static Bundle execute(Context ctx, String pkg, String operation) {
        if (!TaskerPackageQueryWire.validPackage(pkg) || !TaskerPackageManageWire.validOperation(operation))
            return failure("invalid_operation");
        if (pkg.equals(ctx.getPackageName())) return result(pkg, operation, false, "", "", "self_target_denied");
        synchronized (MUTATION_LOCK) {
            final int user = UserHandleHidden.myUserId();
            final int requested = TaskerPackageManageWire.ENABLE.equals(operation)
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
            try {
                ApplicationInfo application = ctx.getPackageManager().getApplicationInfo(pkg, 0);
                if ((application.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0)
                    return result(pkg, operation, false, "", "", "system_package_denied");
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
                return result(pkg, operation, false, "", "", "package_missing");
            } catch (SecurityException denied) {
                return result(pkg, operation, false, "", "", "backend_permission_denied");
            } catch (RuntimeException e) {
                // A failed binder operation may already have changed state; do not claim rollback.
                return result(pkg, operation, false, "", "", "state_unknown_after_error");
            }
        }
    }
}
