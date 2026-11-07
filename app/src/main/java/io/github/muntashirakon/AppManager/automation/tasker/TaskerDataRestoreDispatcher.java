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

/** Strictly internal-data-only native restore. Never perform destructive work on Tasker's receiver thread. */
final class TaskerDataRestoreDispatcher {
    private TaskerDataRestoreDispatcher() { }

    static BatchQueueItem buildQueue(TaskerDataRestoreGate.Selection backup, String requestId) {
        if (backup == null || !TaskerDataRestoreJournal.validRequestId(requestId)
                || !TaskerPackageQueryWire.validPackage(backup.pkg) || backup.user < 0
                || backup.relativeDir == null || backup.relativeDir.isEmpty())
            throw new IllegalArgumentException("invalid fixed restore request");
        return BatchQueueItem.getBatchOpQueue(BatchOpsManager.OP_RESTORE_BACKUP,
                new ArrayList<>(Collections.singletonList(backup.pkg)),
                new ArrayList<>(Collections.singletonList(backup.user)),
                new BatchBackupOptions(BackupFlags.BACKUP_INT_DATA,
                        new String[] {requestId}, new String[] {backup.relativeDir}));
    }

    static Bundle dispatch(Context context, String pkg, int user, String operation) {
        TaskerDataRestoreGate.Selection backup;
        try { backup = TaskerDataRestoreGate.select(context, pkg, user, operation); }
        catch (IOException | RuntimeException rejected) {
            return TaskerPackageManageBackend.result(pkg, operation, false, "", "", "restore_preflight_denied");
        }
        String requestId = "Tasker-Restore-" + UUID.randomUUID();
        TaskerDataRestoreJournal journal = new TaskerDataRestoreJournal(context);
        try { journal.enqueue(backup, requestId); }
        catch (IOException | RuntimeException cannotPersist) {
            return TaskerPackageManageBackend.result(pkg, operation, false, "", "", "restore_receipt_unavailable");
        }
        try {
            BatchQueueItem queue = buildQueue(backup, requestId);
            ContextCompat.startForegroundService(context, BatchOpsService.getServiceIntent(context, queue));
        } catch (RuntimeException dispatchError) {
            journal.dispatchUnknown(requestId);
            Bundle unknown = TaskerPackageManageBackend.result(pkg, operation,
                    false, "", "", "restore_dispatch_uncertain");
            unknown.putString("%am_status", "unknown");
            unknown.putString("%am_restore_request_id", requestId);
            return unknown;
        }
        Bundle out = TaskerPackageManageBackend.result(pkg, operation, true, "", "", "");
        out.putString("%am_status", "queued");
        out.putString("%am_verified", "false");
        out.putString("%am_restore_request_id", requestId);
        return out;
    }
}
