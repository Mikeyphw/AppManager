// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import static org.junit.Assert.*;

import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class TaskerApkBackupJournalTest {
    private static String request() { return "Tasker-" + UUID.randomUUID(); }
    private static String pkg() { return "com.example.amt05c"; }

    private static File temporary() throws IOException {
        File path = File.createTempFile("amt05c-journal-", ".json");
        if (!path.delete()) throw new IOException("cannot prepare missing ledger");
        return path;
    }

    private static void cleanup(File path) {
        path.delete(); new File(path + ".bak").delete(); new File(path + ".new").delete();
    }

    @Test public void queueThenNativeResultSurvivesNewProcessObject() throws Exception {
        File path = temporary(); String name = request();
        try {
            TaskerApkBackupJournal first = new TaskerApkBackupJournal(path);
            first.enqueue(pkg(), 0, name);
            assertEquals("queued", new TaskerApkBackupJournal(path).latest(pkg(), 0)
                    .getString("%am_backup_request_status"));
            first.started(pkg(), 0, name);
            assertEquals("running", new TaskerApkBackupJournal(path).latest(pkg(), 0)
                    .getString("%am_backup_request_status"));
            new TaskerApkBackupJournal(path).finished(pkg(), 0, name, "succeeded", "");
            Bundle result = new TaskerApkBackupJournal(path).latest(pkg(), 0);
            assertEquals(name, result.getString("%am_backup_request_name"));
            assertEquals("succeeded", result.getString("%am_backup_request_status"));
            assertEquals("true", result.getString("%am_backup_request_verified"));
        } finally { cleanup(path); }
    }

    @Test public void failedServiceCannotBecomeVerified() throws Exception {
        File path = temporary(); String name = request();
        try {
            TaskerApkBackupJournal journal = new TaskerApkBackupJournal(path);
            journal.enqueue(pkg(), 0, name);
            journal.finished(pkg(), 0, name, "failed", "native_backup_failed");
            Bundle result = journal.latest(pkg(), 0);
            assertEquals("failed", result.getString("%am_backup_request_status"));
            assertEquals("false", result.getString("%am_backup_request_verified"));
            journal.finished(pkg(), 0, name, "succeeded", "");
            assertEquals("failed", journal.latest(pkg(), 0).getString("%am_backup_request_status"));
        } finally { cleanup(path); }
    }

    @Test public void completionRequiresExactPackageUserAndId() throws Exception {
        File path = temporary(); String name = request();
        try {
            TaskerApkBackupJournal journal = new TaskerApkBackupJournal(path);
            journal.enqueue(pkg(), 0, name);
            try { journal.finished(pkg(), 10, name, "succeeded", "");
                fail("foreign user must be rejected"); }
            catch (IOException expected) { /* exact receipt binding */ }
            try { journal.finished("com.example.other", 0, name, "succeeded", "");
                fail("foreign package must be rejected"); }
            catch (IOException expected) { /* exact receipt binding */ }
            journal.finished(pkg(), 0, request(), "succeeded", "");
            assertEquals("queued", journal.latest(pkg(), 0).getString("%am_backup_request_status"));
            assertEquals("none", journal.latest(pkg(), 10).getString("%am_backup_request_status"));
        } finally { cleanup(path); }
    }

    @Test public void duplicateRequestCannotOverwriteHistory() throws Exception {
        File path = temporary(); String name = request();
        try {
            TaskerApkBackupJournal journal = new TaskerApkBackupJournal(path);
            journal.enqueue(pkg(), 0, name);
            try { journal.enqueue(pkg(), 0, name); fail("duplicate request accepted"); }
            catch (IOException expected) { /* no overwrite */ }
            assertEquals("queued", journal.latest(pkg(), 0).getString("%am_backup_request_status"));
        } finally { cleanup(path); }
    }

    @Test public void corruptLedgerIsNeverSilentlyReset() throws Exception {
        File path = temporary();
        try {
            try (FileOutputStream stream = new FileOutputStream(path)) {
                stream.write("not json".getBytes(StandardCharsets.UTF_8));
            }
            TaskerApkBackupJournal journal = new TaskerApkBackupJournal(path);
            try { journal.enqueue(pkg(), 0, request()); fail("corruption overwritten"); }
            catch (IOException expected) { /* preserve original bytes */ }
            assertEquals("not json", new String(java.nio.file.Files.readAllBytes(path.toPath()),
                    StandardCharsets.UTF_8));
        } finally { cleanup(path); }
    }

    @Test public void expiredPendingReceiptsReclaimCapacityButNeverClaimSuccess() throws Exception {
        File path = temporary();
        try {
            TaskerApkBackupJournal journal = new TaskerApkBackupJournal(path);
            for (int i = 0; i < 64; ++i) journal.enqueue(pkg(), 0, request());
            org.json.JSONObject root = new org.json.JSONObject(new String(
                    java.nio.file.Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8));
            org.json.JSONArray records = root.getJSONArray("records");
            long old = System.currentTimeMillis() - 3L * 60L * 60L * 1000L;
            for (int i = 0; i < records.length(); ++i) {
                org.json.JSONObject item = records.getJSONObject(i);
                item.put("created_ms", old - i);
                item.put("updated_ms", old - i);
            }
            java.nio.file.Files.write(path.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
            String newRequest = request();
            journal.enqueue(pkg(), 0, newRequest);
            Bundle result = journal.latest(pkg(), 0);
            assertEquals(newRequest, result.getString("%am_backup_request_name"));
            assertEquals("queued", result.getString("%am_backup_request_status"));
            assertEquals("1", result.getString("%am_backup_request_pending_count"));
            assertEquals("false", result.getString("%am_backup_request_verified"));
            assertTrue(result.getString("%am_backup_request_history_json")
                    .contains("backup_completion_timeout"));
            org.json.JSONObject after = new org.json.JSONObject(new String(
                    java.nio.file.Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8));
            org.json.JSONArray entries = after.getJSONArray("records");
            assertEquals(64, entries.length());
            int unknown = 0;
            for (int i=0; i<entries.length(); i++) {
                org.json.JSONObject item = entries.getJSONObject(i);
                if ("unknown".equals(item.getString("status"))) ++unknown;
                assertNotEquals("succeeded", item.getString("status"));
            }
            assertEquals(63, unknown);
        } finally { cleanup(path); }
    }

    @Test public void boundedHistoryDoesNotLeakOtherPackagesOrUsers() throws Exception {
        File path = temporary();
        try {
            TaskerApkBackupJournal journal = new TaskerApkBackupJournal(path);
            String oldest = request();
            journal.enqueue(pkg(), 0, oldest);
            for (int i = 0; i < 15; i++) journal.enqueue(pkg(), 0, request());
            String crossUser = request(); String crossPkg = request();
            journal.enqueue(pkg(), 10, crossUser);
            journal.enqueue("com.example.foreign", 0, crossPkg);
            Bundle found = journal.latest(pkg(), 0);
            org.json.JSONArray items = new org.json.JSONArray(found.getString("%am_backup_request_history_json"));
            assertEquals(12, items.length());
            assertEquals("16", found.getString("%am_backup_request_pending_count"));
            String json = items.toString();
            assertFalse(json.contains(crossUser));
            assertFalse(json.contains(crossPkg));
            assertFalse(json.contains(oldest));
            assertFalse(json.contains("relativeDir"));
        } finally { cleanup(path); }
    }

    @Test public void equalTimestampKeepsMostRecentInsertedRequest() throws Exception {
        File path = temporary();
        try {
            TaskerApkBackupJournal journal = new TaskerApkBackupJournal(path);
            String first=request(), second=request();
            journal.enqueue(pkg(),0,first);journal.enqueue(pkg(),0,second);
            org.json.JSONObject root=new org.json.JSONObject(new String(
                    java.nio.file.Files.readAllBytes(path.toPath()),StandardCharsets.UTF_8));
            org.json.JSONArray records=root.getJSONArray("records");
            long earlier=records.getJSONObject(0).getLong("created_ms");
            records.getJSONObject(1).put("created_ms",earlier);
            java.nio.file.Files.write(path.toPath(),root.toString().getBytes(StandardCharsets.UTF_8));
            assertEquals(second,journal.latest(pkg(),0).getString("%am_backup_request_name"));
        } finally { cleanup(path); }
    }

    @Test public void clockRollbackCannotPrematurelyExpireRunningRequest() throws Exception {
        File path = temporary();
        try {
            TaskerApkBackupJournal journal = new TaskerApkBackupJournal(path);
            String name=request();journal.enqueue(pkg(),0,name);
            org.json.JSONObject root=new org.json.JSONObject(new String(
                    java.nio.file.Files.readAllBytes(path.toPath()),StandardCharsets.UTF_8));
            org.json.JSONObject rec=root.getJSONArray("records").getJSONObject(0);
            rec.put("updated_ms",System.currentTimeMillis()+3600000L);
            java.nio.file.Files.write(path.toPath(),root.toString().getBytes(StandardCharsets.UTF_8));
            Bundle found=journal.latest(pkg(),0);
            assertEquals("queued",found.getString("%am_backup_request_status"));
            assertEquals("1",found.getString("%am_backup_request_pending_count"));
        } finally { cleanup(path); }
    }

    @Test public void unknownDispatchCannotClaimVerifiedOutcome() throws Exception {
        File path = temporary(); String name = request();
        try {
            TaskerApkBackupJournal journal = new TaskerApkBackupJournal(path);
            journal.enqueue(pkg(), 0, name);
            journal.dispatchUnknown(pkg(), 0, name);
            Bundle result = journal.latest(pkg(), 0);
            assertEquals("unknown", result.getString("%am_backup_request_status"));
            assertEquals("false", result.getString("%am_backup_request_verified"));
        } finally { cleanup(path); }
    }
}
