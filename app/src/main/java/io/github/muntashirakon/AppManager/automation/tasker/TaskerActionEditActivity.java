// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
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
    private static final String[] ACTIONS = {"Apply profile", "Last profile result"};
    private static final String[] STATES = {"On", "Off"};
    private final List<BaseProfile> profiles = new ArrayList<>();
    private Spinner operation;
    private Spinner profile;
    private Spinner state;
    private TextView feedback;
    private Button save;
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText("App Manager · Tasker");
        title.setTextSize(22);
        root.addView(title);
        operation = field(root, "Action", ACTIONS);
        profile = field(root, "Profile", new String[] {"Loading profiles…"});
        this.state = field(root, "Profile state", STATES);
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
        operation.setSelection(TaskerPluginWire.MODE_STATUS.equals(previousMode) ? 1 : 0);
        if (old != null && TaskerPluginWire.STATE_OFF.equals(old.getString(TaskerPluginWire.KEY_STATE))) {
            this.state.setSelection(1);
        }
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
        if (operation == null || profile == null || save == null) return;
        boolean apply = operation.getSelectedItemPosition() == 0;
        profile.setVisibility(apply ? View.VISIBLE : View.GONE);
        state.setVisibility(apply ? View.VISIBLE : View.GONE);
        save.setEnabled(!apply || !profiles.isEmpty());
        feedback.setText(apply ? (profiles.isEmpty() ? "Create an App Manager profile first." :
                "Runs the selected profile. The action reports queued; Events report the final outcome.") :
                "Returns the latest profile result as %am_* variables.");
    }

    private void save() {
        String mode = operation.getSelectedItemPosition() == 0
                ? TaskerPluginWire.MODE_APPLY : TaskerPluginWire.MODE_STATUS;
        BaseProfile selected = null;
        if (TaskerPluginWire.MODE_APPLY.equals(mode)) {
            int index = profile.getSelectedItemPosition();
            if (index < 0 || index >= profiles.size()) return;
            selected = profiles.get(index);
            if (!TaskerPluginWire.validProfile(selected.profileId)) {
                feedback.setText("This profile ID is unsupported by Tasker v1."); return;
            }
        }
        try {
            String token = TaskerAccess.tokenForTrustedEditor(this);
            String state = selected == null ? null : (this.state.getSelectedItemPosition() == 1
                    ? TaskerPluginWire.STATE_OFF : TaskerPluginWire.STATE_ON);
            Bundle config = TaskerPluginWire.configuration(mode,
                    selected != null ? selected.profileId : null, state, token);
            String blurb = selected == null ? "App Manager · Last profile result" :
                    "App Manager · " + selected.name + " · " + state;
            setResult(RESULT_OK, TaskerPluginWire.editResult(config, blurb));
            finish();
        } catch (IOException error) { feedback.setText("Could not save the Tasker configuration."); }
    }
}
