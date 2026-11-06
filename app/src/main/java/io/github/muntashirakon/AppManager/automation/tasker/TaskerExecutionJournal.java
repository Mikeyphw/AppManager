// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.List;

/**
 * Persistent Tasker request ledger. Every transition is a conditional SQLite update:
 * a request can be claimed exactly once, and cancelling a queued request is atomic
 * with the worker's claim. No profile content or authorization token is recorded.
 *
 * A process death while a profile was running has an UNKNOWN outcome. We record
 * "interrupted", never retry that work, and never imply that the profile rolled back.
 */
public final class TaskerExecutionJournal extends SQLiteOpenHelper {
    private static final String DB = "tasker_execution_v1.db";
    private static final int VERSION = 1;
    private static final int MAX_TERMINAL = 256;
    private static final String PROCESS = UUID.randomUUID().toString();
    private static TaskerExecutionJournal singleton;

    public static final class Record {
        public final String id, profile, state, status, error, route, callback;
        public final long created, started, finished;
        Record(Cursor c) {
            id = c.getString(c.getColumnIndexOrThrow("id"));
            profile = c.getString(c.getColumnIndexOrThrow("profile"));
            state = c.getString(c.getColumnIndexOrThrow("state"));
            status = c.getString(c.getColumnIndexOrThrow("status"));
            error = c.getString(c.getColumnIndexOrThrow("error"));
            route = c.getString(c.getColumnIndexOrThrow("route"));
            callback = c.getString(c.getColumnIndexOrThrow("callback"));
            created = c.getLong(c.getColumnIndexOrThrow("created"));
            started = c.getLong(c.getColumnIndexOrThrow("started"));
            finished = c.getLong(c.getColumnIndexOrThrow("finished"));
        }
    }

    private TaskerExecutionJournal(Context context) {
        // The callback URI is short-lived but may contain host-private fields;
        // keep this database out of Android cloud backup, like TaskerAccess.
        super(context.getApplicationContext(),
                new File(context.getNoBackupFilesDir(), DB).getAbsolutePath(), null, VERSION);
        // First access in this process reconciles only work started in a prior
        // process. Scheduled jobs can still be delivered by JobScheduler.
        SQLiteDatabase db = getWritableDatabase();
        List<Record> previousRunning = selectRecords(db, "status=? AND process<>?",
                new String[] {"running", PROCESS});
        List<Record> lostDirect = selectRecords(db,
                "status='queued' AND route='direct' AND process<>?", new String[]{PROCESS});
        // Resolve each lost transition separately so its terminal sequence is
        // recent and it cannot be evicted merely because it was created early.
        for (Record lost : previousRunning) {
            ContentValues values = new ContentValues();
            values.put("status", "interrupted");
            values.put("error", "process_died_outcome_unknown");
            values.put("finished", System.currentTimeMillis());
            values.put("terminal_seq", nextTerminalSequence());
            if (db.update("runs", values, "id=? AND status='running' AND process<>?",
                    new String[]{lost.id, PROCESS}) == 1)
                TaskerProfileEvents.recoveredInterrupted(context, lost.id, lost.profile, lost.state);
        }
        // START_NOT_STICKY service Intents are not redelivered on process death.
        for (Record lost : lostDirect) {
            ContentValues values = new ContentValues();
            values.put("status", "interrupted");
            values.put("error", "process_died_before_start");
            values.put("finished", System.currentTimeMillis());
            values.put("terminal_seq", nextTerminalSequence());
            if (db.update("runs", values,
                    "id=? AND status='queued' AND route='direct' AND process<>?",
                    new String[]{lost.id, PROCESS}) == 1)
                TaskerProfileEvents.recoveredInterrupted(context, lost.id, lost.profile, lost.state);
        }
        reconcileOrphanedScheduled(context, db);
    }

    private static List<Record> selectRecords(SQLiteDatabase db, String selection, String[] args) {
        List<Record> records = new ArrayList<>();
        try (Cursor c = db.query("runs", null, selection, args, null, null, null)) {
            while (c.moveToNext()) records.add(new Record(c));
        }
        return records;
    }

