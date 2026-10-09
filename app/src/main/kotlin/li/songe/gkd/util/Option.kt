package li.songe.gkd.util

import androidx.compose.ui.graphics.vector.ImageVector
import li.songe.gkd.ui.component.PerfIcon

sealed interface Option<T> {
    val value: T
    val label: String
    val options: List<Option<T>>
}

sealed interface OptionIcon {
    val icon: ImageVector
}

sealed interface OptionMenuLabel {
    val menuLabel: String
}

fun <V, T : Option<V>> Iterable<T>.findOption(value: V): T {
    return find { it.value == value } ?: first()
}

sealed class AppSortOption(override val value: Int, override val label: String) : Option<Int> {
    override val options get() = objects

    data object ByAppName : AppSortOption(0, "按应用名称")
    data object ByActionTime : AppSortOption(2, "按最近触发")
    data object ByUsedTime : AppSortOption(3, "按最近使用")

    companion object {
        val objects by lazy { listOf(ByAppName, ByUsedTime, ByActionTime) }
    }
}

sealed class UpdateTimeOption(
    override val value: Long,
    override val label: String
) : Option<Long> {
    override val options get() = objects

    data object Pause : UpdateTimeOption(-1, "暂停")
    data object Everyday : UpdateTimeOption(24 * 60 * 60_000, "每天")
    data object Every3Days : UpdateTimeOption(24 * 60 * 60_000 * 3, "每3天")
    data object Every7Days : UpdateTimeOption(24 * 60 * 60_000 * 7, "每7天")

    companion object {
        val objects by lazy { listOf(Pause, Everyday, Every3Days, Every7Days) }
    }
}

/**
 * fork(v108): 「摇一摇跳转防护」的开屏时长 —— **当前页面**出现之后多久之内发生的跨应用跳转才算"开屏跳转"。
 *
 * 为什么必须可配 + 默认要够大: 原来写死 1.8 秒, 而真机取证(2026-10-02 gkd-20261002.log, 校园卡 App
 * 的摇一摇广告)三次跳转分别发生在开屏页出现后 **4.2s / 5.0s / 6.8s** —— 1.8 秒一次都追不上,
 * 表现就是"这个功能完全没生效"。默认取 8 秒(能覆盖上述实测), 用户可按自己机型/习惯调。
 * 代价: 调得越大, 越可能把"用户自己刚进应用就点了跳转"的正常操作也退回一次
 * (会 toast 明确告知; 把该应用从「跳转防护应用」里去掉即可)。
 */
sealed class JumpGuardWindowOption(
    override val value: Long,
    override val label: String
) : Option<Long> {
    override val options get() = objects

    data object S1_5 : JumpGuardWindowOption(1_500, "1.5 秒")
    data object S2 : JumpGuardWindowOption(2_000, "2 秒")
    data object S3 : JumpGuardWindowOption(3_000, "3 秒")
    data object S5 : JumpGuardWindowOption(5_000, "5 秒")
    data object S8 : JumpGuardWindowOption(8_000, "8 秒")
    data object S10 : JumpGuardWindowOption(10_000, "10 秒")
    data object S15 : JumpGuardWindowOption(15_000, "15 秒")

    companion object {
        val objects by lazy { listOf(S1_5, S2, S3, S5, S8, S10, S15) }
    }
}

/**
 * fork(fok0030): 「运行日志」保留天数 —— 以前写死 7 天([li.songe.gkd.util.LogUtils] 里的常量)。
 *
 * 现在这个值同时管两处:
 *   1. 写入时的**自动滚动清理**(超过这个天数的文件在开新文件时被删);
 *   2. 「运行日志」页上的「**清除过期**」按钮(只删超出保留天数的历史文件, 当天/近几天的留着)。
 * 默认 7 天, 与老行为一致; 想省空间可以调到 1~3 天, 想留长期取证可以调到 30 天。
 */
