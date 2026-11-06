// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import androidx.core.content.ContextCompat;

import org.json.JSONException;

import java.io.IOException;
import java.util.UUID;

import io.github.muntashirakon.AppManager.profiles.ProfileApplierActivity;
import io.github.muntashirakon.AppManager.profiles.ProfileApplierService;
import io.github.muntashirakon.AppManager.profiles.ProfileManager;
import io.github.muntashirakon.AppManager.profiles.ProfileQueueItem;
import io.github.muntashirakon.AppManager.profiles.struct.BaseProfile;

/**
 * Public only through Tasker's Locale FIRE_SETTING action. The editor's host is
 * verified by Android, and an automatic no-backup capability authenticates
 * the saved Tasker configuration on every invocation. No UI consent step.
 */
public final class TaskerActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!TaskerPluginWire.FIRE_ACTION.equals(intent.getAction())) return;
        boolean ordered = isOrderedBroadcast();
        if (Build.VERSION.SDK_INT >= 34) {
            String sender = getSentFromPackage();
            if (sender != null && !TaskerPluginWire.trustedEditor(sender)) {
                deny(ordered, TaskerPluginWire.RESULT_FAILED,
                        TaskerPluginWire.variables(null, null, "", "denied", "untrusted_host"));
                return;
            }
        }
        Bundle config;
        try { config = TaskerPluginWire.fromIntent(intent); }
        catch (RuntimeException error) { config = null; }
        boolean authorized;
        try {
            authorized = TaskerPluginWire.validConfiguration(config) &&
                    TaskerAccess.valid(context, config.getString(TaskerPluginWire.KEY_TOKEN));
        } catch (RuntimeException invalid) { authorized = false; }
        if (!authorized) {
            deny(ordered, TaskerPluginWire.RESULT_FAILED,
                    TaskerPluginWire.variables(null, null, "", "denied", "invalid_plugin_configuration"));
            return;
        }
        final Bundle saved = new Bundle(config);
        final String callback = TaskerPluginWire.MODE_APPLY.equals(config.getString(TaskerPluginWire.KEY_MODE))
                && config.getBoolean(TaskerPluginWire.KEY_WAIT, false)
                ? TaskerCompletion.acceptedCallback(intent) : null;
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                Bundle vars = dispatch(app, saved, callback);
                if (ordered) {
                    boolean failed = "failed".equals(vars.getString("%am_status"));
                    boolean queuedWithCallback = "queued".equals(vars.getString("%am_status"))
                            && callback != null;
                    pending.setResultCode(failed ? TaskerPluginWire.RESULT_FAILED
                            : queuedWithCallback ? TaskerPluginWire.RESULT_PENDING
                            : TaskerPluginWire.RESULT_OK);
                    Bundle result = new Bundle();
                    result.putBundle(TaskerPluginWire.EXTRA_VARIABLES, vars);
                    pending.setResultExtras(result);
                }
            } catch (RuntimeException error) {
                if (ordered) {
                    pending.setResultCode(TaskerPluginWire.RESULT_FAILED);
                    Bundle result = new Bundle();
                    result.putBundle(TaskerPluginWire.EXTRA_VARIABLES,
                            TaskerPluginWire.variables(null, null, "", "failed", "internal_error"));
                    pending.setResultExtras(result);
                }
            } finally { pending.finish(); }
        }, "tasker-profile-dispatch").start();
    }

    private void deny(boolean ordered, int code, Bundle vars) {
        if (!ordered) return;
        setResultCode(code);
        Bundle result = new Bundle();
        result.putBundle(TaskerPluginWire.EXTRA_VARIABLES, vars);
        setResultExtras(result);
    }

    private Bundle dispatch(Context context, Bundle config, String callbackUri) {
        String mode = config.getString(TaskerPluginWire.KEY_MODE);
        if (TaskerPluginWire.MODE_STATUS.equals(mode)) return TaskerProfileEvents.lastTerminal(context);
        String profileId = config.getString(TaskerPluginWire.KEY_PROFILE);
        String state = config.getString(TaskerPluginWire.KEY_STATE);
        String id = UUID.randomUUID().toString().replace("-", "");
        if (config.getBoolean(TaskerPluginWire.KEY_DYNAMIC, false)
                && (profileId == null || profileId.startsWith("%"))) {
            // Tasker did not replace the reference; never look up a literal
            // %variable as a profile or silently select a different profile.
            TaskerProfileEvents.dispatchFailed(context, id, profileId, state);
            return TaskerPluginWire.variables(id, profileId, state, "failed", "unresolved_tasker_variable");
        }
        try {
            // Re-read the file at execution time: Tasker's saved label is not
            // authoritative, and deleted/renamed profiles fail closed.
            BaseProfile profile = BaseProfile.fromPath(ProfileManager.findProfilePathById(profileId));
            if (!profileId.equals(profile.profileId)) throw new IOException("profile identity mismatch");
            if (!TaskerPluginWire.validProfile(profileId)) throw new IOException("invalid profile ID");
            ProfileApplierActivity.ProfileApplierInfo info = new ProfileApplierActivity.ProfileApplierInfo();
            info.profile = profile;
            info.profileId = profile.profileId;
            info.shortcutType = ProfileApplierActivity.ST_SIMPLE;
            info.state = state;
            info.notify = false;
            ProfileQueueItem item = ProfileQueueItem.fromProfiledApplierInfo(info);
            Intent serviceIntent = ProfileApplierService.getIntent(context, item, false);
            serviceIntent.putExtra(TaskerProfileEvents.EXTRA_REQUEST_ID, id);
            if (callbackUri != null) serviceIntent.putExtra(TaskerCompletion.EXTRA_CALLBACK_URI, callbackUri);
            TaskerProfileEvents.queued(context, item, id);
            ContextCompat.startForegroundService(context, serviceIntent);
            return TaskerPluginWire.variables(id, profileId, state, "queued", "");
        } catch (IOException | JSONException | RuntimeException error) {
            // Includes Android background foreground-service restrictions.
            TaskerProfileEvents.dispatchFailed(context, id, profileId, state);
            return TaskerPluginWire.variables(id, profileId, state, "failed", "profile_dispatch_failed");
        }
    }
}
