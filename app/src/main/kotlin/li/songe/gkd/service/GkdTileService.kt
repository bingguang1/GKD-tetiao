package li.songe.gkd.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import li.songe.gkd.META
import li.songe.gkd.a11y.systemRecentCn
import li.songe.gkd.a11y.topActivityFlow
import li.songe.gkd.accessRestrictedSettingsShowFlow
import li.songe.gkd.app
import li.songe.gkd.appScope
import li.songe.gkd.isActivityVisible
import li.songe.gkd.permission.writeSecureSettingsState
import li.songe.gkd.shizuku.AutomationService
import li.songe.gkd.shizuku.shizukuContextFlow
import li.songe.gkd.shizuku.uiAutomationFlow
import li.songe.gkd.store.actualA11yScopeAppList
import li.songe.gkd.store.actualBlockA11yAppList
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.launchTry
import li.songe.gkd.util.mapState
import li.songe.gkd.util.openA11ySettings
import li.songe.gkd.util.runMainPost
import li.songe.gkd.util.toast

class GkdTileService : BaseTileService() {
    override val activeFlow = combine(A11yService.isRunning, uiAutomationFlow) { a11y, automator ->
        a11y || automator != null
    }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        onTileClicked { switchAutomatorService() }
    }
}

private val modifyA11yMutex = Mutex()

private fun modifyA11yRun(block: suspend () -> Unit) {
    // v97: 锁占用时不再静默丢弃, 改为排队执行 —— 否则通知栏/磁贴快速连点"开启"会被吞掉且无反馈
    appScope.launchTry(Dispatchers.IO) {
        modifyA11yMutex.withLock { block() }
    }
}

private suspend fun switchA11yService() {
    if (A11yService.isRunning.value) {
        // fork: 用户主动关闭(快捷磁贴/控制页/通知栏按钮), 打上手动关闭标记, 防止自动守护立刻重新拉起
        A11yAutoGuard.setManualOff(true)
        A11yService.instance?.disableSelf()
    } else {
        // fork: 用户主动开启, 清除手动关闭标记, 让自动守护恢复正常
        A11yAutoGuard.setManualOff(false)
        val started = A11yAutoGuard.ensureEnabled(
            ignoreManualOff = true,
            ignoreAutoRestore = true,
        )
        if (!started) {
            if (!writeSecureSettingsState.value) {
                toast("请先授予「写入安全设置权限」")
            } else {
                // v97: 自动开启被系统拦截(常见于 Android13+ 受限设置, 首次需在系统设置手动开一次)。
                // 避免"每次快捷开启都弹受限设置模态框": 应用可见时弹一次可操作的引导;
                // 应用不可见(通知/磁贴/小组件点按)时直接拉起系统无障碍设置页。
                if (isActivityVisible) {
                    accessRestrictedSettingsShowFlow.value = true
                } else {
                    toast("自动开启未生效，已为你打开系统无障碍设置，请把 GKD特调版 打开一次")
                    openA11ySettings()
                }
            }
        }
    }
}

private fun switchAutomationService() {
    val newEnabled = uiAutomationFlow.value == null
    uiAutomationFlow.value?.shutdown()
    if (newEnabled && shizukuContextFlow.value.ok) {
        AutomationService.tryConnect()
    }
}

fun switchAutomatorService() = modifyA11yRun {
    if (currentAppUseA11y) {
        switchA11yService()
    } else {
        switchAutomationService()
    }
}

/**
 * fork: 通知栏「常驻通知」上的无障碍一键开关(仅无障碍模式显示该按钮)。
 * 与快捷磁贴/控制页走同一条 [switchA11yService] 逻辑, 关闭时会写入手动关闭标记。
 */
fun toggleA11yByNotifAction() = modifyA11yRun {
    if (!storeFlow.value.useA11y) {
        toast("当前为自动化模式，请在应用内切换")
        return@modifyA11yRun
    }
    switchA11yService()
}

private fun skipBlockApp(): Boolean {
    if (storeFlow.value.enableBlockA11yAppList) {
        val topAppId = if (isActivityVisible || app.justStarted) {
            META.appId
        } else {
            shizukuContextFlow.value.topCpn()?.packageName
        }
        if (topAppId != null && topAppId in actualBlockA11yAppList) {
            return true
        }
    }
    return false
}

/**
 * 自动修复(守护)无障碍: 失败时**不再弹「受限设置」模态框**, 只做提示——
 * 模态框此前会在 进程启动自检/磁贴面板展开 等非用户主动场景反复触发,
 * 是"快捷开启时莫名弹出写入安全限制"的元凶; 失败留给周期闹钟/观察者继续重试。
 * @param userAck true=用户在当前界面主动触发修复, 失败给 toast + (应用可见时)引导弹窗
 */
