// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

import androidx.annotation.Nullable;

/** Background fallback only after a direct FGS start is rejected by Android. */
public final class TaskerWorkScheduler {
    static final String KEY_ID = "tasker.id";
    static final String KEY_PROFILE = "tasker.profile";
    static final String KEY_STATE = "tasker.state";
    static final String KEY_CALLBACK = "tasker.callback";
    private static final int JOB_PREFIX = 0x60000000;
    private TaskerWorkScheduler() { }

    private static int jobId(String id) {
        // Stable 28-bit ID; collision is checked before scheduling or cancel.
        return JOB_PREFIX | (int) Long.parseLong(id.substring(0, 7), 16);
    }

    @Nullable private static JobScheduler scheduler(Context context) {
        return (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    public static boolean schedule(Context context, String id, String profile, String state,
                                   @Nullable String callback) {
        if (id == null || !id.matches("[0-9a-f]{32}")) return false;
        JobScheduler js = scheduler(context);
        if (js == null) return false;
        int jobId = jobId(id);
        try {
            JobInfo pending = null;
            for (JobInfo job : js.getAllPendingJobs()) {
                if (job.getId() == jobId) { pending = job; break; }
            }
            if (pending != null && !id.equals(pending.getExtras().getString(KEY_ID))) {
                // Never replace a different request on the rare integer-ID collision.
                return false;
            }
            PersistableBundle extras = new PersistableBundle();
            extras.putString(KEY_ID, id);
            extras.putString(KEY_PROFILE, profile);
            extras.putString(KEY_STATE, state);
            extras.putString(KEY_CALLBACK, callback == null ? "" : callback);
            JobInfo info = new JobInfo.Builder(jobId,
                    new ComponentName(context, TaskerDispatchJobService.class))
                    .setMinimumLatency(0L)
                    .setOverrideDeadline(120_000L)
                    .setPersisted(false)
                    .setExtras(extras)
                    .build();
            return js.schedule(info) == JobScheduler.RESULT_SUCCESS;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static void cancel(Context context, String id) {
        if (id == null || !id.matches("[0-9a-f]{32}")) return;
        JobScheduler js = scheduler(context);
        if (js == null) return;
        try {
            JobInfo pending = null;
            for (JobInfo job : js.getAllPendingJobs()) {
                if (job.getId() == jobId(id)) { pending = job; break; }
            }
            if (pending != null && id.equals(pending.getExtras().getString(KEY_ID))) {
                js.cancel(jobId(id));
            }
        } catch (RuntimeException ignored) { }
    }
}
