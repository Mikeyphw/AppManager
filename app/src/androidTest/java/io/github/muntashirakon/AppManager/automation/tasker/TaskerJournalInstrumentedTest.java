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
}
