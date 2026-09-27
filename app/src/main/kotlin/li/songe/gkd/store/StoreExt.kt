package li.songe.gkd.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import li.songe.gkd.appScope
import li.songe.gkd.service.ExposeService
import li.songe.gkd.ui.gkdStartCommandText
import li.songe.gkd.util.AppListString
import li.songe.gkd.util.launchTry
import li.songe.gkd.util.toast

val storeFlow by lazy {
    createAnyFlow(
        key = "store",
        default = { SettingsStore() }
    )
}

val actionCountFlow by lazy {
    createTextFlow(
        key = "action_count",
        decode = { it?.toLongOrNull() ?: 0L },
        encode = { it.toString() },
    )
}

val blockMatchAppListFlow by lazy {
    createTextFlow(
        key = "block_match_app_list",
        decode = { it?.let(AppListString::decode) ?: AppListString.getDefaultBlockList() },
        encode = AppListString::encode,
    )
}

val blockA11yAppListFlow by lazy {
    createTextFlow(
        key = "block_a11y_app_list",
        decode = { it?.let(AppListString::decode) ?: emptySet() },
        encode = AppListString::encode,
    )
}

val actualBlockA11yAppList: Set<String>
    get() = if (storeFlow.value.blockA11yAppListFollowMatch) {
        blockMatchAppListFlow.value
    } else {
        blockA11yAppListFlow.value
    }

val a11yScopeAppListFlow by lazy {
    createTextFlow(
        key = "a11y_scope_app_list",
        decode = { it?.let(AppListString::decode) ?: setOf("com.tencent.mm") },
        encode = AppListString::encode,
    )
}

val actualA11yScopeAppList: Set<String>
    get() = if (storeFlow.value.useAutomation) {
        a11yScopeAppListFlow.value
    } else {
        emptySet()
    }

/** fork(v99): 关联应用守护——用户自定义的"打开即守护"App 列表 */
val guardAssocAppListFlow by lazy {
    createTextFlow(
        key = "guard_assoc_app_list",
        decode = { it?.let(AppListString::decode) ?: emptySet() },
        encode = AppListString::encode,
    )
}

val actualGuardAssocAppList: Set<String>
    get() = guardAssocAppListFlow.value

/**
 * fork(v106): 摇一摇跳转防护——用户指定的"在这些应用里拦截开屏跳转"App 列表。
 * **默认为空**(即该功能默认不干预任何应用), 由用户在「设置 → 摇一摇跳转防护 → 跳转防护应用」里自行勾选。
 */
val jumpGuardAppListFlow by lazy {
    createTextFlow(
        key = "jump_guard_app_list",
        decode = { it?.let(AppListString::decode) ?: emptySet() },
        encode = AppListString::encode,
    )
}

fun checkAppBlockMatch(appId: String): Boolean {
    if (blockMatchAppListFlow.value.contains(appId)) {
        return true
    }
    if (storeFlow.value.enableBlockA11yAppList) {
        return actualBlockA11yAppList.contains(appId)
    }
    return false
}

fun initStore() = appScope.launchTry(Dispatchers.IO) {
    // preload
    storeFlow.value
    actionCountFlow.value
    blockMatchAppListFlow.value
    blockA11yAppListFlow.value
    a11yScopeAppListFlow.value
    guardAssocAppListFlow.value
    jumpGuardAppListFlow.value
    gkdStartCommandText
    ExposeService.initCommandFile()
}

fun switchStoreEnableMatch() {
    if (storeFlow.value.enableMatch) {
        toast("暂停规则匹配")
    } else {
        toast("开启规则匹配")
    }
    storeFlow.update { it.copy(enableMatch = !it.enableMatch) }
}

fun updateEnableAutomator(value: Boolean) {
    if (value == storeFlow.value.enableAutomator) return
    storeFlow.update { it.copy(enableAutomator = value) }
}
