package com.pvzh.online

import android.content.Context
import org.json.JSONObject

/**
 * Thin Kotlin facade over the Frida Core JNI layer.
 *
 * Two separate entry points so the self-test can stop half way:
 *  - probe(): connect to the Gadget and enumerate processes, no script.
 *  - attachAndLoad(): attach and load the agent, then keep polling messages.
 */
object FridaBridge {

    @Volatile private var libError: String? = null
    @Volatile private var loaded = false

    private fun ensureLibrary(): String? {
        if (libError != null) return libError
        return runCatching {
            System.loadLibrary("pvzhfrida")
            null
        }.getOrElse {
            val message = "无法加载 libpvzhfrida.so：${it.message ?: it.javaClass.simpleName}"
            libError = message
            message
        }
    }

    @JvmStatic private external fun nativeProbe(): String
    @JvmStatic private external fun nativeAttachAndLoad(source: String): String
    @JvmStatic private external fun nativePollMessages(): String
    @JvmStatic private external fun nativeState(): String
    @JvmStatic private external fun nativeDetach()
    @JvmStatic private external fun nativeShutdown()

    /** L2 self-test. Never throws; returns a readable result line. */
    fun probe(): String {
        ensureLibrary()?.let { return "ERROR: $it" }
        return runCatching { nativeProbe() }.getOrElse {
            "ERROR: 探查 Gadget 时原生层抛异常：${it.message ?: it.javaClass.simpleName}"
        }
    }

    fun buildAgentSource(
        context: Context,
        session: RoomSession,
        asset: String = "agents/p2p_agent.js"
    ): String {
        val template = context.assets.open(asset)
            .bufferedReader().use { it.readText() }
        val config = JSONObject()
            .put("HOST", RelayTunnel.LOCAL_HOST)
            .put("PORT", RelayTunnel.LOCAL_PORT)
            .put("ROOM", session.code)
            .put("ROLE", session.role)
            .put("TOKEN", session.token)
            .put("LOG", true)
        val source = template.replace("__PVZH_CFG__", config.toString())
        check("__PVZH_CFG__" !in source) { "Agent 配置模板替换失败" }
        return source
    }

    /** L3/L4: attach to the Gadget and load the configured agent. */
    fun attachAndLoad(
        context: Context,
        session: RoomSession,
        asset: String = "agents/p2p_agent.js"
    ): String = loadSource(buildAgentSource(context, session, asset))

    /** Loads an arbitrary script source (used for externally supplied agents). */
    fun loadSource(source: String): String {
        ensureLibrary()?.let { return "ERROR: $it" }
        val result = runCatching { nativeAttachAndLoad(source) }.getOrElse {
            "ERROR: 加载 Agent 时原生层抛异常：${it.message ?: it.javaClass.simpleName}"
        }
        if (result.startsWith("OK:")) loaded = true
        return result
    }

    /** Drains buffered script/native messages. Each element is one JSON object. */
    fun poll(): List<String> {
        if (libError != null) return emptyList()
        val raw = runCatching { nativePollMessages() }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    fun state(): String {
        if (libError != null) return "unavailable"
        return runCatching { nativeState() }.getOrElse { "unknown" }
    }

    /** Unloads the agent but keeps the Gadget connection for a later reload. */
    fun detach() {
        if (loaded) runCatching { nativeDetach() }
        loaded = false
    }

    /** Full teardown of the native Frida state. */
    fun shutdown() {
        runCatching { nativeShutdown() }
        loaded = false
    }
}