    private void reconcileOrphanedScheduled(Context context, SQLiteDatabase db) {
        // Reboot and user force-stop may delete non-persisted scheduled jobs.
        // Wait at least 10 minutes to avoid racing normal JobScheduler delivery.
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            Set<String> pending = new HashSet<>();
            List<JobInfo> jobs = js.getAllPendingJobs();
            if (jobs == null) return;
            for (JobInfo job : jobs) {
                String id = job.getExtras().getString(TaskerWorkScheduler.KEY_ID);
                if (id != null) pending.add(id);
            }
            ArrayList<String> orphaned = new ArrayList<>();
            try (Cursor rows = db.query("runs", new String[]{"id"},
                    "status='scheduled' AND created<?",
                    new String[]{Long.toString(System.currentTimeMillis() - 600_000L)},
                    null, null, null)) {
                while (rows.moveToNext()) {
                    String id = rows.getString(0);
                    if (!pending.contains(id)) orphaned.add(id);
                }
            }
            for (String id : orphaned) {
                List<Record> entries = selectRecords(db, "id=?", new String[]{id});
                if (entries.isEmpty()) continue;
                Record original = entries.get(0);
                ContentValues values = new ContentValues();
                values.put("status", "interrupted");
                values.put("error", "scheduled_job_missing_outcome_unknown");
                values.put("finished", System.currentTimeMillis());
                values.put("terminal_seq", nextTerminalSequence());
                if (db.update("runs", values, "id=? AND status='scheduled'", new String[]{id}) == 1) {
                    // Reconcile the State observer as well as the durable journal.
                    TaskerProfileEvents.recoveredInterrupted(context, id,
                            original.profile, original.state);
                }
            }
        } catch (RuntimeException ignored) {
            // Do not invalidate pending work when OS job inventory is unavailable.
        }
    }

    @NonNull public static synchronized TaskerExecutionJournal get(@NonNull Context context) {
        if (singleton == null) singleton = new TaskerExecutionJournal(context);
        return singleton;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE runs (" +
                "id TEXT PRIMARY KEY NOT NULL, profile TEXT NOT NULL, state TEXT NOT NULL, " +
                "status TEXT NOT NULL, error TEXT NOT NULL DEFAULT '', " +
                "route TEXT NOT NULL DEFAULT 'direct', callback TEXT NOT NULL DEFAULT '', " +
                "process TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL, " +
                "started INTEGER NOT NULL DEFAULT 0, finished INTEGER NOT NULL DEFAULT 0, " +
                "terminal_seq INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX runs_created ON runs(created DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unknown Tasker execution journal schema");
    }

    public synchronized boolean enqueue(@NonNull String id, @NonNull String profile,
                                        @NonNull String state, @Nullable String callback) {
        if (!id.matches("[0-9a-f]{32}") || !TaskerPluginWire.validProfile(profile)
                || !(TaskerPluginWire.STATE_ON.equals(state) || TaskerPluginWire.STATE_OFF.equals(state)))
            return false;
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("profile", profile);
        values.put("state", state);
        values.put("status", "queued");
        values.put("process", PROCESS);
        values.put("callback", callback == null ? "" : callback);
        values.put("created", System.currentTimeMillis());
        long inserted = getWritableDatabase().insertWithOnConflict("runs", null, values,
                SQLiteDatabase.CONFLICT_IGNORE);
        if (inserted != -1) prune();
        return inserted != -1;
    }

    /** Only a pending request can be claimed; a cancellation wins if it updated first. */
    public synchronized boolean claim(@NonNull String id) {
        ContentValues values = new ContentValues();
        values.put("status", "running");
        values.put("process", PROCESS);
        values.put("started", System.currentTimeMillis());
        return getWritableDatabase().update("runs", values,
                "id=? AND status IN ('queued','scheduled')", new String[] {id}) == 1;
    }

    public synchronized boolean markScheduled(String id) {
        ContentValues values = new ContentValues();
        values.put("status", "scheduled");
        values.put("route", "job");
        return getWritableDatabase().update("runs", values, "id=? AND status='queued'",
                new String[] {id}) == 1;
    }

    /** Terminal ordering must not rely on millisecond ties or an old rowid.
     * A request created yesterday but cancelled now is the newest completion. */
    private long nextTerminalSequence() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(MAX(terminal_seq), 0) + 1 FROM runs", null)) {
            if (!c.moveToFirst()) throw new IllegalStateException("Missing terminal counter");
            return c.getLong(0);
        }
    }

    public synchronized boolean complete(@NonNull String id, boolean success, String error) {
        ContentValues values = new ContentValues();
        values.put("status", success ? "success" : "failed");
        values.put("error", success ? "" : error);
        values.put("finished", System.currentTimeMillis());
        values.put("terminal_seq", nextTerminalSequence());
        boolean updated = getWritableDatabase().update("runs", values,
                "id=? AND status='running' AND process=?", new String[] {id, PROCESS}) == 1;
        if (updated) prune();
        return updated;
    }

    public synchronized boolean failBeforeStart(String id, String error) {
        ContentValues values = new ContentValues();
        values.put("status", "failed");
        values.put("error", error);
        values.put("finished", System.currentTimeMillis());
        values.put("terminal_seq", nextTerminalSequence());
        boolean updated = getWritableDatabase().update("runs", values,
                "id=? AND status IN ('queued','scheduled')", new String[] {id}) == 1;
        if (updated) prune();
        return updated;
    }

    /** Returns the newly cancelled record, or null if already running or terminal. */
    @Nullable public synchronized Record cancelQueued(@NonNull String id) {
        ContentValues values = new ContentValues();
        values.put("status", "cancelled");
        values.put("error", "request_cancelled");
        values.put("finished", System.currentTimeMillis());
        values.put("terminal_seq", nextTerminalSequence());
        if (getWritableDatabase().update("runs", values,
                "id=? AND status IN ('queued','scheduled')", new String[] {id}) != 1) return null;
        // Take the terminal snapshot before retention pruning. Cancellation
        // must report success even if a very old queued row is pruned.
        Record cancelled = find(id);
        prune();
        return cancelled;
    }

    @Nullable public synchronized Record find(@Nullable String id) {
        if (id == null || !id.matches("[0-9a-f]{32}")) return null;
        try (Cursor cursor = getReadableDatabase().query("runs", null, "id=?",
                new String[] {id}, null, null, null)) {
            return cursor.moveToFirst() ? new Record(cursor) : null;
        }
    }

    @NonNull public synchronized Bundle recent() {
        JSONArray results = new JSONArray();
        Record latest = null;
        try (Cursor cursor = getReadableDatabase().query("runs", null, null, null,
                null, null, "created DESC, rowid DESC", "20")) {
            while (cursor.moveToNext()) {
                Record r = new Record(cursor);
                if (latest == null) latest = r;
                JSONObject json = new JSONObject();
                try {
                    json.put("request_id", r.id);
                    json.put("profile_id", r.profile);
                    json.put("state", r.state);
                    json.put("status", r.status);
                    json.put("error", r.error);
                    json.put("route", r.route);
                    json.put("created_at", r.created);
                    json.put("started_at", r.started);
                    json.put("finished_at", r.finished);
                } catch (JSONException ignored) { }
                results.put(json);
            }
        }
        Bundle output = TaskerPluginWire.variables(latest == null ? "" : latest.id,
                latest == null ? "" : latest.profile, latest == null ? "" : latest.state,
                latest == null ? "unknown" : latest.status,
                latest == null ? "" : latest.error);
        output.putString("%am_history", results.toString());
        output.putString("%am_created_at", latest == null ? "" : Long.toString(latest.created));
        output.putString("%am_started_at", latest == null ? "" : Long.toString(latest.started));
        output.putString("%am_finished_at", latest == null ? "" : Long.toString(latest.finished));
        return output;
    }

    private void prune() {
        // Never prune an unfinished request: the pending scheduler or service
        // must still be able to find and atomically claim/cancel it.
        SQLiteDatabase db = getWritableDatabase();
        ArrayList<String> old = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id FROM runs WHERE status IN " +
                "('success','failed','cancelled','interrupted') ORDER BY terminal_seq DESC, finished DESC, rowid DESC " +
                "LIMIT -1 OFFSET " + MAX_TERMINAL, null)) {
            while (c.moveToNext()) old.add(c.getString(0));
        }
        for (String id : old) db.delete("runs", "id=?", new String[]{id});
    }
}
