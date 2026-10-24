package li.songe.gkd.qingping

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class QingpingRuntimeTest {
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    @Test fun duplicate_submission_and_cancellation_release_the_gate() {
        val dispatcher = QueuedDispatcher()
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + dispatcher)
        val runner = QingpingQueryRunner()
        var executions = 0
        assertTrue(runner.tryStart(scope, dispatcher) { executions++ })
        assertFalse(runner.tryStart(scope, dispatcher) { executions++ })
        dispatcher.drain()
        assertEquals(1, executions)
        assertTrue(runner.tryStart(scope, dispatcher) { executions++ })
        parent.cancel()
        dispatcher.drain()
        val newScope = CoroutineScope(SupervisorJob() + dispatcher)
        assertTrue(runner.tryStart(newScope, dispatcher) { executions++ })
        dispatcher.drain()
        assertEquals(2, executions)
        newScope.cancel()
    }

    @Test fun failed_query_releases_the_gate() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, _ -> })
        val runner = QingpingQueryRunner()
        assertTrue(runner.tryStart(scope, dispatcher) { error("expected") })
        dispatcher.drain()
        assertTrue(runner.tryStart(scope, dispatcher) {})
        dispatcher.drain()
        scope.cancel()
    }

    @Test fun only_the_latest_followup_runs_and_cancel_removes_it() = runBlocking {
        val followup = QingpingFollowup()
        var calls = 0
        followup.replace(this, 30) { calls += 1 }
        followup.replace(this, 1) { calls += 10 }
        delay(60)
        assertEquals(10, calls)
        followup.replace(this, 1) { calls += 100 }
        followup.cancel()
        delay(20)
        assertEquals(10, calls)
    }

    @Test fun gesture_rejection_cancellation_completion_and_timeout() = runBlocking {
        assertFalse(awaitQingpingGesture(30) { false })
        assertFalse(awaitQingpingGesture(30) { done -> done(false); true })
        assertTrue(awaitQingpingGesture(30) { done -> done(true); done(false); true })
        assertFalse(awaitQingpingGesture(10) { true })
        var callback: ((Boolean) -> Unit)? = null
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            awaitQingpingGesture(500) { done -> callback = done; true }
        }
        pending.cancelAndJoin()
        callback!!(true) // A late platform callback must not resume a cancelled action.
        assertTrue(pending.isCancelled)
    }

    @Test fun invalid_geometry_is_rejected_without_breaking_explicit_offsets() {
        assertFalse(QingpingActionBounds.allows(0, 20, 10, 10, true, 5f, 15f))
        assertFalse(QingpingActionBounds.allows(0, 0, 10, 10, false, 5f, 5f))
        assertFalse(QingpingActionBounds.allows(0, 0, 10, 10, true, Float.NaN, 5f))
        assertTrue(QingpingActionBounds.allows(0, 0, 10, 10, true, 15f, 5f))
        assertTrue(QingpingActionBounds.allows(0, 0, 10, 10, true, 5f, 5f))
    }

    @Test fun missing_rule_or_loading_page_never_confirms_exit() {
        assertEquals(QingpingOutcome.UNKNOWN, QingpingOutcomePolicy.decide(true, false, true, false))
        assertEquals(QingpingOutcome.UNKNOWN, QingpingOutcomePolicy.decide(true, true, false, false))
        assertEquals(QingpingOutcome.EXTERNAL, QingpingOutcomePolicy.decide(false, true, true, false))
        assertEquals(QingpingOutcome.REMAINS, QingpingOutcomePolicy.decide(true, true, true, true))
        assertEquals(QingpingOutcome.EXIT_GONE, QingpingOutcomePolicy.decide(true, true, true, false))
    }

    @Test fun incomplete_or_budget_truncated_tree_is_unknown_even_with_text() {
        data class Node(val label: Boolean, val children: List<Node?> = emptyList())
        fun readable(root: Node, budget: Int = 256) = qingpingReadableTree(root,
            { it.children.size }, { n, i -> n.children[i] }, { it.label }, budget)
        assertFalse(readable(Node(true, listOf(null, Node(true)))))
        assertFalse(readable(Node(true, listOf(Node(true))), budget = 1))
        assertFalse(readable(Node(false, listOf(Node(false)))))
        assertTrue(readable(Node(false, listOf(Node(true)))))
    }

    @Test fun action_rechecks_pause_screen_window_and_deadline_after_wait() {
        assertFalse(QingpingPolicy.canAct("splash", false, true, 100, true, false, true))
        assertFalse(QingpingPolicy.canAct("splash", false, false, 100, true, true, true))
        assertFalse(QingpingPolicy.canAct("splash", false, true, 15001, true, true, true))
        assertFalse(QingpingPolicy.canAct("splash", false, true, 100, true, true, false))
        assertTrue(QingpingPolicy.canAct("splash", false, true, 100, true, true, true))
    }

    @Test fun restore_rereads_other_services_and_stops_after_user_disables_it() = runBlocking {
        val current = mutableSetOf("other", "self")
        var enabled = true
        var running = false
        var calls = 0
        val policy = QingpingRestorePolicy()
        policy.restore(
            permitted = { enabled }, running = { running },
            ensureListed = { current.add("self"); calls++ },
            awaitBinding = { current.add("new-other"); enabled = false },
        )
        assertEquals(1, calls)
        assertTrue(current.containsAll(listOf("other", "new-other", "self")))
        policy.restore({ false }, { false }, { fail("must not write") }, {})
        running = true
        policy.restore({ true }, { running }, { fail("already running") }, {})
        calls = 0
        policy.restore({ true }, { false }, { calls++ }, {})
        assertEquals(3, calls)
    }

    @Test fun user_stop_is_last_even_if_recovery_writes_after_stop_intent() = runBlocking {
        val mutex = kotlinx.coroutines.sync.Mutex(locked = true)
        var manualOff = true
        val enabled = mutableSetOf("other")
        val stop = launch(start = CoroutineStart.UNDISPATCHED) {
            qingpingSerializedStop(mutex, { manualOff }) { enabled.remove("self") }
        }
        enabled.add("self") // A previously permitted provider write finishes late.
        mutex.unlock()
        stop.join()
        assertEquals(setOf("other"), enabled)
        manualOff = false
        enabled.add("self")
        qingpingSerializedStop(mutex, { manualOff }) { enabled.remove("self") }
        assertTrue(enabled.contains("self")) // A newer explicit start supersedes old stop work.
    }
}
