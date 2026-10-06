package li.songe.gkd.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import li.songe.gkd.R
import li.songe.gkd.data.AppInfo
import li.songe.gkd.service.DeviceOrientationGuard
import li.songe.gkd.store.deviceOrientationAppListFlow
import li.songe.gkd.ui.component.AnimatedIconButton
import li.songe.gkd.ui.component.AppBarTextField
import li.songe.gkd.ui.component.AppIcon
import li.songe.gkd.ui.component.AppNameText
import li.songe.gkd.ui.component.PerfCheckbox
import li.songe.gkd.ui.component.PerfTopAppBar
import li.songe.gkd.ui.component.autoFocus
import li.songe.gkd.ui.icon.BackCloseIcon
import li.songe.gkd.ui.share.LocalMainViewModel
import li.songe.gkd.ui.share.asMutableState
import li.songe.gkd.ui.style.appItemPadding
import li.songe.gkd.ui.style.scaffoldPadding
import li.songe.gkd.util.switchItem
import li.songe.gkd.util.throttle
import li.songe.gkd.util.toast

@Serializable
data object DeviceOrientationAppListRoute : NavKey

/**
 * fork(v119): 「设备动作与方向」页 —— 防摇一摇广告的**根因防护**入口。
 *
 * 摇一摇广告能跳转, 根因是应用在**开屏那几秒**读到了加速度计/陀螺仪。国产 ROM 从 2024 年底起给了
 * 系统级选项: vivo OriginOS 5「仅开屏禁止」/ 小米 HyperOS 3「仅开屏时拒绝」——
 * **只在开屏那几秒拒绝传感器**, 应用内的正常摇一摇照常可用。
 *
 * 这个开关在 ROM 自己的权限框架里(vivo 上不是标准 AppOps op, 见交接文档 §10.5),
 * 第三方应用**读不到也写不了**, 所以本页做的是三件事:
 *   ① 只读自检(把本机 op/权限枚举一遍, 把证据摆出来 —— 让"有没有可编程入口"可核对);
 *   ② 每个应用一键跳到系统「获取设备动作与方向」权限页(多 ROM 候选 + 通用兜底);
 *   ③ 勾选记录"这个应用我已经设过了"(系统状态读不到, 只能自报), 免得设了一半忘了。
 *
 * ⚠️ 本页**不会改动系统设置**: 勾选只是记录。真正的拦截由系统完成;
 *   GKD 自己的「防摇一摇广告」([li.songe.gkd.service.ShakeGuard], 开屏点掉可点的跳过/关闭按钮)
 *   与「摇一摇跳转防护」([li.songe.gkd.service.JumpGuard], 退回原应用) 保留作兜底。
 */
