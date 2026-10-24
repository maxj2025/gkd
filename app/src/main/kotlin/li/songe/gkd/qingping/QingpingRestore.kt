package li.songe.gkd.qingping

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import li.songe.gkd.app
import li.songe.gkd.appScope
import li.songe.gkd.permission.writeSecureSettingsState
import li.songe.gkd.service.A11yService
import li.songe.gkd.service.currentAppBlocked
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.LogUtils

/** Shared by automatic recovery and user/tile changes; only this app's component is added. */
val qingpingA11yMutex = Mutex()
private val restorePolicy = QingpingRestorePolicy()

private suspend fun restoreQingpingService() {
    if (!qingpingA11yMutex.tryLock()) return
    try {
        withTimeoutOrNull(6_000) {
            restorePolicy.restore(
                permitted = {
                    val state = storeFlow.value
                    state.qingpingAutoRestore && !state.qingpingManualOff && state.useA11y &&
                        !currentAppBlocked && writeSecureSettingsState.updateAndGet()
                },
                running = { A11yService.isRunning.value },
                ensureListed = {
                    // Never reuse a set read before an await; preserve other enabled services.
                    val latest = app.getSecureA11yServices()
                    val state = storeFlow.value
                    if (state.qingpingAutoRestore && !state.qingpingManualOff && state.useA11y && !currentAppBlocked) {
                        latest.add(A11yService.a11yCn)
                        app.putSecureA11yServices(latest)
                        app.putSecureInt(Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                    }
                },
                awaitBinding = { delay(1200) },
            )
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { LogUtils.d("清屏有限恢复失败", e) }
    finally { qingpingA11yMutex.unlock() }
}

fun requestQingpingRestore() = appScope.launch(Dispatchers.IO) { restoreQingpingService() }

fun stopQingpingService() {
    storeFlow.update { it.copy(qingpingManualOff = true) }
    appScope.launch(Dispatchers.IO) {
        try {
            qingpingSerializedStop(qingpingA11yMutex, { storeFlow.value.qingpingManualOff }) {
                A11yService.instance?.disableSelf()
                if (writeSecureSettingsState.updateAndGet()) {
                    val latest = app.getSecureA11yServices()
                    latest.remove(A11yService.a11yCn)
                    app.putSecureA11yServices(latest)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { LogUtils.d("清屏停止服务失败", e) }
    }
}

class QingpingRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        appScope.launch(Dispatchers.IO) {
            try { restoreQingpingService() } finally { pending.finish() }
        }
    }
}
