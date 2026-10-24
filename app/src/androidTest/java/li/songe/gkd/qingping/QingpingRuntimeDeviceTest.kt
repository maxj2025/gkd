package li.songe.gkd.qingping

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.songe.gkd.app
import li.songe.gkd.data.RawSubscription
import li.songe.gkd.data.SubsItem
import li.songe.gkd.db.DbSet
import li.songe.gkd.service.A11yService
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.deleteSubscription
import li.songe.gkd.util.persistSubscription
import org.junit.Assert.*
import org.junit.Test

class QingpingRuntimeDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ui get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun shell(command: String): String = ui.executeShellCommand(command)
        .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }

    private suspend fun bindService() {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        shell("settings delete secure enabled_accessibility_services")
        withTimeout(8000) {
            while (!shell("dumpsys accessibility").contains("Enabled services:{}")) delay(100)
        }
        delay(250)
        shell("settings put secure enabled_accessibility_services ${app.packageName}/com.google.android.accessibility.selecttospeak.SelectToSpeakService")
        shell("settings put secure accessibility_enabled 1")
        withTimeout(15000) { while (A11yService.instance == null) delay(100) }
    }

    @Test fun real_coordinate_gesture_completes_and_exit_is_verified() = runBlocking { checkFixture(false) }
    @Test fun ignored_coordinate_click_is_recorded_as_remaining_not_exit() = runBlocking { checkFixture(true) }

    @Test fun cold_service_binding_discovers_an_already_open_ad_page() = runBlocking {
        val previous = storeFlow.value
        val id = 20261031L
        val fixture = "io.qingping.fixture"
        try {
            shell("settings delete secure enabled_accessibility_services")
            withTimeout(8000) { while (A11yService.instance != null) delay(100) }
            storeFlow.update { it.copy(enableMatch = true, qingpingAutoRestore = false) }
            persistSubscription(RawSubscription.parse("""{id:20261031,name:'冷连接回归',version:1,apps:[{id:'io.qingping.fixture',groups:[
                {key:0,name:'开屏广告-冷连接',enable:true,actionMaximum:1,rules:[{key:0,matches:"[text='跳过']",action:'clickCenter'}]}]}]}"""))
            DbSet.subsItemDao.insertOrIgnore(SubsItem(id = id, enable = true, enableUpdate = false, order = 1))
            shell("am force-stop $fixture")
            shell("am start -W -f 268468224 -n $fixture/.AdActivity --ez ordinary true")
            delay(700)
            // Model a fresh process with no previously observed foreground events.
            synchronized(li.songe.gkd.a11y.topActivityFlow) {
                li.songe.gkd.a11y.topActivityFlow.value = li.songe.gkd.a11y.TopActivity()
                li.songe.gkd.a11y.activityRuleFlow.value = li.songe.gkd.a11y.ActivityRule()
            }
            val before = DbSet.qingpingStatDao.get(fixture, "splash")?.count ?: 0
            bindService()
            withTimeout(6500) {
                while ((DbSet.qingpingStatDao.get(fixture, "splash")?.count ?: 0) == before) delay(100)
            }
            assertTrue(ui.rootInActiveWindow.findAccessibilityNodeInfosByText("已进入内容页").isNotEmpty())
        } finally {
            deleteSubscription(id)
            storeFlow.value = previous
        }
    }

    @Test fun rules_loaded_after_foreground_event_run_without_reopening_app() = runBlocking {
        bindService()
        val previous = storeFlow.value
        val id = 20261030L
        val fixture = "io.qingping.fixture"
        try {
            storeFlow.update { it.copy(enableMatch = true, qingpingAutoRestore = false) }
            shell("input keyevent 3")
            delay(400)
            // Model the user opening another app, rather than a background app launch.
            shell("am start -W -f 268468224 -n $fixture/.AdActivity --ez ordinary true")
            withTimeout(8000) { while (li.songe.gkd.a11y.topActivityFlow.value.appId != fixture) delay(50) }
            delay(1200)
            val appChangedAt = li.songe.gkd.a11y.appChangeTime
            val before = DbSet.qingpingStatDao.get(fixture, "splash")?.count ?: 0
            persistSubscription(RawSubscription.parse("""{id:20261030,name:'异步加载回归',version:1,apps:[{id:'io.qingping.fixture',groups:[
                {key:0,name:'开屏广告-异步加载',enable:true,actionMaximum:1,rules:[{key:0,matches:"[text='跳过']",action:'clickCenter'}]}]}]}"""))
            DbSet.subsItemDao.insertOrIgnore(SubsItem(id = id, enable = true, enableUpdate = false, order = 1))
            withTimeout(6500) {
                while ((DbSet.qingpingStatDao.get(fixture, "splash")?.count ?: 0) == before) delay(100)
            }
            assertEquals(appChangedAt, li.songe.gkd.a11y.appChangeTime)
            assertTrue(ui.rootInActiveWindow.findAccessibilityNodeInfosByText("已进入内容页").isNotEmpty())
        } finally {
            deleteSubscription(id)
            storeFlow.value = previous
        }
    }

    private suspend fun checkFixture(noEffect: Boolean) {
        bindService()
        val previous = storeFlow.value
        val id = if (noEffect) 20261026L else 20261028L
        val fixture = "io.qingping.fixture"
        val kind = if (noEffect) "verify_remains" else "verify_exit_gone"
        val raw = RawSubscription.parse("""{id:$id,name:'坐标回归',version:1,apps:[{id:'io.qingping.fixture',groups:[
          {key:0,name:'开屏广告-坐标回归',enable:true,actionMaximum:1,
           rules:[{key:0,matches:"[text='跳过']",action:'clickCenter'}]}]}]}""")
        try {
            // Leave the previous fixture before enabling its new rule: rule-loading
            // now intentionally starts matching the already visible foreground page.
            shell("input keyevent 3")
            withTimeout(8000) {
                while (li.songe.gkd.a11y.topActivityFlow.value.appId == fixture) delay(100)
            }
            shell("am force-stop $fixture")
            delay(500) // Let the launcher transition finish before the next user launch.
            storeFlow.update { it.copy(enableMatch = true, qingpingAutoRestore = false) }
            persistSubscription(raw)
            DbSet.subsItemDao.insertOrIgnore(SubsItem(id = id, enable = true, enableUpdate = false, order = 1))
            val before = DbSet.qingpingStatDao.get(fixture, kind)?.count ?: 0
            // Give the subscription observer time to produce the new resolved rule.
            withTimeout(12000) {
                while (li.songe.gkd.util.ruleSummaryFlow.value.appIdToRules[fixture].orEmpty().none { it.subsItem.id == id }) delay(100)
            }
            shell("am start -W -f 268468224 -n $fixture/.AdActivity --ez ordinary true --ez noEffect $noEffect")
            try {
                withTimeout(15000) {
                    while ((DbSet.qingpingStatDao.get(fixture, kind)?.count ?: 0) == before) delay(100)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("fixture timeout top=${li.songe.gkd.a11y.topActivityFlow.value} " +
                    "rules=${li.songe.gkd.a11y.activityRuleFlow.value.currentRules.map { it.statusText() }} " +
                    "service=${A11yService.instance != null}", e)
            }
            assertEquals(before + 1, DbSet.qingpingStatDao.get(fixture, kind)!!.count)
            val root = ui.rootInActiveWindow
            assertNotNull(root)
            if (noEffect) assertTrue(root.findAccessibilityNodeInfosByText("跳过").isNotEmpty())
            else assertTrue(root.findAccessibilityNodeInfosByText("已进入内容页").isNotEmpty())
        } finally {
            deleteSubscription(id)
            storeFlow.value = previous
        }
    }

    @Test fun manual_stop_prevents_restore_even_with_permission() = runBlocking {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val previous = storeFlow.value
        val enabledBefore = shell("settings get secure enabled_accessibility_services").trim()
        try {
            shell("pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS")
            shell("settings delete secure enabled_accessibility_services")
            withTimeout(8000) { while (A11yService.isRunning.value) delay(100) }
            storeFlow.update { it.copy(qingpingAutoRestore = true, qingpingManualOff = true) }
            requestQingpingRestore().join()
            assertFalse(A11yService.isRunning.value)
            assertFalse(shell("settings get secure enabled_accessibility_services").contains(app.packageName))
        } finally {
            storeFlow.value = previous
            if (enabledBefore != "null" && enabledBefore.isNotEmpty())
                shell("settings put secure enabled_accessibility_services $enabledBefore")
        }
    }

    @Test fun none_precondition_can_continue_exit_chain_after_fifteen_seconds() = runBlocking {
        bindService()
        val previous = storeFlow.value
        val id = 20261027L
        val fixture = "io.qingping.fixture"
        val raw = RawSubscription.parse("""{id:20261027,name:'前置链回归',version:1,apps:[{id:'io.qingping.fixture',groups:[
          {key:0,name:'广告跳转-前置链',enable:true,actionMaximum:1,rules:[
            {key:0,matches:"[text='稍后广告测试']",action:'none'},
            {key:1,preKeys:[0],matches:"[text='稍后广告测试']",action:'back'}]}]}]}""")
        try {
            storeFlow.update { it.copy(enableMatch = true, qingpingAutoRestore = false,
                qingpingExitApps = it.qingpingExitApps + fixture) }
            persistSubscription(raw)
            DbSet.subsItemDao.insertOrIgnore(SubsItem(id = id, enable = true, enableUpdate = false, order = 1))
            withTimeout(12000) { while (li.songe.gkd.util.ruleSummaryFlow.value.appIdToRules[fixture].orEmpty().none { it.subsItem.id == id }) delay(100) }
            val before = DbSet.qingpingStatDao.get(fixture, "exit")?.count ?: 0
            shell("input keyevent 3")
            delay(300)
            app.startActivity(Intent().setComponent(ComponentName(fixture, "$fixture.AdActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("ordinary", true).putExtra("lateAd", true))
            withTimeout(23000) {
                while ((DbSet.qingpingStatDao.get(fixture, "exit")?.count ?: 0) == before) delay(100)
            }
            assertEquals(before + 1, DbSet.qingpingStatDao.get(fixture, "exit")!!.count)
        } finally {
            deleteSubscription(id)
            storeFlow.value = previous
        }
    }

    @Test fun authorized_restore_rebinds_and_explicit_stop_removes_self() = runBlocking {
        val previous = storeFlow.value
        val enabledBefore = shell("settings get secure enabled_accessibility_services").trim()
        try {
            shell("pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS")
            shell("settings delete secure enabled_accessibility_services")
            withTimeout(8000) { while (A11yService.instance != null) delay(100) }
            storeFlow.update { it.copy(qingpingAutoRestore = true, qingpingManualOff = false,
                automatorMode = li.songe.gkd.util.AutomatorModeOption.A11yMode.value,
                enableBlockA11yAppList = false) }
            requestQingpingRestore().join()
            withTimeout(8000) { while (A11yService.instance == null) delay(100) }
            assertTrue(shell("settings get secure enabled_accessibility_services").contains(app.packageName))
            stopQingpingService()
            withTimeout(8000) {
                while (A11yService.instance != null || shell("settings get secure enabled_accessibility_services").contains(app.packageName)) delay(100)
            }
            assertTrue(storeFlow.value.qingpingManualOff)
            requestQingpingRestore().join()
            assertFalse(shell("settings get secure enabled_accessibility_services").contains(app.packageName))
        } finally {
            storeFlow.value = previous
            if (enabledBefore != "null" && enabledBefore.isNotEmpty())
                shell("settings put secure enabled_accessibility_services $enabledBefore")
        }
    }

    @Test fun screen_off_does_not_execute_late_ad_rule() = runBlocking {
        bindService()
        val previous = storeFlow.value
        val id = 20261029L
        val fixture = "io.qingping.fixture"
        try {
            storeFlow.update { it.copy(enableMatch = true, qingpingAutoRestore = false) }
            persistSubscription(RawSubscription.parse("""{id:20261029,name:'熄屏回归',version:1,apps:[{id:'io.qingping.fixture',groups:[
                {key:0,name:'开屏广告-熄屏',enable:true,actionMaximum:1,rules:[{key:0,matches:"[text='稍后广告测试']",action:'back'}]}]}]}"""))
            DbSet.subsItemDao.insertOrIgnore(SubsItem(id = id, enable = true, enableUpdate = false, order = 1))
            withTimeout(12000) { while (li.songe.gkd.util.ruleSummaryFlow.value.appIdToRules[fixture].orEmpty().none { it.subsItem.id == id }) delay(100) }
            val before = DbSet.qingpingStatDao.get(fixture, "splash")?.count ?: 0
            app.startActivity(Intent().setComponent(ComponentName(fixture, "$fixture.AdActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("ordinary", true).putExtra("lateAd", true).putExtra("adDelayMs", 2000L))
            shell("input keyevent 223")
            withTimeout(8000) { while (li.songe.gkd.a11y.isInteractive) delay(100) }
            delay(2400)
            assertEquals(before, DbSet.qingpingStatDao.get(fixture, "splash")?.count ?: 0)
        } finally {
            storeFlow.value = previous.copy(enableMatch = false)
            shell("input keyevent 224")
            shell("wm dismiss-keyguard")
            deleteSubscription(id)
            storeFlow.value = previous
        }
    }
}
