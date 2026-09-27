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
import li.songe.gkd.store.guardAssocAppListFlow
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
data object GuardAssocAppListRoute : NavKey

/**
 * fork(v99): 「守护关联应用」选择页。
 * 勾选后, 这些 App 回到前台时若 GKD 无障碍被系统清除, 将立即自动恢复(关联应用守护)。
 */
@Composable
fun GuardAssocAppListPage() {
    val mainVm = LocalMainViewModel.current
    val vm = viewModel<GuardAssocAppListVm>()
    val appInfos by vm.appInfosFlow.collectAsState()
    val searchStr by vm.searchStrFlow.collectAsState()
    val showSearchBar by vm.showSearchBarFlow.asMutableState()
    val assocList by guardAssocAppListFlow.collectAsState()

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
                        Text(text = "守护关联应用")
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
                    text = "勾选需要守护的 App(如微信、网易云): 这些 App 被打开时, 若系统清除了 GKD 的无障碍, 会自动立即恢复(需已授予「使用情况访问权限」与「写入安全设置权限」)。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(appInfos, { it.id }) { appInfo ->
                val checked = assocList.contains(appInfo.id)
                AppCheckBoxCard(
                    appInfo = appInfo,
                    checked = checked,
                    onCheckedChange = {
                        guardAssocAppListFlow.update { set -> set.switchItem(appInfo.id) }
                    },
                )
            }
            item(key = "bottom") {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    text = "已守护 ${assocList.size} 个应用",
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
