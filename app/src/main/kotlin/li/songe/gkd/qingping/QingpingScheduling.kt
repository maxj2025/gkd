package li.songe.gkd.qingping

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** Preserve order; coalesce only the contiguous prefix, never across a window change. */
class QingpingEventBuffer<T>(
    private val capacity: Int,
    private val same: (T, T) -> Boolean,
    private val priority: (T) -> Boolean,
) {
    init { require(capacity > 0) }
    private val queue = ArrayDeque<T>()

    @Synchronized fun offer(event: T) {
        if (queue.size == capacity) {
            val oldContent = queue.indexOfFirst { !priority(it) }
            // A content storm must not evict a queue full of window transitions.
            if (oldContent < 0 && !priority(event)) return
            queue.removeAt(if (oldContent < 0) 0 else oldContent)
        }
        queue.addLast(event)
    }

    @Synchronized fun takeBatch(): List<T> {
        val head = queue.firstOrNull() ?: return emptyList()
        val result = mutableListOf<T>()
        while (queue.isNotEmpty() && same(head, queue.first())) result.add(queue.removeFirst())
        return result
    }

    @Synchronized fun hasPending(): Boolean = queue.isNotEmpty()
    @Synchronized fun size(): Int = queue.size
    @Synchronized fun clear() = queue.clear()
}

/** Two recent sources suffice; heterogeneous events require a root query, not an unbounded list. */
class QingpingQueryEvents<T>(private val same: (T, T) -> Boolean) {
    private var pending = false
    private var rootRequired = false
    private val recent = ArrayDeque<T>(2)

    @Synchronized fun addAll(events: List<T>) {
        for (event in events) {
            pending = true
            if (rootRequired) continue
            val previous = recent.lastOrNull()
            if (previous != null && !same(previous, event)) {
                rootRequired = true
                recent.clear()
            } else {
                if (recent.size == 2) recent.removeFirst()
                recent.addLast(event)
            }
        }
    }

    @Synchronized fun hasPending(): Boolean = pending
    @Synchronized fun take(): List<T>? = (if (rootRequired) null else recent.toList()).also { clear() }
    @Synchronized fun clear() { pending = false; rootRequired = false; recent.clear() }
}

/** A timeout stops waiting, not Binder: keep the slot until the actual worker has completed. */
class QingpingReadGate {
    private val occupied = AtomicBoolean(false)
    private val releaseVersion = MutableStateFlow(0L)

    fun isBusy(): Boolean = occupied.get()

    private fun release() {
        occupied.set(false)
        releaseVersion.update { it + 1 }
    }

    private suspend fun acquire(scope: CoroutineScope, waitForSlot: Boolean): Boolean {
        while (scope.isActive) {
            if (occupied.compareAndSet(false, true)) return true
            if (!waitForSlot) return false

            // Subscribe to the next release without missing a release between the
            // occupied check and suspension.
            val observed = releaseVersion.value
            if (occupied.get()) releaseVersion.first { it != observed }
        }
        return false
    }

    fun <T> tryRead(block: () -> T): T? {
        if (!occupied.compareAndSet(false, true)) return null
        return try { block() } finally { release() }
    }

    suspend fun <T> read(
        scope: CoroutineScope,
        dispatcher: CoroutineContext,
        timeoutMs: Long,
        waitForSlot: Boolean = false,
        block: () -> T,
    ): T? {
        if (!acquire(scope, waitForSlot)) return null
        val task = try {
            scope.async(dispatcher, start = CoroutineStart.LAZY) { block() }
        } catch (e: Throwable) {
            release()
            throw e
        }
        task.invokeOnCompletion { release() }
        return try {
            task.start()
            withTimeoutOrNull(timeoutMs) { task.await() }
        } finally {
            // Does not release occupied while an uninterruptible block is still executing.
            task.cancel()
        }
    }
}

data class QingpingPollingRule(
    val allowed: Boolean,
    val runnable: Boolean,
    val forcedRemainingMs: Long,
    val cooldownRemainingMs: Long = 0L,
)

/** Only currently permitted, runnable rules may request a wake-up. */
fun qingpingFollowupDelay(rules: List<QingpingPollingRule>, appAgeMs: Long, sinceActionMs: Long): Long? {
    val active = rules.filter { it.allowed && it.runnable }
    if (active.isEmpty()) return null
    if (appAgeMs in 0 until 3000L || sinceActionMs in 0 until 3000L) return 300L
    val forcedRemaining = active.maxOf { it.forcedRemainingMs }
    if (forcedRemaining > 0) return minOf(500L, forcedRemaining)
    return active.asSequence().map { it.cooldownRemainingMs }.filter { it > 0L }.minOrNull()
}