@Composable
fun DeviceOrientationAppListPage() {
    val mainVm = LocalMainViewModel.current
    val vm = viewModel<DeviceOrientationAppListVm>()
    val appInfos by vm.appInfosFlow.collectAsState()
    val searchStr by vm.searchStrFlow.collectAsState()
    val showSearchBar by vm.showSearchBarFlow.asMutableState()
    val doneList by deviceOrientationAppListFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var probe by remember { mutableStateOf<DeviceOrientationGuard.ProbeResult?>(null) }
    // ROM 对那一项的原文叫法(vivo = 「访问设备动作与方向」/「仅开屏时禁止」; 小米 = 「获取设备动作与方向」/「仅开屏时拒绝」)
    val itemName = remember { DeviceOrientationGuard.itemName() }
    val optionName = remember { DeviceOrientationGuard.targetOptionName() }

    // 只在进页面时跑一次(结果在 DeviceOrientationGuard 里缓存); 不在前台切换时跑, 避免日志噪音
    LaunchedEffect(Unit) {
        probe = withContext(Dispatchers.IO) { DeviceOrientationGuard.probe() }
    }

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
                        Text(text = "设备动作与方向")
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
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        text = "摇一摇广告能跳转, 根因是应用在开屏那几秒读到了加速度计/陀螺仪 —— " +
                            "系统里把这一项叫「$itemName」。把它的权限设成「$optionName」= " +
                            "只在开屏那几秒拒绝传感器: 摇一摇广告晃不动手机, 应用内的正常摇一摇照常可用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        modifier = Modifier.padding(top = 6.dp),
                        text = "⚠️ 这个开关在 ROM 自己的权限框架里, 第三方应用读不到也写不了 —— GKD 不能替你改。" +
                            "所以这里只做两件事: 一键跳到系统权限页 + 记下你设过哪些应用(勾选仅为记录, 不改动系统设置)。" +
                            "GKD 自己的「防摇一摇广告」(开屏点掉跳过/关闭按钮) 与「摇一摇跳转防护」(退回原应用) 保留作兜底。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "probe") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    val p = probe
                    val conclusion = when {
                        p == null -> "正在自检本机是否可编程控制该权限…" to MaterialTheme.colorScheme.onSurfaceVariant
                        p.hasProgrammaticHandle -> "自检结论: 本机存在可编程入口 " +
                            (p.opNames + p.permissionNames).joinToString() to MaterialTheme.colorScheme.primary

                        !p.enumerationTrusted -> "自检结论: 没能枚举本机 op(" + p.opEnumSource +
                            "), 无法判断 —— 请仍按系统设置手动开" to MaterialTheme.colorScheme.error

                        else -> "自检结论: 已枚举本机 " + p.opCount +
                            " 个 op, 未发现「设备动作与方向」类入口 ⇒ 只能在系统设置里手动设置" to
                            MaterialTheme.colorScheme.error
                    }
                    Text(
                        text = conclusion.first,
                        style = MaterialTheme.typography.bodyMedium,
                        color = conclusion.second,
                    )
                    if (p != null) {
                        Text(
                            modifier = Modifier.padding(top = 4.dp),
                            text = "证据: op 枚举来源 = " + p.opEnumSource +
                                "; 候选权限命中 = " +
                                (p.permissionNames.ifEmpty { listOf("无") }).joinToString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (p.opNames.isNotEmpty()) {
                            Text(
                                modifier = Modifier.padding(top = 2.dp),
                                text = "· 设备动作与方向类 op: " + p.opNames.joinToString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (p.sensorOpNames.isNotEmpty()) {
                            Text(
                                modifier = Modifier.padding(top = 2.dp),
                                text = "· 其它传感器类 op(不是这一项): " + p.sensorOpNames.joinToString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        modifier = Modifier.padding(top = 6.dp),
                        text = "本机怎么找: " + DeviceOrientationGuard.menuHint(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // ★ 真机实测(2026-10-02, 联想平板 TB710FU / ZUXOS): 不少 ROM **根本没有这一项**
                    //   (把那台机器的权限/安全中心 APK 全量字符串搜了一遍, 「设备动作与方向/获取设备方向/仅开屏」0 命中)。
                    //   所以自检说"无可编程入口"时必须补一句"找不到是正常的", 否则用户会在设置里白找半天。
                    if (p != null && !p.hasProgrammaticHandle) {
                        Text(
                            modifier = Modifier.padding(top = 4.dp),
                            text = "找不到就是这台 ROM 没提供这一项 —— 目前确认提供的有 vivo(OriginOS 5 起) 与小米(HyperOS 3); " +
                                "联想/摩托等不少 ROM 压根没有这个设置。这种情况请用 GKD 自己的兜底: 上面的「防摇一摇广告」与「摇一摇跳转防护」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = throttle {
                            scope.launch {
                                probe = withContext(Dispatchers.IO) {
                                    DeviceOrientationGuard.probe(force = true)
                                }
                                toast("已重新自检", forced = true)
                            }
                        }) { Text("重新自检") }
                    }
                }
            }
            items(appInfos, { it.id }) { appInfo ->
                DeviceOrientationAppRow(
                    appInfo = appInfo,
                    checked = doneList.contains(appInfo.id),
                    onCheckedChange = {
                        deviceOrientationAppListFlow.update { set -> set.switchItem(appInfo.id) }
                    },
                    onOpenSettings = {
                        val via = DeviceOrientationGuard.openPermissionPage(appInfo.id)
                        if (via == "app-details") {
                            toast("已打开该应用的详情页(本机没有匹配的权限页入口), 请手动进「权限」里找「$itemName」")
                        } else {
                            toast("已打开系统权限页($via), 请把「$itemName」设为「$optionName」")
                        }
                    },
                )
            }
            item(key = "bottom") {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    text = "已记录 " + doneList.size + " 个应用已设为「仅开屏禁止」" +
                        if (doneList.isEmpty()) "(为空 = 还没设过; 勾选只是记录, 不改动系统设置)" else "",
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

/**
 * 单个应用行: 点整行 = 记录/取消"已设为仅开屏禁止"; 点右侧「去设置」= 跳系统权限页。
 *
 * 语义: 不给整行 `clearAndSetSemantics`(那会把「去设置」从无障碍树里抹掉), 而是各自带 onClickLabel,
 * 读屏时"整行"与"去设置"两个动作都可达。
 */
@Composable
private fun DeviceOrientationAppRow(
    appInfo: AppInfo,
    checked: Boolean,
    onCheckedChange: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(
                onClickLabel = if (checked) "取消记录" else "记录为已设为仅开屏禁止",
                onClick = throttle(onCheckedChange),
            )
            .appItemPadding(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(appId = appInfo.id)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            AppNameText(appInfo = appInfo)
            Text(
                text = appInfo.id,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
        }
        Text(
            text = if (checked) "去改" else "去设置",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(
                onClickLabel = "打开系统「获取设备动作与方向」权限页",
                onClick = throttle(onOpenSettings),
            ),
        )
        PerfCheckbox(
            key = appInfo.id,
            checked = checked,
        )
    }
}
