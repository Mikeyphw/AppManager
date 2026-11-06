// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import android.app.Activity;
import android.os.Bundle;
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

/** Native Tasker Event/State editor with an optional exact profile-ID filter. */
public abstract class TaskerConditionEditActivity extends Activity {
    private static final String[] EVENT_LABELS = {"Profile started", "Profile completed", "Profile failed"};
    private static final String[] EVENT_VALUES = {"event.started", "event.success", "event.failed"};
    private static final String[] STATE_LABELS = {"Profile running", "Last profile succeeded", "Last profile failed"};
    private static final String[] STATE_VALUES = {"state.running", "state.success", "state.failed"};
    private final List<String> filterIds = new ArrayList<>();
    private Spinner choice;
    private Spinner profileFilter;
    private TextView note;
    private Button save;
    private String priorProfile;

    protected abstract boolean isEvent();

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        if (!TaskerPluginWire.trustedEditor(getCallingPackage())
                || !(isEvent() ? TaskerPluginWire.EDIT_EVENT : TaskerPluginWire.EDIT_STATE)
                .equals(getIntent().getAction())) { finish(); return; }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText(isEvent() ? "App Manager · Event" : "App Manager · State");
        title.setTextSize(22);
        root.addView(title);
        choice = new Spinner(this);
        String[] labels = isEvent() ? EVENT_LABELS : STATE_LABELS;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        choice.setAdapter(adapter);
        root.addView(choice);
        Bundle previous = TaskerPluginWire.fromIntent(getIntent());
        String old = previous == null ? null : previous.getString(TaskerPluginWire.KEY_MODE);
        priorProfile = previous == null ? null : previous.getString(TaskerPluginWire.KEY_PROFILE);
        String[] values = isEvent() ? EVENT_VALUES : STATE_VALUES;
        for (int i = 0; i < values.length; i++) if (values[i].equals(old)) choice.setSelection(i);
        TextView filterTitle = new TextView(this);
        filterTitle.setText("Filter by profile");
        root.addView(filterTitle);
        profileFilter = new Spinner(this);
        filterIds.add(null); // first entry means any profile (also pre-R2D behavior)
        setProfiles(new String[] {"Any profile"});
        root.addView(profileFilter);
        note = new TextView(this);
        note.setText(isEvent() ? "Only events from the selected profile trigger Tasker." :
                "State refers to the selected profile's latest recorded operation.");
        root.addView(note);
        LinearLayout footer = new LinearLayout(this);
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> finish());
        footer.addView(cancel);
        save = new Button(this);
        save.setText("Save to Tasker");
        save.setOnClickListener(v -> save(values, labels));
        footer.addView(save);
        root.addView(footer);
        setContentView(root);
        loadProfiles();
    }

    private void setProfiles(String[] names) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileFilter.setAdapter(adapter);
    }

    private void loadProfiles() {
        save.setEnabled(false);
        new Thread(() -> {
            List<BaseProfile> profiles = new ArrayList<>();
            try {
                profiles.addAll(ProfileManager.getProfiles());
                profiles.sort(Comparator.comparing(p -> p.name.toLowerCase(java.util.Locale.ROOT)));
            } catch (Exception ignored) { }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                filterIds.clear();
                filterIds.add(null);
                List<String> labels = new ArrayList<>();
                labels.add("Any profile");
                int selected = 0;
                for (BaseProfile profile : profiles) {
                    if (!TaskerPluginWire.validProfile(profile.profileId)) continue;
                    filterIds.add(profile.profileId);
                    labels.add(profile.name);
                    if (profile.profileId.equals(priorProfile)) selected = filterIds.size() - 1;
                }
                if (priorProfile != null && selected == 0 && TaskerPluginWire.validProfile(priorProfile)) {
                    filterIds.add(priorProfile);
                    labels.add("Missing profile: " + priorProfile);
                    selected = filterIds.size() - 1;
                }
                setProfiles(labels.toArray(new String[0]));
                profileFilter.setSelection(selected);
                save.setEnabled(true);
            });
        }, "tasker-load-condition-profiles").start();
    }

    private void save(String[] values, String[] labels) {
        int selection = profileFilter.getSelectedItemPosition();
        int index = choice.getSelectedItemPosition();
        if (selection < 0 || selection >= filterIds.size() || index < 0 || index >= values.length) return;
        String id = filterIds.get(selection);
        try {
            String token = TaskerAccess.tokenForTrustedEditor(this);
            Bundle config = TaskerPluginWire.configuration(values[index], id, null, token);
            String blurb = "App Manager · " + labels[index] + (id == null ? " · any profile" : " · " + id);
            setResult(RESULT_OK, TaskerPluginWire.editResult(config, blurb));
            finish();
        } catch (IOException error) { note.setText("Could not save the Tasker configuration."); }
    }
}
