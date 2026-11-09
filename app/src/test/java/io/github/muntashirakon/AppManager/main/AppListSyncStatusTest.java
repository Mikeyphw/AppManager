// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.main;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppListSyncStatusTest {
    @Test public void runningCannotBeMisreportedAsSuccess() {
        AppListSyncStatus state = AppListSyncStatus.running("pull");
        assertTrue(state.running);
        assertFalse(state.success);
    }

    @Test public void failurePreservesExplicitReason() {
        AppListSyncStatus state = AppListSyncStatus.failure("menu", "PackageQueryException");
        assertFalse(state.success);
        assertEquals("PackageQueryException", state.error);
    }

    @Test public void inaccessibleProfileProducesPartialWarning() {
        AppListSyncStatus state = AppListSyncStatus.success("resume", 100, 1, 1, 55);
        assertTrue(state.partial);
        assertEquals(1, state.skippedUsers);
    }

    @Test public void visibleCountOnlyChangesAfterPublication() {
        AppListSyncStatus started = AppListSyncStatus.success("pull", 100, 1, 0, 88);
        AppListSyncStatus finished = started.withVisibleCount(10, true);
        assertEquals(0, started.visibleCount);
        assertEquals(10, finished.visibleCount);
        assertTrue(finished.filtered);
        assertTrue(finished.success);
    }
}
