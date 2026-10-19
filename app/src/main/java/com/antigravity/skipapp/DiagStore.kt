package com.antigravity.skipapp

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.File

/**
 * 跨进程死亡的诊断落盘（应用私有目录，不需要任何权限）。
 *
 * 存在的理由：内存里的诊断环会随进程一起消失，而"过一夜就不能用"最关键的证据
 * 恰恰是**上一次进程死之前最后发生了什么**。写盘后，下次打开诊断页就能看到：
 * 上次是正常解绑（有 UNBIND/DESTROY 收尾行），还是被强杀/崩溃（没有收尾行）。
 * 全部写入都在独立线程，绝不占用无障碍主线程。
 */
object DiagStore {

    private const val FILE_NAME = "skip_diag.log"
    private const val MAX_LINES = 240
    private const val MAX_LINE_CHARS = 160

    private var io: Handler? = null
    private var file: File? = null
    private var bootAt = 0L

    fun init(context: Context) {
        if (io != null) return
        try {
            bootAt = SystemClock.elapsedRealtime()
            file = File(context.filesDir, FILE_NAME)
            val thread = HandlerThread("skipapp-diag")
            thread.start()
            io = Handler(thread.looper)
        } catch (_: Throwable) {
            // 存储不可用（只读、加密盘未解锁、无空间）时静默降级为"只有内存诊断"。
            io = null
        }
    }

    /** 只记录低频关键节点；热路径（每帧扫描）绝不调用这里。 */
    fun append(event: String, detail: String? = null) {
        val h = io ?: return
        val line = "$event|$detail|${SystemClock.elapsedRealtime() - bootAt}"
        h.post { writeLine(line) }
    }

    private fun writeLine(line: String) {
        try {
            val f = file ?: return
            val capped = if (line.length > MAX_LINE_CHARS) line.substring(0, MAX_LINE_CHARS) else line
            val previous = ArrayList<String>()
            if (f.exists()) previous.addAll(f.readLines())
            previous.add(capped)
            f.writeText(trimToLimit(previous).joinToString("\n"))
        } catch (_: Throwable) {
        }
    }

    fun trimToLimit(lines: List<String>): List<String> =
        if (lines.size <= MAX_LINES) lines else lines.subList(lines.size - MAX_LINES, lines.size)

    /** 上一次进程留下的记录尾部（倒序：最新在前）。 */
    fun readTail(limit: Int): List<String> {
        return try {
            val f = file ?: return emptyList()
            if (!f.exists()) return emptyList()
            val all = f.readLines().filter { it.isNotBlank() }
            all.takeLast(limit.coerceAtLeast(1)).reversed()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun clearStore() {
        val h = io ?: return
        h.post { try { file?.delete() } catch (_: Throwable) { } }
    }
}
