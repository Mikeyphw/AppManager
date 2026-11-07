// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.content.Intent;
import android.os.Bundle;

/** AMT-04B1 fixed-package, fixed-operation Locale contract. No wildcard grants. */
public final class TaskerPackageManageWire {
    public static final int VERSION = 1;
    public static final String KEY_VERSION = "am.pkgmanage.version";
    public static final String KEY_TOKEN = "am.pkgmanage.proof";
    public static final String KEY_PACKAGE = "am.pkgmanage.package";
    public static final String KEY_OPERATION = "am.pkgmanage.operation";
    public static final String ENABLE = "enable";
    public static final String DISABLE = "disable";
    public static final String FORCE_STOP = "force_stop";

    private TaskerPackageManageWire() { }

    public static boolean validOperation(String operation) {
        return ENABLE.equals(operation) || DISABLE.equals(operation) || FORCE_STOP.equals(operation);
    }

    public static Bundle configuration(String pkg, String operation, String proof) {
        if (!TaskerPackageQueryWire.validPackage(pkg) || !validOperation(operation)
                || proof == null || !proof.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("invalid package operation binding");
        }
        Bundle out = new Bundle();
        out.putInt(KEY_VERSION, VERSION);
        out.putString(KEY_PACKAGE, pkg);
        out.putString(KEY_OPERATION, operation);
        out.putString(KEY_TOKEN, proof);
        return out;
    }

    public static boolean validConfiguration(Bundle input) {
        if (input == null || input.size() != 4) return false;
        for (String key : input.keySet()) {
            if (!KEY_VERSION.equals(key) && !KEY_PACKAGE.equals(key)
                    && !KEY_OPERATION.equals(key) && !KEY_TOKEN.equals(key)) return false;
        }
        if (!(input.get(KEY_VERSION) instanceof Integer)
                || input.getInt(KEY_VERSION, -1) != VERSION
                || !(input.get(KEY_PACKAGE) instanceof String)
                || !(input.get(KEY_OPERATION) instanceof String)
                || !(input.get(KEY_TOKEN) instanceof String)) return false;
        String proof = input.getString(KEY_TOKEN);
        return TaskerPackageQueryWire.validPackage(input.getString(KEY_PACKAGE))
                && validOperation(input.getString(KEY_OPERATION))
                && proof != null && proof.matches("[a-f0-9]{64}");
    }

    public static Intent editResult(Bundle config) {
        Intent out = new Intent();
        out.putExtra(TaskerPluginWire.EXTRA_BUNDLE, config);
        out.putExtra(TaskerPluginWire.EXTRA_BLURB,
                "App Manager · " + config.getString(KEY_OPERATION) + " " + config.getString(KEY_PACKAGE));
        out.putExtra(TaskerPluginWire.EXTRA_RELEVANT, new String[] {
                "%am_success\nOperation verified", "%am_status\nExecution state",
                "%am_error\nStructured failure reason", "%am_package\nExact package",
                "%am_operation\nSelected operation", "%am_previous_state\nPrevious enabled setting",
                "%am_new_state\nCurrent enabled setting",
                "%am_verified\nWhether the requested effect was read back"
        });
        return out;
    }
}
