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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class TaskerPackageManageTest {
    private static final String PKG = "com.example.tasker_demo";

    @Test public void fixedOperationOnly() {
        assertTrue(TaskerPackageManageWire.validOperation("enable"));
        assertTrue(TaskerPackageManageWire.validOperation("disable"));
        assertFalse(TaskerPackageManageWire.validOperation("freeze"));
        assertTrue(TaskerPackageManageWire.validOperation("force_stop"));
        assertFalse(TaskerPackageManageWire.validOperation("suspend"));
    }

    @Test public void bindingIsExactAndTamperEvident() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        String enabled = TaskerPackageManageAccess.forTrustedEditor(app, PKG, "enable");
        String disabled = TaskerPackageManageAccess.forTrustedEditor(app, PKG, "disable");
        assertEquals(64, enabled.length());
        assertNotEquals(enabled, disabled);
        assertTrue(TaskerPackageManageAccess.valid(app, PKG, "enable", enabled));
        assertFalse(TaskerPackageManageAccess.valid(app, PKG, "disable", enabled));
        String stop = TaskerPackageManageAccess.forTrustedEditor(app, PKG, "force_stop");
        assertFalse(TaskerPackageManageAccess.valid(app, PKG, "enable", stop));
        assertFalse(TaskerPackageManageAccess.valid(app, PKG, "force_stop", enabled));
        assertFalse(TaskerPackageManageAccess.valid(app, "com.example.other", "enable", enabled));
        assertFalse(TaskerPackageManageAccess.valid(app, PKG, "enable",
                (enabled.startsWith("0") ? "1" : "0") + enabled.substring(1)));
    }

    @Test public void savedBundleRequiresOnlyKnownFields() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        String proof = TaskerPackageManageAccess.forTrustedEditor(app, PKG, "enable");
        Bundle config = TaskerPackageManageWire.configuration(PKG, "enable", proof);
        assertTrue(TaskerPackageManageWire.validConfiguration(config));
        config.putString("am.pkgmanage.variable", "%package");
        assertFalse(TaskerPackageManageWire.validConfiguration(config));
        config.remove("am.pkgmanage.variable");
        config.putString(TaskerPackageManageWire.KEY_PACKAGE, "%package");
        assertFalse(TaskerPackageManageWire.validConfiguration(config));
    }

    @Test public void existingProfileAndQueryCredentialsCannotAuthorizeMutation() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        String readOnly = TaskerPackageQueryAccess.tokenForTrustedEditor(app);
        assertFalse(TaskerPackageManageAccess.valid(app, PKG, "enable", readOnly));
        Bundle query = TaskerPackageQueryWire.saved(PKG, readOnly, false);
        assertFalse(TaskerPackageManageWire.validConfiguration(query));
    }

    @Test public void forbiddenTargetDoesNotModifyApp() {
        Context app = RuntimeEnvironment.getApplication();
        Bundle result = TaskerPackageManageBackend.execute(app, app.getPackageName(), "disable");
        assertEquals("false", result.getString("%am_success"));
        assertEquals("self_target_denied", result.getString("%am_error"));
    }

    @Test public void forceStopCannotTargetTaskerHost() {
        Context app = RuntimeEnvironment.getApplication();
        Bundle result = TaskerPackageManageBackend.execute(app, TaskerPluginWire.TASKER_PACKAGE, "force_stop");
        assertEquals("false", result.getString("%am_success"));
        assertEquals("tasker_target_denied", result.getString("%am_error"));
    }

    @Test public void invalidOperationFailsWithoutChangingPackage() {
        Context app = RuntimeEnvironment.getApplication();
        Bundle result = TaskerPackageManageBackend.execute(app, PKG, "suspend");
        assertEquals("false", result.getString("%am_success"));
        assertEquals("invalid_operation", result.getString("%am_error"));
    }
}
