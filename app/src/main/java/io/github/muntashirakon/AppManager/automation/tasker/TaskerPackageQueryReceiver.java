// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.content.Context;
import android.os.Bundle;

/** Internal evaluator for one shared Locale FIRE_SETTING receiver.
 * This class is not an exported Android component.
 */
public final class TaskerPackageQueryReceiver {
    private TaskerPackageQueryReceiver() { }

    public static Bundle evaluate(Context context, Bundle saved) {
        try {
            if (!TaskerPackageQueryWire.validConfiguration(saved) ||
                    !TaskerPackageQueryAccess.valid(context,
                            saved.getString(TaskerPackageQueryWire.KEY_TOKEN))) {
                return TaskerPackageQuery.failure("invalid_plugin_configuration");
            }
            return TaskerPackageQuery.query(context,
                    saved.getString(TaskerPackageQueryWire.KEY_PACKAGE));
        } catch (RuntimeException ignored) {
            return TaskerPackageQuery.failure("query_dispatch_failed");
        }
    }
}
