// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Automatic local security: no user-facing approval, token entry, or grant expiry. */
public final class TaskerAccess {
    private static final int TOKEN_BYTES = 32;
    private static final String TOKEN_FILE = "tasker_plugin_key_v1";

    private TaskerAccess() { }

    private static AtomicFile file(Context context) {
        // Never include this token in Android cloud/device backup.
        return new AtomicFile(new File(context.getApplicationContext().getNoBackupFilesDir(), TOKEN_FILE));
    }

    @NonNull
    public static synchronized String tokenForTrustedEditor(@NonNull Context context) throws IOException {
        AtomicFile file = file(context);
        if (file.getBaseFile().exists()) {
            String existing = readToken(file);
            if (existing == null) throw new IOException("invalid existing Tasker credential");
            return existing;
        }
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        String value = Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(value.getBytes(StandardCharsets.US_ASCII));
            file.finishWrite(out);
        } catch (IOException | RuntimeException error) {
            if (out != null) file.failWrite(out);
            throw new IOException("could not persist Tasker credential", error);
        }
        return value;
    }

    public static synchronized boolean valid(@NonNull Context context, String supplied) {
        if (supplied == null || supplied.length() != 43) return false;
        AtomicFile file = file(context);
        if (!file.getBaseFile().exists()) return false;
        try {
            String expected = readToken(file);
            return expected != null && MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    supplied.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException error) { return false; }
    }

    private static String readToken(AtomicFile file) throws IOException {
        byte[] data = file.readFully();
        if (data.length != 43) return null;
        String candidate = new String(data, StandardCharsets.US_ASCII);
        if (!candidate.matches("[A-Za-z0-9_-]{43}")) return null;
        return candidate;
    }
}
