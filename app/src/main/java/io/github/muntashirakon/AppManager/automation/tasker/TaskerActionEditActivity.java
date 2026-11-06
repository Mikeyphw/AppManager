// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.text.TextWatcher;
import android.text.Editable;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.muntashirakon.AppManager.profiles.ProfileManager;
import io.github.muntashirakon.AppManager.profiles.struct.BaseProfile;

/** Shown directly inside Tasker's Plugin editor. No separate App Manager grant UI. */
public final class TaskerActionEditActivity extends Activity {
    private static final String[] ACTIONS = {"Apply profile", "Last profile result", "Recent execution history", "Cancel queued request"};
    private static final String[] STATES = {"On", "Off"};
    private final List<BaseProfile> profiles = new ArrayList<>();
    private Spinner operation;
    private Spinner profile;
    private Spinner profileSource;
    private EditText variable;
    private EditText cancelRequest;
    private boolean canReplace;
    private Spinner state;
    private TextView feedback;
    private Button save;
    private CheckBox waitForCompletion;
    private boolean canWait;
    private String initialProfile;

    @Override
    protected void onCreate(@Nullable Bundle stateBundle) {
        super.onCreate(stateBundle);
        setResult(RESULT_CANCELED);
        if (!TaskerPluginWire.EDIT_ACTION.equals(getIntent().getAction())
                || !TaskerPluginWire.trustedEditor(getCallingPackage())) { finish(); return; }

        Bundle old = TaskerPluginWire.fromIntent(getIntent());
        String previousMode = old != null ? old.getString(TaskerPluginWire.KEY_MODE) : null;
        initialProfile = old != null ? old.getString(TaskerPluginWire.KEY_PROFILE) : null;
        canReplace = (getIntent().getIntExtra(TaskerPluginWire.EXTRA_HOST_CAPABILITIES, 0)
                & TaskerPluginWire.CAPABILITY_VARIABLE_REPLACEMENT) != 0;
        canWait = (getIntent().getIntExtra(TaskerPluginWire.EXTRA_HOST_CAPABILITIES, 0)
                & TaskerPluginWire.CAPABILITY_SYNCHRONOUS) != 0;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText("App Manager · Tasker");
        title.setTextSize(22);
        root.addView(title);
        operation = field(root, "Action", ACTIONS);
        profileSource = field(root, "Profile selection", new String[] {
                "Choose existing profile", "Use Tasker variable"});
        profile = field(root, "Profile", new String[] {"Loading profiles…"});
        variable = new EditText(this);
        variable.setSingleLine(true);
        variable.setHint("%profile_id");
        variable.setTextSize(16);
        root.addView(variable);
        cancelRequest = new EditText(this);
        cancelRequest.setSingleLine(true);
        cancelRequest.setTextSize(16);
        cancelRequest.setHint("%am_request_id or 32-character request ID");
        if (old != null) cancelRequest.setText(old.getString(TaskerPluginWire.KEY_REQUEST, ""));
        cancelRequest.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh(); }
            @Override public void afterTextChanged(Editable value) { }
        });
        root.addView(cancelRequest);
        if (old != null && old.getBoolean(TaskerPluginWire.KEY_DYNAMIC, false)) {
            profileSource.setSelection(1);
            variable.setText(initialProfile);
        }
        variable.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh(); }
            @Override public void afterTextChanged(Editable value) { }
        });
        this.state = field(root, "Profile state", STATES);
        waitForCompletion = new CheckBox(this);
        waitForCompletion.setText("Wait for final result in Tasker");
        waitForCompletion.setChecked(canWait && (old == null
                || old.getBoolean(TaskerPluginWire.KEY_WAIT, false)));
        waitForCompletion.setEnabled(canWait);
        root.addView(waitForCompletion);
        feedback = new TextView(this);
        feedback.setText("Choose a profile. Tasker will run it without extra approval screens.");
        root.addView(feedback);
        LinearLayout footer = new LinearLayout(this);
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> finish());
        footer.addView(cancel);
        save = new Button(this);
        save.setText("Save to Tasker");
        save.setEnabled(false);
        save.setOnClickListener(v -> save());
        footer.addView(save);
        root.addView(footer);
        setContentView(root);
        operation.setSelection(TaskerPluginWire.MODE_STATUS.equals(previousMode) ? 1 :
                TaskerPluginWire.MODE_HISTORY.equals(previousMode) ? 2 :
                TaskerPluginWire.MODE_CANCEL.equals(previousMode) ? 3 : 0);
        if (old != null && TaskerPluginWire.STATE_OFF.equals(old.getString(TaskerPluginWire.KEY_STATE))) {
            this.state.setSelection(1);
        }
        profileSource.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { refresh(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { refresh(); }
        });
        operation.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { refresh(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { refresh(); }
        });
        loadProfiles();
    }

    private Spinner field(LinearLayout root, String label, String[] values) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(15);
        root.addView(text);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        root.addView(spinner);
        return spinner;
    }

    private void loadProfiles() {
        new Thread(() -> {
            List<BaseProfile> loaded = new ArrayList<>();
            try {
                loaded.addAll(ProfileManager.getProfiles());
                loaded.sort(Comparator.comparing(x -> x.name.toLowerCase(java.util.Locale.ROOT)));
            } catch (Exception ignored) { }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                profiles.clear();
                profiles.addAll(loaded);
                List<String> labels = new ArrayList<>();
                int selection = 0;
                for (int i = 0; i < profiles.size(); i++) {
                    labels.add(profiles.get(i).name);
                    if (profiles.get(i).profileId.equals(initialProfile)) selection = i;
                }
                if (labels.isEmpty()) labels.add("No profiles available");
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                profile.setAdapter(adapter);
                profile.setSelection(selection);
                refresh();
            });
        }, "tasker-load-profiles").start();
    }

    private void refresh() {
        if (operation == null || profile == null || save == null || profileSource == null) return;
        boolean apply = operation.getSelectedItemPosition() == 0;
        boolean cancel = operation.getSelectedItemPosition() == 3;
        boolean dynamic = apply && profileSource.getSelectedItemPosition() == 1;
        String request = cancelRequest.getText().toString().trim();
        boolean requestValid = TaskerPluginWire.validRequestId(request)
                || (canReplace && TaskerPluginWire.validVariableReference(request));
        profileSource.setVisibility(apply ? View.VISIBLE : View.GONE);
        profile.setVisibility(apply && !dynamic ? View.VISIBLE : View.GONE);
        variable.setVisibility(dynamic ? View.VISIBLE : View.GONE);
        cancelRequest.setVisibility(cancel ? View.VISIBLE : View.GONE);
        state.setVisibility(apply ? View.VISIBLE : View.GONE);
        waitForCompletion.setVisibility(apply && canWait ? View.VISIBLE : View.GONE);
        boolean validVariable = TaskerPluginWire.validVariableReference(variable.getText().toString().trim());
        save.setEnabled(cancel ? requestValid : !apply || (dynamic ? canReplace && validVariable : !profiles.isEmpty()));
        if (cancel) {
            feedback.setText("Cancel a pending request by ID. Running work cannot be interrupted safely.");
            return;
        }
        if (operation.getSelectedItemPosition() == 2) {
            feedback.setText("Returns up to 20 recent requests in %am_history (JSON).");
            return;
        }
        feedback.setText(dynamic ? (canReplace
                ? "Tasker substitutes the variable on each run. It must contain an existing profile ID."
                : "This Tasker host does not advertise variable substitution.")
                : apply ? (profiles.isEmpty() ? "Create a profile first, or use a Tasker variable." :
                "Runs the selected profile. Wait for completion returns the final result when supported.") :
                "Returns the latest profile result as %am_* variables.");
    }

    private void save() {
        int choice = operation.getSelectedItemPosition();
        String mode = choice == 0 ? TaskerPluginWire.MODE_APPLY :
                choice == 1 ? TaskerPluginWire.MODE_STATUS :
                choice == 2 ? TaskerPluginWire.MODE_HISTORY : TaskerPluginWire.MODE_CANCEL;
        boolean apply = TaskerPluginWire.MODE_APPLY.equals(mode);
        boolean dynamic = apply && profileSource.getSelectedItemPosition() == 1;
        BaseProfile selected = null;
        String profileId = null;
        if (dynamic) {
            profileId = variable.getText().toString().trim();
            if (!canReplace || !TaskerPluginWire.validVariableReference(profileId)) {
                feedback.setText("Enter one valid Tasker variable (for example %profile_id).");
                return;
            }
        } else if (apply) {
            int index = profile.getSelectedItemPosition();
            if (index < 0 || index >= profiles.size()) return;
            selected = profiles.get(index);
            profileId = selected.profileId;
            if (!TaskerPluginWire.validProfile(profileId)) {
                feedback.setText("This profile ID is unsupported by Tasker v1."); return;
            }
        }
        try {
            String token = TaskerAccess.tokenForTrustedEditor(this);
            String state = !apply ? null : (this.state.getSelectedItemPosition() == 1
                    ? TaskerPluginWire.STATE_OFF : TaskerPluginWire.STATE_ON);
            boolean wait = apply && canWait && waitForCompletion.isChecked();
            String request = cancelRequest.getText().toString().trim();
            if (TaskerPluginWire.MODE_CANCEL.equals(mode)) {
                dynamic = TaskerPluginWire.validVariableReference(request);
                if (!TaskerPluginWire.validRequestId(request) && !(dynamic && canReplace)) return;
            }
            Bundle config = TaskerPluginWire.configuration(mode, profileId, state, token, wait, dynamic);
            if (TaskerPluginWire.MODE_CANCEL.equals(mode)) config.putString(TaskerPluginWire.KEY_REQUEST, request);
            String blurb = TaskerPluginWire.MODE_CANCEL.equals(mode) ? "App Manager · Cancel queued request" :
                    TaskerPluginWire.MODE_HISTORY.equals(mode) ? "App Manager · Recent execution history" :
                    !apply ? "App Manager · Last profile result" :
                    "App Manager · " + (dynamic ? profileId : selected.name) + " · " + state;
            setResult(RESULT_OK, TaskerPluginWire.editResult(config, blurb, canWait));
            finish();
        } catch (IOException error) { feedback.setText("Could not save the Tasker configuration."); }
    }
}
