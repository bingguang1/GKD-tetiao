package li.songe.gkd.ui

import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import li.songe.gkd.ui.share.BaseViewModel
import li.songe.gkd.ui.share.useAppFilter
import li.songe.gkd.util.AppGroupOption
import li.songe.gkd.util.AppSortOption

/**
 * fork(v107): 「快应用引擎」手动补充页 VM。
 * 复用全局 app 列表过滤/排序管线(useAppFilter), 默认全部用户+系统应用、按最近使用排序。
 */
class QuickAppEnginePickVm : BaseViewModel() {
    private val allGroupType = (1 shl AppGroupOption.normalObjects.size) - 1
    private val appGroupTypeFlow = MutableStateFlow(allGroupType)
    private val sortTypeFlow = MutableStateFlow(AppSortOption.ByUsedTime)

    private val appFilter = useAppFilter(
        appGroupTypeFlow = appGroupTypeFlow,
        sortTypeFlow = sortTypeFlow,
    )

    val searchStrFlow = appFilter.searchStrFlow
    val appInfosFlow = appFilter.appListFlow
    val showSearchBarFlow = MutableStateFlow(false)

    val resetKey = mutableIntStateOf(0)

    init {
        showSearchBarFlow.launchCollect {
            if (!it) {
                searchStrFlow.value = ""
            }
        }
        appInfosFlow.launchOnChange {
            resetKey.intValue++
        }
    }
}
