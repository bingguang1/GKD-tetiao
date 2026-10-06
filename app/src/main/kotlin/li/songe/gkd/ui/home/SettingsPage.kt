package li.songe.gkd.ui.home

import android.view.KeyEvent
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import li.songe.gkd.MainActivity
import li.songe.gkd.R
import li.songe.gkd.permission.canDrawOverlaysState
import li.songe.gkd.permission.foregroundServiceSpecialUseState
import li.songe.gkd.permission.ignoreBatteryOptimizationsState
import li.songe.gkd.permission.notificationState
import li.songe.gkd.permission.requiredPermission
import li.songe.gkd.service.DeviceOrientationGuard
import li.songe.gkd.service.FakeSkipGuard
import li.songe.gkd.service.QuickAppRegistry
import li.songe.gkd.service.StatusService
import li.songe.gkd.service.TrackService
import li.songe.gkd.service.fixRestartAutomatorService
import li.songe.gkd.shizuku.shizukuContextFlow
import li.songe.gkd.store.deviceOrientationAppListFlow
import li.songe.gkd.store.guardAssocAppListFlow
import li.songe.gkd.store.jumpGuardAppListFlow
import li.songe.gkd.store.storeFlow
import li.songe.gkd.ui.AboutRoute
import li.songe.gkd.ui.AdvancedPageRoute
import li.songe.gkd.ui.BlockA11yAppListRoute
import li.songe.gkd.ui.GuardAssocAppListRoute
import li.songe.gkd.ui.DeviceOrientationAppListRoute
import li.songe.gkd.ui.JumpGuardAppListRoute
import li.songe.gkd.ui.QuickAppEngineRoute
import li.songe.gkd.ui.component.CustomOutlinedTextField
import li.songe.gkd.ui.component.FullscreenDialog
import li.songe.gkd.ui.component.PerfCustomIconButton
import li.songe.gkd.ui.component.PerfIcon
import li.songe.gkd.ui.component.PerfIconButton
import li.songe.gkd.ui.component.PerfTopAppBar
import li.songe.gkd.ui.component.SettingItem
import li.songe.gkd.ui.component.TextListDialog
import li.songe.gkd.ui.component.TextMenu
import li.songe.gkd.ui.component.TextSwitch
import li.songe.gkd.ui.component.autoFocus
import li.songe.gkd.ui.component.updateDialogOptions
import li.songe.gkd.ui.component.useScrollBehaviorState
import li.songe.gkd.ui.component.waitResult
import li.songe.gkd.ui.share.LocalMainViewModel
import li.songe.gkd.ui.share.asMutableState
import li.songe.gkd.ui.style.EmptyHeight
import li.songe.gkd.ui.style.iconTextSize
import li.songe.gkd.ui.style.itemHorizontalPadding
import li.songe.gkd.ui.style.titleItemPadding
import li.songe.gkd.util.AndroidTarget
import li.songe.gkd.util.BackupUtils
import li.songe.gkd.util.DarkThemeOption
import li.songe.gkd.util.findOption
import li.songe.gkd.util.JumpGuardWindowOption
import li.songe.gkd.util.launchAsFn
import li.songe.gkd.util.mapState
import li.songe.gkd.util.openAppDetailsSettings
import li.songe.gkd.util.saveFileToDownloads
import li.songe.gkd.util.shareFile
import li.songe.gkd.util.throttle
import li.songe.gkd.util.toast

