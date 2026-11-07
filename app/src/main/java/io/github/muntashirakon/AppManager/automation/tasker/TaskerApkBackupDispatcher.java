// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.content.Context;
import android.os.Bundle;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.io.IOException;

import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.batchops.BatchOpsManager;
import io.github.muntashirakon.AppManager.batchops.BatchOpsService;
import io.github.muntashirakon.AppManager.batchops.BatchQueueItem;
import io.github.muntashirakon.AppManager.batchops.struct.BatchBackupOptions;

/** APK-only, non-overwriting backup enqueue through App Manager's existing foreground service.
 * This class is NOT an exported Android component, and never performs backup I/O directly.
 */
final class TaskerApkBackupDispatcher {
    private static final String REQUEST_PREFIX = "Tasker-";

    private TaskerApkBackupDispatcher() { }

    static BatchQueueItem buildQueue(String pkg, int user, String name) {
        if (!TaskerPackageQueryWire.validPackage(pkg) || user < 0
                || name == null || !name.matches("Tasker-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("invalid fixed APK backup request");
        }
        ArrayList<String> packages = new ArrayList<>(Collections.singletonList(pkg));
        ArrayList<Integer> users = new ArrayList<>(Collections.singletonList(user));
        BatchBackupOptions options = new BatchBackupOptions(BackupFlags.BACKUP_APK_FILES | BackupFlags.BACKUP_MULTIPLE,
                new String[] {name}, null);
        return BatchQueueItem.getBatchOpQueue(BatchOpsManager.OP_BACKUP, packages, users, options);
    }

    static Bundle dispatch(Context context, String pkg, int user) {
        String name = REQUEST_PREFIX + UUID.randomUUID();
        // The receipt MUST be persisted before any possible service side effect.
        TaskerApkBackupJournal receipts = new TaskerApkBackupJournal(context);
        try {
            receipts.enqueue(pkg, user, name);
        } catch (IOException | RuntimeException cannotRecord) {
            return TaskerPackageManageBackend.result(pkg, TaskerPackageManageWire.BACKUP_APK,
                    false, "", "", "backup_receipt_unavailable");
        }
        try {
            BatchQueueItem queue = buildQueue(pkg, user, name);
            ContextCompat.startForegroundService(context,
                    BatchOpsService.getServiceIntent(context, queue));
        } catch (RuntimeException rejected) {
            // A thrown start call cannot prove the request never reached the service.
            try { receipts.dispatchUnknown(pkg, user, name); }
            catch (IOException ignored) { /* remain pending rather than claim failure */ }
            Bundle uncertain = TaskerPackageManageBackend.result(pkg,
                    TaskerPackageManageWire.BACKUP_APK,
                    false, "", "", "backup_dispatch_rejected");
            uncertain.putString("%am_status", "unknown");
            uncertain.putString("%am_backup_name", name);
            return uncertain;
        }
        Bundle queued = TaskerPackageManageBackend.result(pkg, TaskerPackageManageWire.BACKUP_APK,
                true, "", "", "");
        queued.putString("%am_backup_name", name);
        // A successful service-start request is only an accepted request, not completion.
        queued.putString("%am_status", "queued");
        queued.putString("%am_verified", "false");
        return queued;
    }
}
