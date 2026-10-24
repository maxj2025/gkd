package li.songe.gkd.qingping

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.songe.gkd.data.RawSubscription
import li.songe.gkd.data.SubsItem
import li.songe.gkd.db.DbSet
import li.songe.gkd.service.A11yService
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.deleteSubscription
import li.songe.gkd.util.persistSubscription
import li.songe.gkd.util.ruleSummaryFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QingpingExitTest {
    @Test fun adExitRequiresOptInAndCountsTheOriginApp() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val fixtureId = "io.qingping.fixture"
        val subscriptionId = 20261025L
        assertTrue("Run only on the isolated Android emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        // Instrumentation restarts its target; reset only this isolated emulator's binding.
        fun shell(command: String): String {
            val result = android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
            check(!result.contains("Bad arguments")) { result }
            return result
        }
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        shell("settings delete secure enabled_accessibility_services")
        withTimeout(5000) {
            while (!shell("dumpsys accessibility").contains("Enabled services:{}")) delay(100)
        }
        shell("settings put secure enabled_accessibility_services ${context.packageName}/com.google.android.accessibility.selecttospeak.SelectToSpeakService")
        shell("settings put secure accessibility_enabled 1")
        withTimeout(15000) { while (!A11yService.isRunning.value) delay(100) }
        val previous = storeFlow.value
        val raw = RawSubscription.parse("""
            {id: 20261025, name: '清屏测试专用', version: 1, apps: [{id: 'io.qingping.fixture',
             groups: [{key: 0, name: '广告跳转-测试返回', enable: true, actionMaximum: 1,
             rules: [{key: 0, matches: "[text='广告落地页测试']", action: 'back'}]}]}]}
        """.trimIndent())
        try {
            persistSubscription(raw)
            DbSet.subsItemDao.insertOrIgnore(SubsItem(id = subscriptionId, enable = true,
                enableUpdate = false, order = 1))
            withTimeout(15000) {
                while (ruleSummaryFlow.value.appIdToRules[fixtureId].orEmpty().none {
                    it.subsItem.id == subscriptionId
                }) delay(100)
            }
            storeFlow.update { it.copy(qingpingExitApps = it.qingpingExitApps - fixtureId) }
            val before = DbSet.qingpingStatDao.get(fixtureId, "exit")?.count ?: 0
            val intent = Intent().setComponent(ComponentName(fixtureId, "$fixtureId.AdActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("ordinary", true).putExtra("exitPage", true)
            context.startActivity(intent)
            withTimeout(10000) {
                while (ui.rootInActiveWindow?.findAccessibilityNodeInfosByText("广告落地页测试").isNullOrEmpty()) delay(100)
            }
            delay(1500)
            assertEquals(before, DbSet.qingpingStatDao.get(fixtureId, "exit")?.count ?: 0)
            assertTrue(ui.rootInActiveWindow.findAccessibilityNodeInfosByText("广告落地页测试").isNotEmpty())
            storeFlow.update { it.copy(qingpingExitApps = it.qingpingExitApps + fixtureId) }
            context.startActivity(intent)
            withTimeout(10000) {
                while ((DbSet.qingpingStatDao.get(fixtureId, "exit")?.count ?: 0) == before) delay(100)
            }
            assertEquals(before + 1, DbSet.qingpingStatDao.get(fixtureId, "exit")!!.count)
        } finally {
            storeFlow.value = previous
            deleteSubscription(subscriptionId)
        }
    }
}
