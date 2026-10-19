package com.antigravity.skipapp

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** 把服务侧的诊断环与计数器渲染成中文，用户不需要 adb 就能看到"为什么没跳"。 */
class DiagActivity : Activity() {

    private lateinit var summary: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diag)
        DiagStore.init(applicationContext)
        summary = findViewById(R.id.tv_diag_summary)
        logView = findViewById(R.id.tv_diag_log)
        findViewById<TextView>(R.id.btn_diag_reset).setOnClickListener {
            SkipAccessibilityService.resetDiagnostics()
            DiagStore.clearStore()
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun reasonText(code: Int): String = when (code) {
        SkipAccessibilityService.R_SESSION -> "开始扫描新窗口"
        SkipAccessibilityService.R_IGNORED -> "白名单静默（桌面/系统/金融 App，刻意不跳）"
        SkipAccessibilityService.R_SLEEP -> "熄屏或锁屏，暂停扫描"
        SkipAccessibilityService.R_NO_SIGN -> "本帧没有跳过迹象（没有跳过/Skip/倒计时标签）"
        SkipAccessibilityService.R_ZONE -> "拒绝：控件不在上下边缘带"
        SkipAccessibilityService.R_SIZE -> "拒绝：控件尺寸超过 220x85dp"
        SkipAccessibilityService.R_BUSINESS -> "拒绝：命中业务图标词（搜索/头像/静音等）"
        SkipAccessibilityService.R_LIST -> "拒绝：列表行或开关形态"
        SkipAccessibilityService.R_TEXT -> "拒绝：文本不符合短标签规则"
        SkipAccessibilityService.R_NO_AD -> "拒绝：没有广告上下文证据"
        SkipAccessibilityService.R_STATE -> "拒绝：节点不可见/不可用/不属于当前窗口"
        SkipAccessibilityService.R_CLICK_NODE -> "已点击跳过控件（ACTION_CLICK）"
        SkipAccessibilityService.R_CLICK_GESTURE -> "已点击跳过控件（物理手势）"
        SkipAccessibilityService.R_GESTURE_REJECT -> "系统未接受手势"
        SkipAccessibilityService.R_REFRESH_FAIL -> "命中候选但节点刷新失败，未发出点击"
        SkipAccessibilityService.R_EXHAUSTED -> "重开预算用尽，本窗口收束"
        SkipAccessibilityService.R_TIMEOUT -> "手势回调超时"
        SkipAccessibilityService.R_WINDOW_DONE -> "本窗口判定结束"
        SkipAccessibilityService.R_WATCHDOG -> "看门狗发现新会话"
        else -> "未记录"
    }

    private fun render() {
        val connected = SkipAccessibilityService.isConnected
        val c = SkipAccessibilityService.diagCounters
        val head = StringBuilder()
        head.append(if (connected) "服务状态：正在运行\n" else "服务状态：未运行（回系统无障碍重新开启）\n")
        head.append("窗口会话 ${c[SkipAccessibilityService.C_SESSIONS]} 个 · 扫描 ${c[SkipAccessibilityService.C_SCANS]} 帧\n")
        head.append("命中候选 ${c[SkipAccessibilityService.C_CANDIDATES]} 次 · 已发出点击 ")
            .append(c[SkipAccessibilityService.C_NODE_CLICKS] + c[SkipAccessibilityService.C_GESTURE_CLICKS])
            .append(" 次（节点 ${c[SkipAccessibilityService.C_NODE_CLICKS]} / 手势 ${c[SkipAccessibilityService.C_GESTURE_CLICKS]}）\n")
        head.append("系统拒收手势 ${c[SkipAccessibilityService.C_GESTURE_REJECTED]} 次 · 刷新失败 ${c[SkipAccessibilityService.C_REFRESH_FAIL]} 次\n")
        head.append("白名单静默帧 ${c[SkipAccessibilityService.C_IGNORED]} 次\n")
        val lastPkg = SkipAccessibilityService.diagLastScanPkg
        val lastClass = SkipAccessibilityService.diagLastScanClass
        if (lastPkg != null) {
            head.append("\n最近扫描：$lastPkg\n类名：$lastClass")
            val id = SkipAccessibilityService.diagTargetId
            val text = SkipAccessibilityService.diagTargetText
            if (id != null || text != null) head.append("\n最近候选：${id ?: "-"} / ${text ?: "-"}")
        } else {
            head.append("\n还没有扫描过任何应用窗口：说明系统没把事件派发给本服务，或服务未连接。")
        }
        head.append("\n\n注：统计与记录存在内存里，进程被系统清理后会归零（服务随之停止，需重新开启无障碍）。")

        // 上一次进程留下的落盘记录：有 UNBIND/DESTROY 收尾行 = 正常结束；
        // 最后一行是 SESSION/CLICK/EX_* = 进程当时正在干活就被杀了或崩了。
        val tail = DiagStore.readTail(14)
        if (tail.isNotEmpty()) {
            head.append("\n—— 上一次进程的最后记录（最新在上）——\n")
            for (line in tail) head.append(line).append('\n')
            val closedCleanly = tail.firstOrNull { it.startsWith("UNBIND") || it.startsWith("DESTROY") || it.startsWith("INTERRUPT") }
            head.append(if (closedCleanly != null)
                "\n上次是正常收尾（系统解绑/停止服务）。"
            else
                "\n上次**没有**正常收尾：进程是在干活途中被系统强杀或崩溃的。" +
                        "这正是无障碍页显示「无法运行」、必须手动关掉再打开的原因。")
        }
        // 候选为 0 时，这一段是唯一能看出真机无障碍树里到底有什么的证据。
        val nearId = SkipAccessibilityService.diagNearId
        val nearText = SkipAccessibilityService.diagNearText
        val nearClass = SkipAccessibilityService.diagNearClass
        if (nearId != null || nearText != null) {
            val r = SkipAccessibilityService.diagNearRect
            val w = SkipAccessibilityService.diagNearWin
            val st = SkipAccessibilityService.diagNearState
            head.append("\n—— 写着「跳过」却被拒的控件（真机树实况）——\n")
            head.append("文本：").append(nearText?.toString() ?: "（无）").append('\n')
            head.append("id：").append(nearId ?: "（无）").append('\n')
            head.append("类名：").append(nearClass ?: "（无）").append('\n')
            head.append("位置：").append(r[0]).append(',').append(r[1]).append(',').append(r[2]).append(',').append(r[3]).append('\n')
            head.append("窗口：").append(w[0]).append(',').append(w[1]).append(',').append(w[2]).append(',').append(w[3]).append('\n')
            head.append("状态：")
                .append(if (st and 1 != 0) "可见 " else "不可见 ")
                .append(if (st and 2 != 0) "可用 " else "disabled ")
                .append(if (st and 4 != 0) "可点 " else "自身不可点 ")
                .append(if (st and 8 != 0) "可编辑(必被拒) " else "")
                .append(if (st and 32 != 0) "文字在描述里 " else "")
                .append('\n')
            head.append("拒绝原因：").append(reasonText(SkipAccessibilityService.diagNearReject)).append('\n')
        } else if (SkipAccessibilityService.diagCounters[SkipAccessibilityService.C_SCANS] > 0) {
            head.append("\n—— 扫过 N 帧，但整棵树里没有任何带「跳过/skip」字样的节点 ——\n" +
                    "说明这个广告的跳过按钮是 Canvas/SurfaceView/图片画出来的，不是无障碍节点，任何规则都点不到它。")
        }
        summary.text = head

        // 诊断环按写入顺序还原成"最早 → 最新"
        val slots = SkipAccessibilityService.DIAG_SLOTS
        val sb = StringBuilder()
        var previous = -1L
        var printed = 0
        var i = SkipAccessibilityService.diagCursor
        for (k in 0 until slots) {
            val code = SkipAccessibilityService.diagCode[i]
            if (code != 0) {
                val t = SkipAccessibilityService.diagTime[i]
                val delta = if (previous < 0 || t < previous) 0L else t - previous
                sb.append("+").append(delta).append("ms  ").append(reasonText(code))
                SkipAccessibilityService.diagPkg[i]?.let { sb.append("  [").append(it).append("]") }
                sb.append('\n')
                previous = t
                printed++
            }
            i = (i + 1) % slots
        }
        logView.text = if (printed == 0)
            "暂无记录。清空后去冷启动一个带开屏广告的 App，再回到这一页查看。"
        else
            "最近 $printed 条（+毫秒为距上一条的间隔）\n$sb"
    }
}
