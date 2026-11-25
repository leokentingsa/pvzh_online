package com.pvzh.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class RoomSession(
    val code: String,
    val role: String,
    val token: String,
    val relayHost: String,
    val relayPort: Int,
    val relayTls: Boolean
)

class RoomApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun create(baseUrl: String): RoomSession = request(baseUrl, "/api/rooms", null)

    suspend fun join(baseUrl: String, code: String): RoomSession {
        val body = JSONObject().put("code", code).toString()
        return request(baseUrl, "/api/rooms/join", body)
    }

    private suspend fun request(baseUrl: String, path: String, json: String?): RoomSession =
        withContext(Dispatchers.IO) {
            val normalized = baseUrl.trim().trimEnd('/')
            require(normalized.startsWith("http://") || normalized.startsWith("https://")) {
                "服务器地址必须以 http:// 或 https:// 开头"
            }
            val builder = Request.Builder().url(normalized + path)
            if (json == null) builder.post(ByteArray(0).toRequestBody(null))
            else builder.post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                    throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "服务器返回 HTTP ${response.code}")
                }
                val obj = JSONObject(text)
                RoomSession(
                    code = obj.getString("code"),
                    role = obj.getString("role"),
                    token = obj.getString("token"),
                    relayHost = obj.getString("relay_host"),
                    relayPort = obj.getInt("relay_port"),
                    relayTls = obj.optBoolean("relay_tls", false)
                )
            }
        }
}
