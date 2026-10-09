package li.songe.gkd.service

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import li.songe.gkd.META
import li.songe.gkd.a11y.A11yRuleEngine
import li.songe.gkd.a11y.TopActivity
import li.songe.gkd.a11y.topActivityFlow
import li.songe.gkd.appScope
import li.songe.gkd.data.ActionPerformer
import li.songe.gkd.data.ActionResult
import li.songe.gkd.data.ResolvedRule
import li.songe.gkd.shizuku.casted
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.launchTry
import li.songe.gkd.util.toast
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 免 root「假跳过防护」。
 *
 * ## 背景: 开屏广告里的"跳过"有两种在节点属性上无法区分的形态
 *   1. **真跳过**: 文本节点自身 `clickable=false`, 但点它的坐标有效(例: 学习通 `id=…/btn_jump`);
 *   2. **假跳过**: "跳过"只是装饰文字, **下面盖着广告的可点层**, 点它的坐标 = 点广告 → 拉起落地页,
 *      甚至退出当前小程序。
 *
 * ## fok0030 重做(用户实测驱动)
 * 用户报: **"有些广告并不是假的跳过, 只是跳过的按钮区域不在右上角, 这时也会触发这个, 导致广告不跳过"**。
 * 老版本(fok0029 及之前)只有"事后落点校验", 且一旦判错就把**整个 App 拉黑** —— 于是:
 *   - 真跳过被当成假跳过 ⇒ 按返回键把人拉回;
 *   - 拉黑之后**这个 App 的不可点跳过文字永远不再点** ⇒ "广告从此不跳了"。
 *
 * 现在改成两道闸 + 一条铁律:
 *   - **闸 A(事前, 新增) [SkipTreeJudge]**: 点之前用无障碍树判断"这个点上有没有明显更大的可点层盖着"。
 *     判 `Fake` ⇒ 不点; 判 `Real`/`Unknown` ⇒ **照点**(铁律: **判不准就点, 误拦优先避免**)。
 *     规则自己显式声明可点(`action: clickNode` 或选择器写 `clickable=true`) ⇒ **永远放行**, 判据不参与。
 *   - **闸 B(事后)**: 点完 ~1.2s 校验落点。
 *     ★ 关键放宽: 若事前判据判的是 `Real`(那个点上没有覆盖层 ⇒ 这一枪确实打在 App 自己的处理上),
 *       那么"跳到了别的应用"是 **App 自己的正常业务跳转**, **只记日志, 既不按返回键也不降级**
 *       —— 这正是用户报的那类误伤。
 *   - **降级语义不再整 App**: 改成"**某个 App 的某个规则组 + 某种节点形态**"降级, 默认 **24 小时**自动恢复
 *     (老字段 `fakeSkipVetoApps` 仍然读取并尊重, 设置页可一键清空, 不再新增整 App 记录)。
 *
 * ## 与 [JumpGuard] / [ShakeGuard] 的关系
 * `JumpGuard` 管"GKD 没在等落点却被带走"; `ShakeGuard` 管"开屏时把可点关闭按钮点掉"; 本模块只管
 * "**我们点了不可点的跳过文字之后, 落点对不对**"。
 */
private const val LOG_TAG = "FakeSkipGuard"  // 日志/共用工具(GuardUtils)里区分调用方
private const val VERIFY_DELAY = 1200L      // 点击后多久校验落点
private const val BACK_WAIT = 600L          // 返回键之后的观察时间
private const val RELAUNCH_WAIT = 900L      // 拉起原 App 之后的观察时间
private const val COOLDOWN_MS = 4000L       // 两次处置之间的冷却
private const val VETO_MEMO_MS = 3000L      // 同一节点被否决后的记忆窗口(挡同节点的其它规则)
private const val LEFT_SYSTEM_LIMIT = 2     // "疑似被踢到桌面"累计多少次才降级
private const val VETO_LOG_INTERVAL_MS = 3000L // 否决日志节流(规则匹配循环约 300ms 一轮)
private const val JUDGE_LOG_INTERVAL_MS = 3000L // 判据日志节流(fok0030 实测: 不节流会每 300ms 刷一条)
private const val MAX_TEXT_LEN = 20         // 命中节点文本最大长度(排除正文长文本)
private const val JUDGE_MEMO_MS = 5000L     // 事前判据结果的记忆窗口(闸 B 要读它)
private const val DEGRADE_MS = 24 * 60 * 60 * 1000L // 降级有效期: 24 小时后自动恢复
private const val VETO_RULE_FIELD_SEP = '\t'

