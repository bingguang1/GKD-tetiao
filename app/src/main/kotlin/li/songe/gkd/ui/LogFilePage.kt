package li.songe.gkd.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import li.songe.gkd.MainActivity
import li.songe.gkd.R
import li.songe.gkd.ui.component.AnimatedIconButton
import li.songe.gkd.ui.component.AppBarTextField
import li.songe.gkd.ui.component.EmptyText
import li.songe.gkd.ui.component.PerfIcon
import li.songe.gkd.ui.component.PerfIconButton
import li.songe.gkd.ui.component.PerfTopAppBar
import li.songe.gkd.ui.component.autoFocus
import li.songe.gkd.ui.component.waitResult
import li.songe.gkd.ui.icon.BackCloseIcon
import li.songe.gkd.ui.share.LocalMainViewModel
import li.songe.gkd.ui.style.EmptyHeight
import li.songe.gkd.ui.style.scaffoldPadding
import li.songe.gkd.util.copyText
import li.songe.gkd.util.format
import li.songe.gkd.util.launchAsFn
import li.songe.gkd.util.throttle
import li.songe.gkd.util.toast

@Serializable
data object LogFileRoute : NavKey

/**
 * fork(v122): 「运行日志」页 —— 在手机上直接看 GKD 的运行日志(以前只能靠电脑 adb pull + grep)。
 *
 * 页面结构:
 *   ① 日志文件切换(最近 7 天, 由 [li.songe.gkd.util.LogUtils] 自动滚动清理), 点当前那个 = 刷新;
 *   ② 关键字筛选 —— 排障最常用的姿势(输入 `ShakeGuard` / `JumpGuard` / `send BACK`);
 *   ③ 日志列表: 守卫决策高亮成主题色、异常标红, **最新的在最上面**;
 *   ④ 顶栏「复制」(复制当前筛选结果)与「清空」(删掉全部日志文件, 二次确认)。
 *
 * 读法与实现说明见 [LogFileVm]。
 */
