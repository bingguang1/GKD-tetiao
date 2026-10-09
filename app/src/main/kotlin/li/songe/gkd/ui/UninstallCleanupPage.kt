package li.songe.gkd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import li.songe.gkd.service.UninstallCleaner
import li.songe.gkd.shizuku.shizukuContextFlow
import li.songe.gkd.store.storeFlow
import li.songe.gkd.ui.component.PerfTopAppBar
import li.songe.gkd.ui.component.waitResult
import li.songe.gkd.ui.icon.BackCloseIcon
import li.songe.gkd.ui.share.BaseViewModel
import li.songe.gkd.ui.share.LocalMainViewModel
import li.songe.gkd.ui.style.scaffoldPadding
import li.songe.gkd.util.copyText
import li.songe.gkd.util.format
import li.songe.gkd.util.launchAsFn
import li.songe.gkd.util.throttle
import li.songe.gkd.util.toast

@Serializable
data object UninstallCleanupRoute : NavKey

/**
 * fork(fok0030): 「卸载清理」页。
 *
 * ## 为什么需要它
 * 应用被卸载之后**再也执行不了任何代码** —— 所以"卸载残留"只能在**卸载之前**由 App 自己清掉。
 * 本页把"卸载 GKD特调版之后还会留在系统里的痕迹"逐项列出来(每一项都有真机取证, 见
 * `平板幽灵触控排查\GKD卸载残留清单.md`), 支持逐项勾选 + 一键全清, 并且**改动前先记原值**,
 * 事后可以一键恢复(台账见 [UninstallCleaner])。
 *
 * ## 有意不做的事
 * - **不提供"清理后代替用户卸载"按钮**(用户明确要求: 只清理, 卸载仍由用户自己在系统里做);
 * - 不新增电脑端脚本(页面上给的是**可复制的 adb 命令**, 给"没跑清理就卸了"的场景兜底)。
 */
class UninstallCleanupVm : BaseViewModel() {

    val itemsFlow = MutableStateFlow<List<UninstallCleaner.ResidueItem>>(emptyList())
    val selectedFlow = MutableStateFlow<Set<String>>(emptySet())
    val resultFlow = MutableStateFlow<List<String>>(emptyList())
    val scanningFlow = MutableStateFlow(false)
    val lastScanFlow = MutableStateFlow(0L)
    val pendingFlow = MutableStateFlow<List<UninstallCleaner.LedgerEntry>>(emptyList())

    init {
        scan()
    }

    fun scan() {
        viewModelScope.launch {
            scanningFlow.value = true
            runCatching { UninstallCleaner.scan() }.onSuccess { list ->
                itemsFlow.value = list
                // 默认勾选"确实存在且能清"的项(只读说明项不可勾)
                selectedFlow.value = list.filter { it.cleanable }.map { it.id }.toSet()
                pendingFlow.value = UninstallCleaner.pendingRestore()
                lastScanFlow.value = System.currentTimeMillis()
            }.onFailure {
                resultFlow.value = listOf("✗ 扫描失败: ${it.message}")
            }
            scanningFlow.value = false
        }
    }

    fun toggle(id: String, on: Boolean) {
        selectedFlow.value = if (on) selectedFlow.value + id else selectedFlow.value - id
    }

    fun clean(onDone: (List<String>) -> Unit) {
        viewModelScope.launch {
            val ids = selectedFlow.value
            val lines = UninstallCleaner.clean(ids)
            resultFlow.value = lines
            onDone(lines)
            scan()
        }
    }

    fun restore(onDone: (List<String>) -> Unit) {
        viewModelScope.launch {
            val lines = UninstallCleaner.restoreAll()
            resultFlow.value = lines
            onDone(lines)
            scan()
        }
    }
}

