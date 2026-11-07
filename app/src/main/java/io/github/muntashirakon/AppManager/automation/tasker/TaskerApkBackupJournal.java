// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.content.Context;
import android.os.Bundle;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.backup.BackupUtils;
import io.github.muntashirakon.AppManager.backup.struct.BackupOpOptions;
import io.github.muntashirakon.AppManager.batchops.BatchOpsManager;
import io.github.muntashirakon.AppManager.batchops.BatchQueueItem;
import io.github.muntashirakon.AppManager.batchops.struct.BatchBackupOptions;
import io.github.muntashirakon.AppManager.db.entity.Backup;

/**
 * Durable, non-backed-up Tasker APK backup receipts. No implicit retries, data
 * backup, restore or exported component. All service callbacks are direct app
 * calls, not broadcast-derived authorization. This class never claims completion
 * solely because startForegroundService accepted a request.
 */
public final class TaskerApkBackupJournal {
    private static final String STORE = "tasker_apk_backup_requests_v1.json";
    private static final int SCHEMA = 1;
    private static final int LIMIT = 64;
    private static final int HISTORY_LIMIT = 12;
    private static final int MAX_BYTES = 65536;
    private static final long STALE_MS = 2L * 60L * 60L * 1000L;
    private static final int FLAGS = BackupFlags.BACKUP_APK_FILES | BackupFlags.BACKUP_MULTIPLE;
    // BackupOp.setupMetadataAndCrypto strips the MULTIPLE routing flag before metadata is saved.
    private static final int METADATA_FLAGS = BackupFlags.BACKUP_APK_FILES;
    private static final int DATA_FLAGS = BackupFlags.BACKUP_APK_FILES | BackupFlags.BACKUP_INT_DATA | BackupFlags.BACKUP_MULTIPLE;
    private static final int DATA_METADATA_FLAGS = BackupFlags.BACKUP_APK_FILES | BackupFlags.BACKUP_INT_DATA;
    private static final Object IO_LOCK = new Object();
    private final AtomicFile file;

    /** Real store is app-private and excluded from automatic backups. */
    public TaskerApkBackupJournal(Context context) {
        this(new File(context.getApplicationContext().getNoBackupFilesDir(), STORE));
    }

    /** Only used by package-private tests and isolated on-device acceptance. */
    TaskerApkBackupJournal(File isolatedFile) {
        file = new AtomicFile(isolatedFile);
    }

