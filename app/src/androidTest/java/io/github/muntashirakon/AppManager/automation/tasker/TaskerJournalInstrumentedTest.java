// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class TaskerJournalInstrumentedTest {
    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private static TaskerExecutionJournal db() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return TaskerExecutionJournal.get(context);
    }

    @Test public void testCancelQueuedRequest() {
        String request = id();
        assertTrue(db().enqueue(request, "instrumentation-demo", "on", ""));
        assertNotNull(db().cancelQueued(request));
        assertFalse(db().claim(request));
    }

    @Test public void testClaimIsExclusive() {
        String request = id();
        assertTrue(db().enqueue(request, "instrumentation-demo", "off", ""));
        assertTrue(db().markScheduled(request));
        assertTrue(db().claim(request));
        assertFalse(db().claim(request));
        assertTrue(db().complete(request, true, ""));
        assertEquals("success", db().find(request).status);
    }

    @Test public void testQueryOwnPackage() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.os.Bundle vars = TaskerPackageQuery.query(context, context.getPackageName());
        assertEquals("true", vars.getString("%am_success"));
        assertEquals("true", vars.getString("%am_installed"));
        assertEquals(context.getPackageName(), vars.getString("%am_package"));
        assertTrue(new org.json.JSONObject(vars.getString("%am_json")).getBoolean("installed"));
    }

    @Test public void testQueryUnresolvedVariable() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.os.Bundle vars = TaskerPackageQuery.query(context, "%package");
        assertEquals("false", vars.getString("%am_success"));
        assertEquals("unresolved_tasker_variable", vars.getString("%am_error"));
    }

    @Test public void testManageOwnPackageAlwaysDenied() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.os.Bundle vars = TaskerPackageManageBackend.execute(context, context.getPackageName(), "disable");
        assertEquals("false", vars.getString("%am_success"));
        assertEquals("self_target_denied", vars.getString("%am_error"));
    }
    @Test public void testForceStopOwnPackageAlwaysDenied() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.os.Bundle vars = TaskerPackageManageBackend.execute(context, context.getPackageName(), "force_stop");
        assertEquals("false", vars.getString("%am_success"));
        assertEquals("self_target_denied", vars.getString("%am_error"));
    }

    @Test public void testFreezeOwnPackageAlwaysDenied() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.os.Bundle frozen = TaskerPackageManageBackend.execute(context,
                context.getPackageName(), "freeze");
        android.os.Bundle restored = TaskerPackageManageBackend.execute(context,
                context.getPackageName(), "unfreeze");
        assertEquals("self_target_denied", frozen.getString("%am_error"));
        assertEquals("self_target_denied", restored.getString("%am_error"));
    }

    @Test public void testBackupInventoryDeviceScopedReadOnly() throws Exception {
        io.github.muntashirakon.AppManager.db.entity.Backup own =
                new io.github.muntashirakon.AppManager.db.entity.Backup();
        own.packageName = "com.example.amt05target";
        own.userId = 10;
        own.backupName = "fixture";
        own.backupTime = 1234L;
        own.relativeDir = "secret/never/exposed";
        io.github.muntashirakon.AppManager.db.entity.Backup foreign =
                new io.github.muntashirakon.AppManager.db.entity.Backup();
        foreign.packageName = own.packageName;
        foreign.userId = 11;
        foreign.backupName = "private_other_user";
        android.os.Bundle vars = TaskerBackupInventory.summarize(own.packageName, 10,
                java.util.Arrays.asList(own, foreign));
        assertEquals("available", vars.getString("%am_backup_status"));
        assertEquals("1", vars.getString("%am_backup_count"));
        assertFalse(vars.getString("%am_backups_json").contains("private_other_user"));
        assertFalse(vars.getString("%am_backups_json").contains("secret/never/exposed"));
    }

    @Test public void testLedgerSurvivesReopenAndRemovesOnlyMatchingSnapshot() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        java.io.File store = new java.io.File(ctx.getCacheDir(), "amt05-ledger-"+id()+".json");
        String pkg = "com.example.amt05ledger";
        TaskerPackageFreezeLedger.Entry saved = new TaskerPackageFreezeLedger.Entry(42000,
                11111L, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        try {
            TaskerPackageFreezeLedger first = new TaskerPackageFreezeLedger(store);
            first.record(pkg, saved);
            TaskerPackageFreezeLedger reopened = new TaskerPackageFreezeLedger(store);
            assertTrue(reopened.find(pkg).sameAs(saved));
            assertFalse(reopened.find(pkg).matches(42000, 22222L));
            reopened.forgetMatching(pkg, saved);
            assertNull(new TaskerPackageFreezeLedger(store).find(pkg));
        } finally {
            store.delete(); new java.io.File(store+".bak").delete();
            new java.io.File(store+".new").delete();
        }
    }

    @Test public void testLedgerCorruptionFailsClosedOnDevice() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        java.io.File store = new java.io.File(ctx.getCacheDir(), "amt05-corrupt-"+id()+".json");
        try {
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(store)) {
                output.write("not JSON".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            try {
                new TaskerPackageFreezeLedger(store).find("com.example.amt05ledger");
                fail("damaged freeze ledger must not be treated as an empty ledger");
            } catch (java.io.IOException expected) { /* fail-closed */ }
        } finally { store.delete(); new java.io.File(store+".bak").delete(); }
    }

    @Test public void testLedgerNeverOverwritesPriorSnapshotOnDevice() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        java.io.File store = new java.io.File(ctx.getCacheDir(), "amt05-guard-"+id()+".json");
        String pkg = "com.example.amt05guard";
        TaskerPackageFreezeLedger.Entry saved = new TaskerPackageFreezeLedger.Entry(12500,
                9000L, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        try {
            TaskerPackageFreezeLedger ledger = new TaskerPackageFreezeLedger(store);
            ledger.record(pkg, saved);
            try {
                ledger.record(pkg, new TaskerPackageFreezeLedger.Entry(12501, 9000L, 0));
                fail("duplicate freeze record replaced existing snapshot");
            } catch (java.io.IOException expected) { /* fail-closed */ }
            assertTrue(new TaskerPackageFreezeLedger(store).find(pkg).sameAs(saved));
        } finally { store.delete(); new java.io.File(store+".bak").delete(); }
    }

    @Test public void testApkBackupOwnPackageNeverStartsBatchService() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.os.Bundle vars = TaskerPackageManageBackend.execute(ctx,
                ctx.getPackageName(), "backup_apk");
        assertEquals("false", vars.getString("%am_success"));
        assertEquals("self_target_denied", vars.getString("%am_error"));
        assertEquals("", vars.getString("%am_backup_name"));
    }

    @Test public void testApkBackupHostTargetNeverStartsBatchService() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.os.Bundle vars = TaskerPackageManageBackend.execute(ctx,
                TaskerPluginWire.TASKER_PACKAGE, "backup_apk");
        assertEquals("false", vars.getString("%am_success"));
        assertEquals("tasker_target_denied", vars.getString("%am_error"));
    }

    @Test public void testApkReceiptPersistsAcrossObjectReopenOnDevice() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        java.io.File store = new java.io.File(ctx.getCacheDir(), "amt05c-receipt-"+id()+".json");
        String request = "Tasker-" + java.util.UUID.randomUUID();
        String pkg = "com.example.amt05c_device";
        try {
            TaskerApkBackupJournal ledger = new TaskerApkBackupJournal(store);
            ledger.enqueue(pkg, 0, request);
            ledger.started(pkg, 0, request);
            assertEquals("running", new TaskerApkBackupJournal(store).latest(pkg, 0)
                    .getString("%am_backup_request_status"));
            ledger.finished(pkg, 0, request, "succeeded", "");
            assertEquals("true", new TaskerApkBackupJournal(store).latest(pkg, 0)
                    .getString("%am_backup_request_verified"));
            assertEquals("none", ledger.latest(pkg, 10).getString("%am_backup_request_status"));
        } finally {
            store.delete(); new java.io.File(store+".bak").delete();
            new java.io.File(store+".new").delete();
        }
    }

    @Test public void testApkReceiptCorruptionFailsClosedOnDevice() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        java.io.File store = new java.io.File(ctx.getCacheDir(), "amt05c-corrupt-"+id()+".json");
        try {
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(store)) {
                stream.write("corrupt".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            try {
                new TaskerApkBackupJournal(store).enqueue("com.example.amt05c_device", 0,
                        "Tasker-" + java.util.UUID.randomUUID());
                fail("corrupt receipt must never be replaced");
            } catch (java.io.IOException expected) { /* fail closed */ }
        } finally { store.delete(); new java.io.File(store+".bak").delete(); }
    }

    @Test public void testApkReceiptCannotReclassifyFailureAsSuccessOnDevice() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        java.io.File store = new java.io.File(ctx.getCacheDir(), "amt05c-terminal-"+id()+".json");
        String request = "Tasker-" + java.util.UUID.randomUUID();
        String pkg = "com.example.amt05c_device";
        try {
            TaskerApkBackupJournal ledger = new TaskerApkBackupJournal(store);
            ledger.enqueue(pkg, 0, request);
            ledger.finished(pkg, 0, request, "failed", "native_backup_failed");
            ledger.finished(pkg, 0, request, "succeeded", "");
            assertEquals("failed", new TaskerApkBackupJournal(store).latest(pkg, 0)
                    .getString("%am_backup_request_status"));
            assertEquals("false", ledger.latest(pkg, 0)
                    .getString("%am_backup_request_verified"));
        } finally { store.delete(); new java.io.File(store+".bak").delete(); }
    }

    @Test public void testManageUnknownOperationAlwaysDenied() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.os.Bundle vars = TaskerPackageManageBackend.execute(context, "com.example.app", "suspend");
        assertEquals("false", vars.getString("%am_success"));
        assertEquals("invalid_operation", vars.getString("%am_error"));
    }
}
