package li.songe.gkd.qingping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import li.songe.gkd.app
import li.songe.gkd.data.RawSubscription
import li.songe.gkd.data.SubsItem
import li.songe.gkd.db.DbSet
import li.songe.gkd.util.launchTry
import li.songe.gkd.util.toast
import li.songe.gkd.util.persistSubscription

class QingpingVm : ViewModel() {
    fun setAutoRestore(enabled: Boolean) {
        li.songe.gkd.store.storeFlow.update { state -> state.copy(qingpingAutoRestore = enabled,
                qingpingManualOff = if (enabled) false else state.qingpingManualOff)
        }
        if (enabled) requestQingpingRestore()
    }
    val stats = DbSet.qingpingStatDao.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun installBasicRules() {
        viewModelScope.launchTry(Dispatchers.IO) {
            val raw = app.assets.open("qingping-basic.json").bufferedReader().use { it.readText() }
            val subscription = RawSubscription.parse(raw)
            check(subscription.globalGroups.all { it.valid }) { "基础规则校验失败" }
            persistSubscription(subscription)
            DbSet.subsItemDao.insertOrIgnore(SubsItem(
                id = subscription.id, enable = true, enableUpdate = false, order = 0,
            ))
            DbSet.subsItemDao.updateEnable(subscription.id, true)
            launch(Dispatchers.Main) { toast("已启用基础规则；可在订阅中管理") }
        }
    }
}
