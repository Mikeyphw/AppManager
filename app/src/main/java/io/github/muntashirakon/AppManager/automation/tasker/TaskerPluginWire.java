// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


/** Locale/Tasker protocol constants; no additional Gradle dependencies. */
public final class TaskerPluginWire {
    public static final String EDIT_ACTION = "com.twofortyfouram.locale.intent.action.EDIT_SETTING";
    public static final String FIRE_ACTION = "com.twofortyfouram.locale.intent.action.FIRE_SETTING";
    public static final String EDIT_EVENT = "net.dinglisch.android.tasker.ACTION_EDIT_EVENT";
    public static final String EDIT_STATE = "com.twofortyfouram.locale.intent.action.EDIT_CONDITION";
    public static final String QUERY_CONDITION = "com.twofortyfouram.locale.intent.action.QUERY_CONDITION";
    public static final String REQUEST_QUERY = "com.twofortyfouram.locale.intent.action.REQUEST_QUERY";
    public static final String EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE";
    public static final String EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB";
    public static final String EXTRA_ACTIVITY = "com.twofortyfouram.locale.intent.extra.ACTIVITY_CLASS_NAME";
    public static final String EXTRA_VARIABLES = "net.dinglisch.android.tasker.extras.VARIABLES";
    public static final String EXTRA_RELEVANT = "net.dinglisch.android.tasker.RELEVANT_VARIABLES";
    public static final String EXTRA_PASS_THROUGH = "net.dinglisch.android.tasker.extras.PASS_THROUGH_DATA";
    public static final String EXTRA_MESSAGE_ID = "net.dinglisch.android.tasker.MESSAGE_ID";
    public static final int RESULT_OK = Activity.RESULT_OK;
    public static final int RESULT_FAILED = Activity.RESULT_FIRST_USER + 1;
    public static final int CONDITION_SATISFIED = 16;
    public static final int CONDITION_UNSATISFIED = 17;
    public static final int CONDITION_UNKNOWN = 18;
    public static final String KEY_VERSION = "am.version";
    public static final String KEY_TOKEN = "am.access";
    public static final String KEY_MODE = "am.mode";
    public static final String KEY_PROFILE = "am.profile";
    public static final String KEY_STATE = "am.state";
    public static final String KEY_WAIT = "am.wait";
    public static final String EXTRA_HOST_CAPABILITIES = "net.dinglisch.android.tasker.extras.HOST_CAPABILITIES";
    public static final String EXTRA_TIMEOUT = "net.dinglisch.android.tasker.extras.REQUESTED_TIMEOUT";
    public static final String EXTRA_COMPLETION = "net.dinglisch.android.tasker.extras.COMPLETION_INTENT";
    public static final String EXTRA_RESULT_CODE = "net.dinglisch.android.tasker.extras.RESULT_CODE";
    public static final String EXTRA_CALL_SERVICE_PACKAGE = "net.dinglisch.android.taskerm.EXTRA_CALL_SERVICE_PACKAGE";
    public static final String EXTRA_CALL_SERVICE = "net.dinglisch.android.taskerm.EXTRA_CALL_SERVICE";
    public static final String EXTRA_CALL_SERVICE_FOREGROUND = "net.dinglisch.android.taskerm.EXTRA_CALL_SERVICE_FOREGROUND";
    public static final int RESULT_PENDING = Activity.RESULT_FIRST_USER + 2;
    public static final int CAPABILITY_SYNCHRONOUS = 32;
    public static final int COMPLETION_TIMEOUT_MS = 600_000;
    public static final int VERSION = 1;
    public static final String MODE_APPLY = "profile.apply";
    public static final String MODE_STATUS = "profile.status";
    public static final String STATE_ON = "on";
    public static final String STATE_OFF = "off";
    public static final String TASKER_PACKAGE = "net.dinglisch.android.taskerm";
    public static final String LOCALE_PACKAGE = "com.twofortyfouram.locale";

    private TaskerPluginWire() { }

    public static boolean trustedEditor(@Nullable String callingPackage) {
        return TASKER_PACKAGE.equals(callingPackage) || LOCALE_PACKAGE.equals(callingPackage);
    }

