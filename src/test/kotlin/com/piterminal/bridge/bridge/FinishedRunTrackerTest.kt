package com.piterminal.bridge.bridge

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FinishedRunTrackerTest {
    private fun event(runId: String = "run", outcome: String = "completed") = BridgeProtocol.frame("event").apply {
        addProperty("event", "agent.finished")
        addProperty("runId", runId)
        addProperty("sessionId", "session")
        addProperty("outcome", outcome)
    }

    @Test fun `duplicates across reconnect do not alert but other runtimes and tabs can`() {
        val tracker = FinishedRunTracker()
        assertTrue(tracker.accept("tab", "runtime", event()))
        assertFalse(tracker.accept("tab", "runtime", event()))
        assertTrue(tracker.accept("tab", "runtime", event("next", "error")))
        assertTrue(tracker.accept("tab", "new-runtime", event()))
        assertTrue(tracker.accept("other-tab", "runtime", event()))
    }

    @Test fun `aborted runs remain silent even if the same id later claims completion`() {
        val tracker = FinishedRunTracker()
        assertFalse(tracker.accept("tab", "runtime", event(outcome = "aborted")))
        assertFalse(tracker.accept("tab", "runtime", event()))
    }

    @Test fun `snapshots operation results and malformed completions never alert`() {
        val tracker = FinishedRunTracker()
        val invalid = listOf(
            BridgeProtocol.frame("response").apply { addProperty("status", "succeeded") },
            event().apply { addProperty("event", "session.snapshot") },
            event().apply { remove("sessionId") },
            event(outcome = "unknown"), event(runId = ""),
            event().apply { addProperty("runId", 123) }
        )
        invalid.forEach { assertFalse(tracker.accept("tab", "runtime", it)) }
        assertTrue(tracker.accept("tab", "runtime", event()))
    }

    @Test fun `event memory is bounded and retains recent duplicates`() {
        val tracker = FinishedRunTracker()
        repeat(256) { assertTrue(tracker.accept("tab", "runtime", event("run-$it"))) }
        assertFalse(tracker.accept("tab", "runtime", event("run-255")))
        // The contract deliberately remembers only the most recent 128 runs.
        assertTrue(tracker.accept("tab", "runtime", event("run-0")))
    }
}
