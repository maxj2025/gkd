package li.songe.gkd.qingping

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.update
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.appInfoMapFlow
import li.songe.gkd.util.subsMapFlow
import li.songe.gkd.util.format
import li.songe.gkd.ui.AppConfigRoute
import li.songe.gkd.ui.component.AppIcon
import li.songe.gkd.ui.component.PerfIcon
import li.songe.gkd.ui.share.LocalMainViewModel
import li.songe.gkd.ui.style.cardContainerColor

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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = cardContainerColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header with badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (store.enableStatusService) "清屏 · 常驻通知已开启" else "清屏 · 低打扰模式",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (store.enableStatusService) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.primaryContainer
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (store.enableStatusService) "常驻" else "低打扰",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (store.enableStatusService) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Prominent stats indicator block
            val totalCount = stats?.filter { row -> row.kind == "splash" || row.kind == "exit" }?.sumOf { row -> row.count }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        PerfIcon(
                            imageVector = PerfIcon.VerifiedUser,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stats?.let { "累计完成操作 ${totalCount ?: 0} 次" } ?: "正在读取本地统计…",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "开屏匹配限进入应用后 15 秒；熄屏停止匹配。广告跳转防护需按应用开启。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Action row for stats dialog
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(
                    onClick = { showStats = true },
                    shape = MaterialTheme.shapes.small
                ) {
                    Text("统计与应用防护")
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.8.dp
            )

            // Auto restore switch section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "开机与解锁后有限恢复",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = store.qingpingAutoRestore,
                        modifier = Modifier.semantics { contentDescription = "开机与解锁后有限恢复" },
                        onCheckedChange = vm::setAutoRestore
                    )
                }

                if (store.qingpingAutoRestore) {
                    Text(
                        text = if (securePermission) "写入安全设置：已授权" else "尚未授权写入安全设置，自动恢复不会执行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (securePermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "需要一次授予写入安全设置权限及允许自启动；仅尝试恢复，不保证清后台存活。应用内主动关闭服务后暂停恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { mainVm.navigatePage(li.songe.gkd.ui.AuthA11yRoute) },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("查看授权与工作模式")
                    }
                }
            }

            // Basic rules install prompt
            if (!basicInstalled) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "内置规则只识别“跳过广告”按钮，可离线使用；普通“跳过”需添加已适配的订阅规则。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        OutlinedButton(
                            onClick = vm::installBasicRules,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("启用内置基础规则")
                        }
                    }
                }
            }

            // Battery / settings navigation button
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small
            ) {
                Text("后台设置：允许自启动、关闭省电限制")
            }

            Text(
                text = "隐藏任务卡片不会阻止系统清理。强行停止后需重新打开；系统权限提示仍可能显示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PerfIcon(imageVector = PerfIcon.Equalizer, tint = MaterialTheme.colorScheme.primary)
                    Text("统计与应用防护", fontWeight = FontWeight.SemiBold)
                }
            },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "操作数表示节点操作返回成功或手势完成，不保证广告已消失。复查结果独立累计；两次未找到原出口仍不等于广告请求被拦截。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "跳转防护需要同时启用订阅中的对应规则；应用开关不会覆盖你关闭的类别或规则。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        if (stats == null) Text("正在读取…", style = MaterialTheme.typography.bodyMedium)
                        else if (allIds.isEmpty()) Text("暂无记录。成功跳过广告后会显示应用统计。", style = MaterialTheme.typography.bodyMedium)
                    }
                    items(allIds, key = { it }) { id ->
                        val appInfo = apps[id]
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                AppIcon(appId = id, modifier = Modifier.size(28.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = appInfo?.name ?: id,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = id,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            val rows = grouped[id].orEmpty()
                            val splashCount = rows.filter { it.kind == "splash" }.sumOf { it.count }
                            val exitCount = rows.filter { it.kind == "exit" }.sumOf { it.count }

                            Box(
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "跳过 $splashCount 次 · 退出 $exitCount 次",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }

                            if (rows.any { it.kind.startsWith("verify_") }) {
                                fun count(kind: String) = rows.filter { it.kind == "verify_$kind" }.sumOf { it.count }
                                Text(
                                    text = "复查：原出口消失 ${count("exit_gone")} · 仍存在 ${count("remains")} · 未知 ${count("unknown")} · 跨应用 ${count("external")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            rows.maxOfOrNull { it.lastTime }?.let { time ->
                                Text(
                                    text = "最近执行 ${time.format("MM-dd HH:mm:ss")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            if (id in exitApps || id in store.qingpingExitApps) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (id in exitApps) "广告跳转防护" else "防护规则已移除",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Switch(
                                        checked = id in store.qingpingExitApps,
                                        modifier = Modifier.semantics { contentDescription = "${apps[id]?.name ?: id}广告跳转防护" },
                                        onCheckedChange = { enabled ->
                                            storeFlow.update { s ->
                                                s.copy(qingpingExitApps =
                                                    if (enabled) s.qingpingExitApps + id else s.qingpingExitApps - id)
                                            }
                                        },
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        showStats = false
                                        mainVm.navigatePage(AppConfigRoute(appId = id))
                                    },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("配置此应用规则")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showStats = false }) {
                    Text("关闭")
                }
            },
        )
    }
}
