package com.pvzh.online

import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * L1 self-test: does this phone reach the public Relay with a valid TLS chain
 * and a valid room token, without involving PVZH at all?
 */
object NetworkProbe {

    /** L1a: 只测服务器可达与 TLS，不需要房间、不需要游戏。 */
    fun checkServer(baseUrl: String): String {
        val started = System.currentTimeMillis()
        var connection: java.net.HttpURLConnection? = null
        return try {
            val url = java.net.URL(baseUrl.trim().trimEnd('/') + "/health")
            connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
            }
            val code = connection.responseCode
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val ms = System.currentTimeMillis() - started
            val obj = runCatching { JSONObject(body) }.getOrNull()
            if (code == 200 && obj?.optBoolean("ok") == true) {
                "OK: HTTP 200 · ${obj.optString("service")} · 在线房间 ${obj.optInt("rooms")} · ${ms}ms（未建房，仅服务器可达）"
            } else {
                "ERROR: HTTP $code · ${body.take(120)}"
            }
        } catch (e: Exception) {
            "ERROR: ${errorChain(e)}"
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun errorChain(e: Throwable): String =
        generateSequence(e) { it.cause }.take(4)
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }

    fun run(session: RoomSession): String {
        val started = System.currentTimeMillis()
        var socket: Socket? = null
        try {
            val plain = Socket()
            socket = plain
            plain.connect(InetSocketAddress(session.relayHost, session.relayPort), 10_000)
            val tcp = if (session.relayTls) {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = factory.createSocket(plain, session.relayHost, session.relayPort, true) as SSLSocket
                socket = ssl
                ssl.soTimeout = 8_000
                ssl.sslParameters = ssl.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                ssl.startHandshake()
                ssl
            } else plain

            val out = tcp.getOutputStream()
            out.write(
                (JSONObject()
                    .put("op", "join")
                    .put("room", session.code)
                    .put("role", session.role)
                    .put("token", session.token)
                    .toString() + "\n").toByteArray(Charsets.UTF_8)
            )
            out.flush()

            val reader = tcp.getInputStream().bufferedReader(Charsets.UTF_8)
            val line = reader.readLine()
            val ms = System.currentTimeMillis() - started
            val protocol = (tcp as? SSLSocket)?.session?.protocol ?: "明文"
            val cipher = (tcp as? SSLSocket)?.session?.cipherSuite ?: "-"
            if (line == null) return "ERROR: Relay 没有回包（${ms}ms）"

            val packet = runCatching { JSONObject(line) }.getOrNull()
                ?: return "ERROR: Relay 回包不是 JSON：${line.take(120)}"
            return when (packet.optString("op")) {
                "joined" -> "OK: $protocol $cipher · 房间 ${packet.optString("room")} 身份 ${packet.optString("role")} · ${ms}ms"
                "error" -> "ERROR: Relay 拒绝：${packet.optString("error")}（${ms}ms）"
                else -> "ERROR: 意外的回包：${line.take(120)}"
            }
        } catch (e: Exception) {
            return "ERROR: ${errorChain(e)}"
        } finally {
            runCatching { socket?.close() }
        }
    }
}
