package li.songe.gkd.store

import kotlinx.serialization.Serializable
import li.songe.gkd.META
import li.songe.gkd.util.AppGroupOption
import li.songe.gkd.util.AppSortOption
import li.songe.gkd.util.AutomatorModeOption
import li.songe.gkd.util.RuleSortOption
import li.songe.gkd.util.UpdateChannelOption
import li.songe.gkd.util.UpdateTimeOption

@Serializable
data class SettingsStore(
    val enableAutomator: Boolean = false,
    val automatorMode: Int = AutomatorModeOption.A11yMode.value,
    val enableShizuku: Boolean = false,
    val enableMatch: Boolean = true,
    val shakeGuard: Boolean = true,
    val autoRestoreA11y: Boolean = true,
    /** fork: 关联应用守护——打开用户自定义的关联 App 时若无障碍被清除则立即恢复(需要 PACKAGE_USAGE_STATS 授权, 见 manifest) */
    val enableGuardAssoc: Boolean = true,
    /** fork v100: 假跳过防护——点击"跳过/开屏广告"后校验落点, 若被带到广告落地页则立即返回并降级该 App */
    val fakeSkipGuard: Boolean = true,
    /** fork v100: 被判定过假跳过误点的应用(换行分隔), 这些应用里不再自动点击"不可点的跳过文字" */
    val fakeSkipVetoApps: String = "",
    /** fork v102: 坐标守卫——拒绝在"空/反向矩形"的节点上打盲坐标(真机实证: 微信朋友圈广告 key=1 因此点到了广告上) */
    val strictClickGuard: Boolean = true,
    /** fork v102: 坐标守卫——拒绝在"用户看不到"的节点范围内打盲坐标(若有规则故意用不可见节点当坐标原点, 可单独关掉这一条) */
    val guardInvisibleNode: Boolean = true,
    /** fork v105: 摇一摇跳转防护——开屏 1.8 秒内 GKD 没点过任何东西却跳到别的App时, 判为摇一摇广告跳转并立刻退回原App; v106 起**只对「跳转防护应用」名单里的应用生效**(名单默认空, 见 StoreExt.jumpGuardAppListFlow) */
    val jumpGuard: Boolean = true,
    /** fork: 用户在通知栏/控制页/快捷磁贴上手动关闭无障碍后置 true, 自动守护暂停拉起, 直到用户再次手动开启 */
    val manualA11yOff: Boolean = false,
    val enableStatusService: Boolean = false,
    val excludeFromRecents: Boolean = false,
    val captureScreenshot: Boolean = false,
    val screenshotTargetAppId: String = "",
    val screenshotEventSelector: String = "",
    val httpServerPort: Int = 8888,
    val updateSubsInterval: Long = UpdateTimeOption.Everyday.value,
    val captureVolumeChange: Boolean = false,
    val toastWhenClick: Boolean = true,
    val actionToast: String = META.appName,
    val autoClearMemorySubs: Boolean = false,
    val hideSnapshotStatusBar: Boolean = false,
    val enableDarkTheme: Boolean? = null,
    val enableDynamicColor: Boolean = true,
    val showSaveSnapshotToast: Boolean = true,
    val useSystemToast: Boolean = false,
    val useCustomNotifText: Boolean = false,
    val customNotifTitle: String = META.appName,
    val customNotifText: String = $$"${i}全局/${k}应用/${u}规则/${n}触发",
    val updateChannel: Int = if (META.isBeta) UpdateChannelOption.Beta.value else UpdateChannelOption.Stable.value,
    val appSort: Int = AppSortOption.ByUsedTime.value,
    val showBlockApp: Boolean = true,
    val appRuleSort: Int = RuleSortOption.ByDefault.value,
    val subsAppSort: Int = AppSortOption.ByUsedTime.value,
    val subsCategorySort: Int = AppSortOption.ByUsedTime.value,
    val subsAppShowUninstall: Boolean = false,
    val subsAppGroupType: Int = AppGroupOption.UserGroup.value or AppGroupOption.SystemGroup.value,
    val subsCategoryGroupType: Int = AppGroupOption.UserGroup.value or AppGroupOption.SystemGroup.value,
    val subsAppShowBlock: Boolean = false,
    val subsCategoryShowBlock: Boolean = false,
    val subsExcludeSort: Int = AppSortOption.ByUsedTime.value,
    val subsExcludeShowBlockApp: Boolean = true,
    val subsExcludeShowInnerDisabledApp: Boolean = true,
    val subsPowerWarn: Boolean = true,
    val enableBlockA11yAppList: Boolean = false,
    val blockA11yAppListFollowMatch: Boolean = true,
    val a11yAppSort: Int = AppSortOption.ByUsedTime.value,
    val a11yScopeAppSort: Int = AppSortOption.ByUsedTime.value,
    val appGroupType: Int = (1 shl AppGroupOption.normalObjects.size) - 1,
    val a11yAppGroupType: Int = appGroupType,
    val a11yScopeAppGroupType: Int = appGroupType,
    val subsExcludeAppGroupType: Int = appGroupType,
    val showDisabledRule: Boolean = true,
) {
    val useA11y get() = automatorMode == AutomatorModeOption.A11yMode.value
    val useAutomation get() = automatorMode == AutomatorModeOption.AutomationMode.value
}