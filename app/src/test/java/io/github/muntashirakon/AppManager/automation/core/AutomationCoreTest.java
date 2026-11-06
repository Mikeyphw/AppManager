// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.muntashirakon.AppManager.automation.core;

import org.junit.Test;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class AutomationCoreTest {
    private static final String ID = "aabbccddeeff00112233445566778899";
    private static final String DIGEST = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899";
    private static final String OTHER_DIGEST = "bbbbccddeeff00112233445566778899aabbccddeeff00112233445566778899";
    private static final String APP = "net.dinglisch.android.taskerm";
    private static final long NOW = 100_000L;

    @Test public void invalidRequestsFailClosed() {
        assertRejected(() -> AutomationRequest.capabilities("../oops"));
        assertRejected(() -> AutomationRequest.applyProfile(ID, "../etc", "on"));
        assertRejected(() -> AutomationRequest.applyProfile(ID, "allowed", "toggle"));
        assertRejected(() -> AutomationRequest.applyProfile(ID, "..", "on"));
        assertRejected(() -> AutomationRequest.applyProfile(ID, "", "off"));
    }

    @Test public void policyRequiresNarrowMatchingGrant() {
        AutomationCaller caller = new AutomationCaller(10001, APP, DIGEST);
        AutomationRequest request = AutomationRequest.applyProfile(ID, "privacy", "on");
        AutomationGrant grant = new AutomationGrant(10001, APP, DIGEST, AutomationRequest.APPLY_PROFILE,
                "privacy", NOW + 1000);
        assertFalse(AutomationPolicy.permits(caller, request, Collections.emptyList(), NOW));
        assertTrue(AutomationPolicy.permits(caller, request, Collections.singletonList(grant), NOW));
        assertFalse(AutomationPolicy.permits(caller, request, Collections.singletonList(grant), NOW + 1000));
        assertFalse(AutomationPolicy.permits(new AutomationCaller(10002, APP, OTHER_DIGEST),
                request, Collections.singletonList(grant), NOW));
        assertFalse(AutomationPolicy.permits(caller, AutomationRequest.applyProfile(ID, "other", "on"),
                Collections.singletonList(grant), NOW));
        assertFalse(AutomationPolicy.permits(caller, AutomationRequest.capabilities(ID),
                Collections.singletonList(grant), NOW));
        assertRejected(() -> new AutomationGrant(10001, APP, DIGEST, AutomationRequest.APPLY_PROFILE,
                "*", NOW + 1000));
    }

    @Test public void executorNeverCalledOnDeniedRequest() {
        AtomicInteger count = new AtomicInteger();
        AutomationDispatcher dispatcher = new AutomationDispatcher(
                caller -> Collections.emptyList(), (caller, request) -> true, request -> {
                    count.incrementAndGet();
                    return AutomationResult.queued(request.requestId, "job-01");
                });
        AutomationResult result = dispatcher.dispatch(new AutomationCaller(10001, APP, DIGEST),
                AutomationRequest.applyProfile(ID, "privacy", "on"), NOW);
        assertEquals(AutomationResult.Status.DENIED, result.status);
        assertEquals(0, count.get());
    }

    @Test public void authorizedRequestExecutesExactlyOnce() {
        AtomicInteger count = new AtomicInteger();
        AutomationGrant grant = new AutomationGrant(10001, APP, DIGEST, AutomationRequest.APPLY_PROFILE,
                "privacy", NOW + 1000);
        AutomationDispatcher dispatcher = new AutomationDispatcher(
                caller -> Collections.singletonList(grant), (caller, request) -> true, request -> {
                    count.incrementAndGet();
                    return AutomationResult.queued(request.requestId, "job-01");
                });
        AutomationResult result = dispatcher.dispatch(new AutomationCaller(10001, APP, DIGEST),
                AutomationRequest.applyProfile(ID, "privacy", "on"), NOW);
        assertEquals(AutomationResult.Status.QUEUED, result.status);
        assertEquals("job-01", result.jobId);
        assertEquals(1, count.get());
    }

    @Test public void exceptionsAndMismatchedIdsCannotLeak() {
        AutomationGrant grant = new AutomationGrant(10001, APP, DIGEST, AutomationRequest.APPLY_PROFILE,
                "privacy", NOW + 1000);
        AutomationCaller caller = new AutomationCaller(10001, APP, DIGEST);
        AutomationRequest request = AutomationRequest.applyProfile(ID, "privacy", "off");
        AutomationDispatcher throwing = new AutomationDispatcher(c -> Collections.singletonList(grant), (c, r) -> true,
                r -> { throw new IllegalStateException("private path/key"); });
        assertEquals("OPERATION_FAILED", throwing.dispatch(caller, request, NOW).code);
        AutomationDispatcher spoofing = new AutomationDispatcher(c -> Collections.singletonList(grant), (c, r) -> true,
                r -> AutomationResult.success("00000000000000000000000000000000"));
        assertEquals("OPERATION_FAILED", spoofing.dispatch(caller, request, NOW).code);
    }

    @Test public void replayGuardRefusesSecondExecution() {
        AutomationGrant grant = new AutomationGrant(10001, APP, DIGEST, AutomationRequest.APPLY_PROFILE,
                "privacy", NOW + 1000);
        Set<String> seen = new HashSet<>();
        AtomicInteger executed = new AtomicInteger();
        AutomationDispatcher dispatcher = new AutomationDispatcher(
                c -> Collections.singletonList(grant),
                (c, r) -> seen.add(c.packageName + ":" + c.signingCertSha256 + ":" + r.requestId),
                r -> { executed.incrementAndGet(); return AutomationResult.queued(r.requestId, "job-01"); });
        AutomationCaller caller = new AutomationCaller(10001, APP, DIGEST);
        AutomationRequest request = AutomationRequest.applyProfile(ID, "privacy", "on");
        assertEquals(AutomationResult.Status.QUEUED, dispatcher.dispatch(caller, request, NOW).status);
        assertEquals("DUPLICATE_REQUEST", dispatcher.dispatch(caller, request, NOW).code);
        assertEquals(1, executed.get());
    }

    private interface Checked { void run(); }
    private static void assertRejected(Checked test) {
        try { test.run(); fail("expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { /* good */ }
    }
}