object FakeSkipGuard {

    /** 跳过类按钮的文本/desc 关键词 */
    private val skipWords = arrayOf("跳过", "跳過", "跳 过", "skip", "Skip", "SKIP")

    /** 规则组名里出现这些词时, 也认为这次点击是"跳过开屏广告"语义(故意不含泛化的"广告": 避免误伤分段广告的关闭类点击) */
    private val adGroupWords = arrayOf("开屏", "splash", "Splash", "启动广告")

    private val verifyToken = AtomicLong(0)

    @Volatile
    private var lastHandleTime = 0L

    @Volatile
    private var vetoNodeKey: String? = null

    @Volatile
    private var vetoNodeTime = 0L

    private val leftSystemCount = ConcurrentHashMap<String, Int>()
    private val toastShownKeys = ConcurrentHashMap.newKeySet<String>()
    private val lastVetoLogTime = ConcurrentHashMap<String, Long>()
    private val lastJudgeLogTime = ConcurrentHashMap<String, Long>()

    /** 闸 A 的判定结果缓存 —— 闸 B 要读它来决定"这一枪是不是我们自己点的、打在哪" */
    private val judgeMemo = ConcurrentHashMap<String, JudgeMemo>()

    private data class JudgeMemo(val judgement: SkipJudgement, val at: Long)

    /**
     * fork(v108): 最近一次**跳过类点击**的时间 —— 给 [JumpGuard] 判断"此刻该不该让位"用。
     *
     * ⚠️ 语义故意很窄: 只代表"我们刚点了一次跳过、正在等落点判定"。绝不能理解成"本次前台期间点过任何东西" ——
     * JumpGuard 原来就是拿后者(所有 GKD 动作)当让位条件, 而开屏时 GKD 几乎必然点过东西(跳过/关弹窗),
     * 于是它**在整个应用会话里都失效**(真机上表现就是"摇一摇跳转防护没生效")。
     */
    @Volatile
    var lastSkipClickAt = 0L
        private set

    /**
     * JumpGuard 用: 现在是否有一次"跳过类点击"的落点校验**正在进行中**(还在 VERIFY_DELAY 窗口里)。
     * 这种时候让 [FakeSkipGuard] 去判落点, 避免两个模块对同一次跳转各按一次返回键。
     */
    fun isVerifyingSkipClick(now: Long = System.currentTimeMillis()): Boolean =
        storeFlow.value.fakeSkipGuard && lastSkipClickAt > 0L &&
            now - lastSkipClickAt <= VERIFY_DELAY + 300L

    // ---------------- 对外: 设置页用 ----------------

    /** 老语义(fok0029 及之前)的"整 App 降级"名单 —— 只读+可清空, 不再新增(见类注释) */
    fun vetoAppIds(): MutableSet<String> {
        return storeFlow.value.fakeSkipVetoApps.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableSet()
    }

    /** 清空老名单(整 App 降级) */
    fun clearVetoApps() {
        storeFlow.value = storeFlow.value.copy(fakeSkipVetoApps = "")
        LogUtils.d("$LOG_TAG clear-veto-apps(legacy)")
    }

    /** 当前生效的"规则组降级"条目(已过期的自动剔除) */
    fun vetoRules(now: Long = System.currentTimeMillis()): List<VetoRule> =
        parseVetoRules(storeFlow.value.fakeSkipVetoRules).filter { it.expireAt > now }

    fun vetoRuleCount(now: Long = System.currentTimeMillis()): Int = vetoRules(now).size

    /** 设置页展示用: 一行一条, 形如 `com.xx.yy · 开屏广告 · 还剩 23h` */
    fun vetoRuleLines(now: Long = System.currentTimeMillis()): List<String> = vetoRules(now).map { r ->
        val left = ((r.expireAt - now) / 3600_000L).coerceAtLeast(1L)
        "${r.pkg} · ${r.groupName.ifEmpty { r.groupKey.ifEmpty { "未知规则组" } }} · 还剩 ${left}h"
    }

    /** 清空"规则组降级"名单 */
    fun clearVetoRules() {
        storeFlow.value = storeFlow.value.copy(fakeSkipVetoRules = "")
        LogUtils.d("$LOG_TAG clear-veto-rules")
    }

