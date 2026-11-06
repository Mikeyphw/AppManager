// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Binder;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import io.github.muntashirakon.AppManager.automation.core.AutomationCaller;
import io.github.muntashirakon.AppManager.automation.core.AutomationIdentityPolicy;

/**
 * Verifies a caller ONLY during an inbound Binder transaction.
 * Never call from an Activity launched with startActivity(): Binder.getCallingUid()
 * is not a trustworthy representation of that Intent's originating app.
 * Do not expose an endpoint until AMT-02's consent/IPC acceptance is complete.
 */
public final class AndroidCallerVerifier {
    private final PackageManager packageManager;

    public AndroidCallerVerifier(@NonNull Context context) {
        packageManager = context.getApplicationContext().getPackageManager();
    }

    @Nullable
    public AutomationCaller verifyBinderCaller(@Nullable String claimedPackage) {
        // Must execute synchronously inside Binder.onTransact / an AIDL method.
        final int callingUid = Binder.getCallingUid();
        try {
            String[] names = packageManager.getPackagesForUid(callingUid);
            if (!AutomationIdentityPolicy.uniquelyOwned(callingUid, names, claimedPackage)) return null;
            Signature[] signatures = currentSigningCertificates(claimedPackage);
            // Multiple signers are intentionally unsupported for now.
            if (signatures == null || signatures.length != 1 || signatures[0] == null) return null;
            String digest = sha256Hex(signatures[0].toByteArray());
            return AutomationIdentityPolicy.verifiedCaller(callingUid, names, claimedPackage, digest);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            // Fail closed; do not return package manager details to the caller.
            return null;
        }
    }

    @Nullable
    @SuppressWarnings("deprecation")
    private Signature[] currentSigningCertificates(@NonNull String packageName)
            throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo info = packageManager.getPackageInfo(packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo signingInfo = info.signingInfo;
            return signingInfo != null ? signingInfo.getApkContentsSigners() : null;
        }
        PackageInfo info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
        return info.signatures;
    }

    @NonNull
    private static String sha256Hex(@NonNull byte[] certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate);
            char[] out = new char[digest.length * 2];
            final char[] alphabet = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; ++i) {
                out[i * 2] = alphabet[(digest[i] >>> 4) & 0x0f];
                out[i * 2 + 1] = alphabet[digest[i] & 0x0f];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
