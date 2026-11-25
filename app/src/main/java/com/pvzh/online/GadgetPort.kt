package com.pvzh.online

import java.io.File

/**
 * Read-only inspection of the local TCP table for the Gadget port (27042 = 0x69A2).
 *
 * This never opens a socket, so it cannot consume Frida Gadget's single-slot accept
 * queue — which is exactly the bug that made a plain "connect and close" preflight
 * permanently break the gadget.
 *
 * What the numbers mean:
 *  - hasListen = false  -> nothing is listening: the game is not running, or this APK
 *                          is not the gadget-repackaged one.
 *  - hasListen + established >= 1 -> a client socket is already sitting on 27042.
 *                          Gadget accepts once, so any further connect() hangs and
 *                          finally reports "connection timed out".
 */
object GadgetPort {

    const val PORT = 27042
    private const val PORT_HEX = "69A2"

    data class Report(
        val readable: Boolean,
        val hasListen: Boolean,
        val established: Int,
        val detail: String
    ) {
        /** True when it is pointless to attempt a Frida connection right now. */
        val blocked: Boolean get() = readable && (!hasListen || established > 0)
    }

    fun inspect(): Report {
        var listen = 0
        var established = 0
        var readable = false

        for (path in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            val lines = runCatching { File(path).readLines() }.getOrNull() ?: continue
            readable = true
            for (line in lines.drop(1)) {
                val parts = line.trim().split(WHITESPACE)
                if (parts.size < 4) continue
                if (!parts[1].endsWith(":$PORT_HEX")) continue
                when (parts[3].uppercase()) {
                    "0A" -> listen++
                    "01" -> established++
                }
            }
        }

        if (!readable) {
            return Report(false, false, 0, "读不到 /proc/net，跳过端口预检（不影响后续连接）")
        }

        val detail = when {
            listen == 0 ->
                "27042 上没有监听：PVZH 没启动，或这份 APK 不是 gadget 重打包版"
            established > 0 ->
                "27042 已有 $established 个连接占位（Gadget 只接受一次），新的连接只会超时"
            else ->
                "27042 正在监听，且没有别人占用，可以连接"
        }
        return Report(true, listen > 0, established, detail)
    }

    private val WHITESPACE = Regex("\\s+")
}