    /** 顺手清理过期条目(在写入新条目时调用, 避免无限增长) */
    private fun pruneVetoRules(now: Long) {
        val all = parseVetoRules(storeFlow.value.fakeSkipVetoRules)
        val alive = all.filter { it.expireAt > now }
        if (alive.size != all.size) {
            storeFlow.value = storeFlow.value.copy(fakeSkipVetoRules = formatVetoRules(alive))
        }
    }

    // ---------------- 闸 A: 点前(降级名单 + 事前树判据) ----------------

    /**
     * 在规则真正执行动作前调用。返回 false 表示本次点击被否决。
     *
     * 否决只发生在三种情况下(**其余一律放行**):
     *   1. 规则**显式**声明可点(`action: clickNode` / 选择器含 `clickable=true`) —— 不否决, 直接放行;
     *   2. 该 App + 该规则组 + 该节点形态 已在降级名单里(24h 内) —— 否决;
     *   3. 老版整 App 降级名单命中(兼容, 设置页可清空) —— 否决;
     *   4. 事前树判据判 `Fake`(那个点上盖着明显更大的可点层) —— 否决;
     *      判 `Real` / `Unknown` —— **放行**(判不准就点)。
     */
    fun allowAction(rule: ResolvedRule, node: AccessibilityNodeInfo): Boolean {
        val store = storeFlow.value
        if (!store.fakeSkipGuard) return true
        val appId = topActivityFlow.value.appId
        if (appId.isEmpty() || appId == META.appId) return true
        if (node.isClickable) return true
        if (!isSkipLikeTarget(rule, node)) return true
        // ① 规则自己说要"点这个节点" ⇒ 由规则决定, 判据不参与
        if (ruleDeclaresClickable(rule)) return true

        val now = System.currentTimeMillis()
        val shape = shapeKey(node)
        val groupKey = groupKeyOf(rule)
        val groupName = groupNameOf(rule)
        val key0 = nodeKey(node)

        // ② 规则组降级名单
        if (store.fakeSkipVetoRules.isNotEmpty()) {
            val hit = vetoRules(now).any {
                it.pkg == appId && it.shape == shape && (it.groupKey.isEmpty() || it.groupKey == groupKey)
            }
            if (hit) {
                rememberVetoNode(node, now)
                logVetoThrottled(appId, "rule-group", node)
                return false
            }
        }

        // ③ 老版整 App 降级名单(兼容)
        if (store.fakeSkipVetoApps.isNotEmpty() && vetoAppIds().contains(appId)) {
            rememberVetoNode(node, now)
            logVetoThrottled(appId, "legacy-app", node)
            return false
        }

        // ④ 事前树判据
        if (!store.fakeSkipJudgeEnabled) return true
        // ★ fok0030 实测: 被否决的节点在"规则匹配循环"(~300ms 一轮)里会被反复判到 —— 判完就记住它,
        //   几秒内不再重复判(既不刷日志, 也不用反复走无障碍树)。
        if (key0 == vetoNodeKey && now - vetoNodeTime < VETO_MEMO_MS) return false
        val (x, y) = clickPoint(rule, node)
        val judgement = SkipTreeJudge.judge(node, x, y)
        rememberJudge(node, judgement, now)
        if (judgement.verdict == SkipVerdict.Fake) {
            rememberVetoNode(node, now)
            logJudgeThrottled(
                appId = appId,
                verdict = judgement.verdict,
                text = label(node),
                evidence = judgement.evidence,
                extra = "overlay=${judgement.overlay} pos=($x,$y) group=$groupName action=${rule.rule.action ?: "auto"}",
            )
            showJudgeToast(appId)
            return false
        }
        logJudgeThrottled(
            appId = appId,
            verdict = judgement.verdict,
            text = label(node),
            evidence = judgement.evidence,
            extra = "pos=($x,$y)",
        )
        return true
    }

    /** 判据日志节流: 被否决的节点每 300ms 会被再判一次, 不节流会刷屏(3 秒一条) */
    private fun logJudgeThrottled(
        appId: String,
        verdict: SkipVerdict,
        text: String,
        evidence: String,
        extra: String,
    ) {
        val key = "$appId|${verdict.text}|$evidence"
        val now = System.currentTimeMillis()
        if (now - (lastJudgeLogTime[key] ?: 0L) < JUDGE_LOG_INTERVAL_MS) return
        lastJudgeLogTime[key] = now
        LogUtils.d(
            "$LOG_TAG skip-judge ${if (verdict == SkipVerdict.Fake) "reject" else "pass"} " +
                "pkg=$appId text=$text verdict=${verdict.text} evidence=$evidence $extra"
        )
    }

