// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

import static org.junit.Assert.*;

import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.muntashirakon.AppManager.db.entity.Backup;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TaskerBackupInventoryTest {
    private static Backup make(String pkg, int user, int number) {
        Backup item = new Backup();
        item.packageName = pkg;
        item.userId = user;
        item.backupName = "snapshot_" + number;
        item.backupTime = 1000L + number;
        item.flags = number;
        item.versionCode = number;
        item.relativeDir = "private/path/never/exposed";
        return item;
    }

    @Test public void filtersOtherPackagesAndOtherUsers() throws Exception {
        Bundle vars = TaskerBackupInventory.summarize("com.example.target", 10,
                java.util.Arrays.asList(make("com.example.target", 10, 1),
                        make("com.example.target", 11, 2),
                        make("com.example.other", 10, 3)));
        assertEquals("available", vars.getString("%am_backup_status"));
        assertEquals("1", vars.getString("%am_backup_count"));
        assertEquals("1001", vars.getString("%am_backup_latest_ms"));
        String json = vars.getString("%am_backups_json");
        assertFalse(json.contains("private/path"));
        assertFalse(json.contains("snapshot_2"));
        assertFalse(json.contains("snapshot_3"));
        assertEquals(1, new JSONObject(json).getJSONArray("backups").length());
    }

    @Test public void emptyInventoryIsARealZero() throws Exception {
        Bundle vars = TaskerBackupInventory.summarize("com.example.target", 0,
                Collections.<Backup>emptyList());
        assertEquals("available", vars.getString("%am_backup_status"));
        assertEquals("0", vars.getString("%am_backup_count"));
        assertEquals("0", vars.getString("%am_backup_latest_ms"));
    }

    @Test public void capsPayloadWithoutLosingExactCount() throws Exception {
        List<Backup> list = new ArrayList<>();
        for (int i = 0; i < 30; ++i) list.add(make("com.example.target", 0, i));
        Bundle vars = TaskerBackupInventory.summarize("com.example.target", 0, list);
        assertEquals("30", vars.getString("%am_backup_count"));
        assertEquals("1029", vars.getString("%am_backup_latest_ms"));
        JSONObject json = new JSONObject(vars.getString("%am_backups_json"));
        assertTrue(json.getBoolean("truncated"));
        assertEquals(24, json.getJSONArray("backups").length());
    }

    @Test public void unavailableIsNeverLabeledAsNoBackups() {
        Bundle out = TaskerBackupInventory.unavailable("inventory_unavailable");
        assertEquals("inventory_unavailable", out.getString("%am_backup_status"));
        assertEquals("", out.getString("%am_backup_count"));
    }

    @Test public void malformedRequestDenied() {
        Bundle out = TaskerBackupInventory.summarize("%variable", 0, Collections.<Backup>emptyList());
        assertEquals("invalid_inventory_request", out.getString("%am_backup_status"));
    }
}
