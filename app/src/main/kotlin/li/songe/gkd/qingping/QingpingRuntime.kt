package li.songe.gkd.qingping

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/** Occupy the slot before dispatch, release it even if cancellation precedes execution. */
class QingpingQueryRunner {
    private var owner: Any? = null
    private var activeJob: Job? = null

    fun cancel() {
        val job = synchronized(this) { activeJob }
        job?.cancel()
    }

    @Synchronized
    fun tryStart(
        scope: CoroutineScope,
        dispatcher: CoroutineContext,
        onComplete: () -> Unit = {},
        block: suspend CoroutineScope.() -> Unit,
    ): Boolean {
        if (owner != null || !scope.isActive) return false
        val token = Any()
        owner = token
        val job = scope.launch(dispatcher, start = CoroutineStart.LAZY, block = block)
        activeJob = job
        job.invokeOnCompletion {
            synchronized(this) { if (owner === token) { owner = null; activeJob = null } }
            if (scope.isActive) onComplete()
        }
        job.start()
        return true
    }
}

/** A new follow-up replaces the old one; running queries never consume pending work. */
class QingpingFollowup {
    private var job: Job? = null

    @Synchronized fun cancel() { owner = null; job?.cancel(); job = null }

    @Synchronized fun replace(scope: CoroutineScope, delayMs: Long, block: () -> Unit) {
        cancel()
        val token = Any()
        owner = token
        job = scope.launch(start = CoroutineStart.LAZY) {
            delay(delayMs)
            val proceed = synchronized(this@QingpingFollowup) {
                if (owner !== token) false else { job = null; owner = null; true }
            }
            if (proceed) block()
        }.also { it.start() }
    }
    private var owner: Any? = null
}

/** Platform acceptance is insufficient: wait for completion, cancellation, or a bounded timeout. */
suspend fun awaitQingpingGesture(timeoutMs: Long, dispatch: ((Boolean) -> Unit) -> Boolean): Boolean =
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            continuation.invokeOnCancellation { finished.set(true) }
            val done: (Boolean) -> Unit = { result ->
                if (finished.compareAndSet(false, true)) continuation.resume(result)
            }
            try {
                if (!dispatch(done)) done(false)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { done(false) }
        }
    } ?: false

object QingpingActionBounds {
    fun allows(l: Int, t: Int, r: Int, b: Int, visible: Boolean, x: Float, y: Float): Boolean {
        if (r <= l || b <= t || !x.isFinite() || !y.isFinite()) return false
        // Explicit subscription offsets may intentionally point outside the matched node.
        return visible || !(x >= l && x < r && y >= t && y < b)
    }
}

enum class QingpingOutcome { EXIT_GONE, REMAINS, UNKNOWN, EXTERNAL }

object QingpingOutcomePolicy {
    fun decide(sameApp: Boolean, ruleAvailable: Boolean, readable: Boolean, targetPresent: Boolean): QingpingOutcome = when {
        !sameApp -> QingpingOutcome.EXTERNAL
        !ruleAvailable || !readable -> QingpingOutcome.UNKNOWN
        targetPresent -> QingpingOutcome.REMAINS
        else -> QingpingOutcome.EXIT_GONE
    }
}

/** A negative selector result is usable only when the bounded tree was fully readable. */
fun <T : Any> qingpingReadableTree(
    root: T, childCount: (T) -> Int, childAt: (T, Int) -> T?, hasContent: (T) -> Boolean,
    maxNodes: Int = 256,
): Boolean {
    val pending = ArrayDeque<T>().apply { add(root) }
    var seen = 0
    var content = false
    while (pending.isNotEmpty()) {
        if (++seen > maxNodes) return false
        val node = pending.removeFirst()
        content = content || hasContent(node)
        val count = childCount(node)
        if (count < 0 || count > maxNodes || seen + pending.size + count > maxNodes) return false
        repeat(count) { index -> pending.add(childAt(node, index) ?: return false) }
    }
    return seen > 1 && content
}

/** Event-driven, bounded and non-destructive: never remove an enabled service to force rebinding. */
class QingpingRestorePolicy {
    private val mutex = Mutex()
    suspend fun restore(
        permitted: () -> Boolean,
        running: () -> Boolean,
        ensureListed: () -> Unit,
        awaitBinding: suspend () -> Unit,
    ) {
        if (!mutex.tryLock()) return
        try {
            repeat(3) {
                if (!permitted() || running()) return
                ensureListed()
                awaitBinding()
            }
        } finally { mutex.unlock() }
    }
}

suspend fun qingpingSerializedStop(mutex: Mutex, stillStopped: () -> Boolean, stop: () -> Unit) {
    mutex.withLock { if (stillStopped()) stop() }
}
