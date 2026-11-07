// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Read-only package-query capability is separate from profile execution. */
public final class TaskerPackageQueryAccess {
    private static final String KEY_FILE = "tasker_package_query_capability_v1";
    private TaskerPackageQueryAccess() { }

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getApplicationContext().getNoBackupFilesDir(), KEY_FILE));
    }

    public static synchronized String tokenForTrustedEditor(Context context) throws IOException {
        AtomicFile f = file(context);
        if (f.getBaseFile().exists()) {
            String value = read(f);
            if (value == null) throw new IOException("invalid existing query credential");
            return value;
        }
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String token = Base64.encodeToString(secret, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        FileOutputStream stream = null;
        try {
            stream = f.startWrite();
            stream.write(token.getBytes(StandardCharsets.US_ASCII));
            f.finishWrite(stream);
        } catch (IOException | RuntimeException e) {
            if (stream != null) f.failWrite(stream);
            throw new IOException("could not persist query capability", e);
        }
        return token;
    }

    public static synchronized boolean valid(Context context, String provided) {
        if (provided == null || !provided.matches("[A-Za-z0-9_-]{43}")) return false;
        AtomicFile f = file(context);
        if (!f.getBaseFile().exists()) return false;
        try {
            String expected = read(f);
            return expected != null && MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    provided.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) { return false; }
    }

    private static String read(AtomicFile f) throws IOException {
        byte[] bytes = f.readFully();
        if (bytes.length != 43) return null;
        String value = new String(bytes, StandardCharsets.US_ASCII);
        return value.matches("[A-Za-z0-9_-]{43}") ? value : null;
    }
}
