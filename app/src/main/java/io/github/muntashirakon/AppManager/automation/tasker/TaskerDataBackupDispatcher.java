// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.content.Context;
import android.os.Bundle;
import androidx.core.content.ContextCompat;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.batchops.BatchOpsManager;
import io.github.muntashirakon.AppManager.batchops.BatchOpsService;
import io.github.muntashirakon.AppManager.batchops.BatchQueueItem;
import io.github.muntashirakon.AppManager.batchops.struct.BatchBackupOptions;

/** One current-user APK+internal-data backup. Requires the backend's prior privilege gate.
 * No shell, cross-user access, restore, storage override or arbitrary Tasker variables.
 */
final class TaskerDataBackupDispatcher {
    private TaskerDataBackupDispatcher() { }

    static BatchQueueItem buildQueue(String pkg, int user, String name) {
        if (!TaskerPackageQueryWire.validPackage(pkg) || user < 0
                || name == null || !name.matches("Tasker-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("invalid scoped data backup request");
        ArrayList<String> packages = new ArrayList<>(Collections.singletonList(pkg));
        ArrayList<Integer> users = new ArrayList<>(Collections.singletonList(user));
        BatchBackupOptions options = new BatchBackupOptions(
                BackupFlags.BACKUP_APK_FILES | BackupFlags.BACKUP_INT_DATA | BackupFlags.BACKUP_MULTIPLE,
                new String[] {name}, null);
        return BatchQueueItem.getBatchOpQueue(BatchOpsManager.OP_BACKUP, packages, users, options);
    }

    static Bundle dispatch(Context context, String pkg, int user) {
        String name = "Tasker-" + UUID.randomUUID();
        TaskerApkBackupJournal receipts = new TaskerApkBackupJournal(context);
        try {
            receipts.enqueue(pkg, user, name, "data");
        } catch (IOException | RuntimeException rejected) {
            return TaskerPackageManageBackend.result(pkg, TaskerPackageManageWire.BACKUP_DATA,
                    false, "", "", "backup_receipt_unavailable");
        }
        try {
            ContextCompat.startForegroundService(context,
                    BatchOpsService.getServiceIntent(context, buildQueue(pkg, user, name)));
        } catch (RuntimeException rejected) {
            try { receipts.dispatchUnknown(pkg, user, name, "data"); }
            catch (IOException ignored) { /* preserve pending record; never claim failure */ }
            Bundle uncertain = TaskerPackageManageBackend.result(pkg, TaskerPackageManageWire.BACKUP_DATA,
                    false, "", "", "backup_dispatch_rejected");
            uncertain.putString("%am_status", "unknown");
            uncertain.putString("%am_backup_name", name);
            return uncertain;
        }
        Bundle queued = TaskerPackageManageBackend.result(pkg, TaskerPackageManageWire.BACKUP_DATA,
                true, "", "", "");
        queued.putString("%am_backup_name", name);
        queued.putString("%am_status", "queued");
        queued.putString("%am_verified", "false");
        return queued;
    }
}
