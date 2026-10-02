package li.songe.gkd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import li.songe.gkd.service.QuickAppController
import li.songe.gkd.service.QuickAppEngine
import li.songe.gkd.service.QuickAppGuard
import li.songe.gkd.service.QuickAppRegistry
import li.songe.gkd.shizuku.shizukuContextFlow
import li.songe.gkd.store.storeFlow
import li.songe.gkd.ui.component.CopyTextCard
import li.songe.gkd.ui.component.PerfTopAppBar
import li.songe.gkd.ui.icon.BackCloseIcon
import li.songe.gkd.ui.share.LocalMainViewModel
import li.songe.gkd.ui.style.scaffoldPadding
import li.songe.gkd.util.throttle
import li.songe.gkd.util.toast

@Serializable
data object QuickAppEngineRoute : NavKey

/**
 * fork(v107): 「快应用引擎」页 —— 「关闭快应用」功能的第二/三层(根治层)。
 *
 * 上面一页的开关负责**秒退**(零权限, 默认开); 这一页负责**把快应用引擎本身关掉**:
 *   ① 停用引擎包(`pm disable-user`): 系统层面不再响应 hap://, 广告再也拉不起快应用; 可一键恢复;
 *   ② 禁止引擎安装应用(appops REQUEST_INSTALL_PACKAGES=deny): 引擎即使被拉起也装不了 APK;
 *   ③ 结束进程(`am force-stop`): 引擎已在后台跑着时手动清掉。
 * 三条命令都需要 shell 权限 —— 优先走 GKD 已有的 Shizuku 用户服务; 没连 Shizuku 时页面会给出一键 adb 命令,
 * 在电脑上执行一次同样有效(与「一键ADB配置.exe」交付物同一思路)。
 */
@Composable
fun QuickAppEnginePage() {
    val mainVm = LocalMainViewModel.current
    val engines by QuickAppRegistry.enginesFlow.collectAsState()
    val store by storeFlow.collectAsState()
    val shizukuCtx by shizukuContextFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val shellOk = store.enableShizuku && shizukuCtx.serviceWrapper != null

    // 进页面就刷一次识别结果(引擎可能是在 GKD 启动之后才装/停用的)
    LaunchedEffect(Unit) {
        QuickAppRegistry.refresh()
    }

    Scaffold(
        topBar = {
            PerfTopAppBar(
                modifier = Modifier.fillMaxWidth(),
                navigationIcon = {
                    IconButton(onClick = throttle { mainVm.popPage() }) {
                        BackCloseIcon(backOrClose = false)
                    }
                },
                title = { Text(text = "快应用引擎") },
            )
        },
    ) { contentPadding ->
        LazyColumn(modifier = Modifier.scaffoldPadding(contentPadding)) {
            item(key = "tip") {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    text = "快应用是华为/荣耀/小米/OPPO/vivo 等厂商预装的\"免安装小程序\"运行环境(原生渲染, 不是网页)。" +
                        "流氓广告常借它把用户从开屏广告拉进快应用广告页并自动下载 APK。\n" +
                        "「停用」= 系统层面关掉这个引擎(可随时恢复, 推荐); 「禁止安装应用」= 让引擎没法装 APK。" +
                        "两者都需要 shell 权限: 连了 Shizuku 可在这里直接执行, 否则用下面给的一键 adb 命令。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    text = "当前 Shizuku 状态: " + if (shellOk) "已连接 ✅ 可直接执行" else "未连接 ❌ 只能给出 adb 命令",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (shellOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                val lastBlock by QuickAppGuard.lastBlockFlow.collectAsState()
                val blockCount by QuickAppGuard.blockCountFlow.collectAsState()
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    text = "已拦截快应用跳转 $blockCount 次" + if (lastBlock.isEmpty()) "" else "(最近: $lastBlock)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = throttle {
                        QuickAppRegistry.refresh()
                        toast("已重新识别快应用引擎", forced = true)
                    }) { Text("重新识别") }
                    TextButton(onClick = throttle {
                        scope.launch {
                            toast("正在深度扫描(枚举所有已安装应用声明的 scheme)...", forced = true)
                            val found = withContext(Dispatchers.IO) { QuickAppRegistry.deepScan() }
                            if (found.isEmpty()) {
                                toast("深度扫描未发现额外的快应用引擎", forced = true)
                            } else {
                                QuickAppRegistry.refresh()
                                toast("深度扫描发现 ${found.size} 个疑似引擎: ${found.joinToString()}", forced = true)
                            }
                        }
                    }) { Text("深度扫描") }
                    TextButton(onClick = throttle {
                        mainVm.navigatePage(QuickAppEnginePickRoute)
                    }) { Text("手动补充") }
                }
                HorizontalDivider()
            }
            if (engines.isEmpty()) {
                item(key = "empty") {
                    Text(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        text = "没有识别到快应用引擎。\n" +
                            "识别方式: ① 谁响应 hap:// 快应用链接 ② 包名带 quickapp/fastapp/hybrid 等特征 " +
                            "③ 你手动补充的包名。\n" +
                            "若你的手机确实有快应用(设置里能搜到\"快应用中心/快应用引擎\"), 点上面的「深度扫描」或「手动补充」。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(engines, { it.pkg }) { engine ->
                EngineRow(
                    engine = engine,
                    shellOk = shellOk,
                    onDisable = { scope.launch { QuickAppController.disableEngine(engine.pkg) } },
                    onEnable = { scope.launch { QuickAppController.enableEngine(engine.pkg) } },
                    onDenyInstall = { scope.launch { QuickAppController.denyInstall(engine.pkg) } },
                    onAllowInstall = { scope.launch { QuickAppController.allowInstall(engine.pkg) } },
                    onForceStop = { scope.launch { QuickAppController.forceStop(engine.pkg) } },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun EngineRow(
    engine: QuickAppEngine,
    shellOk: Boolean,
    onDisable: () -> Unit,
    onEnable: () -> Unit,
    onDenyInstall: () -> Unit,
    onAllowInstall: () -> Unit,
    onForceStop: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = engine.label + if (engine.disabled) "  [已停用]" else "",
            style = MaterialTheme.typography.bodyLarge,
            color = if (engine.disabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = engine.pkg,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "识别来源: ${engine.sourceText}" + if (engine.isSystem) " · 系统应用" else " · 第三方应用",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (engine.disabled) {
                TextButton(onClick = throttle(onEnable), enabled = shellOk) { Text("恢复") }
            } else {
                TextButton(onClick = throttle(onDisable), enabled = shellOk) { Text("停用") }
            }
            TextButton(onClick = throttle(onDenyInstall), enabled = shellOk) { Text("禁止安装应用") }
            TextButton(onClick = throttle(onAllowInstall), enabled = shellOk) { Text("允许安装") }
            TextButton(onClick = throttle(onForceStop), enabled = shellOk) { Text("结束进程") }
        }
        if (!shellOk) {
            Spacer(modifier = Modifier.height(4.dp))
            CopyTextCard(text = QuickAppController.adbCommands(engine.pkg))
        }
    }
}
