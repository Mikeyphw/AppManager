// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.IOException;

/** User saves a fixed package+operation, not a reusable arbitrary mutator credential. */
public final class TaskerPackageManageEditActivity extends Activity {
    private EditText packageId;
    private Spinner operation;
    private TextView feedback;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        if (!TaskerPluginWire.EDIT_ACTION.equals(getIntent().getAction())
                || !TaskerPluginWire.trustedEditor(getCallingPackage())) { finish(); return; }
        Bundle previous = TaskerPluginWire.fromIntent(getIntent());
        String savedPackage = "";
        String savedOperation = TaskerPackageManageWire.ENABLE;
        if (TaskerPackageManageWire.validConfiguration(previous)) {
            savedPackage = previous.getString(TaskerPackageManageWire.KEY_PACKAGE, "");
            savedOperation = previous.getString(TaskerPackageManageWire.KEY_OPERATION, "enable");
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("App Manager · Manage Package");
        title.setTextSize(22);
        root.addView(title);
        TextView instruction = new TextView(this);
        instruction.setText("Fixed user app only. Freeze preserves prior state; unfreeze restores owned snapshots. Force-stop cannot be undone.");
        root.addView(instruction);
        packageId = new EditText(this);
        packageId.setSingleLine(true);
        packageId.setHint("com.example.userapp");
        packageId.setText(savedPackage);
        root.addView(packageId);
        operation = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[] {"Enable application", "Disable application", "Force-stop application (irreversible)",
                        "Freeze (remember original state)", "Unfreeze (restore saved state)",
                        "Backup APK (new named backup; does not overwrite)",
                        "Backup APK + internal app data (requires privileged access)"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        operation.setAdapter(adapter);
        operation.setSelection(TaskerPackageManageWire.BACKUP_DATA.equals(savedOperation) ? 6 :
                TaskerPackageManageWire.BACKUP_APK.equals(savedOperation) ? 5 :
                TaskerPackageManageWire.UNFREEZE.equals(savedOperation) ? 4 :
                TaskerPackageManageWire.FREEZE.equals(savedOperation) ? 3 :
                TaskerPackageManageWire.FORCE_STOP.equals(savedOperation) ? 2 :
                TaskerPackageManageWire.DISABLE.equals(savedOperation) ? 1 : 0);
        root.addView(operation);
        feedback = new TextView(this);
        feedback.setText("This task uses App Manager's existing backend. " +
                "Each request may use storage; completion is not confirmed by Tasker. " +
                "App-data backups may contain credentials or other sensitive information; check storage encryption settings. " +
                "No permission is added or elevated.");
        root.addView(feedback);
        LinearLayout buttons = new LinearLayout(this);
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> finish());
        buttons.addView(cancel);
        Button save = new Button(this);
        save.setText("Save to Tasker");
        save.setOnClickListener(v -> save());
        buttons.addView(save);
        root.addView(buttons);
        setContentView(root);
    }

    private void save() {
        String pkg = packageId.getText().toString().trim();
        String selected = operation.getSelectedItemPosition() == 6
                ? TaskerPackageManageWire.BACKUP_DATA : operation.getSelectedItemPosition() == 5
                ? TaskerPackageManageWire.BACKUP_APK : operation.getSelectedItemPosition() == 4
                ? TaskerPackageManageWire.UNFREEZE : operation.getSelectedItemPosition() == 3
                ? TaskerPackageManageWire.FREEZE : operation.getSelectedItemPosition() == 2
                ? TaskerPackageManageWire.FORCE_STOP : operation.getSelectedItemPosition() == 1
                ? TaskerPackageManageWire.DISABLE : TaskerPackageManageWire.ENABLE;
        if (!TaskerPackageQueryWire.validPackage(pkg)) {
            feedback.setText("Enter one literal package identifier. Variables and wildcards are unsupported.");
            return;
        }
        if (pkg.equals(getPackageName())) {
            feedback.setText("Cannot modify App Manager itself."); return;
        }
        if (TaskerPackageManageWire.FORCE_STOP.equals(selected)
                && TaskerPluginWire.TASKER_PACKAGE.equals(pkg)) {
            feedback.setText("Cannot force-stop Tasker itself."); return;
        }
        if (!TaskerPackageManageWire.ENABLE.equals(selected)
                && (TaskerPluginWire.TASKER_PACKAGE.equals(pkg)
                        || TaskerPluginWire.LOCALE_PACKAGE.equals(pkg))) {
            feedback.setText("Cannot disrupt the Tasker/Locale automation host."); return;
        }
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            if ((info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
                feedback.setText("System apps cannot be modified from this Tasker action."); return;
            }
            String proof = TaskerPackageManageAccess.forTrustedEditor(this, pkg, selected);
            setResult(RESULT_OK, TaskerPackageManageWire.editResult(
                    TaskerPackageManageWire.configuration(pkg, selected, proof)));
            finish();
        } catch (PackageManager.NameNotFoundException e) {
            feedback.setText("That user application isn't installed.");
        } catch (IOException | RuntimeException e) {
            feedback.setText("Cannot save the package operation configuration.");
        }
    }
}
