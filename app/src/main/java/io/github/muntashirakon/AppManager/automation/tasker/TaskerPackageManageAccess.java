// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.content.Context;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Per-device key; HMAC proof bound to one exact package AND one exact operation. */
public final class TaskerPackageManageAccess {
    private static final String KEY_FILE = "tasker_package_manage_hmac_v1";
    private static final int KEY_BYTES = 32;

    private TaskerPackageManageAccess() { }

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getApplicationContext().getNoBackupFilesDir(), KEY_FILE));
    }

    private static byte[] read(AtomicFile file) throws IOException {
        byte[] key = file.readFully();
        if (key.length != KEY_BYTES) throw new IOException("invalid package-management key");
        return key;
    }

    private static byte[] createForTrustedEditor(Context context) throws IOException {
        AtomicFile f = file(context);
        if (f.getBaseFile().exists()) return read(f);
        byte[] secret = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(secret);
        FileOutputStream stream = null;
        try {
            stream = f.startWrite();
            stream.write(secret);
            f.finishWrite(stream);
        } catch (IOException | RuntimeException error) {
            if (stream != null) f.failWrite(stream);
            throw new IOException("could not persist management key", error);
        }
        return secret;
    }

    private static String sign(byte[] key, String pkg, String operation) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] input = ("AMT04B1\u0000" + pkg + "\u0000" + operation).getBytes(StandardCharsets.UTF_8);
            byte[] digest = mac.doFinal(input);
            final char[] hex = "0123456789abcdef".toCharArray();
            char[] out = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                out[2*i] = hex[(digest[i] >>> 4) & 15];
                out[2*i+1] = hex[digest[i] & 15];
            }
            return new String(out);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    /** Called solely after verifying the Tasker/Locale editor's OS caller. */
    public static synchronized String forTrustedEditor(Context ctx, String pkg, String operation) throws IOException {
        if (!TaskerPackageQueryWire.validPackage(pkg) || !TaskerPackageManageWire.validOperation(operation))
            throw new IOException("invalid operation scope");
        return sign(createForTrustedEditor(ctx), pkg, operation);
    }

    /** A stolen proof cannot authorize a different package or operation. */
    public static synchronized boolean valid(Context ctx, String pkg, String operation, String proof) {
        if (!TaskerPackageQueryWire.validPackage(pkg) || !TaskerPackageManageWire.validOperation(operation)
                || proof == null || !proof.matches("[a-f0-9]{64}")) return false;
        AtomicFile f = file(ctx);
        if (!f.getBaseFile().exists()) return false;
        try {
            String expected = sign(read(f), pkg, operation);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    proof.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException | RuntimeException ignored) { return false; }
    }
}
