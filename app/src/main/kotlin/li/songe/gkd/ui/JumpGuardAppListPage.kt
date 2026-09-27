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
import li.songe.gkd.store.jumpGuardAppListFlow
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
data object JumpGuardAppListRoute : NavKey

/**
 * fork(v106): 「跳转防护应用」选择页。
 * 勾选后, 这些 App **刚打开时**(开屏 1.8 秒内)若在 GKD 没有任何点击动作的情况下跳到别的应用
 * (摇一摇广告的典型行为), 会立刻按返回退回原页面。未勾选的应用一律不拦截。
 */
@Composable
fun JumpGuardAppListPage() {
    val mainVm = LocalMainViewModel.current
    val vm = viewModel<JumpGuardAppListVm>()
    val appInfos by vm.appInfosFlow.collectAsState()
    val searchStr by vm.searchStrFlow.collectAsState()
    val showSearchBar by vm.showSearchBarFlow.asMutableState()
    val guardList by jumpGuardAppListFlow.collectAsState()

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
                        Text(text = "跳转防护应用")
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
                    text = "勾选需要拦截「开屏跳转」的 App(默认空, 需自行添加): 这些 App 刚打开时, 若在没有点击任何东西的情况下跳到别的应用(摇一摇广告的典型行为), 会立刻退回原页面。" +
                        "未勾选的应用不做任何拦截; 若某个 App 的正常流程(如登录/支付跳转)被拦, 取消勾选即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(appInfos, { it.id }) { appInfo ->
                val checked = guardList.contains(appInfo.id)
                AppCheckBoxCard(
                    appInfo = appInfo,
                    checked = checked,
                    onCheckedChange = {
                        jumpGuardAppListFlow.update { set -> set.switchItem(appInfo.id) }
                    },
                )
            }
            item(key = "bottom") {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    text = "已选 ${guardList.size} 个应用" + if (guardList.isEmpty()) "(为空 = 不做任何拦截)" else "",
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
