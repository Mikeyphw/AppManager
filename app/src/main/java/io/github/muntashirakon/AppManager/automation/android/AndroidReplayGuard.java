// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;

import io.github.muntashirakon.AppManager.automation.core.AutomationCaller;
import io.github.muntashirakon.AppManager.automation.core.AutomationDispatcher;
import io.github.muntashirakon.AppManager.automation.core.AutomationRequest;

/** Durable, atomic replay reservation, keyed by authenticated UID/package/signature/request. */
public final class AndroidReplayGuard extends SQLiteOpenHelper implements AutomationDispatcher.ReplayGuard {
    private static final String DB_NAME = "automation_replay_v1.db";
    public AndroidReplayGuard(@NonNull Context context) {
        super(context.getApplicationContext(), DB_NAME, null, 1);
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE reservations (uid INTEGER NOT NULL, pkg TEXT NOT NULL, signer TEXT NOT NULL, " +
                "request_id TEXT NOT NULL, created_at INTEGER NOT NULL, " +
                "PRIMARY KEY(uid, pkg, signer, request_id))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // No automatic downgrade/reset: deleting the ledger permits request replay.
        throw new SQLiteException("unsupported replay schema migration");
    }
    @Override public synchronized boolean reserve(AutomationCaller caller, AutomationRequest request) {
        if (caller == null || request == null) return false;
        try {
            ContentValues row = new ContentValues();
            row.put("uid", caller.uid);
            row.put("pkg", caller.packageName);
            row.put("signer", caller.signingCertSha256);
            row.put("request_id", request.requestId);
            row.put("created_at", System.currentTimeMillis());
            // SQLite UNIQUE/primary-key enforcement atomically rejects duplicates, across processes.
            return getWritableDatabase().insertWithOnConflict("reservations", null, row,
                    SQLiteDatabase.CONFLICT_IGNORE) != -1L;
        } catch (RuntimeException ignored) {
            return false; // storage unavailable -> refuse operation
        }
    }
}
