// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/** Locale condition query receiver for both Tasker Event and State plugins. */
public final class TaskerConditionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!TaskerPluginWire.QUERY_CONDITION.equals(intent.getAction()) || !isOrderedBroadcast()) return;
        setResultCode(TaskerPluginWire.CONDITION_UNSATISFIED);
        // From Android 14 the system can attest the broadcast sender; older
        // platforms rely on the configuration's automatic, unguessable token.
        if (Build.VERSION.SDK_INT >= 34) {
            String sender = getSentFromPackage();
            if (sender != null && !TaskerPluginWire.trustedEditor(sender)) return;
        }
        try {
            Bundle config = TaskerPluginWire.fromIntent(intent);
            if (config == null || config.size() > 3 || config.getInt(TaskerPluginWire.KEY_VERSION, -1) != 1
                    || !TaskerAccess.valid(context, config.getString(TaskerPluginWire.KEY_TOKEN))) return;
            String requested = config.getString(TaskerPluginWire.KEY_MODE);
            if (requested == null) return;
            Bundle output;
            if (requested.startsWith("state.")) {
                if (!TaskerProfileEvents.stateMatches(context, requested)) return;
                output = "state.running".equals(requested)
                        ? TaskerProfileEvents.snapshot(context) : TaskerProfileEvents.lastTerminal(context);
            } else if (requested.startsWith("event.")) {
                Bundle pass = intent.getBundleExtra(TaskerPluginWire.EXTRA_PASS_THROUGH);
                if (pass == null) return; // Never replay a prior event on Tasker startup.
                int messageId = pass.getInt(TaskerPluginWire.EXTRA_MESSAGE_ID, -1);
                output = TaskerProfileEvents.matchingEvent(context, messageId, requested);
                if (output == null) return;
            } else return;
            if (isOrderedBroadcast()) {
                Bundle extras = new Bundle();
                extras.putBundle(TaskerPluginWire.EXTRA_VARIABLES, output);
                setResultExtras(extras);
                setResultCode(TaskerPluginWire.CONDITION_SATISFIED);
            }
        } catch (RuntimeException ignored) { setResultCode(TaskerPluginWire.CONDITION_UNSATISFIED); }
    }
}
