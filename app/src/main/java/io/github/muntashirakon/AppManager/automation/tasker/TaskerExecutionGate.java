// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.tasker;

/** Serializes foreground-service and JobScheduler profile application in this process. */
public final class TaskerExecutionGate {
    public static final Object LOCK = new Object();
    private TaskerExecutionGate() { }
}
