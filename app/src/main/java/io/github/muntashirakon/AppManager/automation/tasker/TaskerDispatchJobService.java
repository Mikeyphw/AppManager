// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import static io.github.muntashirakon.AppManager.history.ops.OpHistoryManager.HISTORY_TYPE_PROFILE;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.os.PersistableBundle;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.muntashirakon.AppManager.history.ops.OpHistoryManager;
import io.github.muntashirakon.AppManager.profiles.ProfileApplierActivity;
import io.github.muntashirakon.AppManager.profiles.ProfileManager;
import io.github.muntashirakon.AppManager.profiles.ProfileQueueItem;
import io.github.muntashirakon.AppManager.profiles.struct.BaseProfile;

/**
 * The OS owns pending delivery, so a killed background process can receive an
 * unstarted job again. Once a request is claimed, it is never automatically
 * executed a second time. No privileged service is exported.
 */
public final class TaskerDispatchJobService extends JobService {
    // JobService is a singleton for all JobScheduler IDs; a stop for request A
    // must never cancel unrelated request B.
    private final Set<String> stoppedIds = Collections.synchronizedSet(new HashSet<>());

    @Override public boolean onStartJob(JobParameters params) {
        String request = params.getExtras().getString(TaskerWorkScheduler.KEY_ID);
        if (request != null) stoppedIds.remove(request);
        new Thread(() -> {
            try {
                execute(params);
            } finally {
                boolean wasStopped = request != null && stoppedIds.remove(request);
                // A stopped job is already closed by the OS. Never reschedule a
                // claimed operation that may have modified package state.
                if (!wasStopped) jobFinished(params, false);
            }
        }, "tasker-profile-job").start();
        return true;
    }

    private void execute(JobParameters params) {
        PersistableBundle extras = params.getExtras();
        String id = extras.getString(TaskerWorkScheduler.KEY_ID);
        String profileId = extras.getString(TaskerWorkScheduler.KEY_PROFILE);
        String state = extras.getString(TaskerWorkScheduler.KEY_STATE);
        String callback = extras.getString(TaskerWorkScheduler.KEY_CALLBACK);
        if (id == null || !id.matches("[0-9a-f]{32}")
                || !TaskerPluginWire.validProfile(profileId)
                || !(TaskerPluginWire.STATE_ON.equals(state) || TaskerPluginWire.STATE_OFF.equals(state))) return;
        synchronized (TaskerExecutionGate.LOCK) {
            if (stoppedIds.contains(id)) return;
            TaskerExecutionJournal journal = TaskerExecutionJournal.get(this);
            TaskerExecutionJournal.Record pending = journal.find(id);
            if (pending == null || !profileId.equals(pending.profile)
                    || !state.equals(pending.state) || !journal.claim(id)) return;
            ProfileQueueItem item = null;
            boolean success = false;
            String error = "profile_apply_failed";
            try {
                BaseProfile profile = BaseProfile.fromPath(ProfileManager.findProfilePathById(profileId));
                if (!profileId.equals(profile.profileId)) throw new IOException("profile identity mismatch");
                ProfileApplierActivity.ProfileApplierInfo info = new ProfileApplierActivity.ProfileApplierInfo();
                info.profile = profile;
                info.profileId = profileId;
                info.shortcutType = ProfileApplierActivity.ST_SIMPLE;
                info.state = state;
                info.notify = false;
                item = ProfileQueueItem.fromProfiledApplierInfo(info);
                TaskerProfileEvents.started(this, item, id);
                // The existing ProfileManager is the execution authority. A null
                // progress handler is supported by applyProfile's nullable API.
                ProfileManager manager = new ProfileManager(profileId, null);
                try {
                    manager.applyProfile(state, null);
                    success = true;
                } finally {
                    manager.conclude();
                }
                OpHistoryManager.addHistoryItem(HISTORY_TYPE_PROFILE, item, true);
            } catch (IOException | org.json.JSONException | RuntimeException failure) {
                error = "profile_apply_failed";
            } finally {
                journal.complete(id, success, error);
                if (item != null) TaskerProfileEvents.finished(this, item, id, success);
                else TaskerProfileEvents.dispatchFailed(this, id, profileId, state);
                TaskerCompletion.finishWithStatus(this, callback, id, profileId, state,
                        success ? "success" : "failed", success ? "" : error);
            }
        }
    }

    @Override public boolean onStopJob(JobParameters params) {
        // The OS can stop an unstarted job. An active profile operation is not
        // safe to interrupt halfway through, and cannot be replayed automatically.
        String id = params.getExtras().getString(TaskerWorkScheduler.KEY_ID);
        if (id != null) stoppedIds.add(id);
        if (id != null && id.matches("[0-9a-f]{32}")) {
            TaskerExecutionJournal journal = TaskerExecutionJournal.get(this);
            if (journal.failBeforeStart(id, "job_stopped_before_start")) {
                TaskerExecutionJournal.Record record = journal.find(id);
                if (record != null) {
                    TaskerProfileEvents.dispatchFailed(this, id, record.profile, record.state);
                    TaskerCompletion.finishWithStatus(this, record.callback, id,
                            record.profile, record.state, "failed", "job_stopped_before_start");
                }
            }
        }
        return false;
    }
}
