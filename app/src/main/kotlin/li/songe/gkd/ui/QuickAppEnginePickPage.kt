package li.songe.gkd.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import li.songe.gkd.R
import li.songe.gkd.service.QuickAppRegistry
import li.songe.gkd.store.quickAppEngineListFlow
import li.songe.gkd.ui.component.AnimatedIconButton
import li.songe.gkd.ui.component.AppBarTextField
import li.songe.gkd.ui.component.AppCheckBoxCard
import li.songe.gkd.ui.component.PerfTopAppBar
import li.songe.gkd.ui.component.autoFocus
import li.songe.gkd.ui.icon.BackCloseIcon
import li.songe.gkd.ui.share.LocalMainViewModel
import li.songe.gkd.ui.share.asMutableState
import li.songe.gkd.ui.style.scaffoldPadding
import li.songe.gkd.util.switchItem
import li.songe.gkd.util.throttle

@Serializable
data object QuickAppEnginePickRoute : NavKey

/**
 * fork(v107): 「快应用引擎」手动补充页。
 *
 * 识别引擎的主力是"谁响应 hap:// 链接"(与包名无关), 但厂商自定义 scheme 等极端情况仍可能漏,
 * 所以允许用户在这里把任意应用勾成"快应用引擎": 勾上后 → 被广告拉进它时同样会被秒退, 也能在上一页停用它。
 * (列表内容是"自动记住的识别结果 ∪ 手动补充", 取消勾选即从名单里移除。)
 */
@Composable
fun QuickAppEnginePickPage() {
    val mainVm = LocalMainViewModel.current
    val vm = viewModel<QuickAppEnginePickVm>()
    val appInfos by vm.appInfosFlow.collectAsState()
    val searchStr by vm.searchStrFlow.collectAsState()
    val showSearchBar by vm.showSearchBarFlow.asMutableState()
    val engineList by quickAppEngineListFlow.collectAsState()

    Scaffold(
        topBar = {
            PerfTopAppBar(
                modifier = Modifier.fillMaxWidth(),
                navigationIcon = {
                    IconButton(
                        onClick = throttle {
                            if (showSearchBar && searchStr.isNotEmpty()) {
                                vm.searchStrFlow.value = ""
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
                            value = searchStr,
                            onValueChange = { newValue ->
                                vm.searchStrFlow.value = newValue.trim()
                            },
                            hint = "请输入应用名称/ID",
                            modifier = Modifier.autoFocus(),
                        )
                    } else {
                        Text(text = "快应用引擎")
                    }
                },
                actions = {
                    AnimatedIconButton(
                        onClick = throttle {
                            if (showSearchBar) {
                                if (searchStr.isEmpty()) {
                                    vm.showSearchBarFlow.value = false
                                } else {
                                    vm.searchStrFlow.value = ""
                                }
                            } else {
                                vm.showSearchBarFlow.value = true
                            }
                        },
                        id = R.drawable.ic_anim_search_close,
                        atEnd = showSearchBar,
                    )
                },
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.scaffoldPadding(contentPadding),
        ) {
            item(key = "tip") {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    text = "识别到的快应用引擎会自动出现在这里(勾选状态)。若你的手机有快应用、但上一页没识别出来," +
                        "可以在这里手动把它勾上 —— 勾上后它同样会被\"关闭快应用\"拦截, 也能被停用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(appInfos, { it.id }) { appInfo ->
                val checked = engineList.contains(appInfo.id)
                AppCheckBoxCard(
                    appInfo = appInfo,
                    checked = checked,
                    onCheckedChange = {
                        quickAppEngineListFlow.update { set -> set.switchItem(appInfo.id) }
                        QuickAppRegistry.refresh()
                    },
                )
            }
            item(key = "bottom") {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    text = "当前名单里有 ${engineList.size} 个包名",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    modifier = Modifier.height(16.dp),
                    text = "",
                )
            }
        }
    }
}
