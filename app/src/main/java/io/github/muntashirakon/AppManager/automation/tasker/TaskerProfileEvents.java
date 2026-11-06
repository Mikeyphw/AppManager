// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    private static final int PROFILE_MAX = 128;
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
            String p = profilePrefix(item.getProfileId());
            store(context).edit().putString("request", id).putString("profile", item.getProfileId())
                    .putString("state", item.getState()).putString("status", "queued")
                    .putString(p + "profile", item.getProfileId()).putString(p + "request", id)
                    .putString(p + "state", item.getState()).putString(p + "status", "queued")
                    .putBoolean(p + "running", false).putLong(p + "time", System.currentTimeMillis()).commit();
            pruneProfileHistory(context);
        } catch (RuntimeException ignored) { }
    }

    public static void dispatchFailed(Context context, String id, String profile, String state) {
        try {
            String p = TaskerPluginWire.validProfile(profile) && !profile.startsWith("%")
                    ? profilePrefix(profile) : null;
            SharedPreferences.Editor edit = store(context).edit().putString("request", id)
                    .putString("profile", profile).putString("state", state).putString("status", "failed")
                    .putBoolean("running", false)
                    .putString("terminal_status", "failed").putString("terminal_request", id)
                    .putString("terminal_profile", profile).putString("terminal_state", state);
            if (p != null) edit.putString(p + "profile", profile).putString(p + "request", id)
                    .putString(p + "state", state).putString(p + "status", "failed")
                    .putBoolean(p + "running", false).putString(p + "terminal_status", "failed")
                    .putString(p + "terminal_request", id).putString(p + "terminal_state", state)
                    .putLong(p + "time", System.currentTimeMillis());
            edit.commit();
            pruneProfileHistory(context);
            requestStateUpdate(context);
        } catch (RuntimeException ignored) { }
    }

    public static void started(Context context, ProfileQueueItem item, String id) {
        try {
            String p = profilePrefix(item.getProfileId());
            store(context).edit().putString("request", id).putString("profile", item.getProfileId())
                    .putString("state", item.getState()).putString("status", "running")
                    .putBoolean("running", true)
                    .putString(p + "profile", item.getProfileId()).putString(p + "request", id)
                    .putString(p + "state", item.getState()).putString(p + "status", "running")
                    .putBoolean(p + "running", true).putLong(p + "time", System.currentTimeMillis()).commit();
            publish(context, "event.started", id, item);
            requestStateUpdate(context);
        } catch (RuntimeException ignored) { }
    }

    public static void finished(Context context, ProfileQueueItem item, String id, boolean success) {
        try {
            String status = success ? "success" : "failed";
            String p = profilePrefix(item.getProfileId());
            store(context).edit().putString("request", id).putString("profile", item.getProfileId())
                    .putString("state", item.getState()).putString("status", status)
                    .putBoolean("running", false)
                    .putString("terminal_status", status).putString("terminal_request", id)
                    .putString("terminal_profile", item.getProfileId())
                    .putString("terminal_state", item.getState())
                    .putString(p + "profile", item.getProfileId()).putString(p + "request", id)
                    .putString(p + "state", item.getState()).putString(p + "status", status)
                    .putBoolean(p + "running", false).putString(p + "terminal_status", status)
                    .putString(p + "terminal_request", id).putString(p + "terminal_state", item.getState())
                    .putLong(p + "time", System.currentTimeMillis()).commit();
            pruneProfileHistory(context);
            publish(context, success ? "event.success" : "event.failed", id, item);
            requestStateUpdate(context);
        } catch (RuntimeException ignored) { }
    }

    /** The legacy no-filter query still reports the most recent global operation. */
    @NonNull public static Bundle snapshot(Context context) {
        return snapshot(context, null);
    }

    @NonNull public static Bundle snapshot(Context context, @Nullable String selectedProfile) {
        SharedPreferences pref = store(context);
        if (selectedProfile == null) return TaskerPluginWire.variables(pref.getString("request", ""),
                pref.getString("profile", ""), pref.getString("state", ""),
                pref.getString("status", "unknown"), "");
        String p = profilePrefix(selectedProfile);
        return TaskerPluginWire.variables(pref.getString(p + "request", ""), selectedProfile,
                pref.getString(p + "state", ""), pref.getString(p + "status", "unknown"), "");
    }

    @NonNull public static Bundle lastTerminal(Context context) {
        return lastTerminal(context, null);
    }

    @NonNull public static Bundle lastTerminal(Context context, @Nullable String selectedProfile) {
        SharedPreferences pref = store(context);
        if (selectedProfile == null) return TaskerPluginWire.variables(pref.getString("terminal_request", ""),
                pref.getString("terminal_profile", ""), pref.getString("terminal_state", ""),
                pref.getString("terminal_status", "unknown"), "");
        String p = profilePrefix(selectedProfile);
        return TaskerPluginWire.variables(pref.getString(p + "terminal_request", ""), selectedProfile,
                pref.getString(p + "terminal_state", ""), pref.getString(p + "terminal_status", "unknown"), "");
    }

    public static boolean stateMatches(Context context, String requested) {
        return stateMatches(context, requested, null);
    }

    public static boolean stateMatches(Context context, String requested, @Nullable String selectedProfile) {
        SharedPreferences pref = store(context);
        String p = selectedProfile == null ? "" : profilePrefix(selectedProfile);
        if (selectedProfile != null && !selectedProfile.equals(pref.getString(p + "profile", ""))) return false;
        if ("state.running".equals(requested)) return pref.getBoolean(p + "running", false);
        if ("state.success".equals(requested)) return "success".equals(pref.getString(
                p + "terminal_status", ""));
        return "state.failed".equals(requested) && "failed".equals(pref.getString(p + "terminal_status", ""));
    }

    @Nullable public static Bundle matchingEvent(Context context, int messageId, String requested) {
        return matchingEvent(context, messageId, requested, null);
    }

    @Nullable public static Bundle matchingEvent(Context context, int messageId, String requested,
                                                   @Nullable String selectedProfile) {
        if (messageId <= 0 || requested == null || !requested.startsWith("event.")) return null;
        SharedPreferences pref = store(context);
        String key = "event." + messageId + ".";
        long time = pref.getLong(key + "time", -1L);
        long elapsed = System.currentTimeMillis() - time;
        if (time <= 0 || elapsed < 0 || elapsed > EVENT_TTL) return null;
        if (!requested.equals(pref.getString(key + "type", ""))) return null;
        String actualProfile = pref.getString(key + "profile", "");
        if (selectedProfile != null && !selectedProfile.equals(actualProfile)) return null;
        return TaskerPluginWire.variables(pref.getString(key + "request", ""),
                actualProfile, pref.getString(key + "state", ""),
                requested.substring("event.".length()), "");
    }

    private static String profilePrefix(String profileId) {
        // Hash IDs instead of using path-like values as SharedPreferences keys.
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    profileId.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder("profile.");
            for (byte b : hash) {
                output.append(Character.forDigit((b >>> 4) & 15, 16));
                output.append(Character.forDigit(b & 15, 16));
            }
            return output.append('.').toString();
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static void pruneProfileHistory(Context context) {
        // Bound persistent profile snapshots; the event journal has its own TTL.
        SharedPreferences pref = store(context);
        List<Map.Entry<String, Long>> timestamps = new ArrayList<>();
        for (Map.Entry<String, ?> entry : pref.getAll().entrySet()) {
            if (!entry.getKey().startsWith("profile.") || !entry.getKey().endsWith(".time")
                    || !(entry.getValue() instanceof Long)) continue;
            timestamps.add(new java.util.AbstractMap.SimpleImmutableEntry<>(
                    entry.getKey().substring(0, entry.getKey().length() - 4), (Long) entry.getValue()));
        }
        if (timestamps.size() <= PROFILE_MAX) return;
        timestamps.sort(Comparator.comparingLong(Map.Entry::getValue));
        SharedPreferences.Editor edit = pref.edit();
        for (int i = 0; i < timestamps.size() - PROFILE_MAX; i++) {
            String p = timestamps.get(i).getKey();
            for (String suffix : new String[] {"profile", "request", "state", "status", "running",
                    "terminal_status", "terminal_request", "terminal_state", "time"}) edit.remove(p + suffix);
        }
        edit.apply();
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