sealed class LogRetainDaysOption(
    override val value: Int,
    override val label: String
) : Option<Int> {
    override val options get() = objects

    data object D1 : LogRetainDaysOption(1, "1 天")
    data object D3 : LogRetainDaysOption(3, "3 天")
    data object D7 : LogRetainDaysOption(7, "7 天")
    data object D14 : LogRetainDaysOption(14, "14 天")
    data object D30 : LogRetainDaysOption(30, "30 天")

    companion object {
        val objects by lazy { listOf(D1, D3, D7, D14, D30) }
    }
}

sealed class DarkThemeOption(
    override val value: Boolean?,
    override val label: String,
    override val menuLabel: String,
    override val icon: ImageVector
) : Option<Boolean?>, OptionIcon, OptionMenuLabel {
    override val options get() = objects

    data object FollowSystem : DarkThemeOption(null, "自动", "自动", PerfIcon.AutoMode)
    data object AlwaysEnable : DarkThemeOption(true, "启用", "深色", PerfIcon.DarkMode)
    data object AlwaysDisable : DarkThemeOption(false, "关闭", "浅色", PerfIcon.LightMode)

    companion object {
        val objects by lazy { listOf(FollowSystem, AlwaysEnable, AlwaysDisable) }
    }
}

sealed class EnableGroupOption(
    override val value: Boolean?,
    override val label: String
) : Option<Boolean?> {
    override val options get() = objects

    data object FollowSubs : EnableGroupOption(null, "跟随订阅")
    data object AllEnable : EnableGroupOption(true, "全部启用")
    data object AllDisable : EnableGroupOption(false, "全部关闭")

    companion object {
        val objects by lazy { listOf(FollowSubs, AllEnable, AllDisable) }
    }
}

sealed class RuleSortOption(override val value: Int, override val label: String) : Option<Int> {
    override val options get() = objects

    data object ByDefault : RuleSortOption(0, "按默认顺序")
    data object ByActionTime : RuleSortOption(1, "按最近触发")
    data object ByRuleName : RuleSortOption(2, "按规则名称")

    companion object {
        val objects by lazy { listOf(ByDefault, ByActionTime, ByRuleName) }
    }
}

sealed class UpdateChannelOption(
    override val value: Int,
    override val label: String,
    val url: String
) : Option<Int> {
    override val options get() = objects

    data object Stable : UpdateChannelOption(
        0,
        "稳定版",
        "https://registry.npmmirror.com/@gkd-kit/app/latest/files/index.json"
    )

    data object Beta : UpdateChannelOption(
        1,
        "测试版",
        "https://registry.npmmirror.com/@gkd-kit/app-beta/latest/files/index.json"
    )

    companion object {
        val objects by lazy { listOf(Stable, Beta) }
    }
}

sealed interface BinaryOption : Option<Int> {
    fun include(flag: Int): Boolean = (value and flag) != 0
    fun invert(flag: Int): Int = value xor flag

    companion object {
        fun combine(options: Collection<BinaryOption>): Int {
            return options.fold(0) { a, b -> a or b.value }
        }
    }
}


sealed class AppGroupOption(
    override val value: Int,
    override val label: String
) : BinaryOption {
    override val options get() = allObjects

    data object SystemGroup : AppGroupOption(1 shl 0, "系统应用")
    data object UserGroup : AppGroupOption(1 shl 1, "用户应用")
    data object UnInstalledGroup : AppGroupOption(1 shl 2, "未安装应用")

    companion object {
        val normalObjects by lazy { listOf(SystemGroup, UserGroup) }
        val allObjects by lazy { listOf(SystemGroup, UserGroup, UnInstalledGroup) }
    }
}

sealed class AutomatorModeOption(
    override val value: Int,
    override val label: String,
) : Option<Int> {
    override val options get() = objects

    data object A11yMode : AutomatorModeOption(1, "无障碍")
    data object AutomationMode : AutomatorModeOption(2, "自动化")

    companion object {
        val objects by lazy { listOf(A11yMode, AutomationMode) }
    }
}