private suspend fun fixA11yService(userAck: Boolean) {
    if (A11yService.isRunning.value) return
    // fork: 用户手动关闭期间(manualA11yOff=true)不自动修复拉起, 保持用户的关闭意愿
    if (storeFlow.value.manualA11yOff) return
    if (skipBlockApp()) return
    if (!writeSecureSettingsState.updateAndGet()) return
    if (!currentAppUseA11y) return
    val ok = A11yAutoGuard.ensureEnabled(
        ignoreManualOff = false,
        ignoreAutoRestore = true,
    )
    if (!ok) {
        LogUtils.d("fixA11yService failed")
        if (userAck) {
            toast("无障碍未能自动恢复，请在系统设置中手动开启一次")
            if (isActivityVisible) {
                accessRestrictedSettingsShowFlow.value = true
            }
        }
    }
}

private fun fixAutomationService() {
    if (uiAutomationFlow.value == null && shizukuContextFlow.value.ok) {
        if (skipBlockApp()) return
        if (currentAppUseA11y) return
        AutomationService.tryConnect(true)
    }
}

/**
 * 修复/重启无障碍或自动化(应用内开关、权限页、磁贴展开等触发)。
 * @param userAck true=用户显式操作后的修复(失败给引导); false=后台自动修复(静默)
 */
fun fixRestartAutomatorService(userAck: Boolean = false) = modifyA11yRun {
    if (storeFlow.value.enableAutomator) {
        if (currentAppUseA11y) {
            fixA11yService(userAck)
        } else {
            fixAutomationService()
        }
    }
}

val currentAppUseA11y
    get() = storeFlow.value.useA11y || topAppIdFlow.value in actualA11yScopeAppList

val currentAppBlocked
    get() = storeFlow.value.enableBlockA11yAppList && topAppIdFlow.value in actualBlockA11yAppList

private suspend fun innerForcedUpdateA11yService(disabled: Boolean) {
    if (!storeFlow.value.enableAutomator) {
        return
    }
    if (disabled) {
        A11yService.instance?.shutdown(true)
        uiAutomationFlow.value?.shutdown(true)
        return
    }
    if (currentAppUseA11y) {
        if (A11yService.isRunning.value) {
            return
        }
        if (!writeSecureSettingsState.stateFlow.value) {
            return
        }
        // v97: 走统一互斥入口, 避免与守护/通知开关并发写列表互相覆盖
        A11yAutoGuard.addToEnabledList()
    } else {
        AutomationService.tryConnect(true)
    }
}

private fun forcedUpdateA11yService(disabled: Boolean) = modifyA11yRun {
    innerForcedUpdateA11yService(disabled)
}

const val A11Y_WHITE_APP_AWAIT_TIME = 3000L

@Volatile
private var lastAppIdChangeTime = 0L
val topAppIdFlow = MutableStateFlow("")
val a11yPartDisabledFlow by lazy {
    topAppIdFlow.mapState(appScope) {
        actualBlockA11yAppList.contains(it)
    }
}

fun updateTopTaskAppId(value: String) {
    if (storeFlow.value.enableBlockA11yAppList || actualA11yScopeAppList.isNotEmpty()) {
        topAppIdFlow.value = value
    }
}

fun initA11yWhiteAppList() {
    val actualFlow = topAppIdFlow.drop(1)
    appScope.launch(Dispatchers.Main) {
        actualFlow.collect {
            lastAppIdChangeTime = System.currentTimeMillis()
            if (!currentAppBlocked) {
                if (topActivityFlow.value.sameAs(systemRecentCn) && currentAppUseA11y) {
                    // 切换无障碍会造成卡顿，在最近任务界面时，延迟这个卡顿
                    val tempTime = lastAppIdChangeTime
                    runMainPost(A11Y_WHITE_APP_AWAIT_TIME) {
                        if (tempTime == lastAppIdChangeTime) {
                            forcedUpdateA11yService(false)
                        }
                    }
                } else {
                    // 切换自动化不会卡顿，直接启动
                    forcedUpdateA11yService(false)
                }
            }
        }
    }
    appScope.launch(Dispatchers.Main) {
        actualFlow.debounce(A11Y_WHITE_APP_AWAIT_TIME).collect {
            if (currentAppBlocked) {
                forcedUpdateA11yService(true)
            }
        }
    }
}
