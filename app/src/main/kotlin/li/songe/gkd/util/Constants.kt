package li.songe.gkd.util

const val FILE_SHORT_URL = "https://f.gkd.li/"
const val IMPORT_SHORT_URL = "https://i.gkd.li/i/"

const val SERVER_SCRIPT_URL =
    "https://registry.npmmirror.com/@gkd-kit/config/latest/files/dist/server.js"

// 本 fork 仓库地址(上游原值: https://github.com/gkd-kit/gkd)
// ⚠️ 仓库规范名是 GKD-tetiao(不是 tejiao): 写错虽然靠 301 重定向也能打开, 但每次跳转都多一次
//    请求, 且 App 内"关于/问题反馈/更新说明"里的链接会一直显示成非规范拼写 —— v122 统一成规范名。
const val REPOSITORY_URL = "https://github.com/bingguang1/GKD-tetiao"
const val ISSUES_URL = "${REPOSITORY_URL}/issues"
const val RELEASES_URL = "${REPOSITORY_URL}/releases/latest"

// 首页入口: 指向本 fork 仓库(上游原值: https://gkd.li)
const val HOME_PAGE_URL = REPOSITORY_URL

const val LOCAL_SUBS_ID = -2L
const val LOCAL_HTTP_SUBS_ID = -1L
val LOCAL_SUBS_IDS = arrayOf(LOCAL_SUBS_ID, LOCAL_HTTP_SUBS_ID)

const val EMPTY_RULE_TIP = "暂无规则"

object ShortUrlSet {
    const val URL1 = "https://gkd.li?r=1"
    const val URL2 = "https://gkd.li?r=2"
    const val URL3 = "https://gkd.li?r=3"
    const val URL4 = "https://gkd.li?r=4"
    const val URL5 = "https://gkd.li?r=5"
    const val URL6 = "https://gkd.li?r=6"
    const val URL10 = "https://gkd.li?r=10"
    const val URL11 = "https://gkd.li?r=11"
    const val URL12 = "https://gkd.li?r=12"
    const val URL13 = "https://gkd.li?r=13"
    const val URL14 = "https://gkd.li?r=14"
    const val URL15 = "https://gkd.li?r=15"
}

const val shizukuAppId = "moe.shizuku.privileged.api"

const val systemUiAppId = "com.android.systemui"
