package com.antigravity.skipapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** 按前台窗口短期扫描，只点击具有广告证据的跳过目标；不保存坐标盲发重试。 */
class SkipAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SkipAccessibility"

        // 生产发布环境调试日志开关。logcat 渠道关闭，可观测性由下方的诊断环承担
        // （主界面直接可见，用户无需 adb）。需要 logcat 时把本行改为 true 再打包。
        private const val DEBUG_LOG = false

        // 服务生命周期全局标志
        var isConnected: Boolean = false
        var instance: SkipAccessibilityService? = null

        private const val SNIFF_WINDOW_MS = 12000L
        private const val RETRY_INTERVAL_MS = 250L
        private const val GESTURE_TIMEOUT_MS = 1000L
        private const val MAX_CLICK_ATTEMPTS = 2
        // 两次点击之间的最小间隔。
        // ⚠️ 这是本轮"一个也跳不过"的直接修复点：
        //    扫描间隔（scanInterval）最快只有 0ms/100ms，而广告 SDK 从收到点击到
        //    真正关闭开屏通常需要 300~500ms。此前没有任何点击间隔下限，
        //    两次尝试会在同一秒内连续打出去，全部落在"还没响应完的同一个死目标"上，
        //    预算瞬间耗尽 → 窗口被判定结束 → 表现为"点了但一个也跳不过"。
        //    加入下限后，第二次尝试必然晚于广告 SDK 的反应时间。
        private const val MIN_CLICK_INTERVAL_MS = 600L
        // 同一窗口内允许"尝试耗尽后重开一轮"的最大次数。
        // 取 1 是为了兼顾两端：既能接住"广告控件刷新后重现跳过按钮"的真实场景，
        // 又不会对同一不变目标反复点击（耗电 / 误触）。
        private const val MAX_RESTARTS_PER_WINDOW = 1
        // 看门狗间隔：仅在"窗口已结束 / 超出嗅探期"时启用的低频兜底复查，
        // 只为发现新窗口与新会话，不参与常规扫描（对功耗影响可忽略）。
        private const val WATCHDOG_INTERVAL_MS = 4000L
        // 看门狗复查窗口长度：仅覆盖"页面刚变动、系统尚未派发后续事件"的恢复期，
        // 到期即自行停止，保证空闲时绝不无限轮询。
        private const val WATCHDOG_WINDOW_MS = 12000L
        // 无活跃会话时的看门狗间隔：仅用于发现"新的前台应用启动"，
        // 这是"服务永不静默"的最后保证，因此不能停；但频率放长以兼顾功耗。
        private const val WATCHDOG_IDLE_INTERVAL_MS = 5000L
        private const val COOLDOWN_MS = 1500L
        // 最大递归深度
        private const val MAX_TRAVERSAL_DEPTH = 22
        // 最大扫描节点预算（因下半屏 60% 已剪枝，300 节点足够应对任意复杂开屏布局）
        private const val MAX_SCAN_NODES = 300

        // 胶囊常见分隔符集合（单例预编译，零临时数组分配）
        private val DIVIDER_CHARS = charArrayOf('|', '｜', '•', '·', '・', ':', '/', '-')

        // 系统桌面、系统设置、系统UI、输入法与金融安全应用白名单（100% 绝对静默，绝不检测、不读取、防任何误触）
        private val IGNORED_PACKAGES = hashSetOf(
            // 系统桌面与系统框架
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher3",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.hihonor.android.launcher",
            "com.oppo.launcher",
            "com.coloros.launcher",
            "com.bbk.launcher2",
            "com.vivo.upslide",
            "com.vivo.launcher",
            "com.hihonor.magichome",
            "com.sec.android.app.launcher",
            "com.google.android.apps.nexuslauncher",

            // 系统设置与权限中心（绝对静默，绝不扫描，防误触系统开关与设置项）
            "com.android.settings",
            "com.coloros.settings",
            "com.oplus.settings",
            "com.oplus.wirelesssettings",
            "com.oplus.trafficmonitor",
            "com.oplus.subsystem",
            "com.oplus.athena",
            "com.oplus.screenrecorder",
            "com.coloros.safecenter",
            "com.oplus.safecenter",
            "com.coloros.securitypermission",
            "com.miui.securitycenter",
            "com.miui.settings",
            "com.huawei.settings",
            "com.hihonor.settings",
            "com.vivo.settings",
            "com.google.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",

            // 输入法白名单
            "com.sohu.inputmethod.sogou",
            "com.baidu.input",
            "com.tencent.qqpinyin",
            "com.google.android.inputmethod.latin",

            // 自身与核心金融安全应用
            "com.antigravity.skipapp",
            "com.eg.android.AlipayGphone", // 支付宝
            "com.tencent.mm",              // 微信
            "com.unionpay"                 // 云闪付
        )

        // 全生态主流厂商系统安全中心与权限弹窗包名（小米/澎湃、华为/荣耀、OPPO/一加、vivo/iQOO、原生）
        private val SYSTEM_SECURITY_PACKAGES = hashSetOf(
            "com.miui.securitycenter",
            "com.huawei.systemmanager",
            "com.hihonor.systemmanager",
            "com.vivo.permissionmanager",
            "com.iqoo.secure",
            "com.coloros.safecenter",
            "com.oplus.safecenter",
            "com.coloros.securitypermission",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "android"
        )

        // 强跳过核心关键词（严禁包含裸词“关闭”与“close”，防止误触系统与应用的【已关闭】等状态文本）
        private val SKIP_KEYWORDS = arrayOf("跳过", "关闭广告", "跳过广告", "skip")

        // 常见媒体播放/付费业务黑名单词汇（防止误触短视频、音频播放器或会员开通按钮）
        private val SKIP_TEXT_BLACKLIST = arrayOf(
            "片头", "片尾", "歌曲", "下一", "上一", "播放", "暂停", "重试", "刷新",
            "会员", "开通", "充值", "购买", "vip"
        )

        // 核心跳过与关闭关键词（单例重用，零堆分配）
        private val TARGET_CLICK_KEYWORDS = arrayOf("跳过", "skip", "关闭广告", "跳过广告")

        // 业务控件标识：只匹配资源 entry 名（":id/" 之后的部分）并按词边界判定。
        // 裸子串匹配会把 "header"/"list_item"/"btn_close" 这类广告关闭控件一起否掉，
        // 因此 btn_close/close_btn/cancel/sign/info/back/more/edit/item/feed/card 已从表中移除。
        private val BUSINESS_ID_TOKENS = arrayOf(
            "search", "scan", "share", "setting", "settings", "user", "profile", "avatar",
            "header", "head", "badge", "unread", "account", "password", "wallet", "message",
            "msg", "chat", "notice", "notify", "bell", "help", "service", "kefu", "vip",
            "login", "cart", "shop", "favorite", "like", "comment", "download", "history",
            "camera", "sound", "mute", "volume", "mic", "follow", "attention", "pay", "buy",
            "member"
        )

        // 中文业务词按子串匹配 contentDescription（中文无词边界可言）。
        private val BUSINESS_TEXT_TOKENS = arrayOf(
            "静音", "声音", "音量", "客服", "帮助", "搜索", "分享", "扫一扫", "关注", "点赞",
            "评论", "下载", "购买", "会员", "充值", "开通", "头像", "账号", "登录", "购物车",
            "详情", "编辑"
        )

        // 广告联盟规范的跳过按钮 ID 特征
        private val SKIP_ID_PATTERNS = arrayOf(
            "splash_skip",
            "tt_splash_skip",
            "ksad_splash_skip",
            "btn_skip",
            "tv_skip",
            "skip_view",
            "skip_text",
            "skip_btn",
            "skip_count",
            "skip_ll",
            "skip_layout",
            "ad_skip",
            "skip"
        )

        // 严禁匹配的 ID 黑名单（过滤短视频播放器、音乐播放器控制条）。
        // 仅当 entry 名不含任何广告特征时才生效，否则 "tt_video_splash_skip_btn"
        // 这类"视频开屏的跳过按钮"会被裸子串一起否掉。
        private val ID_BLACKLIST = arrayOf(
            "exo", "player", "video", "music", "track", "audio", "next", "prev", "forward", "rewind"
        )

        // 列表行 / 开关形态的容器：出现在这些容器里的"跳过广告"文案是设置项而非浮动按钮。
        // ViewPager 是新手引导页的载体，靠它把"引导页跳过"和"开屏跳过"分开。
        private val LIST_CONTAINER_CLASSES = arrayOf(
            "ListView", "RecyclerView", "GridView", "ScrollView", "HorizontalScrollView", "AdapterView",
            "ViewPager"
        )

        // 引导/欢迎页的 Activity 名一般不被混淆（要在 Manifest 里声明），可作为第二道区分。
        private val GUIDE_CLASS_TOKENS = arrayOf(
            "guide", "welcome", "intro", "onboard", "tutorial", "adfree"
        )

        // 开关/输入形态控件本身永不可点。
        private val CONTROL_CLASSES = arrayOf(
            "Switch", "CheckBox", "RadioButton", "ToggleButton", "SeekBar", "Spinner",
            "CompoundButton", "EditText", "AutoCompleteTextView", "CheckableImageButton"
        )

        // 广告特征 token（用于 id / 类名判定）
        private val AD_FEATURE_TOKENS = arrayOf(
            "splash_ad", "ad_splash", "splashad", "advert", "interstitial", "adactivity",
            "ad_activity", "ad_container", "adcontainer", "tt_splash", "ksad", "gdt_splash",
            "com.baidu.mobads"
        )

        private inline fun logD(crossinline msg: () -> String) {
            if (DEBUG_LOG) {
                Log.d(TAG, msg())
            }
        }

        // ── 运行诊断 ─────────────────────────────────────────────────────────────
        // 主界面直接读这几个定长数组，用户不需要 adb 就能看到"为什么没跳"。
        // 热路径只写 int / 引用槽位，不拼字符串、不建对象。
        const val DIAG_SLOTS = 64
        const val R_SESSION = 1
        const val R_IGNORED = 2
        const val R_SLEEP = 3
        const val R_NO_SIGN = 4
        const val R_ZONE = 5
        const val R_SIZE = 6
        const val R_BUSINESS = 7
        const val R_LIST = 8
        const val R_TEXT = 9
        const val R_NO_AD = 10
        const val R_STATE = 11
        const val R_CLICK_NODE = 12
        const val R_CLICK_GESTURE = 13
        const val R_GESTURE_REJECT = 14
        const val R_REFRESH_FAIL = 15
        const val R_EXHAUSTED = 16
        const val R_TIMEOUT = 17
        const val R_WINDOW_DONE = 18
        const val R_WATCHDOG = 19

        @JvmField val diagCode = IntArray(DIAG_SLOTS)
        @JvmField val diagPkg = arrayOfNulls<String>(DIAG_SLOTS)
        @JvmField val diagTime = LongArray(DIAG_SLOTS)
        @JvmField var diagCursor = 0

        // 0 扫描帧 1 命中候选 2 节点点击 3 手势点击 4 手势被拒 5 refresh失败 6 会话数 7 忽略包帧
        const val C_SCANS = 0
        const val C_CANDIDATES = 1
        const val C_NODE_CLICKS = 2
        const val C_GESTURE_CLICKS = 3
        const val C_GESTURE_REJECTED = 4
        const val C_REFRESH_FAIL = 5
        const val C_SESSIONS = 6
        const val C_IGNORED = 7
        const val C_COUNTERS = 8
        @JvmField val diagCounters = IntArray(C_COUNTERS)
        @JvmField var diagReject = 0
        @JvmField var diagTargetId: String? = null
        @JvmField var diagTargetText: CharSequence? = null
        @JvmField var diagLastScanPkg: String? = null
        @JvmField var diagLastScanClass: CharSequence? = null

        // 最近一个"写着跳过却被拒"的节点。候选为 0 时，这是唯一能看出真机树长什么样的入口。
        // 只存引用与整数，不拼字符串（热路径零分配）。
        @JvmField var diagNearId: String? = null
        @JvmField var diagNearText: CharSequence? = null
        @JvmField var diagNearClass: CharSequence? = null
        @JvmField var diagNearReject = 0
        @JvmField val diagNearRect = IntArray(4)
        @JvmField val diagNearWin = IntArray(4)
        // 位标记：1 可见 2 可用 4 可点 8 可编辑 16 文本含跳过 32 描述含跳过
        @JvmField var diagNearState = 0

        fun diagRecord(reason: Int, pkg: String?) {
            diagCode[diagCursor] = reason
            diagPkg[diagCursor] = pkg
            diagTime[diagCursor] = SystemClock.elapsedRealtime()
            diagCursor = (diagCursor + 1) % DIAG_SLOTS
        }

        fun resetDiagnostics() {
            diagCode.fill(0)
            diagPkg.fill(null)
            diagTime.fill(0L)
            diagCounters.fill(0)
            diagCursor = 0
            diagReject = 0
            diagTargetId = null
            diagTargetText = null
            diagLastScanPkg = null
            diagLastScanClass = null
            diagNearId = null
            diagNearText = null
            diagNearClass = null
            diagNearReject = 0
            diagNearState = 0
            diagNearRect.fill(0)
            diagNearWin.fill(0)
        }
    }

    private var currentPackage = ""
    private var currentWindowClassName: CharSequence = ""
    private var currentWindowId = -1
    private var appEnterTime = 0L
    private var lastSkipTime = 0L
    private var lastScanTime = 0L
    private var visitBudget = 0
    private var hasSkippedCurrentWindow = false
    private var clickAttempts = 0
    private var consecutiveNoAdScans = 0
    // 同一窗口内"尝试耗尽后重开一轮"的剩余次数，防止对同一不变目标无限点击。
    private var restartBudget = MAX_RESTARTS_PER_WINDOW
    // 看门狗的时间窗口截止点：只在"页面刚发生变动"后的一小段时间内低频复查，
    // 超时即停，避免空闲时无限轮询（省电红线）。
    private var watchdogUntil = 0L
    // 看门狗需要连续两次看到同一个新窗口 id 才认定为新会话，用于识破 windowId 抖动。
    private var pendingWatchdogWindowId = -1
    private var suspended = false
    private var hasAdContext = false
    private var gestureTarget: AccessibilityNodeInfo? = null
    private var powerManager: PowerManager? = null
    private var keyguardManager: KeyguardManager? = null
    private var cachedDensity = 2.75f
    private val windowRect = Rect()
    private val bestCandidateRect = Rect()
    private val targetClickRect = Rect()
    private val tempNodeRect = Rect()
    private val tempParentRect = Rect()
    private var bestCandidateNode: AccessibilityNodeInfo? = null
    private var bestCandidateArea = Int.MAX_VALUE
    private val gesturePath = Path()
    private val handler = Handler(Looper.getMainLooper())
    private var pendingGesture: GestureDescription? = null
    private var gestureStartedAt = 0L
    private var gestureWindowId = -1

    private val scanRunnable = object : Runnable {
        override fun run() {
            try {
                if (!isConnected || !canInteract()) {
                    resetSession()
                    suspended = true
                    return
                }
                val now = SystemClock.elapsedRealtime()
                if (hasSkippedCurrentWindow) {
                    // 本窗口已判定结束：常规扫描不再继续，但必须续上看门狗复查窗口，
                    // 否则一旦系统不再派发事件，服务就永久静默。
                    armWatchdog()
                    return
                }
                if (now - appEnterTime > SNIFF_WINDOW_MS) { finishWindow(); return }
                val root = rootInActiveWindow ?: run {
                    scheduleScan(250L)
                    return
                }
                try {
                    // 异步重试必须从当前前台窗口重新找目标，绝不能使用旧节点或旧坐标。
                    if (!isCurrentRoot(root)) {
                        // ⚠️ 死锁点（历史）：这里曾 resetSession() 后直接 return。
                        // resetSession() 会把 currentPackage 清空，而清空后本函数与
                        // scheduleScan 都不再续期（前者已 return，后者受 hasSkippedCurrentWindow 约束），
                        // 于是扫描链彻底停摆——若此后该 App 不再产生事件，就永久不跳。
                        // 现在 isCurrentRoot 只比包名（窗口抖动由 scanWindow 重绑，不走这条路），
                        // 因此本分支只代表"前台换了一个应用"：立即用当前根重建会话并继续复查。
                        val rootPkg = root.packageName?.toString()
                        if (rootPkg != null &&
                            !IGNORED_PACKAGES.contains(rootPkg) && !SYSTEM_SECURITY_PACKAGES.contains(rootPkg)) {
                            beginWindow(rootPkg, root.windowId, root.className ?: "", now)
                            scanWindow(root, now)
                        } else {
                            resetSession()
                        }
                        return
                    }
                    scanWindow(root, now)
                } finally {
                    recycleNode(root)
                }
            } catch (e: Exception) {
                DiagStore.append("EX_SCAN", e.toString())
                Log.e(TAG, "定时复查失败", e)
                scheduleScan(800L)
            }
        }
    }

    /**
     * 低频看门狗。
     *
     * 存在的唯一理由：常规扫描的所有闸门（窗口已结束 / 超出嗅探期 / 尝试耗尽）都可能关闭，
     * 若无兜底就再也没有任何代码路径能把扫描重启 —— 表现为服务"永久静默"。
     * 因此这里只做一件事：检查前台根节点是否属于"一个新的会话"，
     * 是则重开会话并交还给常规扫描；否则继续低频等待。
     * "新会话"= 换了应用，或上一个窗口已收束后连续两次看到同一个新窗口 id（识破 windowId 抖动）。
     * 它不点击、不做重判定，功耗影响可忽略。
     */
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                if (!isConnected) return
                // 熄屏/锁屏中不消耗复查窗口，只重新排队等待可交互。
                if (!canInteract()) {
                    scheduleWatchdog()
                    return
                }
                val now = SystemClock.elapsedRealtime()
                val root = rootInActiveWindow ?: run {
                    scheduleWatchdog()
                    return
                }
                try {
                    val rootPkg = root.packageName?.toString()
                    if (rootPkg == null || IGNORED_PACKAGES.contains(rootPkg) ||
                        SYSTEM_SECURITY_PACKAGES.contains(rootPkg)) {
                        scheduleWatchdog()
                        return
                    }
                    // 换应用一定是新会话；同一应用只有在"上一个窗口已收束"之后的窗口变化
                    // 才是新会话。真机上同一广告窗口的 windowId 会反复漂移（地图/悬浮窗/画中画），
                    // 若把漂移当新会话，clickAttempts 与 restartBudget 每帧被清零 → 死控件被无限补枪。
                    val newApp = rootPkg != currentPackage
                    val newWindowAfterDone = hasSkippedCurrentWindow && root.windowId != currentWindowId
                    // 真机同一条广告窗口的 windowId 会来回跳（1↔7↔…）。只认"连续两次看到同一个新窗口"，
                    // 否则抖动会被当成新会话，清空 clickAttempts / restartBudget → 对死控件无限补枪。
                    val confirmedNewWindow = newWindowAfterDone && root.windowId == pendingWatchdogWindowId
                    if (newApp || confirmedNewWindow) {
                        pendingWatchdogWindowId = -1
                        diagRecord(R_WATCHDOG, rootPkg)
                        logD { "看门狗发现新会话: $currentPackage -> $rootPkg (win=${root.windowId})" }
                        beginWindow(rootPkg, root.windowId, root.className ?: "", now)
                        scanWindow(root, now)
                    } else {
                        pendingWatchdogWindowId = if (newWindowAfterDone) root.windowId else -1
                        if (!newApp) currentWindowId = root.windowId
                        scheduleWatchdog()
                    }
                } finally {
                    recycleNode(root)
                }
            } catch (e: Exception) {
                DiagStore.append("EX_WATCHDOG", e.toString())
                Log.e(TAG, "看门狗复查失败", e)
                scheduleWatchdog()
            }
        }
    }

    private fun scheduleWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
        if (!isConnected) return
        if (handler.hasCallbacks(scanRunnable)) return
        // 有活跃会话时只在短窗口内密集复查；无活跃会话时长期低频存活，
        // 唯一目的是发现"新的前台应用启动"，这是"永不静默"的最后一道保证。
        val hasActiveSession = currentPackage.isNotEmpty() && !hasSkippedCurrentWindow &&
                SystemClock.elapsedRealtime() - appEnterTime <= SNIFF_WINDOW_MS
        val interval = if (hasActiveSession || SystemClock.elapsedRealtime() < watchdogUntil) {
            WATCHDOG_INTERVAL_MS
        } else {
            WATCHDOG_IDLE_INTERVAL_MS
        }
        handler.postDelayed(watchdogRunnable, interval)
    }

    /** 页面发生变动（新会话 / 新事件）时，为看门狗续上密集复查窗口。 */
    private fun armWatchdog() {
        watchdogUntil = SystemClock.elapsedRealtime() + WATCHDOG_WINDOW_MS
        scheduleWatchdog()
    }

    private val gestureCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription) {
            if (pendingGesture !== gestureDescription) return
            pendingGesture = null
            if (gestureWindowId == currentWindowId) {
                Log.i(TAG, "手势已完成，将检查广告目标是否仍在")
                if (clickAttempts >= MAX_CLICK_ATTEMPTS) restartAttempts() else scheduleScan(RETRY_INTERVAL_MS)
            }
        }

        override fun onCancelled(gestureDescription: GestureDescription) {
            if (pendingGesture !== gestureDescription) return
            pendingGesture = null
            Log.i(TAG, "手势被取消，将重新校验当前目标")
            if (gestureWindowId == currentWindowId) {
                if (clickAttempts >= MAX_CLICK_ATTEMPTS) restartAttempts() else scheduleScan(RETRY_INTERVAL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        DiagStore.init(applicationContext)
        DiagStore.append("CONNECT", null)
        resetSession()
        applyServiceFlags()
        refreshScreenMetrics()
        isConnected = true
        instance = this
        Log.i(TAG, "无障碍服务已连接")
    }

    // 保留 Android 自带的未捕获异常处理器，主线程崩溃时让系统正常回收并重新绑定。
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun applyServiceFlags() {
        try {
            val info = serviceInfo ?: return
            val requiredFlags = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            if ((info.flags and requiredFlags) != requiredFlags || info.notificationTimeout != 20L) {
                info.flags = info.flags or requiredFlags
                info.notificationTimeout = 20L
                serviceInfo = info
            }
        } catch (e: Exception) {
            Log.e(TAG, "同步无障碍配置失败", e)
        }
    }

    private fun refreshScreenMetrics() {
        cachedDensity = resources.displayMetrics.density
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshScreenMetrics()
        // resetSession 会摘掉 scan 与看门狗两条队列并且清零 watchdogUntil：
        // 旋转/大小窗切换后若系统不再派发事件，这里不补兜底就是永久静默。
        resetSession()
        armWatchdog()
    }

    private fun canInteract(): Boolean = powerManager?.isInteractive != false &&
            powerManager?.isDeviceIdleMode != true && keyguardManager?.isKeyguardLocked != true

    private fun resetSession() {
        handler.removeCallbacks(scanRunnable)
        handler.removeCallbacks(watchdogRunnable)
        pendingGesture = null
        currentPackage = ""
        currentWindowClassName = ""
        currentWindowId = -1
        hasSkippedCurrentWindow = false
        clickAttempts = 0
        consecutiveNoAdScans = 0
        lastScanTime = 0L
        lastSkipTime = 0L
        restartBudget = MAX_RESTARTS_PER_WINDOW
        watchdogUntil = 0L
        pendingWatchdogWindowId = -1
        // 熄屏/锁屏/解绑后重置时，必须一并解除挂起，否则恢复后会被残留的 suspended 永久挡住。
        suspended = false
        recycleNode(gestureTarget)
        gestureTarget = null
        releaseCandidates()
    }

    private fun beginWindow(pkg: String, id: Int, className: CharSequence, now: Long) {
        handler.removeCallbacks(scanRunnable)
        pendingGesture = null
        recycleNode(gestureTarget)
        gestureTarget = null
        currentPackage = pkg
        currentWindowId = id
        currentWindowClassName = className
        appEnterTime = now
        lastScanTime = 0L
        lastSkipTime = 0L
        clickAttempts = 0
        consecutiveNoAdScans = 0
        hasSkippedCurrentWindow = false
        restartBudget = MAX_RESTARTS_PER_WINDOW
        // 注意：这里不重置 watchdogUntil，避免"看门狗→beginWindow→看门狗"的自持循环。
        // 由真实事件驱动的会话切换会在调用处显式 armWatchdog()。
        suspended = false
        diagReject = 0
        diagCounters[C_SESSIONS]++
        diagRecord(R_SESSION, pkg)
        DiagStore.append("SESSION", pkg)
        logD { "开始检查前台窗口: $pkg" }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            if (!canInteract()) {
                if (!suspended) diagRecord(R_SLEEP, currentPackage)
                resetSession()
                suspended = true
                handler.removeCallbacks(scanRunnable)
                // 熄屏/锁屏期间不扫描；但要留一个复查窗口，保证解锁后即使系统不再派发事件也能恢复。
                armWatchdog()
                return
            }
            val pkg = event.packageName?.toString()
            if (pkg != null && (IGNORED_PACKAGES.contains(pkg) || SYSTEM_SECURITY_PACKAGES.contains(pkg))) {
                // 回到桌面/系统设置/安全中心即代表离开原应用，必须清除上一应用的会话，
                // 否则残留的 hasSkippedCurrentWindow 会让下一次冷启动被视为"已跳过"而不再扫描。
                if (pkg != currentPackage) resetSession()
                diagCounters[C_IGNORED]++
                // 白名单静默是刻意的，但用户必须能在诊断页看到"这个包被跳过了"，
                // 否则"某个 App 不跳"和"服务坏了"长得一模一样。
                if (diagCode[diagCursor] != R_IGNORED || diagPkg[diagCursor] != pkg) diagRecord(R_IGNORED, pkg)
                // 回桌面后若系统不再派发新事件（长时间静止），必须有兜底才能发现下一次冷启动。
                armWatchdog()
                return
            }
            val windowEvent = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            val now = SystemClock.elapsedRealtime()
            if (!windowEvent && pkg == currentPackage && !suspended) {
                if (hasSkippedCurrentWindow || now - appEnterTime > SNIFF_WINDOW_MS) {
                    if (!hasNewAdSource(event, now)) { armWatchdog(); return }
                    beginWindow(currentPackage, currentWindowId, currentWindowClassName, now)
                    armWatchdog()
                } else if (now - lastScanTime < scanInterval()) { armWatchdog(); return }
            }
            val root = rootInActiveWindow ?: run {
                if (pkg != null && pkg != currentPackage) {
                    beginWindow(pkg, event.windowId, event.className ?: "", now)
                    armWatchdog()
                }
                if (currentPackage.isNotEmpty()) scheduleScan(250L) else armWatchdog()
                return
            }
            try {
                val rootPkg = root.packageName?.toString() ?: run { armWatchdog(); return }
                if (IGNORED_PACKAGES.contains(rootPkg) || SYSTEM_SECURITY_PACKAGES.contains(rootPkg)) {
                    armWatchdog()
                    return
                }
                // 【包名一致性校验】
                // 判定原则：以 rootInActiveWindow 为唯一权威（它由系统维护，代表真正的当前活动窗口），
                // 事件包名只允许用于"同一前台树的降噪"，绝不允许用它来否掉根节点所属的应用——
                // 真机上事件包名长期滞后、甚至由别的进程（前台服务/悬浮窗）发出都是常态。
                //
                // 具体分支：
                //  (a) 事件包名与根包不一致，且根包就是当前会话应用 —— 前台树未变，
                //      事件来自后台包，本帧只做降噪丢弃；
                //  (b) 事件包名与根包不一致，且当前没有会话 —— 刚被 resetSession 清空（回桌面/切 App/熄屏恢复），
                //      必须先信任根节点重建会话，否则会话永远建不起来；
                //  (c) 事件包名与根包不一致，且根包已切换到别的应用 —— 前台确实换了 App，
                //      下方"重开会话"逻辑会接管，这里绝不能拦截。
                //
                // ⚠️ 历史缺陷（用户实测"百度地图跳不过一次后所有 App 全失效"）：
                //    这里曾写成 `pkg != rootPkg && (currentPackage.isEmpty() || rootPkg == currentPackage)` 就 return，
                //    其中 `rootPkg == currentPackage` 在会话已建立后恒成立，
                //    于是"事件包名持续不等于前台包名"的 App（导航/悬浮窗类）每一帧都被丢弃，
                //    而 resetSession 后第一帧又因 `currentPackage.isEmpty()` 被丢弃 —— 两条路径叠加即永久失效。
                val noSessionYet = currentPackage.isEmpty()
                if (pkg != null && pkg != rootPkg && (noSessionYet || rootPkg == currentPackage)) {
                    logD { "跳过: 事件包($pkg)与根包($rootPkg)不一致" }
                    if (noSessionYet) {
                        // 仅重建会话（本帧不点击），交给随后的帧或定时复查去扫。
                        beginWindow(rootPkg, root.windowId, event.className ?: root.className ?: "", now)
                        armWatchdog()
                    }
                    return
                }
                val cls = event.className ?: root.className ?: ""
                val classChanged = windowEvent && isActivityClass(cls) && cls != currentWindowClassName
                // 同一应用内 windowId 漂移不算新会话（否则每帧清空 clickAttempts → 对死控件无限补枪），
                // 只有上一个窗口已收束后的新窗口才重开。scanWindow 会把会话窗口重绑到当前根。
                val windowDrift = root.windowId != currentWindowId
                val newWindow = windowDrift && (hasSkippedCurrentWindow || currentPackage.isEmpty())
                if (rootPkg != currentPackage || newWindow || classChanged || suspended) {
                    logD { "重开会话: $currentPackage -> $rootPkg (win=${root.windowId}, cls=$cls)" }
                    beginWindow(rootPkg, root.windowId, cls, now)
                    // 由真实事件驱动的会话切换：为看门狗续上复查窗口。
                    armWatchdog()
                } else if (windowDrift) {
                    currentWindowId = root.windowId
                }
                scanWindow(root, now)
            } finally {
                recycleNode(root)
            }
        } catch (e: Exception) {
            DiagStore.append("EX_EVENT", e.toString())
            Log.e(TAG, "无障碍事件处理失败", e)
            // 异常也不能让调度链静默，续上看门狗窗口以保证服务可恢复。
            armWatchdog()
        }
    }

    private fun isActivityClass(cls: CharSequence): Boolean = cls.endsWith("Activity", true)

    private fun hasNewAdSource(event: AccessibilityEvent, now: Long): Boolean {
        // 两次无效尝试后，同窗口的倒计时事件不能不断重置预算；等待真正的窗口/Activity 切换。
        if (clickAttempts >= MAX_CLICK_ATTEMPTS || now - lastSkipTime < COOLDOWN_MS) return false
        val source = event.source ?: return false
        return try {
            source.isVisibleToUser && source.isEnabled && source.packageName?.contentEquals(currentPackage) == true &&
                    source.windowId == currentWindowId &&
                    (hasAdFeature(source.viewIdResourceName) || hasAdFeature(source.className))
        } finally {
            recycleNode(source)
        }
    }

    private fun isCurrentRoot(root: AccessibilityNodeInfo): Boolean = root.packageName?.contentEquals(currentPackage) == true &&
            !IGNORED_PACKAGES.contains(currentPackage)

    private fun scanInterval(): Long = when (consecutiveNoAdScans) {
        0 -> 0L
        1 -> 100L
        2 -> 250L
        3 -> 450L
        else -> 800L
    }

    private fun scheduleScan(delay: Long) {
        handler.removeCallbacks(scanRunnable)
        if (isConnected && !hasSkippedCurrentWindow &&
            SystemClock.elapsedRealtime() - appEnterTime <= SNIFF_WINDOW_MS) {
            // 常规扫描恢复接管，看门狗立即退位，避免两者同时轮询。
            handler.removeCallbacks(watchdogRunnable)
            handler.postDelayed(scanRunnable, delay.coerceAtLeast(100L))
        } else if (isConnected) {
            // ⚠️ 本轮修复的第三个（架构级）致命点：
            // scheduleScan 的两个闸门（窗口已结束 / 超出嗅探期）一旦同时关闭，
            // 就再也没有任何路径能把扫描重新启动起来 —— 整个服务进入"永久静默"，
            // 只能等系统派发新事件；而长时间运行的无障碍服务被系统降频派发事件是常态，
            // 于是表现为"某个 App 之后，其他所有 App 全都不能跳"。
            // 兜底：保留一个低频看门狗，只为发现"新的窗口 / 新的会话"。
            armWatchdog()
        }
    }

    private fun finishWindow() {
        hasSkippedCurrentWindow = true
        handler.removeCallbacks(scanRunnable)
        recycleNode(gestureTarget)
        gestureTarget = null
        diagRecord(R_WINDOW_DONE, currentPackage)
        DiagStore.append("WINDOW_DONE", currentPackage)
        // 窗口被判结束后必须留下看门狗，否则一旦系统不再派发事件就永久静默。
        armWatchdog()
    }

    /**
     * 尝试次数耗尽后的处理。
     *
     * ⚠️ 本轮修复的第二个致命点：此前"尝试耗尽"直接调用 [finishWindow]，
     * 把整个窗口永久判定为已跳过，扫描链随之彻底停摆 ——
     * 真机上只要某个 App 消耗掉 2 次尝试（跳不过），它与之后的判断就全废。
     * 但"点了两次没跳掉"不等于"开屏已结束"：广告控件刷新、倒计时重置、
     * 插屏替换开屏都是常态，之后往往还有新的跳过机会。
     *
     * 同时必须避免走向另一个极端（同一控件被无限反复点击 → 耗电 + 误触）：
     * 因此重开一轮尝试受两个闸门约束：
     *  1. [restartBudget] 限制同窗口内最多重开次数；
     *  2. 调用方只在"检测到新广告来源 / 窗口切换"时才真正调用本函数。
     */
    private fun restartAttempts() {
        recycleNode(gestureTarget)
        gestureTarget = null
        // 每次重开前都要求广告 SDK 已有足够反应时间，避免"重开"退化成连续补枪。
        if (SystemClock.elapsedRealtime() - lastSkipTime < MIN_CLICK_INTERVAL_MS) {
            scheduleScan(RETRY_INTERVAL_MS)
            return
        }
        if (restartBudget <= 0) {
            // 预算用尽：本轮确实无法跳过，正常收束该窗口（等新窗口/新会话）。
            diagRecord(R_EXHAUSTED, currentPackage)
            finishWindow()
            return
        }
        restartBudget--
        clickAttempts = 0
        lastSkipTime = 0L
        consecutiveNoAdScans = 0
        scheduleScan(scanInterval())
    }

    private fun scanWindow(root: AccessibilityNodeInfo, now: Long) {
        // ⚠️ 本轮修复：以下提前返回曾直接 return，既不调度也不清理，
        //    导致"某一帧的临时不一致"会把整个定时复查链永久打断 → 之后该 App 再也不跳。
        //    统一原则：只有"正常终止"（已跳过 finishWindow / 超时）才允许静默返回，
        //    其余情况必须续期调度，让下一帧有机会恢复。
        if (!isCurrentRoot(root)) {
            // 前台已经换应用（不是窗口抖动——抖动由下面的重绑处理）。
            // 不做 finishWindow（那会把整个窗口判死），只续期等待下一次复查。
            if (currentPackage.isEmpty()) {
                // 没有会话：无需等待，由后续事件重建。
                return
            }
            scheduleScan(RETRY_INTERVAL_MS)
            return
        }
        if (hasSkippedCurrentWindow) {
            // 同样续上看门狗，保证任何一条提前退出路径都不会让服务静默。
            armWatchdog()
            return
        }
        // 活动根节点是窗口归属的唯一权威：广告常在自己的独立窗口里绘制，其 windowId 与
        // 建会话时（甚至与上一帧的根）不一致是常态。用它重绑，否则同一应用内换窗口即漏扫。
        if (root.windowId != currentWindowId) currentWindowId = root.windowId
        if (now - appEnterTime > SNIFF_WINDOW_MS) { finishWindow(); return }
        if (pendingGesture != null) {
            if (now - gestureStartedAt < GESTURE_TIMEOUT_MS) {
                scheduleScan(RETRY_INTERVAL_MS)
                return
            }
            pendingGesture = null // 忽略随后到达的旧回调，下一次重试仍重新扫描当前前台树。
            diagRecord(R_TIMEOUT, currentPackage)
            Log.w(TAG, "手势回调超时，重新校验目标")
        }
        // 尝试耗尽 ≠ 开屏结束：广告控件刷新后往往还有新的跳过机会，故只重开一轮尝试。
        if (clickAttempts >= MAX_CLICK_ATTEMPTS) {
            // ⚠️ 重开一轮前必须先等广告 SDK 反应完，否则"重开"只是在同一秒里再打两枪，
            //    既不会有结果，还会把重开预算一起烧掉（本轮"一个也跳不过"的成因之一）。
            if (now - lastSkipTime < MIN_CLICK_INTERVAL_MS) {
                scheduleScan(RETRY_INTERVAL_MS)
                return
            }
            restartAttempts()
            return
        }
        if (clickAttempts > 0 && now - lastSkipTime < MIN_CLICK_INTERVAL_MS) {
            // 关键修复：下一次尝试不得早于广告 SDK 的响应时间。
            // 旧值 RETRY_INTERVAL_MS(250ms) < 反应时间(约 300~500ms)，
            // 导致"点一次没反应就立刻补第二枪"，两次都无效，随后判定窗口结束。
            scheduleScan(RETRY_INTERVAL_MS)
            return
        }
        lastScanTime = now
        root.getBoundsInScreen(windowRect)
        if (windowRect.isEmpty) {
            // 读不到窗口边界只代表这一帧的树不可用，不代表开屏结束，必须继续复查。
            scheduleScan(RETRY_INTERVAL_MS)
            return
        }
        hasAdContext = hasAdFeature(currentWindowClassName)
        visitBudget = MAX_SCAN_NODES
        collectAdContext(root, 0)
        try {
            bestCandidateArea = Int.MAX_VALUE
            visitBudget = MAX_SCAN_NODES
            searchBestSkipCandidate(root, 0)
            diagCounters[C_SCANS]++
            diagLastScanPkg = currentPackage
            diagLastScanClass = currentWindowClassName
            val target = bestCandidateNode
            if (target == null) {
                // 目标消失只代表"这一帧没找到"，不代表整个开屏结束：
                // 旧实现直接 finishWindow() 会把同一开屏内稍后出现的插屏广告一起锁死。
                if (gestureTarget != null) {
                    recycleNode(gestureTarget)
                    gestureTarget = null
                    clickAttempts = 0
                }
                consecutiveNoAdScans++
                // 只在前几次与整 5 次时入环，避免几十毫秒一轮的轮询把诊断环冲满。
                if (consecutiveNoAdScans == 1 || consecutiveNoAdScans % 5 == 0) {
                    diagRecord(if (diagReject == 0) R_NO_SIGN else diagReject, currentPackage)
                }
                logD { "本帧未找到跳过目标 (第 $consecutiveNoAdScans 次无广告)" }
            } else {
                diagCounters[C_CANDIDATES]++
                diagTargetId = target.viewIdResourceName
                diagTargetText = (target.text ?: target.contentDescription)?.toString()
                // 诊断日志：使用局部变量测量，避免污染 tempNodeRect 影响后续判定。
                if (DEBUG_LOG) {
                    val dbg = Rect()
                    target.getBoundsInScreen(dbg)
                    Log.d(TAG, "命中候选: id=${target.viewIdResourceName} text=${target.text} " +
                            "bounds=${dbg.left},${dbg.top},${dbg.right},${dbg.bottom} adContext=$hasAdContext")
                }
                // 目标换成了另一个控件：说明旧广告已不是当前目标（可能被替换或翻页），
                // 只丢弃旧快照并重新开始计数，绝不能据此把整个窗口判定为"已跳过"，
                // 否则同一次开屏里的第二个广告会被连带吞掉。
                if (gestureTarget != null && target != gestureTarget) {
                    recycleNode(gestureTarget)
                    gestureTarget = null
                    clickAttempts = 0
                }
                targetClickRect.set(bestCandidateRect)
                if (target.refresh()) {
                    target.getBoundsInScreen(targetClickRect)
                    if (!isStrictSkipTarget(target, targetClickRect)) {
                        // 控件刷新后不再满足严格判定（倒计时重置 / 布局抖动 / 位置漂移）：
                        // 这只是"本帧目标暂时不合格"，绝不能直接 return 打断定时复查链。
                        // 丢弃这一帧的候选并续期，等下一帧重新扫描（真机上该类抖动极为常见）。
                        consecutiveNoAdScans++
                        scheduleScan(scanInterval())
                        return
                    }
                    clickAttempts++
                    lastSkipTime = now
                    if (performDualActionClick(target, targetClickRect)) {
                        consecutiveNoAdScans = 0
                        if (pendingGesture == null) {
                            Log.i(TAG, "已执行跳过点击，将检查广告目标是否仍在")
                        }
                    } else {
                        Log.w(TAG, "跳过点击未被系统接受")
                    }
                } else {
                    // refresh 失败此前是彻底静默的一条路：命中了候选却一次点击都没发出，
                    // 用户侧表现与"没找到"完全相同，必须留下痕迹。
                    diagCounters[C_REFRESH_FAIL]++
                    diagRecord(R_REFRESH_FAIL, currentPackage)
                }
            }
        } finally {
            releaseCandidates()
        }
        scheduleScan(if (clickAttempts > 0) RETRY_INTERVAL_MS else scanInterval())
    }

    private fun collectAdContext(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > MAX_TRAVERSAL_DEPTH || visitBudget-- <= 0 || hasAdContext || !node.isVisibleToUser) return
        node.getBoundsInScreen(tempParentRect)
        if ((hasAdFeature(node.viewIdResourceName) || hasAdFeature(node.className)) &&
            tempParentRect.width() >= windowRect.width() * 0.85f && tempParentRect.height() >= windowRect.height() * 0.70f) {
            hasAdContext = true
            return
        }
        for (i in 0 until node.childCount) {
            if (visitBudget <= 0 || hasAdContext) break
            val child = node.getChild(i) ?: continue
            try { collectAdContext(child, depth + 1) } finally { recycleNode(child) }
        }
    }

    private fun hasAdFeature(value: CharSequence?): Boolean {
        if (value.isNullOrEmpty()) return false
        for (token in AD_FEATURE_TOKENS) if (value.contains(token, true)) return true
        return false
    }

    /** 资源名形如 "包名:id/entry"；只有 entry 部分才描述控件身份。 */
    private fun entryOffset(id: String): Int {
        val qualified = id.indexOf(":id/")
        if (qualified >= 0) return qualified + 4
        val plain = id.indexOf('/')
        return if (plain >= 0) plain + 1 else 0
    }

    private fun isIdChar(ch: Char): Boolean = ch.isLetterOrDigit()

    /** 只在 entry 名内做词边界匹配，避免 "header" 命中 "head"、包名命中业务词。 */
    private fun matchesEntryToken(id: String, tokens: Array<String>): Boolean {
        val from = entryOffset(id)
        if (from >= id.length) return false
        for (word in tokens) {
            var at = id.indexOf(word, from, true)
            while (at >= 0) {
                val end = at + word.length
                if ((at == from || !isIdChar(id[at - 1])) && (end >= id.length || !isIdChar(id[end]))) return true
                at = id.indexOf(word, end, true)
            }
        }
        return false
    }

    private fun hasAdFeatureInEntry(id: String?): Boolean {
        if (id.isNullOrEmpty()) return false
        val from = entryOffset(id)
        for (token in AD_FEATURE_TOKENS) if (id.indexOf(token, from, true) >= 0) return true
        return false
    }

    private fun searchBestSkipCandidate(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > MAX_TRAVERSAL_DEPTH || visitBudget-- <= 0 || !node.isVisibleToUser) return
        node.getBoundsInScreen(tempNodeRect)
        // 中部内容区整枝剪掉，但上下边缘带都要保留（插屏的"关闭广告"常在底部）。
        if (depth > 0 && tempNodeRect.top > windowRect.top + windowRect.height() * 0.30f &&
            tempNodeRect.bottom < windowRect.top + windowRect.height() * 0.72f) return
        if (isStrictSkipTarget(node, tempNodeRect)) {
            val area = tempNodeRect.width() * tempNodeRect.height()
            if (area < bestCandidateArea) {
                recycleNode(bestCandidateNode)
                // 拷贝候选节点取得独立所有权，遍历节点统一在 finally 回收。
                bestCandidateNode = copyNode(node)
                bestCandidateArea = area
                bestCandidateRect.set(tempNodeRect)
            }
        } else {
            noteNearMiss(node, tempNodeRect)
        }
        for (i in 0 until node.childCount) {
            if (visitBudget <= 0) break
            val child = node.getChild(i) ?: continue
            try { searchBestSkipCandidate(child, depth + 1) } finally { recycleNode(child) }
        }
    }

    /**
     * 记下"写着跳过但被拒"的节点，用于在候选为 0 时看清真机树。
     * 先用长度与关键词把绝大多数普通节点挡掉，保证热路径几乎无额外开销、且零字符串分配。
     */
    private fun noteNearMiss(node: AccessibilityNodeInfo, rect: Rect) {
        val text = node.text
        val desc = node.contentDescription
        val textHit = text != null && text.length <= 24 && (text.contains("跳过", true) || text.contains("skip", true))
        val descHit = desc != null && desc.length <= 24 && (desc.contains("跳过", true) || desc.contains("skip", true))
        val id = node.viewIdResourceName
        val idHit = id != null && id.length <= 64 && id.contains("skip", true)
        if (!textHit && !descHit && !idHit) return
        diagNearId = id
        diagNearText = if (textHit) text else if (descHit) desc else null
        diagNearClass = node.className
        diagNearReject = diagReject
        diagNearRect[0] = rect.left; diagNearRect[1] = rect.top
        diagNearRect[2] = rect.right; diagNearRect[3] = rect.bottom
        diagNearWin[0] = windowRect.left; diagNearWin[1] = windowRect.top
        diagNearWin[2] = windowRect.right; diagNearWin[3] = windowRect.bottom
        diagNearState = (if (node.isVisibleToUser) 1 else 0) or (if (node.isEnabled) 2 else 0) or
                (if (node.isClickable) 4 else 0) or (if (node.isEditable) 8 else 0) or
                (if (textHit) 16 else 0) or (if (descHit) 32 else 0)
    }

    private fun isStrictSkipTarget(node: AccessibilityNodeInfo, rect: Rect): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled || node.isEditable || node.packageName?.contentEquals(currentPackage) != true ||
            node.windowId != currentWindowId || rect.width() < 3 || rect.height() < 3 || !windowRect.contains(rect)) {
            diagReject = R_STATE
            return false
        }
        if (!inSkipBand(rect)) {
            diagReject = R_ZONE
            return false
        }
        if (rect.width() > 220 * cachedDensity || rect.height() > 85 * cachedDensity) {
            diagReject = R_SIZE
            return false
        }
        val text = node.text
        val desc = node.contentDescription
        val id = node.viewIdResourceName
        val explicitAdLabel = text?.contains("跳过广告") == true || text?.contains("关闭广告") == true ||
                desc?.contains("跳过广告") == true || desc?.contains("关闭广告") == true
        val skipIdentityId = id != null && matchSkipViewId(id)
        val countdownIdentity = id?.contains("count_down", true) == true || id?.contains("countdown_view", true) == true ||
                node.className?.contains("CountDown", true) == true
        val cleanLabel = matchCleanText(text, rect) || matchCleanText(desc, rect)
        // 先要有一点跳过迹象，才付出祖先链遍历的代价（一屏普通节点必须零向上回溯）。
        if (!explicitAdLabel && !skipIdentityId && !countdownIdentity && !cleanLabel) {
            diagReject = R_NO_SIGN
            return false
        }
        if (isControlShaped(node) || isInsideList(node)) {
            diagReject = R_LIST
            return false
        }
        val business = hasBusinessFeature(node)
        val strongSkipId = skipIdentityId && id != null &&
                (hasAdFeatureInEntry(id) || id.contains("splash_skip", true) || id.contains("ad_skip", true))
        if (business) {
            diagReject = R_BUSINESS
            return false
        }
        if (strongSkipId) return true
        val inTopRight = inTopRight(rect, 0.65f, 0.2f)
        if (cleanLabel) {
            if (explicitAdLabel) return true
            // 边缘带内的短标签即开屏跳过按钮。真机三张截图各占一种：百度地图是顶部圆圈"跳过"、
            // 百度贴吧是右上"跳过 02"（裸数字，不带"秒/s"）、雷速体育是**左下**"跳过 2"，
            // 三家都拿不到任何广告容器/类名证据。inSkipBand 已把中部内容区排除在外。
            // 误触风险由这几道守卫兜住：只在新窗口 12 秒嗅探期内、必须在上下边缘带、
            // 不能是引导页/翻页容器、不能命中业务词与开关形态。
            if (!isGuideWindow()) return true
            if (inTopRight && (hasAdContext || hasAdAncestor(node))) return true
            diagReject = if (hasAdContext || hasAdAncestor(node)) R_ZONE else R_NO_AD
            return false
        }
        if (!hasAdContext && !hasAdAncestor(node)) {
            diagReject = R_NO_AD
            return false
        }
        if ((skipIdentityId || inTopRight) && countdownIdentity &&
            matchCountDownSplashFeature(rect, text ?: desc)) return true
        val circle = matchTopRightCircleSkipFeature(node, rect)
        if (!circle) diagReject = R_TEXT
        return circle
    }

    private fun hasAdAncestor(node: AccessibilityNodeInfo): Boolean {
        if (hasAdFeature(currentWindowClassName)) return true
        var parent = node.parent
        try {
            for (level in 0..8) {
                val p = parent ?: break
                p.getBoundsInScreen(tempParentRect)
                if ((hasAdFeature(p.viewIdResourceName) || hasAdFeature(p.className)) &&
                    tempParentRect.width() >= windowRect.width() * 0.85f &&
                    tempParentRect.height() >= windowRect.height() * 0.70f) return true
                val next = p.parent
                recycleNode(p)
                parent = next
            }
        } finally { recycleNode(parent) }
        return false
    }

    private fun inTopRight(rect: Rect, x: Float, y: Float): Boolean =
            rect.centerX() >= windowRect.left + windowRect.width() * x &&
            rect.centerY() <= windowRect.top + windowRect.height() * y

    /** 跳过控件只出现在开屏的上下边缘带；中部是内容区。 */
    private fun inSkipBand(rect: Rect): Boolean {
        val centerY = rect.centerY()
        return centerY <= windowRect.top + windowRect.height() * 0.30f ||
                centerY >= windowRect.top + windowRect.height() * 0.72f
    }

    private fun hasBusinessFeature(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName
        if (id != null && matchesEntryToken(id, BUSINESS_ID_TOKENS)) return true
        val desc = node.contentDescription
        if (desc.isNullOrEmpty()) return false
        for (word in BUSINESS_TEXT_TOKENS) if (desc.contains(word, true)) return true
        for (word in BUSINESS_ID_TOKENS) if (desc.contains(word, true)) return true
        return false
    }

    private fun matchesAny(value: CharSequence?, tokens: Array<String>): Boolean {
        if (value.isNullOrEmpty()) return false
        for (word in tokens) if (value.contains(word, true)) return true
        return false
    }

    /** 引导/欢迎页的"跳过"不是广告；这类 Activity 名不会被混淆，可直接判别。 */
    private fun isGuideWindow(): Boolean = matchesAny(currentWindowClassName, GUIDE_CLASS_TOKENS)

    private fun isControlShaped(node: AccessibilityNodeInfo): Boolean =
        matchesAny(node.className, CONTROL_CLASSES)

    /** 设置项/列表行里的同名文案不是浮动广告按钮。 */
    private fun isInsideList(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        try {
            for (level in 0..3) {
                val p = parent ?: break
                if (matchesAny(p.className, LIST_CONTAINER_CLASSES)) return true
                val next = p.parent
                recycleNode(p)
                parent = next
            }
        } finally {
            recycleNode(parent)
        }
        return false
    }

    // 只接受短按钮标签及倒计时装饰；不创建清洗字符串或 Matcher，也不匹配 skipping/文案中的跳过。
    private fun matchCleanText(raw: CharSequence?, rect: Rect): Boolean {
        if (raw.isNullOrEmpty() || raw.length > 32) return false
        for (word in SKIP_TEXT_BLACKLIST) if (raw.contains(word, true)) return false
        if (raw == "✕" || raw == "×" || raw == "✖") {
            return hasAdContext && inTopRight(rect, 0.78f, 0.16f) && rect.width() <= 48 * cachedDensity
        }
        for (keyword in SKIP_KEYWORDS) {
            for (start in raw.indices) {
                var pos = start
                var matched = true
                for (ch in keyword) {
                    while (pos < raw.length && isSeparator(raw[pos])) pos++
                    if (pos >= raw.length || !raw[pos].equals(ch, true)) { matched = false; break }
                    pos++
                }
                if (!matched) continue
                var valid = true
                for (i in raw.indices) {
                    if (i in start until pos) continue
                    val ch = raw[i]
                    // 装饰只允许倒计时与连接词："15 秒后跳过""跳过 (5s)" 合格，句子文案不合格。
                    if (!isSeparator(ch) && !ch.isDigit() && !isCountdownDecoration(ch)) { valid = false; break }
                }
                if (valid) return true
            }
        }
        return false
    }

    private fun isCountdownDecoration(ch: Char): Boolean =
        ch == '秒' || ch.equals('s', true) || ch == '后' || ch == '内' || ch == '自' || ch == '动' ||
                ch == '\uD83D' || ch in '\uDD07'..'\uDD0A'

    private fun isSeparator(ch: Char): Boolean = ch.isWhitespace() || ch in '\u200B'..'\u200D' ||
            ch == '\uFEFF' || ch == '[' || ch == ']' || ch == '(' || ch == ')' ||
            ch == '（' || ch == '）' || DIVIDER_CHARS.contains(ch)

    private fun matchSkipViewId(viewId: String): Boolean {
        // entry 名里带广告或开屏特征时，播放器黑名单不再生效：
        // "tt_video_splash_skip_btn" 是视频开屏的跳过按钮，不是短视频控制条。
        if (!hasAdFeatureInEntry(viewId) && !viewIdHasSplashToken(viewId) &&
            matchesEntryToken(viewId, ID_BLACKLIST)) return false
        return matchesEntryToken(viewId, SKIP_ID_PATTERNS)
    }

    private fun viewIdHasSplashToken(id: String): Boolean = id.indexOf("splash", entryOffset(id), true) >= 0

    private fun matchCountDownSplashFeature(rect: Rect, text: CharSequence?): Boolean {
        if (text.isNullOrEmpty() || text.length > 8 || !inTopRight(rect, 0.65f, 0.15f)) return false
        var digits = 0
        var value = 0
        var suffix = false
        for (ch in text) {
            if (isSeparator(ch)) continue
            if (ch in '0'..'9' && !suffix) { digits++; value = value * 10 + (ch - '0') }
            else if ((ch == '秒' || ch.equals('s', true)) && digits > 0 && !suffix) suffix = true
            else return false
        }
        return digits in 1..2 && value <= 30
    }

    private fun matchTopRightCircleSkipFeature(node: AccessibilityNodeInfo, rect: Rect): Boolean {
        if (!hasAdContext || !inTopRight(rect, 0.68f, 0.18f) || hasBusinessFeature(node) ||
            !node.text.isNullOrEmpty() || node.isEditable) return false
        val minD = (24 * cachedDensity).toInt()
        val maxD = (68 * cachedDensity).toInt()
        if (rect.width() !in minD..maxD || rect.height() !in minD..maxD ||
            rect.width().toFloat() / rect.height() !in 0.72f..1.38f) return false
        var parent = node.parent
        try {
            for (level in 0..2) {
                val p = parent ?: break
                val cls = p.className
                val id = p.viewIdResourceName
                if (cls?.contains("Toolbar", true) == true || cls?.contains("ActionBar", true) == true ||
                    id?.contains("toolbar", true) == true || id?.contains("header", true) == true ||
                    id?.contains("title", true) == true) return false
                val next = p.parent
                recycleNode(p)
                parent = next
            }
        } finally {
            recycleNode(parent)
        }
        val id = node.viewIdResourceName
        val cls = node.className
        val explicitCircle = id?.contains("countdown_view", true) == true || id?.contains("count_down", true) == true ||
                cls?.contains("CountDown", true) == true
        // 无法从矩形判断一个匿名 View 是静音按钮还是跳过圆环，证据不足时不猜测。
        return explicitCircle
    }

    private fun performDualActionClick(node: AccessibilityNodeInfo, rect: Rect): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            diagCounters[C_NODE_CLICKS]++
            diagRecord(R_CLICK_NODE, currentPackage)
            DiagStore.append("CLICK_NODE", currentPackage)
            rememberTarget(node)
            return true
        }
        var parent = node.parent
        try {
            for (level in 0..2) {
                val p = parent ?: break
                p.getBoundsInScreen(tempParentRect)
                val smallContainer = tempParentRect.contains(rect) && tempParentRect.width() <= 220 * cachedDensity &&
                        tempParentRect.height() <= 85 * cachedDensity &&
                        tempParentRect.width() <= rect.width() * 1.6f && tempParentRect.height() <= rect.height() * 1.6f
                val hasSkipIdentity = p.viewIdResourceName?.let { matchSkipViewId(it) } == true ||
                        matchCleanText(p.text, tempParentRect) || matchCleanText(p.contentDescription, tempParentRect)
                if (smallContainer && hasSkipIdentity && p.packageName?.contentEquals(currentPackage) == true && p.windowId == currentWindowId &&
                    p.isVisibleToUser && p.isEnabled && p.isClickable && !p.isEditable &&
                    p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    diagCounters[C_NODE_CLICKS]++
                    diagRecord(R_CLICK_NODE, currentPackage)
                    DiagStore.append("CLICK_NODE", currentPackage)
                    rememberTarget(node)
                    return true
                }
                if (!smallContainer) break
                val next = p.parent
                recycleNode(p)
                parent = next
            }
        } finally {
            recycleNode(parent)
        }
        val raw = if (!node.text.isNullOrEmpty()) node.text else node.contentDescription
        var ratio = 0.5f
        if (raw != null && rect.width() > 36 * cachedDensity) {
            for (keyword in TARGET_CLICK_KEYWORDS) {
                val index = raw.indexOf(keyword, ignoreCase = true)
                if (index < 0) continue
                var beforeDigit = false
                var afterDigit = false
                for (i in 0 until index) if (raw[i].isDigit()) beforeDigit = true
                for (i in index + keyword.length until raw.length) if (raw[i].isDigit()) afterDigit = true
                ratio = when {
                    afterDigit && !beforeDigit -> 0.35f
                    beforeDigit && !afterDigit -> 0.65f
                    else -> 0.5f
                }
                break
            }
        }
        // 在目标内部限制落点，不能为了边缘安全把微触移到相邻业务控件或状态栏。
        val x = (rect.left + rect.width() * ratio).coerceIn(rect.left + 1f, rect.right - 1f)
        val y = rect.centerY().toFloat().coerceIn(rect.top + 1f, rect.bottom - 1f)
        val accepted = dispatchClickGesture(x, y)
        if (accepted) rememberTarget(node)
        return accepted
    }

    private fun rememberTarget(node: AccessibilityNodeInfo) {
        recycleNode(gestureTarget)
        gestureTarget = copyNode(node)
    }

    private fun dispatchClickGesture(x: Float, y: Float): Boolean {
        if (pendingGesture != null && SystemClock.elapsedRealtime() - gestureStartedAt >= GESTURE_TIMEOUT_MS) pendingGesture = null
        if (!canInteract() || pendingGesture != null) return false
        return try {
            gesturePath.reset()
            gesturePath.moveTo(x, y)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(gesturePath, 0, 50)).build()
            pendingGesture = gesture
            gestureStartedAt = SystemClock.elapsedRealtime()
            gestureWindowId = currentWindowId
            val accepted = dispatchGesture(gesture, gestureCallback, handler)
            if (!accepted) pendingGesture = null
            if (accepted) { diagCounters[C_GESTURE_CLICKS]++; diagRecord(R_CLICK_GESTURE, currentPackage); DiagStore.append("CLICK_GESTURE", currentPackage) }
            else { diagCounters[C_GESTURE_REJECTED]++; diagRecord(R_GESTURE_REJECT, currentPackage) }
            accepted
        } catch (e: Exception) {
            pendingGesture = null
            Log.e(TAG, "手势派发失败", e)
            false
        }
    }

    private fun copyNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AccessibilityNodeInfo(node)
        else {
            @Suppress("DEPRECATION")
            AccessibilityNodeInfo.obtain(node)
        }
    }

    private fun recycleNode(node: AccessibilityNodeInfo?) {
        if (node != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun releaseCandidates() {
        recycleNode(bestCandidateNode)
        bestCandidateNode = null
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        releaseCandidates()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        releaseCandidates()
    }

    override fun onInterrupt() {
        DiagStore.append("INTERRUPT", null)
        resetSession()
        armWatchdog()
        Log.i(TAG, "无障碍服务中断，已清除旧窗口状态")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        DiagStore.append("UNBIND", null)
        resetSession()
        isConnected = false
        instance = null
        Log.i(TAG, "无障碍服务已解绑，保留系统重绑通道")
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        onServiceConnected()
    }

    override fun onDestroy() {
        DiagStore.append("DESTROY", null)
        resetSession()
        isConnected = false
        instance = null
        super.onDestroy()
        Log.i(TAG, "无障碍服务已销毁")
    }
}
