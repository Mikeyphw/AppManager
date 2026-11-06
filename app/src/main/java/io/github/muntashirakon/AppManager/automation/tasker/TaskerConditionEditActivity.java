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

/** Shared implementation for Tasker's Event and State editors. */
public abstract class TaskerConditionEditActivity extends Activity {
    private static final String[] EVENT_LABELS = {"Profile started", "Profile completed", "Profile failed"};
    private static final String[] EVENT_VALUES = {"event.started", "event.success", "event.failed"};
    private static final String[] STATE_LABELS = {"Profile running", "Last profile succeeded", "Last profile failed"};
    private static final String[] STATE_VALUES = {"state.running", "state.success", "state.failed"};
    private Spinner choice;
    private TextView note;

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
        String[] values = isEvent() ? EVENT_VALUES : STATE_VALUES;
        for (int i = 0; i < values.length; i++) if (values[i].equals(old)) choice.setSelection(i);
        note = new TextView(this);
        note.setText(isEvent() ? "Tasker reacts when a profile operation changes." :
                "Tasker queries the most recently observed profile operation.");
        root.addView(note);
        LinearLayout footer = new LinearLayout(this);
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> finish());
        footer.addView(cancel);
        Button save = new Button(this);
        save.setText("Save to Tasker");
        save.setOnClickListener(v -> {
            try {
                String token = TaskerAccess.tokenForTrustedEditor(this);
                int index = choice.getSelectedItemPosition();
                Bundle config = TaskerPluginWire.configuration(values[index], null, null, token);
                setResult(RESULT_OK, TaskerPluginWire.editResult(config,
                        "App Manager · " + labels[index]));
                finish();
            } catch (IOException error) { note.setText("Could not save the Tasker configuration."); }
        });
        footer.addView(save);
        root.addView(footer);
        setContentView(root);
    }
}
