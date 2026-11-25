package com.pvzh.online

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * Parses the JSONL stream produced by the Agent through Frida's send() channel
 * and keeps the pieces the UI needs. Nothing here touches the game.
 */
class Telemetry {

    // L3 - agent side
    var booted by mutableStateOf(false)
    var moduleBase by mutableStateOf("-")
    var moduleSize by mutableStateOf(0L)
    var rvaSummary by mutableStateOf("尚未校验")
    var hooksSummary by mutableStateOf("尚未安装")
    var fatal by mutableStateOf<String?>(null)

    // L4 - transport side
    var relayState by mutableStateOf("未连接")
    var sent by mutableIntStateOf(0)
    var accepted by mutableIntStateOf(0)
    var delivered by mutableIntStateOf(0)
    var injected by mutableIntStateOf(0)
    var outbox by mutableIntStateOf(0)
    var inbox by mutableIntStateOf(0)
    var reconnects by mutableIntStateOf(0)
    var lastDelayMs by mutableLongStateOf(-1L)
    var lastOutTy by mutableStateOf("-")
    var lastInTy by mutableStateOf("-")
    var lastEvent by mutableStateOf("")

    // L3+ - proof that the hooks are actually being called by the game
    var hitsSummary by mutableStateOf("尚未命中")
    var lastStatsAtMs by mutableLongStateOf(0L)

    fun heartbeatAgeMs(): Long =
        if (lastStatsAtMs == 0L) -1L else (System.currentTimeMillis() - lastStatsAtMs)

    val hooksInstalled: Boolean get() = hooksSummary.startsWith("全部安装")
    val rvaAllPassed: Boolean get() = rvaSummary.startsWith("6/6")

    /** The working build always showed a ~3 s receive delay. Keeping it is the health signal. */
    fun healthText(): String {
        val age = heartbeatAgeMs()
        return when {
            fatal != null -> "中断：${fatal}"
            age in 15000L..Long.MAX_VALUE -> "Agent 心跳已中断 ${age / 1000} 秒（脚本停止运行或被宿主冻结）"
            age < 0L -> "还没收到 Agent 的任何状态（脚本没有跑起来）"
            injected == 0 && delivered == 0 -> "尚未收到对方任何消息"
            injected == 0 && delivered > 0 -> "已收到 ${delivered} 条，但一条都没注入游戏（注入环节失败）"
            lastDelayMs in 1500L..6000L -> "正常：注入延迟 ${lastDelayMs} ms（与已验证的约 3 秒节奏一致）"
            lastDelayMs in 0L..1499L -> "偏快：${lastDelayMs} ms，如果对局同时报错，说明消息没真正送达"
            else -> "注入 ${injected} 条"
        }
    }

    fun reset() {
        booted = false; moduleBase = "-"; moduleSize = 0L
        rvaSummary = "尚未校验"; hooksSummary = "尚未安装"; fatal = null
        relayState = "未连接"; sent = 0; accepted = 0; delivered = 0; injected = 0
        outbox = 0; inbox = 0; reconnects = 0; lastDelayMs = -1
        lastOutTy = "-"; lastInTy = "-"; lastEvent = ""
    }

    /** Returns a human readable line when the message is worth showing in the log list. */
    fun consume(rawLine: String): String? {
        val obj = runCatching { JSONObject(rawLine) }.getOrNull() ?: return null
        when (obj.optString("type")) {
            "error" -> {
                // a runtime error inside the agent script
                val description = obj.optString("description").ifBlank { obj.optString("stack") }
                fatal = description
                return "AGENT 脚本错误：$description"
            }
            "native" -> {
                val text = obj.optJSONObject("payload")?.optString("text").orEmpty()
                if (text.startsWith("ERROR")) fatal = text
                return text
            }
            "send" -> {
                val payload = obj.optJSONObject("payload") ?: return null
                return handlePayload(payload)
            }
            else -> return null
        }
    }