@Composable
fun useSettingsPage(): ScaffoldExt {
    val mainVm = LocalMainViewModel.current
    val context = LocalActivity.current as MainActivity
    val store by storeFlow.collectAsState()
    val vm = viewModel<HomeVm>()

    var showToastInputDlg by vm.showToastInputDlgFlow.asMutableState()

    if (showToastInputDlg) {
        var value by remember {
            mutableStateOf(store.actionToast)
        }
        val maxCharLen = 64
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "触发提示")
                    PerfIconButton(
                        imageVector = PerfIcon.HelpOutline,
                        contentDescription = "文案规则",
                        onClickLabel = "打开文案规则弹窗",
                        onClick = throttle {
                            showToastInputDlg = false
                            val confirmAction = {
                                mainVm.dialogFlow.value = null
                                showToastInputDlg = true
                            }
                            mainVm.dialogFlow.updateDialogOptions(
                                title = "文案规则",
                                text = $$"触发文案支持变量替换，规则如下\n${1} 子规则名称\n${2} 规则名称\n${3} 触发次数\n\n示例模板\n${1}/${2}/${3}\n\n替换结果\n子规则a/规则A/3",
                                confirmAction = confirmAction,
                                onDismissRequest = confirmAction,
                            )
                        },
                    )
                }
            },
            text = {
                OutlinedTextField(
                    value = value,
                    placeholder = {
                        Text(text = "请输入提示内容")
                    },
                    onValueChange = {
                        value = it.take(maxCharLen)
                    },
                    supportingText = {
                        Text(
                            text = "${value.length} / $maxCharLen",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.End,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .autoFocus()
                )
            },
            onDismissRequest = { showToastInputDlg = false },
            confirmButton = {
                TextButton(enabled = value.isNotEmpty(), onClick = {
                    if (value != storeFlow.value.actionToast) {
                        storeFlow.update { it.copy(actionToast = value) }
                        toast("更新成功")
                    }
                    showToastInputDlg = false
                }) {
                    Text(text = "确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showToastInputDlg = false }) {
                    Text(text = "取消")
                }
            }
        )
    }

    var showNotifTextInputDlg by vm.showNotifTextInputDlgFlow.asMutableState()
    if (showNotifTextInputDlg) {
        var titleValue by remember { mutableStateOf(store.customNotifTitle) }
        var textValue by remember { mutableStateOf(store.customNotifText) }
        AlertDialog(
            properties = DialogProperties(dismissOnClickOutside = false),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "通知文案")
                    PerfIconButton(
                        imageVector = PerfIcon.HelpOutline,
                        contentDescription = "文案规则",
                        onClickLabel = "打开文案规则弹窗",
                        onClick = throttle {
                            showNotifTextInputDlg = false
                            val confirmAction = {
                                mainVm.dialogFlow.value = null
                                showNotifTextInputDlg = true
                            }
                            mainVm.dialogFlow.updateDialogOptions(
                                title = "文案规则",
                                text = $$"通知文案支持变量替换，规则如下\n${i} 全局规则数\n${k} 应用数\n${u} 应用规则数\n${n} 触发次数\n\n示例模板\n${i}全局/${k}应用/${u}规则/${n}触发\n\n替换结果\n0全局/1应用/2规则/3触发",
                                confirmAction = confirmAction,
                                onDismissRequest = confirmAction,
                            )
                        },
                    )
                }
            },
            text = {
                val titleMaxLen = 32
                val textMaxLen = 64
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CustomOutlinedTextField(
                        label = { Text("主标题") },
                        value = titleValue,
                        placeholder = { Text(text = "请输入内容，支持变量替换") },
                        onValueChange = {
                            titleValue = (if (it.length > titleMaxLen) it.take(titleMaxLen) else it)
                                .filter { c -> c !in "\n\r" }
                        },
                        supportingText = {
                            Text(
                                text = "${titleValue.length} / $titleMaxLen",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.End,
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    CustomOutlinedTextField(
                        label = { Text("副标题") },
                        value = textValue,
                        placeholder = { Text(text = "请输入内容，支持变量替换") },
                        onValueChange = {
                            textValue = if (it.length > textMaxLen) it.take(textMaxLen) else it
                        },
                        supportingText = {
                            Text(
                                text = "${textValue.length} / $textMaxLen",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.End,
                            )
                        },
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .autoFocus(),
                        contentPadding = PaddingValues(12.dp),
                    )
                }
            },
            onDismissRequest = {
                showNotifTextInputDlg = false
            },
            confirmButton = {
                TextButton(onClick = {
                    context.justHideSoftInput()
                    if (store.customNotifTitle != textValue || store.customNotifText != textValue) {
                        storeFlow.update {
                            it.copy(
                                customNotifTitle = titleValue,
                                customNotifText = textValue
                            )
                        }
                        toast("更新成功")
                    }
                    showNotifTextInputDlg = false
                }) {
                    Text(
                        text = "确认",
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotifTextInputDlg = false }) {
                    Text(
                        text = "取消",
                    )
                }
            })
    }


    var showA11yBlockDlg by vm.showA11yBlockDlgFlow.asMutableState()
    if (showA11yBlockDlg) {
        BlockA11yDialog(onDismissRequest = { showA11yBlockDlg = false })
    }
    if (vm.showBackupDlgFlow.collectAsState().value) {
        TextListDialog(
            onDismiss = { vm.showBackupDlgFlow.value = false },
            textList = listOf(
                "导入备份" to vm.viewModelScope.launchAsFn(Dispatchers.IO) {
                    val uri = context.pickFile("application/zip")
                    if (uri != null) {
                        BackupUtils.importBackUpData(uri)
                    }
                },
                "导出备份" to {
                    vm.showExportBackupDlgFlow.value = true
                },
            )
        )
    }
    if (vm.showExportBackupDlgFlow.collectAsState().value) {
        TextListDialog(
            onDismiss = { vm.showExportBackupDlgFlow.value = false },
            textList = listOf(
                "分享到其他应用" to vm.viewModelScope.launchAsFn(Dispatchers.IO) {
                    val file = BackupUtils.exportBackUpData()
                    context.shareFile(file, "分享备份文件")
                },
                "保存到下载" to vm.viewModelScope.launchAsFn(Dispatchers.IO) {
                    val file = BackupUtils.exportBackUpData()
                    context.saveFileToDownloads(file)
                },
            )
        )
    }

    val scrollKey = rememberSaveable { mutableIntStateOf(0) }
    val (scrollBehavior, scrollState) = useScrollBehaviorState(scrollKey)
    LaunchedEffect(null) {
        mainVm.resetPageScrollEvent.collect {
            if (it == BottomNavItem.Settings) {
                scrollKey.intValue++
            }
        }
    }
    return ScaffoldExt(
        navItem = BottomNavItem.Settings,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PerfTopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    Text(
                        text = BottomNavItem.Settings.label,
                    )
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .padding(contentPadding)
        ) {

            Text(
                text = "常规",
                modifier = Modifier.titleItemPadding(showTop = false),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            val showToastSettingsDlg by vm.showToastSettingsDlgFlow.asMutableState()
            TextSwitch(
                title = "触发提示",
                subtitle = store.actionToast,
                checked = store.toastWhenClick,
                onClickLabel = "打开触发提示弹窗",
                onClick = {
                    showToastInputDlg = true
                },
                suffixIcon = {
                    PerfCustomIconButton(
                        size = 32.dp,
                        iconSize = 20.dp,
                        onClickLabel = "打开提示设置弹窗",
                        onClick = { vm.showToastSettingsDlgFlow.update { !it } },
                        id = R.drawable.ic_page_info,
                        contentDescription = "提示设置",
                        tint = if (showToastSettingsDlg) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                },
                onCheckedChange = {
                    storeFlow.value = store.copy(
                        toastWhenClick = it
                    )
                })

            AnimatedVisibility(visible = showToastSettingsDlg) {
                Column {
                    TextSwitch(
                        title = "提示样式",
                        subtitle = "使用系统样式",
                        suffix = "查看限制",
                        onSuffixClick = {
                            mainVm.dialogFlow.updateDialogOptions(
                                title = "限制说明",
                                text = "系统 Toast 存在频率限制, 触发过于频繁会被系统强制不显示\n\n如果只使用开屏一类低频率规则可使用系统提示, 否则建议关闭此项使用自定义样式提示",
                            )
                        },
                        checked = store.useSystemToast,
                        onCheckedChange = {
                            storeFlow.value = store.copy(
                                useSystemToast = it
                            )
                        })
                    TextSwitch(
                        title = "轨迹提示",
                        subtitle = "显示触发位置信息",
                        checked = TrackService.isRunning.collectAsState().value,
                        onCheckedChange = vm.viewModelScope.launchAsFn<Boolean> {
                            if (it) {
                                mainVm.dialogFlow.waitResult(
                                    title = "使用须知",
                                    text = "开启「轨迹提示」后点击或滑动后会在屏幕上使用悬浮窗绘制轨迹(一段时间后消失)，如果新触摸事件恰好在悬浮窗区域内，可能会被目标应用拒绝，从而导致点击或滑动无响应",
                                    confirmText = "继续",
                                )
                                requiredPermission(context, foregroundServiceSpecialUseState)
                                requiredPermission(context, notificationState)
                                requiredPermission(context, canDrawOverlaysState)
                                TrackService.start()
                            } else {
                                TrackService.stop()
                            }
                        }
                    )
                }
            }

            TextSwitch(
                title = "防摇一摇广告",
                subtitle = "兜底手段: 打开应用后的「开屏时长」内, 自动点掉广告上真正可点的\"跳过/关闭\"按钮(免root); " +
                    "已经被晃走的情况由下面的「摇一摇跳转防护」退回来。根因防护见下面的「设备动作与方向」",
                checked = store.shakeGuard,
                onClickLabel = "切换防摇一摇广告开关",
                onCheckedChange = {
                    storeFlow.value = store.copy(
                        shakeGuard = it
                    )
                })

            // fork(v119): 「防摇一摇」的根因防护入口 = 系统权限「访问/获取设备动作与方向」→「仅开屏时禁止」。
            // 这个开关在 ROM 自己的权限框架里(vivo 上不是标准 AppOps op, 见 §10.5), GKD 读不到也写不了,
            // 所以这里给的是"引导 + 自检 + 清单"入口, 真正的拦截由系统完成。
            // ★★ 真机实测(2026-10-02)教训: 这一行**不能**挂在 `store.shakeGuard` 的 AnimatedVisibility 里 ——
            //    用户手机上 `shakeGuard=false`(把「防摇一摇广告」这个**兜底**关掉了), 于是根因防护的入口**整个消失**,
            //    用户根本找不到它。两者是**不同机制**(一个点广告、一个掐传感器), 入口必须常显。
            run {
                val orientationList by deviceOrientationAppListFlow.collectAsState()
                val itemName = DeviceOrientationGuard.itemName()
                val optionName = DeviceOrientationGuard.targetOptionName()
                SettingItem(
                    title = "设备动作与方向 (${orientationList.size})",
                    subtitle = if (orientationList.isEmpty()) {
                        "根因防护: 在系统里把「$itemName」设为「$optionName」" +
                            "—— 只在开屏那几秒拒绝传感器, 应用内摇一摇照常可用; 点击进入引导"
                    } else {
                        "已记录 ${orientationList.size} 个应用设为「$optionName」, 点击查看/继续设置"
                    },
                    onClickLabel = "进入设备动作与方向页面",
                    onClick = {
                        mainVm.navigatePage(DeviceOrientationAppListRoute)
                    })
            }

            TextSwitch(
                title = "无障碍自动守护",
                subtitle = "熄屏/后台被系统关闭后自动恢复, 开机自启(需先执行一次ADB授权)",
                checked = store.autoRestoreA11y,
                onClickLabel = "切换无障碍自动守护开关",
                onCheckedChange = {
                    storeFlow.value = store.copy(
                        autoRestoreA11y = it
                    )
                })

            val guardAssocList by guardAssocAppListFlow.collectAsState()
            TextSwitch(
                title = "关联应用守护",
                subtitle = "打开关联的App时若无障碍被清除立即恢复(更快更稳)",
                checked = store.enableGuardAssoc,
                onClickLabel = "切换关联应用守护开关",
                onCheckedChange = {
                    storeFlow.value = store.copy(
                        enableGuardAssoc = it
                    )
                })
            AnimatedVisibility(visible = store.enableGuardAssoc) {
                SettingItem(
                    title = "守护关联应用",
                    subtitle = "已守护 ${guardAssocList.size} 个应用, 点击设置",
                    onClickLabel = "进入守护关联应用页面",
                    onClick = {
                        mainVm.navigatePage(GuardAssocAppListRoute)
                    })
            }

            TextSwitch(
                title = "假跳过防护",
                subtitle = "点击\"跳过\"后校验落点: 被带到广告落地页时立即返回, 并停止在该应用点不可点的跳过文字",
                checked = store.fakeSkipGuard,
                onClickLabel = "切换假跳过防护开关",
                onCheckedChange = {
                    storeFlow.value = store.copy(
                        fakeSkipGuard = it
                    )
                })
            run {
                val skipVetoList = store.fakeSkipVetoApps.split('\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                AnimatedVisibility(visible = store.fakeSkipGuard && skipVetoList.isNotEmpty()) {
                    SettingItem(
                        title = "已降级应用 (${skipVetoList.size})",
                        subtitle = "这些应用里不再自动点击不可点的跳过文字, 点击恢复",
                        onClickLabel = "清空假跳过降级名单",
                        onClick = {
                            FakeSkipGuard.clearVetoApps()
                        })
                }
            }

            TextSwitch(
                title = "摇一摇跳转防护",
                subtitle = "在下面选定的应用里: 打开后的「开屏时长」内(默认 8 秒)跳到别的应用(浏览器/市场/落地页)时, 判为摇一摇广告跳转并立刻退回原页面",
                checked = store.jumpGuard,
                onClickLabel = "切换摇一摇跳转防护开关",
                onCheckedChange = {
                    storeFlow.value = store.copy(
                        jumpGuard = it
                    )
                })
            run {
                val jumpGuardList by jumpGuardAppListFlow.collectAsState()
                AnimatedVisibility(visible = store.jumpGuard) {
                    Column {
                        // fork(v108): 开屏时长改为用户可配 —— 原来写死 1.8 秒, 真机上摇一摇广告常在
                        // 开屏 3~5 秒后才被晃走, 于是"功能看起来没生效"
                        TextMenu(
                            title = "开屏时长",
                            option = JumpGuardWindowOption.objects.findOption(store.jumpGuardWindowMs),
                            onOptionChange = {
                                storeFlow.update { s -> s.copy(jumpGuardWindowMs = it.value) }
                            },
                        )
                        SettingItem(
                            title = "跳转防护应用 (${jumpGuardList.size})",
                            subtitle = if (jumpGuardList.isEmpty()) {
                                "默认为空 = 不做任何拦截, 点击添加要防护的应用"
                            } else {
                                "只在这些应用里拦截开屏跳转, 点击修改"
                            },
                            onClickLabel = "进入跳转防护应用选择页",
                            onClick = {
                                mainVm.navigatePage(JumpGuardAppListRoute)
                            })
                        SettingItem(
                            title = "",
                            subtitle = "开屏时长 = **当前页面**出现后多久之内算\"开屏\"(「防摇一摇广告」与「摇一摇跳转防护」共用这个值)。" +
                                "跳转防护还有一个兜底: **从开屏/广告页(Splash/Ad 之类)跳走时按 15 秒判** —— 所以这里调小也不会漏掉开屏广告。" +
                                "想知道哪些应用总在开屏时跳走, 可在日志里搜 \"JumpGuard not-guarded\"。",
                            imageVector = null,
                        )
                    }
                }
            }

            TextSwitch(
                title = "关闭快应用",
                subtitle = "被广告拉进\"快应用引擎\"时立刻退回原应用(快应用是厂商预装的运行环境, 广告常借它自动下载 APK)",
                checked = store.quickAppGuard,
                onClickLabel = "切换关闭快应用开关",
                onCheckedChange = {
                    storeFlow.value = store.copy(
                        quickAppGuard = it
                    )
                })
            run {
                val engines by QuickAppRegistry.enginesFlow.collectAsState()
                SettingItem(
                    title = "快应用引擎 (${engines.size})",
                    subtitle = if (engines.isEmpty()) {
                        "未识别到快应用引擎, 点击查看说明/手动补充"
                    } else {
                        "已停用 ${engines.count { it.disabled }} 个; 点击可停用引擎、禁止引擎安装应用(需 Shizuku 或一键 ADB)"
                    },
                    onClickLabel = "进入快应用引擎页",
                    onClick = {
                        mainVm.navigatePage(QuickAppEngineRoute)
                    })
            }

            TextSwitch(
                title = "坐标点击守卫",
                subtitle = "拒绝在\"空/反向矩形\"的节点上打盲坐标(真机实证: 微信朋友圈广告会因此点到广告上)",
                checked = store.strictClickGuard,
                onClickLabel = "切换坐标点击守卫开关",
                onCheckedChange = {
                    storeFlow.value = store.copy(
                        strictClickGuard = it
                    )
                })
            AnimatedVisibility(visible = store.strictClickGuard) {
                TextSwitch(
                    title = "同时拒绝不可见节点内的坐标",
                    subtitle = "若有规则故意用\"用户看不到的节点\"当坐标原点, 关掉这一条即可(不影响上面的守卫)",
                    checked = store.guardInvisibleNode,
                    onClickLabel = "切换不可见节点坐标守卫开关",
                    onCheckedChange = {
                        storeFlow.value = store.copy(
                            guardInvisibleNode = it
                        )
                    })
            }

            val subsStatus by vm.subsStatusFlow.collectAsState()
            TextSwitch(
                title = "通知文案",
                subtitle = if (store.useCustomNotifText) {
                    store.customNotifTitle + " / " + store.customNotifText
                } else {
                    subsStatus
                },
                checked = store.useCustomNotifText,
                onClickLabel = "打开修改通知文案弹窗",
                onClick = { showNotifTextInputDlg = true },
                onCheckedChange = {
                    storeFlow.value = store.copy(
                        useCustomNotifText = it
                    )
                })

            TextSwitch(
                title = "后台隐藏",
                subtitle = "在「最近任务」隐藏卡片",
                checked = store.excludeFromRecents,
                onCheckedChange = vm.viewModelScope.launchAsFn<Boolean> {
                    if (it) {
                        mainVm.dialogFlow.waitResult(
                            title = "后台隐藏",
                            text = "隐藏卡片后可能导致部分设备无法给任务卡片加锁后台，建议先加锁后再隐藏，若已加锁或没有锁后台机制请继续",
                            confirmText = "继续",
                        )
                    }
                    storeFlow.value = store.copy(
                        excludeFromRecents = !store.excludeFromRecents
                    )
                })

            val scope = rememberCoroutineScope()
            val lazyOn = remember {
                storeFlow.mapState(scope) { it.enableBlockA11yAppList }.debounce(300)
                    .stateIn(scope, SharingStarted.Eagerly, store.enableBlockA11yAppList)
            }.collectAsState()
            AnimatedVisibility(visible = lazyOn.value) {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .titleItemPadding(),
                    text = "无障碍",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TextSwitch(
                title = "局部关闭",
                subtitle = "白名单内关闭服务",
                checked = store.enableBlockA11yAppList && shizukuContextFlow.collectAsState().value.ok,
                onCheckedChange = vm.viewModelScope.launchAsFn<Boolean> {
                    if (it) {
                        showA11yBlockDlg = true
                    } else {
                        storeFlow.value = store.copy(enableBlockA11yAppList = false)
                        fixRestartAutomatorService(userAck = true)
                    }
                },
            )
            AnimatedVisibility(visible = lazyOn.value) {
                SettingItem(title = "白名单", onClickLabel = "进入无障碍白名单页面", onClick = {
                    mainVm.navigatePage(BlockA11yAppListRoute)
                })
            }

            Text(
                text = "外观",
                modifier = Modifier.titleItemPadding(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            TextMenu(
                title = "深色模式",
                option = DarkThemeOption.objects.findOption(store.enableDarkTheme),
                onOptionChange = {
                    storeFlow.update { s -> s.copy(enableDarkTheme = it.value) }
                }
            )

            if (AndroidTarget.S) {
                TextSwitch(
                    title = "动态配色",
                    checked = store.enableDynamicColor,
                    onCheckedChange = {
                        storeFlow.update { s -> s.copy(enableDynamicColor = it) }
                    }
                )
            }

            Text(
                text = "其他",
                modifier = Modifier.titleItemPadding(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            SettingItem(title = "高级设置", onClick = {
                mainVm.navigatePage(AdvancedPageRoute)
            })
            SettingItem(title = "备份恢复", onClick = {
                vm.showBackupDlgFlow.value = true
            })

            SettingItem(title = "关于", onClick = {
                mainVm.navigatePage(AboutRoute)
            })

            Spacer(modifier = Modifier.height(EmptyHeight))
        }
    }
}

@Composable
private fun BlockA11yDialog(onDismissRequest: () -> Unit) = FullscreenDialog(onDismissRequest) {
    val mainVm = LocalMainViewModel.current
    val statusRunning by StatusService.isRunning.collectAsState()
    val shizukuContext by shizukuContextFlow.collectAsState()
    val ignoreBatteryOptimizations by ignoreBatteryOptimizationsState.stateFlow.collectAsState()
    val context = LocalActivity.current as MainActivity
    Scaffold(
        topBar = {
            PerfTopAppBar(
                navigationIcon = {
                    PerfIconButton(
                        imageVector = PerfIcon.Close,
                        onClickLabel = "关闭弹窗",
                        onClick = onDismissRequest,
                    )
                },
                title = {
                    Text(text = "局部关闭")
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    enabled = shizukuContext.ok && statusRunning && ignoreBatteryOptimizations,
                    onClick = mainVm.viewModelScope.launchAsFn {
                        onDismissRequest()
                        delay(200)
                        storeFlow.update { it.copy(enableBlockA11yAppList = true) }
                    }
                ) {
                    Text(text = "继续")
                }
                Spacer(modifier = Modifier.width(itemHorizontalPadding))
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = itemHorizontalPadding)
        ) {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium) {
                Text(text = "「局部关闭」可在白名单应用内关闭服务，来解决界面异常，游戏掉帧或无障碍检测的问题")
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "使用须知", style = MaterialTheme.typography.titleMedium)
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RequiredTextItem(text = "切换服务会造成短暂触摸卡顿，请自行测试后再编辑白名单")
                    RequiredTextItem(text = "使用其它无障碍应用可能导致优化无效，可在服务关闭后自行确认")
                    RequiredTextItem(text = "必须确保服务关闭后的持续后台运行，否则会被系统暂停或结束运行导致重启失败")
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "使用条件", style = MaterialTheme.typography.titleMedium)
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RequiredTextItem(
                        text = "Shizuku 授权",
                        enabled = !shizukuContext.ok,
                        imageVector = if (shizukuContext.ok) PerfIcon.Check else PerfIcon.ArrowForward,
                        onClick = mainVm.viewModelScope.launchAsFn(Dispatchers.IO) {
                            mainVm.guardShizukuContext()
                        },
                    )
                    RequiredTextItem(
                        text = "开启「常驻通知」",
                        enabled = !statusRunning,
                        imageVector = if (statusRunning) PerfIcon.Check else PerfIcon.ArrowForward,
                        onClick = mainVm.viewModelScope.launchAsFn {
                            StatusService.requestStart(context)
                        },
                    )
                    RequiredTextItem(
                        text = "省电策略设置为无限制",
                        enabled = !ignoreBatteryOptimizations,
                        imageVector = if (ignoreBatteryOptimizations) PerfIcon.Check else PerfIcon.ArrowForward,
                        onClickLabel = "打开忽略电池优化设置页面",
                        onClick = mainVm.viewModelScope.launchAsFn {
                            requiredPermission(context, ignoreBatteryOptimizationsState)
                        },
                    )
                    RequiredTextItem(
                        text = "(可选) 允许自启动",
                        enabled = true,
                        imageVector = PerfIcon.OpenInNew,
                        onClickLabel = "打开应用详情页面",
                        onClick = {
                            openAppDetailsSettings()
                        },
                    )
                    RequiredTextItem(
                        text = "(可选) 在「最近任务」锁定",
                        enabled = true,
                        imageVector = PerfIcon.OpenInNew,
                        onClickLabel = "打开应用详情页面",
                        onClick = {
                            val m = shizukuContextFlow.value.inputManager
                            if (m != null) {
                                m.key(KeyEvent.KEYCODE_APP_SWITCH)
                            } else {
                                toast("请先授权 Shizuku")
                            }
                        },
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "某些场景下服务刚启动时概率不工作，如多次遇到此情况则不建议使用此功能")
            }
            Spacer(modifier = Modifier.height(EmptyHeight))
        }
    }
}

@Composable
private fun RequiredTextItem(
    text: String,
    imageVector: ImageVector? = null,
    enabled: Boolean = false,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .run {
                if (onClick != null) {
                    clickable(
                        enabled = enabled,
                        onClick = throttle(onClick),
                        onClickLabel = onClickLabel
                    )
                } else {
                    this
                }
            }
            .padding(horizontal = 4.dp),
    ) {
        val lineHeightDp = LocalDensity.current.run { LocalTextStyle.current.lineHeight.toDp() }
        Spacer(
            modifier = Modifier
                .padding(vertical = (lineHeightDp - 4.dp) / 2)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiary)
                .size(4.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text)
        if (imageVector != null) {
            PerfIcon(
                imageVector = imageVector,
                modifier = Modifier.iconTextSize(),
            )
        }
    }

}
