// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TaskerPackageQueryTest {
    private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Test public void packageValidationIsExact() {
        assertTrue(TaskerPackageQueryWire.validPackage("com.example.app"));
        assertFalse(TaskerPackageQueryWire.validPackage("com"));
        assertFalse(TaskerPackageQueryWire.validPackage("../com.example"));
        assertFalse(TaskerPackageQueryWire.validPackage("com.example;exit"));
        assertFalse(TaskerPackageQueryWire.validPackage("com.example\nother"));
        assertFalse(TaskerPackageQueryWire.validPackage("%package"));
    }

    @Test public void dynamicRequiresOneTaskerVariable() {
        assertTrue(TaskerPackageQueryWire.validVariable("%package"));
        assertFalse(TaskerPackageQueryWire.validVariable("%package/%other"));
        assertFalse(TaskerPackageQueryWire.validVariable("%package suffix"));
        Bundle saved = TaskerPackageQueryWire.saved("%package", TOKEN, true);
        assertTrue(TaskerPackageQueryWire.validConfiguration(saved));
        Intent result = TaskerPackageQueryWire.editResult(saved, "read only");
        assertEquals(TaskerPackageQueryWire.KEY_PACKAGE,
                result.getStringExtra(TaskerPluginWire.EXTRA_VARIABLE_REPLACE_KEYS));
    }

    @Test public void invalidBundleNeverBecomesAnOperation() {
        Bundle config = TaskerPackageQueryWire.saved("com.example.app", TOKEN, false);
        assertTrue(TaskerPackageQueryWire.validConfiguration(config));
        config.putString("am.pkgquery.action", "freeze");
        assertFalse(TaskerPackageQueryWire.validConfiguration(config));
        config.remove("am.pkgquery.action");
        config.putBoolean(TaskerPackageQueryWire.KEY_DYNAMIC, false);
        assertFalse(TaskerPackageQueryWire.validConfiguration(config));
        config.remove(TaskerPackageQueryWire.KEY_DYNAMIC);
        config.putInt(TaskerPackageQueryWire.KEY_VERSION, 3);
        assertFalse(TaskerPackageQueryWire.validConfiguration(config));
    }

    @Test public void profileConfigurationIsNotPackageQuery() {
        Bundle profile = TaskerPluginWire.configuration(TaskerPluginWire.MODE_APPLY,
                "example-profile", "on", TOKEN);
        assertFalse(TaskerPackageQueryWire.validConfiguration(profile));
        assertFalse(profile.containsKey(TaskerPackageQueryWire.KEY_VERSION));
    }

    @Test public void separateCapabilityIsPersistentAndFailClosed() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        String token = TaskerPackageQueryAccess.tokenForTrustedEditor(app);
        assertEquals(43, token.length());
        assertEquals(token, TaskerPackageQueryAccess.tokenForTrustedEditor(app));
        assertTrue(TaskerPackageQueryAccess.valid(app, token));
        assertFalse(TaskerPackageQueryAccess.valid(app, TOKEN.equals(token) ? token.substring(1) : TOKEN));
        assertFalse(TaskerPackageQueryAccess.valid(app, "short"));
        assertFalse(TaskerAccess.valid(app, token));
    }

    @Test public void queryOwnPackageReturnsStructuredMetadata() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        String pkg = app.getPackageName();
        Bundle vars = TaskerPackageQuery.query(app, pkg);
        assertEquals("true", vars.getString("%am_success"));
        assertEquals("true", vars.getString("%am_installed"));
        assertEquals(pkg, vars.getString("%am_package"));
        assertEquals("success", vars.getString("%am_status"));
        JSONObject json = new JSONObject(vars.getString("%am_json"));
        assertTrue(json.getBoolean("installed"));
        assertEquals(pkg, json.getString("package"));
    }

    @Test public void missingIsNotFailure() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        Bundle vars = TaskerPackageQuery.query(app, "com.example.nonexistent_amt04_a");
        assertEquals("true", vars.getString("%am_success"));
        assertEquals("false", vars.getString("%am_installed"));
        assertEquals("", vars.getString("%am_error"));
        assertFalse(new JSONObject(vars.getString("%am_json")).getBoolean("installed"));
    }

    @Test public void unresolvedVariableNeverExecutesPackageLookup() {
        Bundle vars = TaskerPackageQuery.query(RuntimeEnvironment.getApplication(), "%package");
        assertEquals("false", vars.getString("%am_success"));
        assertEquals("unresolved_tasker_variable", vars.getString("%am_error"));
    }
}
