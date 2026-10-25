package li.songe.gkd.qingping

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class QingpingSchedulingTest {
    private data class Event(val id: Int, val kind: String, val state: Boolean = false)
    private fun buffer(capacity: Int = 64) = QingpingEventBuffer<Event>(capacity,
        same = { a, b -> a.kind == b.kind }, priority = { it.state })

    @Test fun interleaved_events_are_consumed_once_in_order() {
        val events = buffer()
        val input = listOf(Event(1, "A"), Event(2, "B", true), Event(3, "A"))
        input.forEach(events::offer)
        val actual = generateSequence { events.takeBatch().takeIf { it.isNotEmpty() } }.flatten().toList()
        assertEquals(input, actual)
        assertFalse(events.hasPending())
    }

    @Test fun overload_preserves_window_state_and_latest_content() {
        val events = buffer(4)
        events.offer(Event(0, "window", true))
        repeat(10000) { events.offer(Event(it + 1, "content")) }
        assertEquals(4, events.size())
        assertEquals(listOf(0), events.takeBatch().map { it.id })
        assertEquals(listOf(9998, 9999, 10000), events.takeBatch().map { it.id })
    }

    @Test fun window_only_overload_keeps_the_latest_states_in_order() {
        val events = buffer(2)
        repeat(10) { events.offer(Event(it, "window", true)) }
        assertEquals(listOf(8, 9), events.takeBatch().map { it.id })
        events.offer(Event(10, "content"))
        events.clear()
        assertFalse(events.hasPending())
    }

    @Test fun query_event_storm_retains_only_the_latest_two_nodes() {
        var comparisons = 0
        val events = QingpingQueryEvents<Event> { a, b -> comparisons++; a.kind == b.kind }
        repeat(10000) { events.addAll(listOf(Event(it, "content"))) }
        assertTrue(events.hasPending())
        assertEquals(listOf(9998, 9999), events.take()?.map { it.id })
        assertTrue("Comparison cost must stay linear", comparisons <= 20000)
        assertFalse(events.hasPending())
    }

    @Test fun mixed_query_events_require_one_root_query_and_release_references() {
        val events = QingpingQueryEvents<Event> { a, b -> a.kind == b.kind }
        events.addAll(listOf(Event(1, "A"), Event(2, "B"), Event(3, "A")))
        assertTrue(events.hasPending())
        assertNull(events.take())
        assertFalse(events.hasPending())
        events.addAll(listOf(Event(4, "C")))
        assertEquals(listOf(4), events.take()?.map { it.id })
        events.addAll(listOf(Event(5, "D")))
        events.clear()
        assertFalse(events.hasPending())
    }

    @Test fun timed_out_blocking_read_keeps_its_slot_until_it_really_finishes() = runBlocking {
        val gate = QingpingReadGate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            val first = async { gate.read(scope, Dispatchers.IO, 100) {
                calls.incrementAndGet(); entered.countDown(); release.await(5, TimeUnit.SECONDS); "late"
            } }
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            assertNull(first.await())
            repeat(100) { assertNull(gate.read(scope, Dispatchers.IO, 100) { calls.incrementAndGet(); "bad" }) }
            assertEquals(1, calls.get())
            assertNull(gate.tryRead { fail("Synchronous reads must share the slot"); "bad" })
            release.countDown()
            var result: String? = null
            withTimeout(2000) { while (result == null) { result = gate.read(scope, Dispatchers.IO, 500) { "fresh" }; yield() } }
            assertEquals("fresh", result)
        } finally { release.countDown(); scope.cancel() }
    }

    @Test fun cancelling_waiter_never_frees_a_running_blocking_read() = runBlocking {
        val gate = QingpingReadGate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val first = launch { gate.read(scope, Dispatchers.IO, 5000) {
                entered.countDown(); release.await(5, TimeUnit.SECONDS); "late"
            } }
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            first.cancelAndJoin()
            assertNull(gate.read(scope, Dispatchers.IO, 100) { fail("Must not overlap"); "bad" })
        } finally { release.countDown(); scope.cancel() }
    }

    @Test fun failed_reads_release_the_slot() = runBlocking {
        val gate = QingpingReadGate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            try { gate.read<String>(scope, Dispatchers.IO, 500) { error("expected") }; fail("Expected failure") }
            catch (e: IllegalStateException) { assertEquals("expected", e.message) }
            assertEquals("next", gate.read(scope, Dispatchers.IO, 500) { "next" })
        } finally { scope.cancel() }
    }

    @Test fun inactive_or_exhausted_rules_never_schedule_polling() {
        assertNull(qingpingFollowupDelay(listOf(QingpingPollingRule(false, true, 10000)), 100, 100))
        assertNull(qingpingFollowupDelay(listOf(QingpingPollingRule(true, false, 10000)), 100, 100))
        assertNull(qingpingFollowupDelay(listOf(QingpingPollingRule(false, true, 60000),
            QingpingPollingRule(true, true, 0)), 20000, 20000))
    }

    @Test fun polling_keeps_short_launch_window_and_respects_forced_deadline() {
        val live = listOf(QingpingPollingRule(true, true, 0))
        assertEquals(300L, qingpingFollowupDelay(live, 0, 10000))
        assertEquals(300L, qingpingFollowupDelay(live, 10000, 0))
        assertNull(qingpingFollowupDelay(live, 3000, 3000))
        assertEquals(500L, qingpingFollowupDelay(listOf(QingpingPollingRule(true, true, 10000)), 3000, 3000))
        assertEquals(40L, qingpingFollowupDelay(listOf(QingpingPollingRule(true, true, 40)), 3000, 3000))
    }

    @Test fun cancelling_a_queued_read_releases_the_slot_without_executing() = runBlocking {
        val queue = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.addLast(block) }
        }
        val gate = QingpingReadGate()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val read = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.read(scope, dispatcher, 5000) { fail("Cancelled queued block ran"); "bad" }
        }
        read.cancelAndJoin()
        while (queue.isNotEmpty()) queue.removeFirst().run()
        assertEquals("next", gate.read(scope, Dispatchers.Unconfined, 100) { "next" })
        scope.cancel()
    }

    @Test fun synchronous_read_releases_slot_on_failure() {
        val gate = QingpingReadGate()
        try { gate.tryRead<String> { error("expected") }; fail("Expected failure") }
        catch (e: IllegalStateException) { assertEquals("expected", e.message) }
        assertEquals("next", gate.tryRead { "next" })
    }
}
