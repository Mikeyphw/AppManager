// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.batchops.BatchOpsManager;
import io.github.muntashirakon.AppManager.batchops.BatchQueueItem;
import io.github.muntashirakon.AppManager.batchops.struct.BatchBackupOptions;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class TaskerDataRestoreTest {
    private static final String PKG = "com.example.amt06b";
    private static final String NAME = "Tasker-12345678-1234-1234-1234-123456789abc";
    private static final String REQUEST = "Tasker-Restore-87654321-1234-1234-1234-123456789abc";
    private static TaskerDataRestoreGate.Selection snapshot() {
        return new TaskerDataRestoreGate.Selection(PKG, NAME, "backups/snapshot-uuid",
                0, 12345, 987654321L, 28L);
    }
    private static File temporary() throws IOException {
        File f = File.createTempFile("amt06b-journal-", ".json");
        if (!f.delete()) throw new IOException("cannot prepare fixture");
        return f;
    }
    private static void cleanup(File f) {
        f.delete(); new File(f + ".bak").delete(); new File(f + ".new").delete();
    }

    @Test public void identityAndBackupNameAreImmutableWithinHmacOperation() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        String bound = TaskerPackageManageWire.restoreOperation(NAME, 12345, 987654321L);
        assertTrue(TaskerPackageManageWire.isDataRestore(bound));
        assertEquals(NAME, TaskerPackageManageWire.restoreName(bound));
        assertEquals(12345, TaskerPackageManageWire.restoreUid(bound));
        assertEquals(987654321L, TaskerPackageManageWire.restoreInstalledAt(bound));
        String proof = TaskerPackageManageAccess.forTrustedEditor(app, PKG, bound);
        assertTrue(TaskerPackageManageAccess.valid(app, PKG, bound, proof));
        assertFalse(TaskerPackageManageAccess.valid(app, PKG,
                TaskerPackageManageWire.restoreOperation(NAME, 12345, 987654322L), proof));
        assertFalse(TaskerPackageManageAccess.valid(app, "com.example.other", bound, proof));
        assertFalse(TaskerPackageManageAccess.valid(app, PKG, "disable", proof));
    }

    @Test public void maliciousOrDynamicRestoreStringsNeverAuthorize() {
        assertFalse(TaskerPackageManageWire.validOperation("restore_data"));
        assertFalse(TaskerPackageManageWire.validOperation("restore_data:%backup:12345:56789"));
        assertFalse(TaskerPackageManageWire.validOperation("restore_data:latest:12345:56789"));
        assertFalse(TaskerPackageManageWire.validOperation("restore_data:" + NAME + ":-1:1"));
        assertFalse(TaskerPackageManageWire.validOperation("restore_data:" + NAME + ":1:0"));
        assertFalse(TaskerPackageManageWire.validOperation("restore_data:" + NAME + ":1:1:extra"));
    }

    @Test public void onlyCurrentUserInternalDataIsInNativeQueue() {
        BatchQueueItem item = TaskerDataRestoreDispatcher.buildQueue(snapshot(), REQUEST);
        assertEquals(BatchOpsManager.OP_RESTORE_BACKUP, item.getOp());
        assertEquals(1, item.getPackages().size());
        assertEquals(PKG, item.getPackages().get(0));
        assertEquals(1, item.getUsers().size());
        assertEquals(Integer.valueOf(0), item.getUsers().get(0));
        assertTrue(item.getOptions() instanceof BatchBackupOptions);
        io.github.muntashirakon.AppManager.backup.struct.RestoreOpOptions options =
                ((BatchBackupOptions) item.getOptions()).getRestoreOpOptions(PKG, 0);
        assertEquals(BackupFlags.BACKUP_INT_DATA, options.flags.getFlags());
        assertEquals("backups/snapshot-uuid", options.relativeDir);
        assertEquals(REQUEST, ((BatchBackupOptions) item.getOptions())
                .getBackupOpOptions(PKG, 0).backupName);
    }

    @Test public void nativeServiceReceiptIsDurableAndNeverClaimsVerifiedRestore() throws Exception {
        File file = temporary();
        try {
            TaskerDataRestoreJournal first = new TaskerDataRestoreJournal(file);
            first.enqueue(snapshot(), REQUEST);
            Bundle next = new TaskerDataRestoreJournal(file).latest(PKG, 0);
            assertEquals("queued", next.getString("%am_restore_request_status"));
            assertEquals(REQUEST, next.getString("%am_restore_request_id"));
            assertEquals("false", next.getString("%am_restore_request_verified"));
            assertEquals("none", new TaskerDataRestoreJournal(file).latest(PKG, 10)
                    .getString("%am_restore_request_status"));
        } finally { cleanup(file); }
    }

    @Test public void corruptedJournalCannotAuthorizeNewRestore() throws Exception {
        File file = temporary();
        try {
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write("damaged".getBytes(StandardCharsets.UTF_8));
            }
            try { new TaskerDataRestoreJournal(file).enqueue(snapshot(), REQUEST);
                fail("corrupt journal must never be overwritten"); }
            catch (IOException expected) { /* fail-closed */ }
            assertEquals("damaged", new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8));
        } finally { cleanup(file); }
    }

    @Test public void secondRestoreCannotBypassAnActiveRequest() throws Exception {
        File file = temporary();
        try {
            TaskerDataRestoreJournal journal = new TaskerDataRestoreJournal(file);
            journal.enqueue(snapshot(), REQUEST);
            try { journal.enqueue(snapshot(), "Tasker-Restore-12345678-1234-1234-1234-123456789abc");
                fail("concurrent destructive restore must be refused"); }
            catch (IOException expected) { /* protected single-in-flight request */ }
            assertEquals("queued", new TaskerDataRestoreJournal(file).latest(PKG, 0)
                    .getString("%am_restore_request_status"));
        } finally { cleanup(file); }
    }
}
