package li.songe.gkd.qingping

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.update
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.appInfoMapFlow
import li.songe.gkd.util.subsMapFlow
import li.songe.gkd.util.format
import li.songe.gkd.ui.AppConfigRoute
import li.songe.gkd.ui.share.LocalMainViewModel

@Composable
fun QingpingCard() {
    val vm = viewModel<QingpingVm>()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val subs by subsMapFlow.collectAsStateWithLifecycle()
    val apps by appInfoMapFlow.collectAsStateWithLifecycle()
    val store by storeFlow.collectAsStateWithLifecycle()
    val securePermission by li.songe.gkd.permission.writeSecureSettingsState.stateFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val mainVm = LocalMainViewModel.current
    var showStats by remember { mutableStateOf(false) }
    val basicInstalled = subs.containsKey(20261024L)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (store.enableStatusService) "清屏 · 常驻通知已开启" else "清屏 · 低打扰模式",
                style = MaterialTheme.typography.titleLarge)
            Text(stats?.let { "累计完成操作 ${it.filter { row -> row.kind == "splash" || row.kind == "exit" }.sumOf { row -> row.count }} 次" } ?: "正在读取本地统计…")
            Text("开屏匹配限进入应用后 15 秒；熄屏停止匹配。广告跳转防护需按应用开启。",
                style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { showStats = true }) { Text("统计与应用防护") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("开机与解锁后有限恢复", Modifier.weight(1f))
                Switch(checked = store.qingpingAutoRestore, onCheckedChange = vm::setAutoRestore)
            }
            if (store.qingpingAutoRestore) {
                Text(if (securePermission) "写入安全设置：已授权" else "尚未授权写入安全设置，自动恢复不会执行。",
                    style = MaterialTheme.typography.bodySmall)
                Text("需要一次授予写入安全设置权限及允许自启动；仅尝试恢复，不保证清后台存活。应用内主动关闭服务后暂停恢复。",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { mainVm.navigatePage(li.songe.gkd.ui.AuthA11yRoute) }) { Text("查看授权与工作模式") }
            }
            if (!basicInstalled) {
                Text("内置规则只识别“跳过广告”按钮，可离线使用；普通“跳过”需添加已适配的订阅规则。")
                TextButton(onClick = vm::installBasicRules) { Text("启用内置基础规则") }
            }
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")))
            }) { Text("后台设置：允许自启动、关闭省电限制") }
            Text("隐藏任务卡片不会阻止系统清理。强行停止后需重新打开；系统权限提示仍可能显示。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showStats) {
        val grouped = stats?.groupBy { it.appId }.orEmpty()
        val exitApps = subs.values.flatMap { it.apps }.filter { a ->
            a.groups.any { QingpingPolicy.kind(it.name) == "exit" }
        }.map { it.id }.filter { apps.containsKey(it) }.toSet()
        val allIds = (grouped.keys + exitApps + store.qingpingExitApps).sortedWith(
            compareByDescending<String> { id -> grouped[id]?.filter { it.kind == "splash" || it.kind == "exit" }?.sumOf { it.count } ?: 0 }
                .thenBy { apps[it]?.name ?: it }
        )
        AlertDialog(
            onDismissRequest = { showStats = false },
            title = { Text("统计与应用防护") },
            text = {
                LazyColumn(Modifier.heightIn(max = 450.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Text("操作数表示节点操作返回成功或手势完成，不保证广告已消失。复查结果独立累计；两次未找到原出口仍不等于广告请求被拦截。")
                        Text("跳转防护需要同时启用订阅中的对应规则；应用开关不会覆盖你关闭的类别或规则。")
                        if (stats == null) Text("正在读取…")
                        else if (allIds.isEmpty()) Text("暂无记录。成功跳过广告后会显示应用统计。")
                    }
                    items(allIds, key = { it }) { id ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(apps[id]?.name ?: id, style = MaterialTheme.typography.titleSmall)
                            val rows = grouped[id].orEmpty()
                            Text("跳过 ${rows.filter { it.kind == "splash" }.sumOf { it.count }} 次 · 退出 ${rows.filter { it.kind == "exit" }.sumOf { it.count }} 次")
                            if (rows.any { it.kind.startsWith("verify_") }) {
                                fun count(kind: String) = rows.filter { it.kind == "verify_$kind" }.sumOf { it.count }
                                Text("复查：原出口消失 ${count("exit_gone")} · 仍存在 ${count("remains")} · 未知 ${count("unknown")} · 跨应用 ${count("external")}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            rows.maxOfOrNull { it.lastTime }?.let { time ->
                                Text("最近执行 ${time.format("MM-dd HH:mm:ss")}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (id in exitApps || id in store.qingpingExitApps) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (id in exitApps) "广告跳转防护" else "防护规则已移除", Modifier.weight(1f))
                                    Switch(
                                        checked = id in store.qingpingExitApps,
                                        modifier = Modifier.semantics { contentDescription = "${apps[id]?.name ?: id}广告跳转防护" },
                                        onCheckedChange = { enabled ->
                                            storeFlow.update { s -> s.copy(qingpingExitApps =
                                                if (enabled) s.qingpingExitApps + id else s.qingpingExitApps - id) }
                                        },
                                    )
                                }
                                TextButton(onClick = {
                                    showStats = false
                                    mainVm.navigatePage(AppConfigRoute(appId = id))
                                }) { Text("配置此应用规则") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showStats = false }) { Text("关闭") } },
        )
    }
}
