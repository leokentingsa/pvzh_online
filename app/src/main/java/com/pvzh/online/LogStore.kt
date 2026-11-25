package com.pvzh.online

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Session log sink. Keeps the most recent lines in memory for the UI and appends
 * everything to a file in the app's external files dir so it can be exported.
 */
object LogStore {
    private const val MAX_MEMORY_LINES = 400
    private val lines = ArrayDeque<String>()
    private val lock = Any()
    private var file: File? = null

    private fun stamp(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    fun init(context: Context) {
        if (file != null) return
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        val name = "pvzh-online-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log"
        file = File(dir, name)
    }

    fun add(line: String) {
        val entry = "${stamp()}  $line"
        synchronized(lock) {
            lines.addLast(entry)
            while (lines.size > MAX_MEMORY_LINES) lines.removeFirst()
            runCatching {
                file?.appendText(entry + "\n")
            }
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun currentFile(): File? = file

    /** Writes a copy containing the in-memory tail plus a short header. */
    fun export(context: Context): File {
        init(context)
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        val out = File(dir, "pvzh-online-export-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt")
        val header = buildString {
            appendLine("PVZH Online 0.5.0 日志导出")
            appendLine("时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("设备：" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                " / Android " + android.os.Build.VERSION.RELEASE + " / " + android.os.Build.SUPPORTED_ABIS.joinToString(","))
            appendLine("Frida 状态：" + FridaBridge.state())
            appendLine("隧道错误：" + (RelayTunnel.lastError ?: "无"))
            appendLine("---- 会话日志 ----")
        }
        out.writeText(header + snapshot().joinToString("\n"))
        return out
    }
}