    private static boolean validName(String name) {
        return name != null && name.matches("Tasker-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    }

    private static boolean validType(String type) {
        return "apk".equals(type) || "data".equals(type);
    }

    private static String receiptType(JSONObject record) {
        return record.optString("type", "apk");
    }

    private static boolean pending(String status) {
        return "queued".equals(status) || "running".equals(status);
    }

    private static boolean validStatus(String status) {
        return pending(status) || "succeeded".equals(status) || "failed".equals(status)
                || "unverified".equals(status) || "unknown".equals(status);
    }

    private static JSONObject parsed(JSONObject rec) throws JSONException, IOException {
        if ((rec.length() != 7 && rec.length() != 8) || !validType(receiptType(rec))
                || !validName(rec.getString("name"))
                || !TaskerPackageQueryWire.validPackage(rec.getString("package"))
                || rec.getInt("user") < 0 || rec.getLong("created_ms") <= 0
                || rec.getLong("updated_ms") < rec.getLong("created_ms")
                || !validStatus(rec.getString("status"))
                || rec.getString("error").length() > 96) {
            throw new IOException("invalid Tasker backup receipt");
        }
        return rec;
    }

    private JSONArray read() throws IOException {
        final byte[] bytes;
        try { bytes = file.readFully(); }
        catch (FileNotFoundException missing) { return new JSONArray(); }
        if (bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new IOException("invalid backup receipt size");
        try {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (root.length() != 2 || root.getInt("schema") != SCHEMA)
                throw new IOException("unknown backup receipt schema");
            JSONArray array = root.getJSONArray("records");
            if (array.length() > LIMIT) throw new IOException("too many backup receipts");
            for (int i = 0; i < array.length(); i++) parsed(array.getJSONObject(i));
            return array;
        } catch (JSONException | RuntimeException bad) {
            throw new IOException("corrupt backup receipt ledger", bad);
        }
    }

    private void write(JSONArray records) throws IOException {
        final byte[] bytes;
        try {
            JSONObject root = new JSONObject();
            root.put("schema", SCHEMA);
            root.put("records", records);
            bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException bad) { throw new IOException("receipt encoding failed", bad); }
        if (bytes.length > MAX_BYTES || records.length() > LIMIT)
            throw new IOException("backup receipt ledger capacity exceeded");
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
        } catch (IOException | RuntimeException bad) {
            if (output != null) file.failWrite(output);
            throw new IOException("receipt atomic write failed", bad);
        }
    }

    /**
     * Reclaims capacity from expired receipts only. The original backup may
     * have finished after a crash, so this records UNKNOWN, never FAILED or
     * SUCCEEDED, and deliberately performs no retry or file mutation.
     * Caller holds IO_LOCK and persists the result atomically with its enqueue.
     */
    private static void expireStalePending(JSONArray records, long now) throws IOException {
        try {
            for (int i = 0; i < records.length(); ++i) {
                JSONObject record = records.getJSONObject(i);
                String status = record.getString("status");
                long updated = record.getLong("updated_ms");
                if (pending(status) && now >= updated && now - updated > STALE_MS) {
                    record.put("status", "unknown");
                    record.put("error", "backup_completion_timeout");
                    record.put("updated_ms", now);
                }
            }
        } catch (JSONException malformed) {
            throw new IOException("cannot recover malformed backup receipt", malformed);
        }
    }

    /** Saves a request before the foreground-service dispatch; never overrides a prior ID. */
    void enqueue(String pkg, int user, String name) throws IOException {
        enqueue(pkg, user, name, "apk");
    }

    void enqueue(String pkg, int user, String name, String type) throws IOException {
        if (!TaskerPackageQueryWire.validPackage(pkg) || user < 0 || !validName(name)
                || !validType(type))
            throw new IOException("invalid backup request identity");
        synchronized (IO_LOCK) {
            JSONArray records = read();
            final long recoveryNow = Math.max(1L, System.currentTimeMillis());
            expireStalePending(records, recoveryNow);
            for (int i = 0; i < records.length(); i++) {
                try {
                    if (name.equals(records.getJSONObject(i).getString("name")))
                        throw new IOException("duplicate Tasker backup request");
                } catch (JSONException bad) { throw new IOException("corrupt request", bad); }
            }
            // Evict only the oldest terminal receipt, never a pending job.
            if (records.length() >= LIMIT) {
                int oldest = -1;
                long when = Long.MAX_VALUE;
                try {
                    for (int i = 0; i < records.length(); i++) {
                        JSONObject item = records.getJSONObject(i);
                        if (!pending(item.getString("status")) && item.getLong("created_ms") < when) {
                            when = item.getLong("created_ms");
                            oldest = i;
                        }
                    }
                } catch (JSONException bad) { throw new IOException("corrupt request", bad); }
                if (oldest < 0) throw new IOException("pending backup receipt capacity reached");
                JSONArray smaller = new JSONArray();
                for (int i = 0; i < records.length(); i++) {
                    if (i != oldest) {
                        try { smaller.put(records.getJSONObject(i)); }
                        catch (JSONException bad) { throw new IOException("corrupt request", bad); }
                    }
                }
                records = smaller;
            }
            try {
                long now = Math.max(1L, System.currentTimeMillis());
                JSONObject item = new JSONObject();
                item.put("name", name);
                item.put("package", pkg);
                item.put("user", user);
                item.put("created_ms", now);
                item.put("updated_ms", now);
                item.put("status", "queued");
                item.put("error", "");
                if ("data".equals(type)) item.put("type", type);
                records.put(item);
                write(records);
            } catch (JSONException bad) { throw new IOException("receipt serialization failed", bad); }
        }
    }

    /** A thrown startForegroundService call doesn't prove that no work started. */
    void dispatchUnknown(String pkg, int user, String name) throws IOException {
        dispatchUnknown(pkg, user, name, "apk");
    }

    void dispatchUnknown(String pkg, int user, String name, String type) throws IOException {
        transition(pkg, user, name, type, "unknown", "backup_dispatch_uncertain", false);
    }

    void started(String pkg, int user, String name) throws IOException {
        started(pkg, user, name, "apk");
    }

    void started(String pkg, int user, String name, String type) throws IOException {
        transition(pkg, user, name, type, "running", "", false);
    }

    void finished(String pkg, int user, String name, String status, String error) throws IOException {
        finished(pkg, user, name, "apk", status, error);
    }

    void finished(String pkg, int user, String name, String type, String status, String error) throws IOException {
        if (!"succeeded".equals(status) && !"failed".equals(status)
                && !"unverified".equals(status)) throw new IOException("invalid terminal state");
        transition(pkg, user, name, type, status, error, true);
    }

    private void transition(String pkg, int user, String name, String type, String state, String error,
                            boolean terminal) throws IOException {
        if (!validType(type)) throw new IOException("invalid backup receipt type");
        synchronized (IO_LOCK) {
            JSONArray records = read();
            boolean found = false;
            try {
                for (int i = 0; i < records.length(); i++) {
                    JSONObject rec = records.getJSONObject(i);
                    if (!name.equals(rec.getString("name"))) continue;
                    if (!pkg.equals(rec.getString("package")) || user != rec.getInt("user"))
                        throw new IOException("mismatched backup callback identity");
                    if (!type.equals(receiptType(rec))) return;
                    String current = rec.getString("status");
                    // A late callback or duplicate service result cannot rewrite a terminal receipt.
                    if (!pending(current)) return;
                    if (terminal && !"queued".equals(current) && !"running".equals(current)) return;
                    rec.put("status", state);
                    rec.put("error", error);
                    rec.put("updated_ms", Math.max(rec.getLong("updated_ms"), System.currentTimeMillis()));
                    found = true;
                    break;
                }
            } catch (JSONException bad) { throw new IOException("corrupt callback receipt", bad); }
            // Foreign/native backups cannot invent Tasker receipt records.
            if (!found) return;
            write(records);
        }
    }

    /** Current user's latest matching request; never exposes backup path or archive bytes. */
    Bundle latest(String pkg, int user) throws IOException {
        Bundle out = empty();
        if (!TaskerPackageQueryWire.validPackage(pkg) || user < 0) return out;
        synchronized (IO_LOCK) {
            JSONArray records = read();
            out.putString("%am_backup_request_pending_count", "0");
            JSONObject latest = null;
            JSONArray history = new JSONArray();
            int active = 0;
            long now = System.currentTimeMillis();
            try {
                // Reverse insertion order: newest first, bounded to 12 receipts.
                // No cross-user data, archive paths, payload bytes or keys.
                for (int i = records.length() - 1; i >= 0; --i) {
                    JSONObject rec = records.getJSONObject(i);
                    if (!pkg.equals(rec.getString("package")) || user != rec.getInt("user"))
                        continue;
                    if (latest == null || rec.getLong("created_ms") > latest.getLong("created_ms"))
                        latest = rec;
                    String observed = rec.getString("status");
                    long updated = rec.getLong("updated_ms");
                    if (pending(observed) && now >= updated && now - updated > STALE_MS)
                        observed = "unknown";
                    if (pending(observed)) ++active;
                    if (history.length() < HISTORY_LIMIT) {
                        JSONObject item = new JSONObject();
                        item.put("name", rec.getString("name"));
                        item.put("type", receiptType(rec));
                        item.put("status", observed);
                        item.put("error", "unknown".equals(observed) && pending(rec.getString("status"))
                                ? "backup_completion_timeout" : rec.getString("error"));
                        item.put("updated_ms", updated);
                        item.put("verified", "succeeded".equals(observed));
                        history.put(item);
                    }
                }
                out.putString("%am_backup_request_pending_count", Integer.toString(active));
                out.putString("%am_backup_request_history_json", history.toString());
                if (latest == null) return out;
                String status = latest.getString("status");
                long timestamp = latest.getLong("updated_ms");
                // Status is indeterminate after an abandoned request; no automatic retry.
                String observedError = latest.getString("error");
                if (pending(status) && now >= timestamp && now - timestamp > STALE_MS) {
                    status = "unknown";
                    observedError = "backup_completion_timeout";
                }
                out.putString("%am_backup_request_name", latest.getString("name"));
                out.putString("%am_backup_request_type", receiptType(latest));
                out.putString("%am_backup_request_status", status);
                out.putString("%am_backup_request_error", observedError);
                out.putString("%am_backup_request_updated_ms", Long.toString(timestamp));
                out.putString("%am_backup_request_verified", Boolean.toString("succeeded".equals(status)));
                return out;
            } catch (JSONException bad) { throw new IOException("invalid backup receipt query", bad); }
        }
    }

    public static Bundle empty() {
        Bundle out = new Bundle();
        out.putString("%am_backup_request_name", "");
        out.putString("%am_backup_request_type", "");
        out.putString("%am_backup_request_status", "none");
        out.putString("%am_backup_request_error", "");
        out.putString("%am_backup_request_updated_ms", "");
        out.putString("%am_backup_request_verified", "false");
        out.putString("%am_backup_request_pending_count", "");
        out.putString("%am_backup_request_history_json", "[]");
        return out;
    }

    public static Bundle query(Context context, String pkg, int user) {
        try { return new TaskerApkBackupJournal(context).latest(pkg, user); }
        catch (IOException | RuntimeException unavailable) {
            Bundle out = empty();
            out.putString("%am_backup_request_status", "unavailable");
            out.putString("%am_backup_request_error", "receipt_unavailable");
            return out;
        }
    }

    /** Only exact, single-package, APK-only native queues may affect Tasker receipts. */
    private static BackupOpOptions match(BatchQueueItem item) {
        try {
            if (item == null || item.getOp() != BatchOpsManager.OP_BACKUP
                    || item.getPackages().size() != 1 || item.getUsers().size() != 1
                    || !(item.getOptions() instanceof BatchBackupOptions)) return null;
            String pkg = item.getPackages().get(0);
            Integer user = item.getUsers().get(0);
            if (!TaskerPackageQueryWire.validPackage(pkg) || user == null || user < 0) return null;
            BackupOpOptions options = ((BatchBackupOptions) item.getOptions()).getBackupOpOptions(pkg, user);
            if ((options.flags.getFlags() != FLAGS && options.flags.getFlags() != DATA_FLAGS)
                    || options.override || !validName(options.backupName)
                    || !pkg.equals(options.packageName) || options.userId != user) return null;
            return options;
        } catch (RuntimeException bad) { return null; }
    }

    public static void serviceStarted(Context context, BatchQueueItem item) {
        BackupOpOptions request = match(item);
        if (request == null) return;
        try {
            new TaskerApkBackupJournal(context).started(request.packageName, request.userId,
                    request.backupName, request.flags.getFlags() == DATA_FLAGS ? "data" : "apk");
        } catch (IOException | RuntimeException ignored) {
            // Journal corruption must never interrupt native backup service execution.
        }
    }

    /** Failures come from the native batch result; successful completion still needs DB proof. */
    public static void serviceFinished(Context context, BatchQueueItem item, boolean batchSucceeded) {
        BackupOpOptions request = match(item);
        if (request == null) return;
        String state = "failed";
        String error = "native_backup_failed";
        boolean data = request.flags.getFlags() == DATA_FLAGS;
        if (batchSucceeded) {
            state = "unverified";
            error = "backup_metadata_unverified";
            try {
                List<Backup> backups = BackupUtils.getBackupMetadataFromDbNoLockValidate(request.packageName);
                for (Backup backup : backups) {
                    if (backup != null && request.packageName.equals(backup.packageName)
                            && request.userId == backup.userId
                            && request.backupName.equals(backup.backupName)
                            && ((data && backup.flags == DATA_METADATA_FLAGS)
                                || (!data && backup.flags == METADATA_FLAGS))) {
                        state = "succeeded";
                        error = "";
                        break;
                    }
                }
            } catch (RuntimeException ignored) {
                error = "backup_metadata_unavailable";
            }
        }
        try {
            new TaskerApkBackupJournal(context).finished(request.packageName, request.userId,
                    request.backupName, data ? "data" : "apk", state, error);
        } catch (IOException | RuntimeException ignored) {
            // The persisted record remains pending/unknown; no false terminal success.
        }
    }
}
