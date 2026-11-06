// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;

import io.github.muntashirakon.AppManager.profiles.ProfileQueueItem;

/**
 * Narrow local observation store; execution remains owned by ProfileApplierService.
 * Notification failures never affect profile execution. Events carry a Tasker
 * pass-through message ID that must exist in this app's private bounded journal.
 */
public final class TaskerProfileEvents {
    private static final String STORE = "tasker_profile_observations_v1";
    private static final long EVENT_TTL = 2 * 60 * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final String EXTRA_REQUEST_ID = "am.tasker.request_id";

    private TaskerProfileEvents() { }

    private static SharedPreferences store(Context c) {
        return c.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    @NonNull public static String requestId(@Nullable Intent intent) {
        if (intent != null) {
            String fromTasker = intent.getStringExtra(EXTRA_REQUEST_ID);
            if (fromTasker != null && fromTasker.matches("[0-9a-f]{32}")) return fromTasker;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static void queued(Context context, ProfileQueueItem item, String id) {
        try {
            store(context).edit().putString("request", id).putString("profile", item.getProfileId())
                    .putString("state", item.getState()).putString("status", "queued").commit();
        } catch (RuntimeException ignored) { }
    }

    public static void dispatchFailed(Context context, String id, String profile, String state) {
        try {
            store(context).edit().putString("request", id).putString("profile", profile)
                    .putString("state", state).putString("status", "failed")
                    .putBoolean("running", false)
                    .putString("terminal_status", "failed").putString("terminal_request", id)
                    .putString("terminal_profile", profile).putString("terminal_state", state).commit();
            requestStateUpdate(context);
        } catch (RuntimeException ignored) { }
    }

    public static void started(Context context, ProfileQueueItem item, String id) {
        try {
            store(context).edit().putString("request", id).putString("profile", item.getProfileId())
                    .putString("state", item.getState()).putString("status", "running")
                    .putBoolean("running", true).commit();
            publish(context, "event.started", id, item);
            requestStateUpdate(context);
        } catch (RuntimeException ignored) { }
    }

    public static void finished(Context context, ProfileQueueItem item, String id, boolean success) {
        try {
            String status = success ? "success" : "failed";
            store(context).edit().putString("request", id).putString("profile", item.getProfileId())
                    .putString("state", item.getState()).putString("status", status)
                    .putBoolean("running", false)
                    .putString("terminal_status", status).putString("terminal_request", id)
                    .putString("terminal_profile", item.getProfileId())
                    .putString("terminal_state", item.getState()).commit();
            publish(context, success ? "event.success" : "event.failed", id, item);
            requestStateUpdate(context);
        } catch (RuntimeException ignored) { }
    }

    @NonNull public static Bundle snapshot(Context context) {
        SharedPreferences pref = store(context);
        return TaskerPluginWire.variables(pref.getString("request", ""),
                pref.getString("profile", ""), pref.getString("state", ""),
                pref.getString("status", "unknown"), "");
    }

    @NonNull public static Bundle lastTerminal(Context context) {
        SharedPreferences pref = store(context);
        return TaskerPluginWire.variables(pref.getString("terminal_request", ""),
                pref.getString("terminal_profile", ""), pref.getString("terminal_state", ""),
                pref.getString("terminal_status", "unknown"), "");
    }

    public static boolean stateMatches(Context context, String requested) {
        SharedPreferences pref = store(context);
        if ("state.running".equals(requested)) return pref.getBoolean("running", false);
        if ("state.success".equals(requested)) return "success".equals(pref.getString("terminal_status", ""));
        return "state.failed".equals(requested) && "failed".equals(pref.getString("terminal_status", ""));
    }

    @Nullable public static Bundle matchingEvent(Context context, int messageId, String requested) {
        if (messageId <= 0 || requested == null || !requested.startsWith("event.")) return null;
        SharedPreferences pref = store(context);
        String key = "event." + messageId + ".";
        long time = pref.getLong(key + "time", -1L);
        long elapsed = System.currentTimeMillis() - time;
        if (time <= 0 || elapsed < 0 || elapsed > EVENT_TTL) return null;
        if (!requested.equals(pref.getString(key + "type", ""))) return null;
        return TaskerPluginWire.variables(pref.getString(key + "request", ""),
                pref.getString(key + "profile", ""), pref.getString(key + "state", ""),
                requested.substring("event.".length()), "");
    }

    private static void publish(Context context, String type, String id, ProfileQueueItem item) {
        SharedPreferences pref = store(context);
        int messageId = RANDOM.nextInt(Integer.MAX_VALUE - 1) + 1;
        long now = System.currentTimeMillis();
        String key = "event." + messageId + ".";
        SharedPreferences.Editor edit = pref.edit()
                .putLong(key + "time", now).putString(key + "type", type)
                .putString(key + "request", id).putString(key + "profile", item.getProfileId())
                .putString(key + "state", item.getState());
        // Bound stored event history, including entries the Tasker host never queries.
        for (Map.Entry<String, ?> entry : pref.getAll().entrySet()) {
            String k = entry.getKey();
            if (!k.startsWith("event.") || !k.endsWith(".time")) continue;
            Object value = entry.getValue();
            long timestamp = value instanceof Long ? (Long) value : 0;
            if (timestamp > now - EVENT_TTL) continue;
            String prefix = k.substring(0, k.length() - 4);
            edit.remove(prefix + "time").remove(prefix + "type").remove(prefix + "request")
                    .remove(prefix + "profile").remove(prefix + "state");
        }
        if (!edit.commit()) return;
        Intent request = new Intent(TaskerPluginWire.REQUEST_QUERY);
        request.setPackage(TaskerPluginWire.TASKER_PACKAGE);
        request.putExtra(TaskerPluginWire.EXTRA_ACTIVITY, TaskerEventEditActivity.class.getName());
        Bundle passThrough = new Bundle();
        passThrough.putInt(TaskerPluginWire.EXTRA_MESSAGE_ID, messageId);
        request.putExtra(TaskerPluginWire.EXTRA_PASS_THROUGH, passThrough);
        context.sendBroadcast(request);
    }

    private static void requestStateUpdate(Context context) {
        Intent intent = new Intent(TaskerPluginWire.REQUEST_QUERY);
        intent.setPackage(TaskerPluginWire.TASKER_PACKAGE);
        intent.putExtra(TaskerPluginWire.EXTRA_ACTIVITY, TaskerStateEditActivity.class.getName());
        context.sendBroadcast(intent);
    }
}
