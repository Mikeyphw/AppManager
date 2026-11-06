// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.UUID;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TaskerExecutionJournalTest {
    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private static TaskerExecutionJournal db() {
        Context context = RuntimeEnvironment.getApplication();
        return TaskerExecutionJournal.get(context);
    }

    @Test public void testCancelledRequestNeverStarts() {
        String request = id();
        assertTrue(db().enqueue(request, "demo", "on", null));
        assertNotNull(db().cancelQueued(request));
        assertFalse(db().claim(request));
        assertEquals("cancelled", db().find(request).status);
    }

    @Test public void testClaimExactlyOnce() {
        String request = id();
        assertTrue(db().enqueue(request, "demo", "on", null));
        assertFalse(db().enqueue(request, "demo", "on", null));
        assertTrue(db().claim(request));
        assertFalse(db().claim(request));
        assertTrue(db().complete(request, true, ""));
        assertFalse(db().complete(request, false, "late"));
        assertEquals("success", db().find(request).status);
    }

    @Test public void testFinishedCannotBeCancelled() {
        String request = id();
        assertTrue(db().enqueue(request, "demo", "off", null));
        assertTrue(db().claim(request));
        assertNull(db().cancelQueued(request));
        assertTrue(db().complete(request, false, "profile_apply_failed"));
        assertNull(db().cancelQueued(request));
        assertEquals("failed", db().find(request).status);
    }

    @Test public void testHistoryBoundedResult() {
        String request = id();
        assertTrue(db().enqueue(request, "demo", "on", null));
        Bundle snapshot = db().recent();
        assertEquals(request, snapshot.getString("%am_request_id"));
        assertTrue(snapshot.getString("%am_history").contains(request));
    }

    @Test public void testCancelledOldRequestReturnsOutcomeAfterRetentionPrune() {
        String old = id();
        assertTrue(db().enqueue(old, "demo", "on", null));
        for (int n = 0; n < 260; n++) {
            String current = id();
            assertTrue(db().enqueue(current, "demo", "on", null));
            assertTrue(db().claim(current));
            assertTrue(db().complete(current, true, ""));
        }
        TaskerExecutionJournal.Record cancelled = db().cancelQueued(old);
        assertNotNull("A committed cancellation must not be reported as rejected", cancelled);
        assertEquals("cancelled", cancelled.status);
        assertNotNull("Newest terminal transitions must survive retention", db().find(old));
    }

    @Test public void testCancellingQueueDoesNotClearOtherRunningState() {
        Context c = RuntimeEnvironment.getApplication();
        c.getSharedPreferences("tasker_profile_observations_v1", Context.MODE_PRIVATE)
                .edit().putBoolean("running", true).putString("request", "other-running")
                .putString("status", "running").commit();
        TaskerProfileEvents.cancelled(c, id(), "queued-demo", "off");
        assertTrue(c.getSharedPreferences("tasker_profile_observations_v1", Context.MODE_PRIVATE)
                .getBoolean("running", false));
    }


    private static String profilePrefix(String profile) throws Exception {
        byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(
                profile.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder("profile.");
        for (byte b : bytes) {
            result.append(Character.forDigit((b >>> 4) & 15, 16));
            result.append(Character.forDigit(b & 15, 16));
        }
        return result.append('.').toString();
    }

    @Test public void testQueuedCancellationAndFailurePreserveActiveSameProfile() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        String profile = "shared-profile";
        String prefix = profilePrefix(profile);
        String active = id();
        android.content.SharedPreferences prefs = c.getSharedPreferences(
                "tasker_profile_observations_v1", Context.MODE_PRIVATE);
        prefs.edit().putString("request", active).putString("status", "running")
                .putBoolean("running", true).putString(prefix + "profile", profile)
                .putString(prefix + "request", active).putString(prefix + "status", "running")
                .putBoolean(prefix + "running", true).commit();
        TaskerProfileEvents.cancelled(c, id(), profile, "off");
        assertTrue(prefs.getBoolean("running", false));
        assertTrue(prefs.getBoolean(prefix + "running", false));
        assertEquals(active, prefs.getString(prefix + "request", ""));
        TaskerProfileEvents.dispatchFailed(c, id(), profile, "off");
        assertTrue(prefs.getBoolean("running", false));
        assertTrue(prefs.getBoolean(prefix + "running", false));
        assertEquals(active, prefs.getString(prefix + "request", ""));
    }

    @Test public void testRecoveryClearsStaleRunningStateWithoutInventingSuccess() {
        Context c = RuntimeEnvironment.getApplication();
        String old = id();
        c.getSharedPreferences("tasker_profile_observations_v1", Context.MODE_PRIVATE)
                .edit().putString("request", old).putString("status", "running")
                .putBoolean("running", true).commit();
        TaskerProfileEvents.recoveredInterrupted(c, old, "lost-profile", "on");
        assertFalse(c.getSharedPreferences("tasker_profile_observations_v1", Context.MODE_PRIVATE)
                .getBoolean("running", true));
        assertEquals("interrupted", c.getSharedPreferences(
                "tasker_profile_observations_v1", Context.MODE_PRIVATE).getString("status", ""));
    }

}
