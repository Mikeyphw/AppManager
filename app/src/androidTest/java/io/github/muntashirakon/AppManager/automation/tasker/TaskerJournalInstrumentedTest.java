// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class TaskerJournalInstrumentedTest {
    private static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private static TaskerExecutionJournal db() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return TaskerExecutionJournal.get(context);
    }

    @Test public void testCancelQueuedRequest() {
        String request = id();
        assertTrue(db().enqueue(request, "instrumentation-demo", "on", ""));
        assertNotNull(db().cancelQueued(request));
        assertFalse(db().claim(request));
    }

    @Test public void testClaimIsExclusive() {
        String request = id();
        assertTrue(db().enqueue(request, "instrumentation-demo", "off", ""));
        assertTrue(db().markScheduled(request));
        assertTrue(db().claim(request));
        assertFalse(db().claim(request));
        assertTrue(db().complete(request, true, ""));
        assertEquals("success", db().find(request).status);
    }

    @Test public void testQueryOwnPackage() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.os.Bundle vars = TaskerPackageQuery.query(context, context.getPackageName());
        assertEquals("true", vars.getString("%am_success"));
        assertEquals("true", vars.getString("%am_installed"));
        assertEquals(context.getPackageName(), vars.getString("%am_package"));
        assertTrue(new org.json.JSONObject(vars.getString("%am_json")).getBoolean("installed"));
    }

    @Test public void testQueryUnresolvedVariable() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.os.Bundle vars = TaskerPackageQuery.query(context, "%package");
        assertEquals("false", vars.getString("%am_success"));
        assertEquals("unresolved_tasker_variable", vars.getString("%am_error"));
    }

    @Test public void testManageOwnPackageAlwaysDenied() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.os.Bundle vars = TaskerPackageManageBackend.execute(context, context.getPackageName(), "disable");
        assertEquals("false", vars.getString("%am_success"));
        assertEquals("self_target_denied", vars.getString("%am_error"));
    }
    @Test public void testForceStopOwnPackageAlwaysDenied() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.os.Bundle vars = TaskerPackageManageBackend.execute(context, context.getPackageName(), "force_stop");
        assertEquals("false", vars.getString("%am_success"));
        assertEquals("self_target_denied", vars.getString("%am_error"));
    }

    @Test public void testFreezeOwnPackageAlwaysDenied() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.os.Bundle frozen = TaskerPackageManageBackend.execute(context,
                context.getPackageName(), "freeze");
        android.os.Bundle restored = TaskerPackageManageBackend.execute(context,
                context.getPackageName(), "unfreeze");
        assertEquals("self_target_denied", frozen.getString("%am_error"));
        assertEquals("self_target_denied", restored.getString("%am_error"));
    }

    @Test public void testManageUnknownOperationAlwaysDenied() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.os.Bundle vars = TaskerPackageManageBackend.execute(context, "com.example.app", "suspend");
        assertEquals("false", vars.getString("%am_success"));
        assertEquals("invalid_operation", vars.getString("%am_error"));
    }
}
