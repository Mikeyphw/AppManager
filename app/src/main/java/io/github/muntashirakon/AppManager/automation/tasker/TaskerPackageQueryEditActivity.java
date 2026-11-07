// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.IOException;

/** Separate Tasker editor: queries do not acquire profile-operation authority. */
public final class TaskerPackageQueryEditActivity extends Activity {
    private EditText packageName;
    private CheckBox dynamic;
    private Button save;
    private TextView hint;
    private boolean canReplace;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        if (!TaskerPluginWire.EDIT_ACTION.equals(getIntent().getAction()) ||
                !TaskerPluginWire.trustedEditor(getCallingPackage())) {
            finish();
            return;
        }
        canReplace = (getIntent().getIntExtra(TaskerPluginWire.EXTRA_HOST_CAPABILITIES, 0)
                & TaskerPluginWire.CAPABILITY_VARIABLE_REPLACEMENT) != 0;
        Bundle previous = TaskerPluginWire.fromIntent(getIntent());
        boolean oldDynamic = false;
        String oldPackage = "";
        try {
            if (TaskerPackageQueryWire.validConfiguration(previous)) {
                oldDynamic = previous.getBoolean(TaskerPackageQueryWire.KEY_DYNAMIC, false);
                oldPackage = previous.getString(TaskerPackageQueryWire.KEY_PACKAGE, "");
            }
        } catch (RuntimeException ignored) { /* malformed previous configuration */ }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(20 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, p);
        TextView title = new TextView(this);
        title.setText("App Manager · Query Package");
        title.setTextSize(22);
        root.addView(title);
        TextView label = new TextView(this);
        label.setText("Android package name");
        root.addView(label);
        packageName = new EditText(this);
        packageName.setSingleLine(true);
        packageName.setHint("com.example.app or %package");
        packageName.setText(oldPackage);
        root.addView(packageName);
        dynamic = new CheckBox(this);
        dynamic.setText("Use Tasker variable");
        dynamic.setEnabled(canReplace);
        dynamic.setChecked(canReplace && oldDynamic);
        root.addView(dynamic);
        hint = new TextView(this);
        root.addView(hint);
        LinearLayout footer = new LinearLayout(this);
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> finish());
        footer.addView(cancel);
        save = new Button(this);
        save.setText("Save to Tasker");
        save.setOnClickListener(v -> save());
        footer.addView(save);
        root.addView(footer);
        setContentView(root);
        dynamic.setOnCheckedChangeListener((button, checked) -> refresh());
        packageName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh(); }
            @Override public void afterTextChanged(Editable e) { }
        });
        refresh();
    }

    private void refresh() {
        String text = packageName.getText().toString().trim();
        boolean variable = dynamic.isChecked();
        boolean valid = variable ? canReplace && TaskerPackageQueryWire.validVariable(text)
                : TaskerPackageQueryWire.validPackage(text);
        save.setEnabled(valid);
        hint.setText(variable
                ? "Tasker replaces this single variable before the package is queried."
                : "Read-only query: installed state, version, UID, installer, enabled/system flags.");
    }

    private void save() {
        String target = packageName.getText().toString().trim();
        boolean variable = dynamic.isChecked();
        if (!(variable ? canReplace && TaskerPackageQueryWire.validVariable(target)
                : TaskerPackageQueryWire.validPackage(target))) return;
        try {
            String capability = TaskerPackageQueryAccess.tokenForTrustedEditor(this);
            Bundle config = TaskerPackageQueryWire.saved(target, capability, variable);
            setResult(RESULT_OK, TaskerPackageQueryWire.editResult(config,
                    "App Manager · Query " + target));
            finish();
        } catch (IOException | RuntimeException e) {
            hint.setText("Could not save this package query configuration.");
        }
    }
}
