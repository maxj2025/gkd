package li.songe.gkd.qingping

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import li.songe.gkd.db.AppDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QingpingStatTest {
    @Test
    fun cumulativeCountsPersistAfterDatabaseReopens() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qingping-test-${java.util.UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, AppDb::class.java, name).build()
        try {
            db.qingpingStatDao().record("test.persist", "splash", 1234)
            db.close()
            db = Room.databaseBuilder(context, AppDb::class.java, name).build()
            assertEquals(1L, db.qingpingStatDao().get("test.persist", "splash")!!.count)
            assertEquals(1234L, db.qingpingStatDao().get("test.persist", "splash")!!.lastTime)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test
    fun defaultsUseQuietModeAndDoNotEnableAdExit() {
        val settings = li.songe.gkd.store.SettingsStore()
        assertTrue(settings.excludeFromRecents)
        assertTrue(settings.qingpingExitApps.isEmpty())
        org.junit.Assert.assertFalse(settings.enableStatusService)
        org.junit.Assert.assertFalse(settings.toastWhenClick)
        org.junit.Assert.assertFalse(settings.enableShizuku)
        assertEquals(-1L, settings.updateSubsInterval)
    }

    @Test
    fun cumulativeCountsSurviveDetailedLogCleanupAndStaySeparated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDb::class.java).build()
        try {
            val dao = db.qingpingStatDao()
            repeat(601) { dao.record("test.app", "splash", it.toLong()) }
            dao.record("test.app", "exit", 700)
            dao.record("other.app", "splash", 800)
            db.actionLogDao().deleteKeepLatest()
            db.actionLogDao().deleteAll()
            assertEquals(601L, dao.get("test.app", "splash")!!.count)
            assertEquals(600L, dao.get("test.app", "splash")!!.lastTime)
            assertEquals(1L, dao.get("test.app", "exit")!!.count)
            assertEquals(1L, dao.get("other.app", "splash")!!.count)
        } finally { db.close() }
    }

    @Test
    fun bundledSelectorsParseAndAreValid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val raw = context.assets.open("qingping-basic.json").bufferedReader().use { it.readText() }
        val subs = li.songe.gkd.data.RawSubscription.parse(raw)
        assertTrue(subs.globalGroups.isNotEmpty())
        assertTrue(subs.globalGroups.all { it.valid })
    }

    @Test
    fun categoryDisableStillAppliesToAdExitRules() {
        val subs = li.songe.gkd.data.RawSubscription.parse("""
            {id: 9, name: 'test', version: 1,
             categories: [{key: 0, name: '广告跳转', enable: true}],
             apps: [{id: 'test.app', groups: [{key: 0, name: '广告跳转-返回', enable: true,
               rules: [{matches: "[text='广告']", action: 'back'}]}]}]}
        """.trimIndent())
        val group = subs.apps.single().groups.single()
        assertTrue(group.valid)
        org.junit.Assert.assertFalse(li.songe.gkd.util.getGroupEnable(
            group, null, subs.categories.single(),
            li.songe.gkd.data.CategoryConfig(subsId = 9, categoryKey = 0, enable = false),
        ))
    }
}
