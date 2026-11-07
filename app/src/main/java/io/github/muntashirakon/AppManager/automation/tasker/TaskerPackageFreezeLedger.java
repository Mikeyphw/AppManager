// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * Crash-recoverable, private, non-backed-up record of exactly which enabled
 * state App Manager changed. Never an automatic repair or blind unfreeze queue.
 * All read/modify/write calls must hold TaskerPackageManageBackend.MUTATION_LOCK.
 */
final class TaskerPackageFreezeLedger {
    private static final String FILE = "tasker_package_freeze_ledger_v1.json";
    private static final int SCHEMA = 1;
    private static final int MAX_BYTES = 65536;
    private static final int MAX_RECORDS = 128;
    private final AtomicFile file;

    static final class Entry {
        final int uid;
        final long installedAt;
        final int previousState;
        Entry(int uid, long installedAt, int previousState) {
            this.uid = uid;
            this.installedAt = installedAt;
            this.previousState = previousState;
        }
        boolean matches(int uid, long installedAt) {
            return this.uid == uid && this.installedAt == installedAt;
        }
        boolean sameAs(Entry other) {
            return other != null && uid == other.uid && installedAt == other.installedAt
                    && previousState == other.previousState;
        }
    }

    TaskerPackageFreezeLedger(Context context) {
        file = new AtomicFile(new File(context.getApplicationContext().getNoBackupFilesDir(), FILE));
    }

    private static boolean validPreviousState(int state) {
        return state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
    }

    private JSONObject read() throws IOException {
        final byte[] bytes;
        try { bytes = file.readFully(); }
        catch (FileNotFoundException missing) { return new JSONObject(); }
        if (bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new IOException("invalid freeze ledger length");
        try {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (root.length() != 2 || root.getInt("schema") != SCHEMA)
                throw new IOException("unsupported freeze ledger schema");
            JSONObject records = root.getJSONObject("records");
            if (records.length() > MAX_RECORDS) throw new IOException("freeze ledger capacity exceeded");
            Iterator<String> keys = records.keys();
            while (keys.hasNext()) {
                String pkg = keys.next();
                if (!TaskerPackageQueryWire.validPackage(pkg)) throw new IOException("bad package in freeze ledger");
                parse(records.getJSONObject(pkg));
            }
            return records;
        } catch (JSONException | RuntimeException invalid) {
            throw new IOException("invalid freeze ledger", invalid);
        }
    }

    private static Entry parse(JSONObject data) throws JSONException, IOException {
        if (data.length() != 3) throw new IOException("unexpected freeze entry fields");
        int uid = data.getInt("uid");
        long installedAt = data.getLong("installed_at");
        int previous = data.getInt("previous");
        if (uid < 0 || installedAt < 0 || !validPreviousState(previous))
            throw new IOException("invalid freeze entry");
        return new Entry(uid, installedAt, previous);
    }

    Entry find(String pkg) throws IOException {
        if (!TaskerPackageQueryWire.validPackage(pkg)) throw new IOException("invalid package");
        JSONObject records = read();
        if (!records.has(pkg)) return null;
        try { return parse(records.getJSONObject(pkg)); }
        catch (JSONException invalid) { throw new IOException("unreadable freeze entry", invalid); }
    }

    void record(String pkg, Entry entry) throws IOException {
        if (!TaskerPackageQueryWire.validPackage(pkg) || entry == null
                || entry.uid < 0 || entry.installedAt < 0 || !validPreviousState(entry.previousState))
            throw new IOException("invalid freeze snapshot");
        JSONObject records = read();
        if (records.has(pkg)) throw new IOException("freeze snapshot already exists");
        if (records.length() >= MAX_RECORDS) throw new IOException("freeze ledger full");
        try {
            JSONObject data = new JSONObject();
            data.put("uid", entry.uid);
            data.put("installed_at", entry.installedAt);
            data.put("previous", entry.previousState);
            records.put(pkg, data);
            write(records);
        } catch (JSONException invalid) { throw new IOException("freeze snapshot serialization error", invalid); }
    }

    void forgetMatching(String pkg, Entry expected) throws IOException {
        JSONObject records = read();
        if (!records.has(pkg)) throw new IOException("freeze record missing during finalize");
        try {
            Entry actual = parse(records.getJSONObject(pkg));
            if (!actual.sameAs(expected)) throw new IOException("freeze record changed during finalize");
            records.remove(pkg);
            write(records);
        } catch (JSONException invalid) { throw new IOException("freeze record finalize failed", invalid); }
    }

    private void write(JSONObject records) throws IOException {
        final byte[] bytes;
        try {
            JSONObject root = new JSONObject();
            root.put("schema", SCHEMA);
            root.put("records", records);
            bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException invalid) { throw new IOException("freeze ledger serialization failed", invalid); }
        if (bytes.length > MAX_BYTES) throw new IOException("freeze ledger oversized");
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
        } catch (IOException | RuntimeException failure) {
            if (output != null) file.failWrite(output);
            throw new IOException("freeze ledger atomic write failed", failure);
        }
    }
}