    // ---------------- 闸 B: 点后校验 ----------------

    /**
     * 在规则动作执行成功后调用(引擎侧 addActionLog 之后)。
     */
    fun onActionExecuted(
        rule: ResolvedRule,
        topActivity: TopActivity,
        node: AccessibilityNodeInfo,
        actionResult: ActionResult,
    ) {
        if (!storeFlow.value.fakeSkipGuard) return
        if (!actionResult.result) return
        if (!actionResult.action.startsWith("click")) return
        val pkgBefore = topActivity.appId
        if (pkgBefore.isEmpty() || pkgBefore == META.appId) return
        if (ruleDeclaresClickable(rule)) return
        if (!isSkipLikeTarget(rule, node)) return
        val key = nodeKey(node)
        val now = System.currentTimeMillis()
        if (key == vetoNodeKey && now - vetoNodeTime < VETO_MEMO_MS) return
        val token = verifyToken.incrementAndGet()
        val judgement = judgeMemo[key]?.takeIf { now - it.at <= JUDGE_MEMO_MS }?.judgement
        // fork(v108): 登记"跳过类点击已发生", 供 JumpGuard 判断此刻是否该让位
        lastSkipClickAt = now
        val activityBefore = topActivity.activityId
        val shape = shapeKey(node)
        val groupKey = groupKeyOf(rule)
        val groupName = groupNameOf(rule)
        appScope.launchTry(Dispatchers.Default) {
            delay(VERIFY_DELAY)
            if (token != verifyToken.get()) return@launchTry
            verify(pkgBefore, activityBefore, label(node), judgement, shape, groupKey, groupName)
        }
    }

