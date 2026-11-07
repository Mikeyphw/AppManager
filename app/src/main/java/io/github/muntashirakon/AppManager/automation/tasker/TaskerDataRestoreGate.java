// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.UserHandleHidden;
import android.os.Build;

import java.io.IOException;
import java.util.Arrays;
import io.github.muntashirakon.AppManager.backup.BackupItems;
import io.github.muntashirakon.AppManager.backup.CryptoUtils;
import io.github.muntashirakon.AppManager.backup.struct.BackupMetadataV5;
import io.github.muntashirakon.AppManager.crypto.CryptoException;
import io.github.muntashirakon.AppManager.utils.PackageUtils;

import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.backup.BackupUtils;
import io.github.muntashirakon.AppManager.db.entity.Backup;
import io.github.muntashirakon.AppManager.self.SelfPermissions;

/** Read-only, exact-snapshot preflight; native RestoreOp performs its own signature/checksum checks. */
final class TaskerDataRestoreGate {
    private static final int REQUIRED_FLAGS = BackupFlags.BACKUP_APK_FILES | BackupFlags.BACKUP_INT_DATA;

    static final class Selection {
        final String pkg, name, relativeDir;
        final int user, uid;
        final long installedAt, versionCode;
        Selection(String pkg, String name, String relativeDir, int user, int uid,
                  long installedAt, long versionCode) {
            this.pkg = pkg; this.name = name; this.relativeDir = relativeDir;
            this.user = user; this.uid = uid; this.installedAt = installedAt;
            this.versionCode = versionCode;
        }
    }

    private TaskerDataRestoreGate() { }

    private static void ensureSigningAndRestorableData(Context context, String pkg, Backup backup)
            throws IOException {
        BackupItems.BackupItem item = backup.getItem();
        try {
            BackupMetadataV5.Info archiveInfo = item.getInfo();
            if (!CryptoUtils.isAvailable(archiveInfo.crypto))
                throw new IOException("backup crypto unavailable");
            item.setCrypto(archiveInfo.getCrypto());
            BackupMetadataV5.Metadata metadata = item.getMetadata(archiveInfo).metadata;
            if (!pkg.equals(metadata.packageName) || metadata.dataDirs == null
                    || metadata.dataDirs.length == 0)
                throw new IOException("backup missing restorable internal data");
            try (BackupItems.Checksum checks = item.getChecksum()) {
                String[] archived = BackupItems.Checksum.getCertChecksums(checks);
                PackageInfo signedInfo = context.getPackageManager().getPackageInfo(pkg,
                        PackageManager.GET_SIGNING_CERTIFICATES);
                String[] installed = PackageUtils.getSigningCertChecksums(
                        archiveInfo.checksumAlgo, signedInfo, false);
                if (archived.length == 0 || installed.length == 0)
                    throw new IOException("restore signer information unavailable");
                for (String signer : archived) {
                    if (!Arrays.asList(installed).contains(signer))
                        throw new IOException("restore signer mismatch");
                }
            }
        } catch (CryptoException | PackageManager.NameNotFoundException e) {
            throw new IOException("backup cryptographic preflight unavailable", e);
        } finally {
            item.cleanup();
        }
    }

    static Selection select(Context context, String pkg, int user, String operation) throws IOException {
        if (!TaskerPackageQueryWire.validPackage(pkg)
                || !TaskerPackageManageWire.isDataRestore(operation)
                || user != UserHandleHidden.myUserId()) throw new IOException("invalid restore scope");
        if (pkg.equals(context.getPackageName()) || pkg.equals(TaskerPluginWire.TASKER_PACKAGE)
                || pkg.equals(TaskerPluginWire.LOCALE_PACKAGE))
            throw new IOException("protected restore target");
        if (!SelfPermissions.canWriteToDataData()) throw new IOException("privileged data access unavailable");
        final PackageInfo identity;
        try { identity = context.getPackageManager().getPackageInfo(pkg, 0); }
        catch (PackageManager.NameNotFoundException e) { throw new IOException("restore package missing", e); }
        final ApplicationInfo ai = identity.applicationInfo;
        if (ai == null || (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0)
            throw new IOException("system or invalid restore target");
        if (ai.uid != TaskerPackageManageWire.restoreUid(operation)
                || identity.firstInstallTime != TaskerPackageManageWire.restoreInstalledAt(operation))
            throw new IOException("package installation changed since authorization");
        String name = TaskerPackageManageWire.restoreName(operation);
        long version = Build.VERSION.SDK_INT >= 28 ? identity.getLongVersionCode() : identity.versionCode;
        Backup selected = null;
        try {
            for (Backup candidate : BackupUtils.getBackupMetadataFromDbNoLockValidate(pkg)) {
                if (candidate == null || !pkg.equals(candidate.packageName)
                        || candidate.userId != user || !name.equals(candidate.backupName)) continue;
                // This early check is intentionally strict; no APK reinstall, rules, external data,
                // signature-bypass or backups from other installations/Android users.
                if (candidate.isSystem || candidate.hasKeyStore || candidate.flags != REQUIRED_FLAGS
                        || candidate.versionCode != version || candidate.relativeDir == null
                        || candidate.relativeDir.isEmpty() || candidate.getItem().isFrozen())
                    throw new IOException("backup not eligible for data-only restore");
                if (selected != null) throw new IOException("ambiguous restore backup name");
                selected = candidate;
            }
        } catch (RuntimeException e) { throw new IOException("restore inventory unavailable", e); }
        if (selected == null) throw new IOException("matching current-user backup unavailable");
        // RestoreOp verifies APK signer only inside restoreApkFiles(), which this data-only
        // path deliberately does NOT invoke. Therefore signing must be verified HERE,
        // in the non-destructive preflight, before native clearApplicationUserData().
        ensureSigningAndRestorableData(context, pkg, selected);
        return new Selection(pkg, name, selected.relativeDir, user, ai.uid,
                identity.firstInstallTime, version);
    }
}
