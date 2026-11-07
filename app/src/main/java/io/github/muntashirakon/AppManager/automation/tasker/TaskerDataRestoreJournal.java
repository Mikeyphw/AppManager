// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.content.Context;
import android.os.Bundle;
import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.backup.struct.BackupOpOptions;
import io.github.muntashirakon.AppManager.backup.struct.RestoreOpOptions;
import io.github.muntashirakon.AppManager.batchops.BatchOpsManager;
import io.github.muntashirakon.AppManager.batchops.BatchQueueItem;
import io.github.muntashirakon.AppManager.batchops.struct.BatchBackupOptions;

/** Private, non-backed-up, single-outstanding-restore receipt. No retry or rollback is automatic. */
public final class TaskerDataRestoreJournal {
    private static final String FILE = "tasker_data_restore_receipt_v1.json";
    private static final int SCHEMA = 1;
    private static final int MAX_BYTES = 8192;
    private static final long STALE_MS = 2L * 60 * 60 * 1000;
    private static final Object LOCK = new Object();
    private final AtomicFile file;

    public TaskerDataRestoreJournal(Context context) {
        this(new File(context.getApplicationContext().getNoBackupFilesDir(), FILE));
    }
    TaskerDataRestoreJournal(File testFile) { file = new AtomicFile(testFile); }
    static boolean validRequestId(String name) {
        return name != null && name.matches("Tasker-Restore-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    }
    private static boolean pending(String status) {
        return "queued".equals(status) || "running".equals(status);
    }
    private static boolean validStatus(String s) {
        return pending(s) || "native_completed".equals(s) || "failed".equals(s) || "unknown".equals(s);
    }
    private JSONObject read() throws IOException {
        byte[] bytes;
        try { bytes = file.readFully(); }
        catch (FileNotFoundException absent) { return null; }
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("restore journal size invalid");
        try {
            JSONObject rec = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (rec.length() != 12 || rec.getInt("schema") != SCHEMA
                    || !validRequestId(rec.getString("request"))
                    || !TaskerPackageQueryWire.validPackage(rec.getString("package"))
                    || rec.getInt("user") < 0 || rec.getInt("uid") < 0
                    || rec.getLong("installed_at") <= 0 || rec.getLong("created_ms") <= 0
                    || rec.getLong("updated_ms") < rec.getLong("created_ms")
                    || rec.getString("relative_dir").isEmpty()
                    || !TaskerPackageManageWire.isDataRestore(rec.getString("operation"))
                    || !validStatus(rec.getString("status"))
                    || rec.getString("error").length() > 96)
                throw new IOException("restore journal malformed");
            return rec;
        } catch (JSONException | RuntimeException corrupt) {
            throw new IOException("restore journal corrupt", corrupt);
        }
    }
    private void write(JSONObject rec) throws IOException {
        byte[] bytes = rec.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("restore journal too large");
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(bytes);
            file.finishWrite(out);
        } catch (IOException | RuntimeException failure) {
            if (out != null) file.failWrite(out);
            throw new IOException("restore journal persistence failed", failure);
        }
    }
    void enqueue(TaskerDataRestoreGate.Selection selected, String requestId) throws IOException {
        if (selected == null || !validRequestId(requestId)) throw new IOException("invalid restore request");
        synchronized (LOCK) {
            JSONObject old = read();
            long now = Math.max(1, System.currentTimeMillis());
            if (old != null && pending(old.optString("status"))) {
                long updated = old.optLong("updated_ms", now);
                if (now >= updated && now - updated <= STALE_MS)
                    throw new IOException("another restore is still pending");
                // Unknown is the only safe classification after a lost worker.
            }
            String operation = TaskerPackageManageWire.restoreOperation(
                    selected.name, selected.uid, selected.installedAt);
            try {
                JSONObject rec = new JSONObject();
                rec.put("schema", SCHEMA);
                rec.put("request", requestId);
                rec.put("package", selected.pkg);
                rec.put("user", selected.user);
                rec.put("relative_dir", selected.relativeDir);
                rec.put("operation", operation);
                rec.put("uid", selected.uid);
                rec.put("installed_at", selected.installedAt);
                rec.put("status", "queued");
                rec.put("error", "");
                rec.put("created_ms", now);
                rec.put("updated_ms", now);
                write(rec);
            } catch (JSONException failed) { throw new IOException("restore receipt cannot serialize", failed); }
        }
    }
    void dispatchUnknown(String requestId) {
        try { transition(requestId, "unknown", "restore_dispatch_uncertain"); }
        catch (IOException ignored) { /* preserve prior pending receipt instead of fabricating success */ }
    }
    private void transition(String requestId, String status, String error) throws IOException {
        synchronized (LOCK) {
            JSONObject rec = read();
            if (rec == null || !requestId.equals(rec.optString("request"))) return;
            if (!pending(rec.optString("status"))) return; // late result cannot rewrite terminal state
            try {
                rec.put("status", status);
                rec.put("error", error);
                rec.put("updated_ms", Math.max(rec.getLong("updated_ms"), System.currentTimeMillis()));
                write(rec);
            } catch (JSONException malformed) { throw new IOException("bad restore transition", malformed); }
        }
    }
    private static String queueRequest(BatchQueueItem item) {
        if (item == null || item.getOp() != BatchOpsManager.OP_RESTORE_BACKUP
                || item.getPackages().size() != 1 || item.getUsers().size() != 1
                || !(item.getOptions() instanceof BatchBackupOptions)) return null;
        try {
            String pkg = item.getPackages().get(0);
            Integer user = item.getUsers().get(0);
            if (!TaskerPackageQueryWire.validPackage(pkg) || user == null || user < 0) return null;
            BackupOpOptions options = ((BatchBackupOptions) item.getOptions()).getBackupOpOptions(pkg, user);
            if (options == null || !validRequestId(options.backupName)) return null;
            return options.backupName;
        } catch (RuntimeException invalid) { return null; }
    }

    /** Called on native service worker thread immediately BEFORE a potentially destructive restore. */
    public static boolean authorizeServiceQueue(Context context, BatchQueueItem queue) {
        String requestId = queueRequest(queue);
        if (requestId == null) return true; // untouched native non-Tasker backup/restore flows
        try {
            synchronized (LOCK) {
                TaskerDataRestoreJournal journal = new TaskerDataRestoreJournal(context);
                JSONObject record = journal.read();
                if (record == null || !requestId.equals(record.getString("request"))
                        || !pending(record.getString("status"))) return false;
                String pkg = queue.getPackages().get(0);
                int user = queue.getUsers().get(0);
                if (!pkg.equals(record.getString("package")) || user != record.getInt("user"))
                    return false;
                RestoreOpOptions options = ((BatchBackupOptions) queue.getOptions())
                        .getRestoreOpOptions(pkg, user);
                if (options.flags.getFlags() != BackupFlags.BACKUP_INT_DATA
                        || options.relativeDir == null
                        || !options.relativeDir.equals(record.getString("relative_dir"))) return false;
                TaskerDataRestoreGate.Selection checked = TaskerDataRestoreGate.select(
                        context, pkg, user, record.getString("operation"));
                if (!checked.relativeDir.equals(options.relativeDir)
                        || checked.uid != record.getInt("uid")
                        || checked.installedAt != record.getLong("installed_at")) return false;
                // The native RestoreOp will still enforce backup integrity and signing checks.
                journal.transition(requestId, "running", "");
                return true;
            }
        } catch (IOException | JSONException | RuntimeException failure) {
            try { new TaskerDataRestoreJournal(context).transition(
                    requestId, "failed", "restore_service_preflight_denied"); }
            catch (IOException ignored) { /* never bypass a failed preflight */ }
            return false;
        }
    }

    /** Native service result, NOT a guarantee that app data are semantically valid. */
    public static void serviceFinished(Context context, BatchQueueItem queue, boolean nativeSuccess) {
        String requestId = queueRequest(queue);
        if (requestId == null) return;
        try { new TaskerDataRestoreJournal(context).transition(requestId,
                nativeSuccess ? "native_completed" : "failed",
                nativeSuccess ? "" : "native_restore_failed"); }
        catch (IOException ignored) { /* retain uncertain state instead of false verified success */ }
    }

    Bundle latest(String pkg, int user) throws IOException {
        Bundle vars = empty();
        if (!TaskerPackageQueryWire.validPackage(pkg) || user < 0) return vars;
        synchronized (LOCK) {
            JSONObject rec = read();
            if (rec == null) return vars;
            try {
                if (!pkg.equals(rec.getString("package")) || user != rec.getInt("user")) return vars;
                String status = rec.getString("status");
                String error = rec.getString("error");
                long updated = rec.getLong("updated_ms");
                long now = System.currentTimeMillis();
                if (pending(status) && now >= updated && now - updated > STALE_MS) {
                    status = "unknown";
                    error = "restore_completion_timeout";
                }
                vars.putString("%am_restore_request_id", rec.getString("request"));
                vars.putString("%am_restore_request_status", status);
                vars.putString("%am_restore_request_error", error);
                vars.putString("%am_restore_request_updated_ms", Long.toString(updated));
                // Native service completion is NOT equivalent to verified restored application state.
                vars.putString("%am_restore_request_verified", "false");
                return vars;
            } catch (JSONException bad) { throw new IOException("restore result corrupted", bad); }
        }
    }
    public static Bundle empty() {
        Bundle b = new Bundle();
        b.putString("%am_restore_request_id", "");
        b.putString("%am_restore_request_status", "none");
        b.putString("%am_restore_request_error", "");
        b.putString("%am_restore_request_updated_ms", "");
        b.putString("%am_restore_request_verified", "false");
        return b;
    }
    public static Bundle query(Context context, String pkg, int user) {
        try { return new TaskerDataRestoreJournal(context).latest(pkg, user); }
        catch (IOException | RuntimeException unreadable) {
            Bundle result = empty();
            result.putString("%am_restore_request_status", "unavailable");
            result.putString("%am_restore_request_error", "restore_receipt_unavailable");
            return result;
        }
    }
}
