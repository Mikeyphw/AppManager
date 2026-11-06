// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.android;

import android.content.Context;
import android.util.AtomicFile;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import io.github.muntashirakon.AppManager.automation.core.AutomationCaller;
import io.github.muntashirakon.AppManager.automation.core.AutomationDispatcher;
import io.github.muntashirakon.AppManager.automation.core.AutomationGrant;

/**
 * Private, atomic grant store. Only the future in-app approval UI may write grants.
 * Caller-provided Intents, Binder payloads and automation actions must NEVER call
 * putUserApprovedGrant(). Corrupt/unreadable files fail closed, not allow-all.
 */
public final class AndroidGrantRepository implements AutomationDispatcher.GrantProvider {
    private static final int FORMAT_VERSION = 1;
    private final AtomicFile file;

    public AndroidGrantRepository(@NonNull Context context) {
        File base = new File(context.getApplicationContext().getFilesDir(), "automation_grants_v1.json");
        file = new AtomicFile(base);
    }

    @Override
    @NonNull
    public synchronized Collection<AutomationGrant> grantsFor(AutomationCaller verifiedCaller) {
        if (verifiedCaller == null) return Collections.emptyList();
        try {
            List<AutomationGrant> filtered = new ArrayList<>();
            for (AutomationGrant item : loadAll()) {
                if (item.uid == verifiedCaller.uid &&
                        item.packageName.equals(verifiedCaller.packageName) &&
                        item.signingCertSha256.equals(verifiedCaller.signingCertSha256)) {
                    filtered.add(item);
                }
            }
            return Collections.unmodifiableList(filtered);
        } catch (IOException ignored) {
            return Collections.emptyList();
        }
    }

    /** UI-only: caller must have been approved by an authenticated local user. */
    public synchronized void putUserApprovedGrant(@NonNull AutomationGrant approved) throws IOException {
        if (approved.expiresAtMillis <= System.currentTimeMillis())
            throw new IOException("grant already expired");
        List<AutomationGrant> all = loadAll();
        all.removeIf(g -> sameScope(g, approved));
        all.add(approved);
        writeAll(all);
    }

    /** UI-only: revoke exact caller+operation+profile scope. */
    public synchronized void revokeGrant(@NonNull AutomationGrant approved) throws IOException {
        List<AutomationGrant> all = loadAll();
        all.removeIf(g -> sameScope(g, approved));
        writeAll(all);
    }

    private static boolean sameScope(AutomationGrant x, AutomationGrant y) {
        return x.uid == y.uid && x.packageName.equals(y.packageName)
                && x.signingCertSha256.equals(y.signingCertSha256)
                && x.operation.equals(y.operation)
                && (x.profileId == null ? y.profileId == null : x.profileId.equals(y.profileId));
    }

    @NonNull
    private List<AutomationGrant> loadAll() throws IOException {
        if (!file.getBaseFile().exists()) return new ArrayList<>();
        try {
            JSONObject doc = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            if (doc.getInt("version") != FORMAT_VERSION) throw new IOException("unsupported grant schema");
            JSONArray array = doc.getJSONArray("grants");
            if (array.length() > 4096) throw new IOException("excessive grant count");
            List<AutomationGrant> result = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject g = array.getJSONObject(i);
                result.add(new AutomationGrant(g.getInt("uid"), g.getString("package"),
                        g.getString("signer"), g.getString("operation"),
                        g.isNull("profile") ? null : g.getString("profile"),
                        g.getLong("expiry")));
            }
            return result;
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException("invalid private grant store", e);
        }
    }

    private void writeAll(@NonNull List<AutomationGrant> values) throws IOException {
        if (values.size() > 4096) throw new IOException("grant limit reached");
        JSONObject doc = new JSONObject();
        JSONArray array = new JSONArray();
        try {
            doc.put("version", FORMAT_VERSION);
            for (AutomationGrant g : values) {
                JSONObject item = new JSONObject();
                item.put("uid", g.uid);
                item.put("package", g.packageName);
                item.put("signer", g.signingCertSha256);
                item.put("operation", g.operation);
                item.put("profile", g.profileId == null ? JSONObject.NULL : g.profileId);
                item.put("expiry", g.expiresAtMillis);
                array.put(item);
            }
            doc.put("grants", array);
        } catch (JSONException e) {
            throw new IOException("grant serialization failed", e);
        }
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(doc.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (IOException | RuntimeException e) {
            if (output != null) file.failWrite(output);
            throw new IOException("grant write failed", e);
        }
    }
}
