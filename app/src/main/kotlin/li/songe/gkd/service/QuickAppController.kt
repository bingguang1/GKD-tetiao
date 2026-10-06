package li.songe.gkd.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.songe.gkd.shizuku.UserServiceWrapper
import li.songe.gkd.shizuku.shizukuContextFlow
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.toast

/** 一次特权命令的执行结果(code/err/out 原文都保留, 便于在真机上如实呈现失败原因) */
data class CmdOutcome(val ok: Boolean, val detail: String)

/**
 * fork(v107): 「关闭快应用」的**特权层** —— 停用/恢复引擎包、掐掉引擎的"安装应用"权限。
 *
 * 为什么必须有这一层: 无障碍层的 [QuickAppGuard] 只能"事后秒退"(用户还是会被闪一下、引擎进程还在跑),
 * 而流氓快应用真正的危害是**自动下载并安装 APK** —— 只有把引擎停用/掐掉安装权限才算根治。
 *
 * 权限从哪来(实测结论, 不是猜的):
 *   - `pm disable-user` / `pm enable` 是**包级**状态变更, shell 有权执行(shizuku 的用户服务进程即 shell 权限);
 *     ⚠️ 反过来 **组件级**(`pm disable <pkg>/<组件>`)在 Android 14+ 会被系统直接拒绝:
 *        `SecurityException: Shell cannot change component state ... to 2` —— 所以这里只用包级命令。
 *   - `cmd appops set <pkg> REQUEST_INSTALL_PACKAGES deny` 同理可用(名字已实测有效, 假名会报 Unknown operation string)。
 *   - 命令通过 GKD 已有的 Shizuku 用户服务执行([UserServiceWrapper.execCommandForResult], `input tap`/`screencap` 也走它),
 *     不新增任何隐藏 API; 未连接 Shizuku 时本层整体降级(返回失败原因 + 由 UI 给出一键 adb 命令)。
 *
 * 所有动作都是**用户显式点击才执行**(不做自动停用), 且执行结果原样反馈, 失败不会静默。
 */
object QuickAppController {

    private const val TAG = "QuickApp"

    private fun wrapper(): UserServiceWrapper? =
        if (storeFlow.value.enableShizuku) shizukuContextFlow.value.serviceWrapper else null

    /** 给"没连 Shizuku"的用户的兜底: 在电脑上执行一次即可 */
    fun adbCommands(pkg: String): String = listOf(
        "adb shell pm disable-user --user 0 $pkg",
        "adb shell cmd appops set $pkg REQUEST_INSTALL_PACKAGES deny",
    ).joinToString("\n")

    suspend fun disableEngine(pkg: String) =
        act("停用", pkg, "pm disable-user --user 0 $pkg")

    suspend fun enableEngine(pkg: String) =
        act("恢复", pkg, "pm enable $pkg")

    suspend fun denyInstall(pkg: String) =
        act("禁止安装应用", pkg, "cmd appops set $pkg REQUEST_INSTALL_PACKAGES deny")

    suspend fun allowInstall(pkg: String) =
        act("允许安装应用", pkg, "cmd appops set $pkg REQUEST_INSTALL_PACKAGES default")

    suspend fun forceStop(pkg: String) =
        act("结束进程", pkg, "am force-stop $pkg")

    private suspend fun act(verb: String, pkg: String, command: String): CmdOutcome =
        withContext(Dispatchers.IO) {
            val r = runCommand(command)
            // 命令成功与否都刷一次: 停用/恢复会改变引擎的 enabledState 与 deeplink 响应能力
            QuickAppRegistry.refreshNow()
            withContext(Dispatchers.Main) {
                val label = appLabel(pkg)
                toast(
                    if (r.ok) "$verb 成功: 「$label」" else "$verb 失败: 「$label」(${r.detail})",
                    forced = true,
                )
            }
            r
        }

    private fun runCommand(command: String): CmdOutcome {
        val w = wrapper() ?: return CmdOutcome(false, "未连接 Shizuku")
        val r = runCatching { w.execCommandForResult(command) }.getOrElse { e ->
            LogUtils.d("$TAG command error [$command]", e)
            return CmdOutcome(false, e.message ?: "命令执行异常")
        }
        val detail = buildString {
            append("code=").append(r.code)
            r.error?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" err=").append(it.take(200)) }
            r.result.trim().takeIf { it.isNotEmpty() }?.let { append(" out=").append(it.take(200)) }
        }
        LogUtils.d("$TAG cmd=[$command] ok=${r.ok} $detail")
        return CmdOutcome(r.ok, detail)
    }
}