    private suspend fun verify(
        pkgBefore: String,
        activityBefore: String?,
        target: String,
        judgement: SkipJudgement?,
        shape: String,
        groupKey: String,
        groupName: String,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastHandleTime < COOLDOWN_MS) return
        val after = topActivityFlow.value
        val pkgAfter = after.appId
        if (pkgAfter == pkgBefore) {
            LogUtils.d(
                "$LOG_TAG ok pkg=$pkgBefore activity=${after.activityId} before=${activityBefore ?: ""} target=$target"
            )
            return
        }
        // ★ v121: 落到"用户主动发起的意图目标"(相机/相册/文件选择器/输入法) → 不算假跳过误点
        if (SystemSurfaces.isUserIntentTarget(pkgAfter)) {
            LogUtils.d("$LOG_TAG landed-user-intent pkg=$pkgBefore -> $pkgAfter target=$target (不动作)")
            return
        }
        if (pkgAfter.isEmpty() || SystemSurfaces.isSystemSurface(pkgAfter)) {
            // 回到桌面/系统界面: 不抢返回键(可能只是用户自己按了 Home 或上滑打开了面板), 仅累计记录。
            val n = (leftSystemCount[pkgBefore] ?: 0) + 1
            leftSystemCount[pkgBefore] = n
            LogUtils.d(
                "$LOG_TAG left-system pkg=$pkgBefore -> ${pkgAfter.ifEmpty { "null" }} count=$n target=$target"
            )
            if (n >= LEFT_SYSTEM_LIMIT) {
                degrade(pkgBefore, groupKey, groupName, shape, reason = "left-system", toastEnabled = true)
                val ok = relaunchApp(pkgBefore, LOG_TAG)
                LogUtils.d("$LOG_TAG left-system relaunch=$ok pkg=$pkgBefore")
            }
            return
        }
        // ★★ fok0030 关键放宽: 事前判据判 Real = "那个点上没有可点覆盖层" ⇒ 这一枪确实打在 App 自己的
        //   处理上, 之后跳到别的应用是 **App 自己的正常业务跳转**(用户实测的高频误伤形态)。
        //   此时只记日志: **不按返回键、不降级**。
        if (judgement?.verdict == SkipVerdict.Real) {
            LogUtils.d(
                "$LOG_TAG landed-cross-app-real pkg=$pkgBefore -> $pkgAfter target=$target " +
                    "evidence=${judgement.evidence} (事前判据=real ⇒ 判为 App 自己的跳转, 不动作)"
            )
            return
        }
        // 跳到别的应用 → 假跳过误点
        lastHandleTime = now
        LogUtils.d(
            "$LOG_TAG misclick pkg=$pkgBefore -> $pkgAfter target=$target judge=${judgement?.verdict?.text ?: "none"} " +
                "evidence=${judgement?.evidence ?: "-"} group=$groupName, send BACK"
        )
        degrade(pkgBefore, groupKey, groupName, shape, reason = "to:$pkgAfter", toastEnabled = true)
        val backed = A11yRuleEngine.performActionBack()
        delay(BACK_WAIT)
        // 真机(vivo/Android16)实测: BACK 返回 true 也可能什么都没发生, 且 topActivityFlow 是**缓存值**
        // (屏幕锁了/没有新事件时会停在旧值) —— 所以这里必须用**新读一次**的窗口包名来判断
        val freshPkg = currentForegroundPkg()
        if (freshPkg == pkgBefore) {
            LogUtils.d("$LOG_TAG back ok sent=$backed now=$freshPkg")
            return
        }
        val relaunched = relaunchApp(pkgBefore, LOG_TAG)
        delay(RELAUNCH_WAIT)
        LogUtils.d(
            "$LOG_TAG back missed sent=$backed now=${freshPkg ?: "null"} relaunch=$relaunched after=${currentForegroundPkg() ?: "null"}"
        )
    }

    // ---------------- 内部 ----------------

    /**
     * 记录一次降级 —— **只降级"这个 App 的这个规则组 + 这种节点形态"**, 24 小时后自动恢复。
     * (fok0029 及之前是 `fakeSkipVetoApps` 整 App 永久拉黑, 用户实测反馈那会让"这个 App 的广告从此不跳"。)
     */
    private fun degrade(
        appId: String,
        groupKey: String,
        groupName: String,
        shape: String,
        reason: String,
        toastEnabled: Boolean,
    ) {
        if (appId.isEmpty()) return
        val now = System.currentTimeMillis()
        val list = parseVetoRules(storeFlow.value.fakeSkipVetoRules).filter { it.expireAt > now }.toMutableList()
        val idx = list.indexOfFirst { it.pkg == appId && it.groupKey == groupKey && it.shape == shape }
        if (idx >= 0) {
            val old = list[idx]
            list[idx] = old.copy(expireAt = now + DEGRADE_MS, reason = reason, count = old.count + 1)
            LogUtils.d("$LOG_TAG degrade extend pkg=$appId group=$groupName count=${old.count + 1} reason=$reason")
        } else {
            list.add(
                VetoRule(
                    pkg = appId,
                    groupKey = groupKey,
                    groupName = groupName,
                    shape = shape,
                    expireAt = now + DEGRADE_MS,
                    reason = reason,
                    count = 1,
                )
            )
            LogUtils.d(
                "$LOG_TAG degrade add pkg=$appId group=$groupName shapeGroup=${shape.take(60)} " +
                    "reason=$reason total=${list.size} ttl=24h"
            )
        }
        storeFlow.value = storeFlow.value.copy(fakeSkipVetoRules = formatVetoRules(list))
        if (toastEnabled) showDegradeToast(appId, groupName)
    }

    private fun showDegradeToast(appId: String, groupName: String) {
        val key = "$appId|$groupName"
        if (!toastShownKeys.add(key)) return
        toast(
            "假跳过防护: 已暂停在「${appLabel(appId)}」${if (groupName.isEmpty()) "" else "的「$groupName」"}里" +
                "自动点击不可点的跳过文字\n24 小时后自动恢复, 也可在 设置 页立即恢复",
            forced = true,
        )
    }

    private fun showJudgeToast(appId: String) {
        val key = "judge|$appId"
        if (!toastShownKeys.add(key)) return
        toast(
            "假跳过防护: 「${appLabel(appId)}」这个跳过是压在广告层上的假按钮, 已跳过不点(见运行日志)",
            forced = true,
        )
    }

    /** 该规则**显式**要求点节点(或选择器写明 clickable=true) ⇒ 由规则决定, 本模块放行 */
    private fun ruleDeclaresClickable(rule: ResolvedRule): Boolean {
        if (rule.rule.action == ActionPerformer.ClickNode.action) return true
        return rule.rule.matches?.any { it.contains("clickable=true") } == true
    }

    /** 与 [li.songe.gkd.data.ActionPerformer.ClickCenter] 同口径的点击点(有 position 表达式就用它算) */
    private fun clickPoint(rule: ResolvedRule, node: AccessibilityNodeInfo): Pair<Float, Float> {
        val rect = runCatching { node.casted.boundsInScreen }.getOrNull()
        val p = rect?.let { rule.rule.position?.calc(it) }
        if (p != null) return p
        val l = rect?.left ?: 0
        val t = rect?.top ?: 0
        val r = rect?.right ?: 0
        val b = rect?.bottom ?: 0
        return ((l + r) / 2f) to ((t + b) / 2f)
    }

    private fun groupKeyOf(rule: ResolvedRule): String =
        runCatching { rule.g.group.key.toString() }.getOrDefault("")

    private fun groupNameOf(rule: ResolvedRule): String = runCatching { rule.g.group.name }.getOrDefault("")

    private fun rememberJudge(node: AccessibilityNodeInfo, judgement: SkipJudgement, now: Long) {
        if (judgeMemo.size > 64) {
            judgeMemo.entries.removeIf { now - it.value.at > JUDGE_MEMO_MS }
        }
        judgeMemo[nodeKey(node)] = JudgeMemo(judgement, now)
    }

    private fun rememberVetoNode(node: AccessibilityNodeInfo, now: Long) {
        vetoNodeKey = nodeKey(node)
        vetoNodeTime = now
    }

    private fun logVetoThrottled(appId: String, why: String, node: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        val lastLog = lastVetoLogTime[appId] ?: 0L
        if (now - lastLog >= VETO_LOG_INTERVAL_MS) {
            lastVetoLogTime[appId] = now
            LogUtils.d(
                "$LOG_TAG veto pkg=$appId why=$why text=${label(node)} bounds=${nodeBounds(node)}"
            )
        }
    }

    /** 这次点击是否属于"跳过广告"语义(节点文本像跳过, 或所在规则组名像开屏/广告) */
    private fun isSkipLikeTarget(rule: ResolvedRule, node: AccessibilityNodeInfo): Boolean {
        val text = label(node)
        if (text.isNotEmpty() && text.length <= MAX_TEXT_LEN && skipWords.any { text.contains(it) }) {
            return true
        }
        val groupName = groupNameOf(rule)
        return groupName.isNotEmpty() && adGroupWords.any { groupName.contains(it) }
    }

    private fun label(node: AccessibilityNodeInfo): String {
        val t = node.text?.toString() ?: ""
        if (t.isNotEmpty()) return t
        return node.contentDescription?.toString() ?: ""
    }

    private fun nodeBounds(node: AccessibilityNodeInfo): String {
        return runCatching {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            "[${r.left},${r.top}][${r.right},${r.bottom}]"
        }.getOrDefault("[]")
    }

    private fun nodeKey(node: AccessibilityNodeInfo): String = "${nodeBounds(node)}@${label(node)}"

    /** 节点形态指纹: 类名 + 尺寸 + 文本 —— 用来把"降级"限定在同一种形状的节点上 */
    private fun shapeKey(node: AccessibilityNodeInfo): String {
        val r = runCatching { node.casted.boundsInScreen }.getOrNull()
        val size = if (r == null) "?" else "${r.width()}x${r.height()}"
        return "${node.className ?: "?"}|$size|${label(node)}"
    }

    // ---------------- 降级名单的序列化(单行一条, 制表符分隔, 便于手机上看) ----------------

    data class VetoRule(
        val pkg: String,
        val groupKey: String,
        val groupName: String,
        val shape: String,
        val expireAt: Long,
        val reason: String,
        val count: Int,
    )

    private fun parseVetoRules(text: String): List<VetoRule> {
        if (text.isBlank()) return emptyList()
        return text.split('\n').mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty()) return@mapNotNull null
            val parts = t.split(VETO_RULE_FIELD_SEP)
            if (parts.size < 5) return@mapNotNull null
            runCatching {
                VetoRule(
                    pkg = parts[0],
                    groupKey = parts[1],
                    groupName = parts[2],
                    shape = parts[3],
                    expireAt = parts[4].toLong(),
                    reason = parts.getOrElse(5) { "" },
                    count = parts.getOrElse(6) { "1" }.toIntOrNull() ?: 1,
                )
            }.getOrNull()
        }
    }

    private fun formatVetoRules(list: List<VetoRule>): String = list.joinToString("\n") { r ->
        listOf(
            r.pkg, r.groupKey, r.groupName, r.shape, r.expireAt.toString(), r.reason, r.count.toString()
        ).joinToString(VETO_RULE_FIELD_SEP.toString())
    }
}