    private fun handlePayload(p: JSONObject): String? {
        return when (val type = p.optString("type")) {
            "boot" -> {
                booted = true
                lastEvent = "Agent 已启动：room=${p.optString("room")} role=${p.optString("role")} " +
                    "token=${if (p.optBoolean("hasToken")) "有" else "无"}"
                lastEvent
            }
            "waiting-module" -> {
                lastEvent = "等待 libil2cpp.so 出现（已等 ${p.optLong("waitedMs")} ms）"
                lastEvent
            }
            "module" -> {
                moduleBase = p.optString("base", "-")
                moduleSize = p.optLong("size")
                lastEvent = "libil2cpp.so 已定位 base=$moduleBase size=$moduleSize " +
                    "arch=${p.optString("arch")} ptr=${p.optInt("pointerSize")} pid=${p.optInt("pid")}"
                lastEvent
            }
            "rva" -> {
                val failed = p.optInt("failed", 0)
                val table = p.optJSONArray("table")
                val total = table?.length() ?: 0
                rvaSummary = "${total - failed}/$total RVA 校验通过"
                val heads = buildString {
                    for (i in 0 until total) {
                        val row = table?.optJSONObject(i) ?: continue
                        append(row.optString("name")).append('=')
                            .append(row.optString("head").take(8)).append(' ')
                    }
                }
                if (failed > 0) {
                    val names = buildString {
                        for (i in 0 until total) {
                            val row = table?.optJSONObject(i) ?: continue
                            if (!row.optBoolean("ok")) append(row.optString("name")).append('(')
                                .append(row.optString("reason")).append(") ")
                        }
                    }
                    fatal = "RVA 不匹配：$names"
                }
                "RVA 校验 ${total - failed}/$total · 头字节 $heads"
            }
            "hooks" -> {
                val keys = listOf("authority", "serialize", "postNext", "processResponse")
                val failed = keys.filter { p.optString(it) != "ok" }
                hooksSummary = if (failed.isEmpty()) "全部安装（4 个关键 Hook）"
                else "安装失败：${failed.joinToString(",")}"
                hooksSummary
            }
            "ready" -> {
                hooksSummary = "全部安装（4 个关键 Hook）"
                lastEvent = p.optString("message", "Hook 就绪")
                lastEvent
            }
            "fatal" -> {
                fatal = p.optString("reason")
                "致命：$fatal"
            }
            "stats" -> {
                lastStatsAtMs = System.currentTimeMillis()
                val h = p.optJSONObject("hits")
                if (h != null) {
                    hitsSummary = "stringNew ${h.optInt("stringNew")} · authority ${h.optInt("authority")} · " +
                        "serialize ${h.optInt("serialize")} · postNext ${h.optInt("postNext")} · " +
                        "processResponse ${h.optInt("processResponse")}"
                }
                relayState = when (p.optString("relay")) {
                    "joined" -> "已加入房间"
                    "waiting-join" -> "已连接，等待房间确认"
                    else -> "未连接"
                }
                sent = p.optInt("sent"); accepted = p.optInt("accepted")
                delivered = p.optInt("delivered"); injected = p.optInt("injected")
                outbox = p.optInt("outbox"); inbox = p.optInt("inbox")
                reconnects = p.optInt("reconnects")
                lastDelayMs = p.optLong("lastInjectDelayMs", -1L)
                lastOutTy = p.optString("lastOutboundType", "-")
                lastInTy = p.optString("lastInboundType", "-")
                null
            }
            "outbound" -> {
                lastOutTy = p.optString("ty")
                lastEvent = "发出 ${lastOutTy} ${p.optInt("bytes")}B relay=${p.optString("relay")}"
                lastEvent
            }
            "deliver" -> {
                lastEvent = "收到 ${p.optString("ty")} seq=${p.optInt("seq")} 待注入 ${p.optInt("inbox")} 条"
                lastEvent
            }
            "injected" -> {
                lastDelayMs = p.optLong("delayMs")
                lastInTy = p.optString("ty")
                lastEvent = "注入 ${lastInTy} seq=${p.optInt("seq")} 延迟 ${lastDelayMs} ms"
                lastEvent
            }
            "relay-joined" -> {
                relayState = "已加入房间"
                "Relay 已确认房间 ${p.optString("room")} 身份 ${p.optString("role")}"
            }
            "relay-connected" -> {
                relayState = "已连接"
                "已连接 Relay ${p.optString("host")}:${p.optInt("port")}"
            }
            "relay-connecting" -> {
                relayState = "连接中"
                "正在连接 Relay ${p.optString("host")}:${p.optInt("port")}"
            }
            "relay-connect-failed" -> {
                relayState = "连接失败"
                "Relay 连接失败：${p.optString("error")}"
            }
            "relay-error" -> {
                relayState = "被拒绝"
                "Relay 错误：${p.optString("error")}"
            }
            "outbound-rejected" -> "出站消息被丢弃：${p.optString("reason")}"
            "outbound-lost" -> "未确认消息放弃：${p.optString("ty")}"
            "inject-failed" -> {
                fatal = "注入失败 seq=${p.optInt("seq")}：${p.optString("error")}"
                "注入失败：${p.optString("error")}"
            }
            "post-queue-failed" -> "本地队列完成失败：${p.optString("error")}"
            "hook-hit" -> "Hook 命中 ${p.optString("hook")}（首次）"
            "serialize-unreadable" -> "CreateSerializedMessage 返回不可读"
            "socket-error", "socket-read-failed" -> "套接字异常：${p.optString("error")}"
            else -> type.takeIf { it.isNotBlank() }?.let { "事件：$it" }
        }
    }
}
