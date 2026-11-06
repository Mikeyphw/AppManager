// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.Nullable;

import java.net.URISyntaxException;

import io.github.muntashirakon.AppManager.profiles.ProfileQueueItem;

/**
 * Implements Tasker's standard pending-setting completion protocol.
 * The saved action is authorized by TaskerAccess before a callback is accepted.
 * Completion is deliberately routed ONLY to Tasker's own package. No arbitrary
 * Intent URI may redirect profile status to another app or request execution.
 */
public final class TaskerCompletion {
    public static final String EXTRA_CALLBACK_URI = "am.tasker.internal.callback_uri";
    private static final int MAX_URI_LENGTH = 8192;

    private TaskerCompletion() { }

    @Nullable
    public static String acceptedCallback(@Nullable Intent fireIntent) {
        if (fireIntent == null) return null;
        String uri;
        try { uri = fireIntent.getStringExtra(TaskerPluginWire.EXTRA_COMPLETION); }
        catch (RuntimeException ignored) { return null; }
        return validUri(uri) ? uri : null;
    }

    private static boolean validUri(@Nullable String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_URI_LENGTH) return false;
        try {
            Intent reply = Intent.parseUri(value, Intent.URI_INTENT_SCHEME);
            if (reply.getSelector() != null) return false;
            ComponentName component = reply.getComponent();
            if (component != null && !TaskerPluginWire.TASKER_PACKAGE.equals(component.getPackageName()))
                return false;
            if (reply.getPackage() != null && !TaskerPluginWire.TASKER_PACKAGE.equals(reply.getPackage()))
                return false;
            String servicePackage = reply.getStringExtra(TaskerPluginWire.EXTRA_CALL_SERVICE_PACKAGE);
            String service = reply.getStringExtra(TaskerPluginWire.EXTRA_CALL_SERVICE);
            if (servicePackage != null || service != null) {
                if (!TaskerPluginWire.TASKER_PACKAGE.equals(servicePackage)
                        || service == null || service.isEmpty()) return false;
            }
            // A completely empty explicit callback has no safe purpose.
            return (component != null || (reply.getAction() != null && !reply.getAction().isEmpty()));
        } catch (URISyntaxException | RuntimeException ignored) { return false; }
    }

    /** Called from the existing internal profile service AFTER the final outcome is recorded. */
    public static void finish(Context context, @Nullable Intent serviceIntent, String requestId,
                              ProfileQueueItem item, boolean success) {
        if (serviceIntent == null || item == null) return;
        String uri;
        try { uri = serviceIntent.getStringExtra(EXTRA_CALLBACK_URI); }
        catch (RuntimeException ignored) { return; }
        if (!validUri(uri)) return;
        try {
            Intent reply = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
            Bundle vars = TaskerPluginWire.variables(requestId, item.getProfileId(),
                    item.getState() == null ? "" : item.getState(),
                    success ? "success" : "failed", success ? "" : "profile_apply_failed");
            reply.putExtra(TaskerPluginWire.EXTRA_RESULT_CODE,
                    success ? TaskerPluginWire.RESULT_OK : TaskerPluginWire.RESULT_FAILED);
            reply.putExtra(TaskerPluginWire.EXTRA_VARIABLES, vars);
            reply.setPackage(TaskerPluginWire.TASKER_PACKAGE);
            String servicePackage = reply.getStringExtra(TaskerPluginWire.EXTRA_CALL_SERVICE_PACKAGE);
            String service = reply.getStringExtra(TaskerPluginWire.EXTRA_CALL_SERVICE);
            if (servicePackage != null && service != null) {
                reply.setComponent(new ComponentName(servicePackage, service));
                if (reply.getBooleanExtra(TaskerPluginWire.EXTRA_CALL_SERVICE_FOREGROUND, false)
                        && Build.VERSION.SDK_INT >= 26) context.startForegroundService(reply);
                else context.startService(reply);
            } else context.sendBroadcast(reply);
        } catch (URISyntaxException | RuntimeException ignored) {
            // A Tasker callback must never change the outcome of the profile service.
        }
    }
    /** Deliver a terminal result without a service Intent (cancel/job path). */
    public static void finishWithStatus(Context context, @Nullable String callbackUri,
                                        String requestId, String profileId, String state,
                                        String status, String error) {
        if (!validUri(callbackUri)) return;
        try {
            Intent reply = Intent.parseUri(callbackUri, Intent.URI_INTENT_SCHEME);
            Bundle vars = TaskerPluginWire.variables(requestId, profileId,
                    state == null ? "" : state, status, error);
            reply.putExtra(TaskerPluginWire.EXTRA_RESULT_CODE,
                    "success".equals(status) ? TaskerPluginWire.RESULT_OK : TaskerPluginWire.RESULT_FAILED);
            reply.putExtra(TaskerPluginWire.EXTRA_VARIABLES, vars);
            reply.setPackage(TaskerPluginWire.TASKER_PACKAGE);
            String servicePackage = reply.getStringExtra(TaskerPluginWire.EXTRA_CALL_SERVICE_PACKAGE);
            String service = reply.getStringExtra(TaskerPluginWire.EXTRA_CALL_SERVICE);
            if (servicePackage != null && service != null) {
                reply.setComponent(new ComponentName(servicePackage, service));
                if (reply.getBooleanExtra(TaskerPluginWire.EXTRA_CALL_SERVICE_FOREGROUND, false)
                        && Build.VERSION.SDK_INT >= 26) context.startForegroundService(reply);
                else context.startService(reply);
            } else context.sendBroadcast(reply);
        } catch (URISyntaxException | RuntimeException ignored) {
            // A Tasker callback cannot change an already committed outcome.
        }
    }
}
