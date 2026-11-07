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
import java.io.IOException;
import java.util.UUID;
import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.backup.struct.BackupOpOptions;
import io.github.muntashirakon.AppManager.batchops.BatchOpsManager;
import io.github.muntashirakon.AppManager.batchops.BatchQueueItem;
import io.github.muntashirakon.AppManager.batchops.struct.BatchBackupOptions;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public final class TaskerDataBackupTest {
    private static final String PKG = "com.example.amt06a";
    private static final String NAME = "Tasker-12345678-1234-1234-1234-123456789abc";

    @Test public void oneCurrentUserNativeBackupWithExactFlagsAndNoOverwrite() {
        BatchQueueItem item = TaskerDataBackupDispatcher.buildQueue(PKG, 0, NAME);
        assertEquals(BatchOpsManager.OP_BACKUP, item.getOp());
        assertEquals(1, item.getPackages().size());
        assertEquals(PKG, item.getPackages().get(0));
        assertEquals(1, item.getUsers().size());
        assertEquals(Integer.valueOf(0), item.getUsers().get(0));
        BackupOpOptions options = ((BatchBackupOptions)item.getOptions()).getBackupOpOptions(PKG, 0);
        assertEquals(BackupFlags.BACKUP_APK_FILES | BackupFlags.BACKUP_INT_DATA | BackupFlags.BACKUP_MULTIPLE,
                options.flags.getFlags());
        assertFalse(options.override);
        assertEquals(NAME, options.backupName);
    }

    @Test public void rejectsVariablesAndInvalidUsers() {
        try {TaskerDataBackupDispatcher.buildQueue("%user_package",0,NAME);fail("dynamic target");}
        catch(IllegalArgumentException expected) { }
        try {TaskerDataBackupDispatcher.buildQueue(PKG,-1,NAME);fail("cross-user");}
        catch(IllegalArgumentException expected) { }
        try {TaskerDataBackupDispatcher.buildQueue(PKG,0,"../secret");fail("arbitrary name");}
        catch(IllegalArgumentException expected) { }
    }

    @Test public void operationBoundProofCannotAuthorizeApkOrDisable() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        String proof=TaskerPackageManageAccess.forTrustedEditor(context,PKG,"backup_data");
        assertTrue(TaskerPackageManageAccess.valid(context,PKG,"backup_data",proof));
        assertFalse(TaskerPackageManageAccess.valid(context,PKG,"backup_apk",proof));
        assertFalse(TaskerPackageManageAccess.valid(context,PKG,"disable",proof));
        assertFalse(TaskerPackageManageAccess.valid(context,"com.example.other","backup_data",proof));
    }

    @Test public void queuedDataResultDoesNotClaimVerified() {
        Bundle vars=TaskerPackageManageBackend.result(PKG,TaskerPackageManageWire.BACKUP_DATA,true,"","","");
        assertEquals("queued",vars.getString("%am_status"));
        assertEquals("false",vars.getString("%am_verified"));
    }

    @Test public void typedDataReceiptPersistsWithoutChangingApkRecords() throws Exception {
        File file=File.createTempFile("amt06a-",".json");
        if(!file.delete())throw new IOException("failed to create ledger fixture");
        String name="Tasker-"+UUID.randomUUID();
        try {
            TaskerApkBackupJournal receipt=new TaskerApkBackupJournal(file);
            receipt.enqueue(PKG,0,name,"data");
            assertEquals("data",new TaskerApkBackupJournal(file).latest(PKG,0)
                    .getString("%am_backup_request_type"));
            receipt.started(PKG,0,name,"apk");
            assertEquals("queued",receipt.latest(PKG,0).getString("%am_backup_request_status"));
            receipt.started(PKG,0,name,"data");
            assertEquals("running",receipt.latest(PKG,0).getString("%am_backup_request_status"));
            receipt.finished(PKG,0,name,"apk","succeeded","");
            assertEquals("running",receipt.latest(PKG,0).getString("%am_backup_request_status"));
        } finally {file.delete();new File(file+".bak").delete();new File(file+".new").delete();}
    }
}