    public static boolean validProfile(@Nullable String profile) {
        if (profile == null || profile.isEmpty() || profile.length() > 128
                || profile.trim().isEmpty() || profile.contains("..")) return false;
        for (int i = 0; i < profile.length(); i++) {
            char c = profile.charAt(i);
            if (c == '/' || c == '\\' || Character.isISOControl(c)) return false;
        }
        return true;
    }

    @NonNull
    public static Bundle configuration(String mode, @Nullable String profile, @Nullable String state,
                                       @NonNull String accessToken) {
        return configuration(mode, profile, state, accessToken, false);
    }

    @NonNull
    public static Bundle configuration(String mode, @Nullable String profile, @Nullable String state,
                                       @NonNull String accessToken, boolean waitForCompletion) {
        Bundle out = new Bundle();
        out.putInt(KEY_VERSION, VERSION);
        out.putString(KEY_TOKEN, accessToken);
        out.putString(KEY_MODE, mode);
        if (profile != null) out.putString(KEY_PROFILE, profile);
        if (state != null) out.putString(KEY_STATE, state);
        if (waitForCompletion && MODE_APPLY.equals(mode)) out.putBoolean(KEY_WAIT, true);
        return out;
    }

    public static boolean validConfiguration(@Nullable Bundle value) {
        if (value == null || value.size() > 6 || value.getInt(KEY_VERSION, -1) != VERSION) return false;
        for (String key : value.keySet()) {
            if (!KEY_VERSION.equals(key) && !KEY_TOKEN.equals(key) && !KEY_MODE.equals(key)
                    && !KEY_PROFILE.equals(key) && !KEY_STATE.equals(key) && !KEY_WAIT.equals(key)) return false;
        }
        String mode = value.getString(KEY_MODE);
        if (MODE_STATUS.equals(mode)) return !value.containsKey(KEY_PROFILE) && !value.containsKey(KEY_STATE)
                && !value.containsKey(KEY_WAIT);
        if (value.containsKey(KEY_WAIT) && !(value.get(KEY_WAIT) instanceof Boolean)) return false;
        return MODE_APPLY.equals(mode) && validProfile(value.getString(KEY_PROFILE))
                && (STATE_ON.equals(value.getString(KEY_STATE)) || STATE_OFF.equals(value.getString(KEY_STATE)));
    }

    @Nullable
    public static Bundle fromIntent(@Nullable Intent intent) {
        if (intent == null) return null;
        try { return intent.getBundleExtra(EXTRA_BUNDLE); }
        catch (RuntimeException error) { return null; }
    }

    @NonNull
    public static Intent editResult(@NonNull Bundle configuration, @NonNull String blurb) {
        return editResult(configuration, blurb, false);
    }

    @NonNull
    public static Intent editResult(@NonNull Bundle configuration, @NonNull String blurb,
                                    boolean hostSupportsCompletion) {
        Intent output = new Intent();
        output.putExtra(EXTRA_BUNDLE, configuration);
        output.putExtra(EXTRA_BLURB, blurb);
        if (hostSupportsCompletion && configuration.getBoolean(KEY_WAIT, false)) {
            output.putExtra(EXTRA_TIMEOUT, COMPLETION_TIMEOUT_MS);
        }
        output.putExtra(EXTRA_RELEVANT, new String[] {
                "%am_request_id\nRequest ID", "%am_profile_id\nProfile ID", "%am_status\nQueued or final status",
                "%am_state\nProfile state", "%am_error\nError code"
        });
        return output;
    }

    @NonNull
    public static Bundle variables(@Nullable String requestId, @Nullable String profile,
                                   @NonNull String state, @NonNull String status, @NonNull String error) {
        Bundle output = new Bundle();
        output.putString("%am_request_id", requestId != null ? requestId : "");
        output.putString("%am_profile_id", profile != null ? profile : "");
        output.putString("%am_state", state);
        output.putString("%am_status", status);
        output.putString("%am_error", error);
        return output;
    }
}
