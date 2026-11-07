// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.content.Context;
import android.os.Bundle;

/** Internal fixed-scope evaluator. No exported component or implicit broadcast filter.
 * The shared TaskerActionReceiver owns ordered-broadcast results and goAsync().
 */
public final class TaskerPackageManageReceiver {
    private TaskerPackageManageReceiver() { }

    public static Bundle evaluate(Context context, Bundle saved) {
        try {
            if (!TaskerPackageManageWire.validConfiguration(saved) ||
                    !TaskerPackageManageAccess.valid(context,
                            saved.getString(TaskerPackageManageWire.KEY_PACKAGE),
                            saved.getString(TaskerPackageManageWire.KEY_OPERATION),
                            saved.getString(TaskerPackageManageWire.KEY_TOKEN))) {
                return TaskerPackageManageBackend.failure("invalid_plugin_configuration");
            }
            return TaskerPackageManageBackend.execute(context,
                    saved.getString(TaskerPackageManageWire.KEY_PACKAGE),
                    saved.getString(TaskerPackageManageWire.KEY_OPERATION));
        } catch (RuntimeException ignored) {
            return TaskerPackageManageBackend.failure("operation_dispatch_failed");
        }
    }
}