@Composable
fun LogFilePage() {
    LocalActivity.current as MainActivity
    val mainVm = LocalMainViewModel.current
    val vm = viewModel<LogFileVm>()

    val files by vm.filesFlow.collectAsState()
    val selected by vm.selectedFlow.collectAsState()
    val entries by vm.entriesFlow.collectAsState()
    val filter by vm.filterFlow.collectAsState()
    val showSearchBar by vm.showSearchBarFlow.collectAsState()
    val loading by vm.loadingFlow.collectAsState()
    val truncated by vm.truncatedFlow.collectAsState()

    val shown = remember(entries, filter) {
        val key = filter.trim()
        if (key.isEmpty()) entries else entries.filter { it.contains(key, ignoreCase = true) }
    }
    val current = files.firstOrNull { it.name == selected }

    Scaffold(
        topBar = {
            PerfTopAppBar(
                modifier = Modifier.fillMaxWidth(),
                navigationIcon = {
                    IconButton(
                        onClick = throttle {
                            if (showSearchBar && filter.isNotEmpty()) {
                                vm.filterFlow.value = ""
                            } else if (showSearchBar) {
                                vm.showSearchBarFlow.value = false
                            } else {
                                mainVm.popPage()
                            }
                        }
                    ) {
                        BackCloseIcon(backOrClose = !showSearchBar)
                    }
                },
                title = {
                    if (showSearchBar) {
                        AppBarTextField(
                            value = filter,
                            onValueChange = { newValue ->
                                vm.filterFlow.value = newValue
                            },
                            hint = "筛选日志, 如 ShakeGuard",
                            modifier = Modifier.autoFocus(),
                        )
                    } else {
                        Text(text = "运行日志")
                    }
                },
                actions = {
                    AnimatedIconButton(
                        onClick = throttle {
                            if (showSearchBar) {
                                if (filter.isEmpty()) {
                                    vm.showSearchBarFlow.value = false
                                } else {
                                    vm.filterFlow.value = ""
                                }
                            } else {
                                vm.showSearchBarFlow.value = true
                            }
                        },
                        id = R.drawable.ic_anim_search_close,
                        atEnd = showSearchBar,
                    )
                    PerfIconButton(
                        imageVector = PerfIcon.ContentCopy,
                        enabled = shown.isNotEmpty(),
                        onClickLabel = "复制当前筛选出来的日志",
                        onClick = throttle {
                            copyText(shown.joinToString("\n\n"))
                            toast("已复制 ${shown.size} 条日志")
                        },
                    )
                    if (files.isNotEmpty()) {
                        PerfIconButton(
                            imageVector = PerfIcon.Delete,
                            onClickLabel = "清空全部日志",
                            onClick = throttle(fn = vm.viewModelScope.launchAsFn {
                                mainVm.dialogFlow.waitResult(
                                    title = "清空日志",
                                    text = "确定删除全部运行日志(共 ${files.size} 个文件)?\n" +
                                        "删除后 App 会继续记录新的日志。",
                                    error = true,
                                )
                                vm.clearAll { count ->
                                    toast("已清空 $count 个日志文件")
                                }
                            }),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.scaffoldPadding(contentPadding),
        ) {
            item(key = "head") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (files.isEmpty()) {
                        Text(
                            text = "还没有运行日志。GKD 会把守卫的每一条决策(跳过/退回/拦截)、" +
                                "订阅异常等都写进日志文件 —— 正常用一会儿(打开几个应用)之后点下面的「刷新」就有内容了。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            files.forEach { item ->
                                FilterChip(
                                    selected = item.name == selected,
                                    label = { Text(text = item.name.logChipLabel()) },
                                    onClick = throttle { vm.select(item.name) },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = buildString {
                                append(current?.name ?: "-")
                                append(" · ").append(current?.size?.logSizeText() ?: "-")
                                current?.mtime?.let {
                                    append(" · 最后写入 ").append(it.format("HH:mm:ss"))
                                }
                                append(" · 共 ").append(entries.size).append(" 条")
                                if (filter.isNotBlank()) {
                                    append(" · 筛选后 ").append(shown.size).append(" 条")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (truncated) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "文件较大, 只显示最近的 2 MB(更早的内容请用下面的「导出」)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    // ★ v122: 这一行**始终**显示 —— 特别是"刚清空完"的那一刻(列表为空)也必须能刷新,
                    //   否则日志很快又写回来了, 用户却没有任何入口让它重新出现(只能退出页面再进)。
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            enabled = !loading,
                            onClick = throttle { vm.reload() },
                        ) { Text(text = if (loading) "读取中…" else "刷新") }
                        TextButton(
                            onClick = throttle {
                                mainVm.showShareLogDlgFlow.value = true
                            },
                        ) { Text(text = "导出") }
                    }
                }
                HorizontalDivider()
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Spacer(modifier = Modifier.height(EmptyHeight))
                    EmptyText(
                        text = if (entries.isEmpty()) "暂无数据" else "没有匹配「${filter.trim()}」的日志"
                    )
                }
            }
            itemsIndexed(
                items = shown,
                key = { index, _ -> index },
            ) { _, entry ->
                LogEntryCard(entry = entry)
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
            item(key = "tail") {
                Spacer(modifier = Modifier.height(EmptyHeight))
            }
        }
    }
}

@Composable
private fun LogEntryCard(entry: String) {
    val lines = remember(entry) { entry.split('\n') }
    val isError = LogFileVm.isErrorEntry(entry)
    val isGuard = LogFileVm.isGuardEntry(entry)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = lines.first(),
            style = MaterialTheme.typography.bodySmall,
            color = when {
                isError -> MaterialTheme.colorScheme.error
                isGuard -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (lines.size > 1) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = lines.drop(1).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** `gkd-20261003.log` → `10-03` */
private fun String.logChipLabel(): String {
    val date = removePrefix("gkd-").removeSuffix(".log")
    return if (date.length == 8) "${date.substring(4, 6)}-${date.substring(6, 8)}" else this
}

private fun Long.logSizeText(): String = when {
    this >= 1024L * 1024L -> "%.1f MB".format(this / 1024.0 / 1024.0)
    this >= 1024L -> "%.0f KB".format(this / 1024.0)
    else -> "$this B"
}
