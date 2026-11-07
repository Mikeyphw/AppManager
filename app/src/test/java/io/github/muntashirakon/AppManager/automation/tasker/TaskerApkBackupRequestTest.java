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

import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.batchops.BatchOpsManager;
import io.github.muntashirakon.AppManager.batchops.BatchQueueItem;
import io.github.muntashirakon.AppManager.batchops.struct.BatchBackupOptions;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class TaskerApkBackupRequestTest {
    private static final String PACKAGE = "com.example.disposable";
    private static final String NAME = "Tasker-12345678-1234-1234-1234-123456789abc";

    @Test public void backupQueueTargetsOnePackageCurrentUserOnly() {
        BatchQueueItem item = TaskerApkBackupDispatcher.buildQueue(PACKAGE, 0, NAME);
        assertEquals(BatchOpsManager.OP_BACKUP, item.getOp());
        assertEquals(1, item.getPackages().size());
        assertEquals(PACKAGE, item.getPackages().get(0));
        assertEquals(1, item.getUsers().size());
        assertEquals(Integer.valueOf(0), item.getUsers().get(0));
        assertTrue(item.getOptions() instanceof BatchBackupOptions);
        BatchBackupOptions options = (BatchBackupOptions) item.getOptions();
        io.github.muntashirakon.AppManager.backup.struct.BackupOpOptions request =
                options.getBackupOpOptions(PACKAGE, 0);
        assertEquals(BackupFlags.BACKUP_APK_FILES | BackupFlags.BACKUP_MULTIPLE,
                request.flags.getFlags());
        assertFalse(request.override);
        assertEquals(NAME, request.backupName);
    }

    @Test public void queuedResultDoesNotClaimBackupCompleted() {
        Bundle vars = TaskerPackageManageBackend.result(PACKAGE,
                TaskerPackageManageWire.BACKUP_APK, true, "", "", "");
        assertEquals("queued", vars.getString("%am_status"));
        assertEquals("false", vars.getString("%am_verified"));
        assertEquals("true", vars.getString("%am_success"));
        assertEquals("", vars.getString("%am_backup_name"));
    }

    @Test public void apkBackupProofCannotAuthorizeForceStop() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        String proof = TaskerPackageManageAccess.forTrustedEditor(app, PACKAGE, "backup_apk");
        assertTrue(TaskerPackageManageAccess.valid(app, PACKAGE, "backup_apk", proof));
        assertFalse(TaskerPackageManageAccess.valid(app, PACKAGE, "force_stop", proof));
        assertFalse(TaskerPackageManageAccess.valid(app, "com.example.other", "backup_apk", proof));
    }

    @Test public void backupOperationRefusesDynamicPackage() throws Exception {
        assertFalse(TaskerPackageQueryWire.validPackage("%package"));
        try {
            TaskerApkBackupDispatcher.buildQueue("%package", 0, NAME);
            fail("Tasker variable must not select arbitrary backup target");
        } catch (IllegalArgumentException expected) { /* fail closed */ }
        try {
            TaskerApkBackupDispatcher.buildQueue(PACKAGE, -1, NAME);
            fail("invalid user must be denied");
        } catch (IllegalArgumentException expected) { /* fail closed */ }
    }
}
