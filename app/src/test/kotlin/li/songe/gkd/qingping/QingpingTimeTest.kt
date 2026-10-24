package li.songe.gkd.qingping

import li.songe.gkd.util.format
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class QingpingTimeTest {
    @Test fun concurrent_formatting_matches_thread_local_reference() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (0..7).map { worker -> Callable {
                val reference = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                repeat(2000) { n ->
                    val time = 1_700_000_000_000L + worker * 86_400_000L + n * 1234L
                    assertEquals(reference.format(time), time.format("yyyy-MM-dd HH:mm:ss.SSS"))
                }
            } }
            pool.invokeAll(jobs).forEach { it.get() }
        } finally { pool.shutdownNow() }
    }
}