@Composable
fun UninstallCleanupPage() {
    val mainVm = LocalMainViewModel.current
    val vm = viewModel<UninstallCleanupVm>()
    val store by storeFlow.collectAsState()
    val shizukuCtx by shizukuContextFlow.collectAsState()

    val items by vm.itemsFlow.collectAsState()
    val selected by vm.selectedFlow.collectAsState()
    val results by vm.resultFlow.collectAsState()
    val scanning by vm.scanningFlow.collectAsState()
    val lastScan by vm.lastScanFlow.collectAsState()
    val pending by vm.pendingFlow.collectAsState()

    val writeOk = UninstallCleaner.hasWriteSecureSettings()
    val shellOk = store.enableShizuku && shizukuCtx.serviceWrapper != null
    val cleanableCount = items.count { it.cleanable }

    Scaffold(
        topBar = {
            PerfTopAppBar(
                modifier = Modifier.fillMaxWidth(),
                navigationIcon = {
                    IconButton(onClick = throttle { mainVm.popPage() }) {
                        BackCloseIcon(backOrClose = false)
                    }
                },
                title = { Text(text = "卸载清理") },
            )
        },
    ) { contentPadding ->
        LazyColumn(modifier = Modifier.scaffoldPadding(contentPadding)) {
            item(key = "head") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        text = "GKD特调版 卸载之后, 系统里会留下一些它写过的痕迹(控制中心的磁贴、权限记录、" +
                            "被停用的快应用引擎…)。应用被卸载后就无法再执行任何代码, 所以请先在这里清干净, 再去卸载。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "权限状态: " +
                            "WRITE_SECURE_SETTINGS " + if (writeOk) "已授予 ✅" else "未授予 ❌(磁贴/权限键清不了, 用下面的 adb 命令)" +
                            " · Shizuku " + if (shellOk) "已连接 ✅" else "未连接 ❌(引擎还原需要它或 adb)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (writeOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "上次扫描: " + (if (lastScan == 0L) "-" else lastScan.format("HH:mm:ss")) +
                            " · 扫描到 " + items.size + " 项, 其中可清 $cleanableCount 项" +
                            (if (pending.isEmpty()) "" else " · 台账里有 ${pending.size} 条未恢复的系统改动"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = !scanning, onClick = throttle { vm.scan() }) {
                            Text(text = if (scanning) "扫描中…" else "重新扫描")
                        }
                        TextButton(
                            enabled = selected.isNotEmpty(),
                            onClick = throttle(fn = vm.viewModelScope.launchAsFn {
                                mainVm.dialogFlow.waitResult(
                                    title = "清理残留",
                                    text = "将清理 ${selected.size} 项系统痕迹(每项都会先记下原值, 之后可在这一页恢复)。\n" +
                                        "清理后 App 会继续正常使用; 真正卸载请在系统设置/桌面上操作。",
                                    error = true,
                                )
                                vm.clean { lines -> toast("清理完成: ${lines.size} 条结果") }
                            }),
                        ) { Text(text = "清理勾选项 (${selected.size})") }
                        TextButton(
                            enabled = pending.isNotEmpty(),
                            onClick = throttle(fn = vm.viewModelScope.launchAsFn {
                                mainVm.dialogFlow.waitResult(
                                    title = "恢复改动",
                                    text = "按台账把本应用改过的系统状态全部还原(共 ${pending.size} 条)。",
                                    error = false,
                                )
                                vm.restore { lines -> toast("恢复完成: ${lines.size} 条结果") }
                            }),
                        ) { Text(text = "恢复改动 (${pending.size})") }
                    }
                    TextButton(onClick = throttle {
                        copyText(UninstallCleaner.adbFallbackCommands())
                        toast("已复制 adb 命令(没跑清理就卸载时的兜底)")
                    }) { Text(text = "复制 adb 命令") }
                    HorizontalDivider()
                }
            }

            items(items, { it.id }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (item.cleanable) {
                        Checkbox(
                            checked = selected.contains(item.id),
                            onCheckedChange = { vm.toggle(item.id, it) },
                        )
                    } else {
                        Spacer(modifier = Modifier.size(48.dp))
                    }
                    Column(modifier = Modifier.padding(start = 4.dp, top = 8.dp)) {
                        Text(text = item.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = item.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (item.need.isNotEmpty() && item.cleanable) {
                            Text(
                                text = "需要: ${item.need}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (item.extra.isNotBlank()) {
                            Text(
                                text = item.extra,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }

            if (results.isNotEmpty()) {
                item(key = "result-title") {
                    Text(
                        text = "本次结果",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                itemsIndexed(results) { index, line ->
                    Text(
                        text = line,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (line.startsWith("✗")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            item(key = "tail") {
                Text(
                    text = "说明: 桌面小组件与系统安装历史是**清不掉**的(系统没有对应接口), 它们无害, 页面里已写明; " +
                        "电池白名单/权限授权/appops/应用数据目录会随卸载自动消失。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
