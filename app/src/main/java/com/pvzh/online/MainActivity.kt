package com.pvzh.online

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

private const val DEFAULT_SERVER = ""

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogStore.init(this)
        LogStore.add("应用启动")
        setContent { MaterialTheme { PvzhOnlineScreen() } }
    }
}

@Composable
private fun PvzhOnlineScreen() {
    val context = LocalContext.current
    val telemetry = remember { Telemetry() }
    val logs = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(DEFAULT_SERVER) }
    var roomCode by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<RoomSession?>(null) }
    var busy by remember { mutableStateOf(false) }
    var gameConnected by remember { mutableStateOf(false) }

    var networkResult by remember { mutableStateOf("尚未自检") }
    var fridaResult by remember { mutableStateOf("尚未自检") }
    var agentResult by remember { mutableStateOf("尚未加载") }
    var statusline by remember { mutableStateOf("请先填写服务器地址（https://<你的节点>.ts.net），再创建或加入房间") }

    // Optional externally supplied agent script (the original A/B scripts can be
    // loaded verbatim to compare against the built-in instrumented one).
    var externalAgentName by remember { mutableStateOf<String?>(null) }
    var externalAgentSource by remember { mutableStateOf<String?>(null) }
    var scriptMode by remember { mutableStateOf(0) }  // 0 = 内置遥测版, 1 = 原始脚本

    fun pushLog(line: String) {
        LogStore.add(line)
        logs.add(line)
        if (logs.size > 200) logs.removeAt(0)
    }

    val pickAgentScript = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            }.onSuccess { text ->
                externalAgentSource = text
                externalAgentName = uri.lastPathSegment ?: "外部脚本"
                pushLog("已载入外部 Agent 脚本：$externalAgentName（${text.length} 字符，原样加载）")
            }.onFailure { pushLog("读取外部脚本失败：${it.message}") }
        }
    }

    DisposableEffect(Unit) {
        RelayTunnel.onEvent = { message -> pushLog("[隧道] $message") }
        onDispose { RelayTunnel.onEvent = null }
    }

    // Telemetry pump: drains whatever the native layer buffered from the agent.
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            val batch = FridaBridge.poll()
            if (batch.isNotEmpty()) {
                batch.forEach { raw ->
                    telemetry.consume(raw)?.let { pushLog(it) }
                }
            }
        }
    }

    // Keeps transport counters ticking even when the agent reports nothing.
    var uiTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            uiTick++
        }
    }

    val serverAddressValid = remember(serverUrl) { isValidServerAddress(serverUrl) }

    fun runRequest(action: suspend () -> RoomSession) {
        if (busy) return
        if (!serverAddressValid) { statusline = "请填写有效的服务器地址"; return }
        busy = true
        statusline = "正在连接房间服务器……"
        scope.launch {
            try {
                val s = action()
                session = s
                statusline = "房间 ${s.code} · 你的身份 ${s.role} · 请先启动 PVZH 再做自检"
                pushLog("房间 ${s.code} 就绪，身份 ${s.role}，Relay ${s.relayHost}:${s.relayPort} TLS=${s.relayTls}")
            } catch (e: Exception) {
                statusline = "操作失败：${e.message ?: e.javaClass.simpleName}"
                pushLog("[房间] 失败：${e.message}")
            } finally {
                busy = false
            }
        }
    }

    fun runNetworkCheck() {
        if (busy) return
        busy = true
        networkResult = "检测中……"
        scope.launch {
            val current = session
            val result = withContext(Dispatchers.IO) {
                if (current != null) NetworkProbe.run(current) else NetworkProbe.checkServer(serverUrl)
            }
            networkResult = result
            pushLog("[L1] $result")
            busy = false
        }
    }

    /**
     * Non-destructive look at the Gadget port before touching Frida.
     * Returns a human readable reason when a connect attempt is pointless.
     */
    suspend fun portBlockReason(): String? = withContext(Dispatchers.IO) {
        if (FridaBridge.state() != "no-device") return@withContext null  // we already own it
        val report = GadgetPort.inspect()
        pushLog("[端口] ${report.detail}")
        if (report.blocked) report.detail else null
    }

    fun runFridaCheck() {
        if (busy) return
        busy = true
        fridaResult = "检测中……"
        scope.launch {
            val block = portBlockReason()
            if (block != null) {
                fridaResult = "ERROR: $block"
                pushLog("[L2] 预检拦下：$block")
                busy = false
                return@launch
            }
            val result = withContext(Dispatchers.IO) { FridaBridge.probe() }
            fridaResult = result
            pushLog("[L2] $result")
            busy = false
        }
    }

    suspend fun launchOnline(s: RoomSession) {
        val block = portBlockReason()
        if (block != null) {
            agentResult = "ERROR: $block"
            pushLog("[L3] 预检拦下：$block")
            statusline = "预检未通过：$block"
            return
        }
        RelayTunnel.start(s)
        val intent = Intent(context, PvzhSessionService::class.java).apply {
            putExtra(PvzhSessionService.EXTRA_ROOM, s.code)
            putExtra(PvzhSessionService.EXTRA_ROLE, s.role)
            putExtra(PvzhSessionService.EXTRA_TOKEN, s.token)
            putExtra(PvzhSessionService.EXTRA_HOST, s.relayHost)
            putExtra(PvzhSessionService.EXTRA_PORT, s.relayPort)
            putExtra(PvzhSessionService.EXTRA_TLS, s.relayTls)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)

        telemetry.reset()
        val external = externalAgentSource
        val asset = if (scriptMode == 1) "agents/pvzh_lan_original.js" else "agents/p2p_agent.js"
        val attach = withContext(Dispatchers.IO) {
            if (external != null) FridaBridge.loadSource(external)
            else FridaBridge.attachAndLoad(context, s, asset)
        }
        agentResult = attach
        pushLog("[L3] $attach")
        gameConnected = attach.startsWith("OK:")
        statusline = if (gameConnected) "Agent 已加载，等待连接状态上报" else "加载失败：$attach"
    }

    fun startOnline() {
        val s = session ?: return
        if (busy || gameConnected) return
        busy = true
        statusline = "预检端口并加载 Agent……"
        scope.launch {
            try {
                launchOnline(s)
            } catch (e: Throwable) {
                RelayTunnel.close()
                statusline = "启动失败：${e.message ?: e.javaClass.simpleName}"
                pushLog("[启动] 异常：${e.message}")
            } finally {
                busy = false
            }
        }
    }

    fun stopOnline() {
        FridaBridge.detach()
        RelayTunnel.close()
        context.stopService(Intent(context, PvzhSessionService::class.java))
        gameConnected = false
        agentResult = "已断开"
        statusline = "已断开本地 Gadget 与 Relay"
        pushLog("手动断开联机")
    }

    fun exportLogs() {
        scope.launch {
            val file = withContext(Dispatchers.IO) { LogStore.export(context) }
            pushLog("日志已导出：${file.absolutePath}")
            runCatching {
                val uri = FileProvider.getUriForFile(context, "com.pvzh.online.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "分享 PVZH 日志"))
            }.onFailure { pushLog("分享失败：${it.message}") }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("PVZH Online 0.5.0-r4", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("好友房间 · 分层自检联机工具", style = MaterialTheme.typography.titleSmall)
            Text(statusline, style = MaterialTheme.typography.bodyMedium)
            Text(
                "对局正常时：每次收到对方的牌，注入延迟应在 1.5 秒以上（约 3 秒是已验证的正常节奏）。" +
                    "若延迟变成 0 或一直收不到消息，直接看下面哪一层没通过。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("房间服务器地址") },
                placeholder = { Text("https://<你的节点>.ts.net") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = serverUrl.isNotBlank() && !serverAddressValid
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. 房间", style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = { runRequest { RoomApi().create(serverUrl) } },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && !gameConnected && serverAddressValid
                    ) { Text("创建六位房间码") }
                    OutlinedTextField(
                        value = roomCode,
                        onValueChange = { roomCode = it.filter { c -> c in '0'..'9' }.take(6) },
                        label = { Text("输入六位房间码") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = { runRequest { RoomApi().join(serverUrl, roomCode) } },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && !gameConnected && roomCode.length == 6 && serverAddressValid
                    ) { Text("加入好友房间") }
                    session?.let { s ->
                        Text("房间 ${s.code} · 身份 ${s.role} · ${s.relayHost}:${s.relayPort}", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            LayerCard(
                index = "L1 网络",
                status = networkResult,
                ok = networkResult.startsWith("OK"),
                failed = networkResult.startsWith("ERROR"),
                actionLabel = if (session == null) "自检服务器可达" else "自检 Relay 连通",
                actionEnabled = !busy && !gameConnected && serverAddressValid,
                hint = if (session == null) "没建房也可以点：只验证服务器与 TLS；建房后再点一次才是 Relay + token 全链路"
                else null,
                onAction = { runNetworkCheck() }
            )

            LayerCard(
                index = "L2 Frida",
                status = fridaResult,
                ok = fridaResult.startsWith("OK"),
                failed = fridaResult.startsWith("ERROR"),
                actionLabel = "自检 Gadget 27042",
                actionEnabled = !busy && !gameConnected,
                hint = "先做端口预检：27042 没监听、或已有连接占位，会立刻报出来，不用等 35 秒超时",
                onAction = { runFridaCheck() }
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("L3 / L4 Agent 与转发", style = MaterialTheme.typography.titleMedium)
                    if (session == null) {
                        Text(
                            "先创建或加入房间才能连游戏：Agent 启动时必须带上房间码和 token。" +
                                "L2 现在就能点，L1 也能点（只测服务器）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(agentResult, style = MaterialTheme.typography.bodySmall)
                    Text("libil2cpp base：${telemetry.moduleBase}", style = MaterialTheme.typography.bodySmall)
                    Text("RVA：${telemetry.rvaSummary}", style = MaterialTheme.typography.bodySmall)
                    Text("Hook：${telemetry.hooksSummary}", style = MaterialTheme.typography.bodySmall)
                    Text("Hook 命中：${telemetry.hitsSummary}", style = MaterialTheme.typography.bodySmall)
                    val heartbeat = telemetry.heartbeatAgeMs()
                    Text(
                        "Agent 心跳：" + if (heartbeat < 0) "还没收到（脚本没跑起来）" else "${heartbeat / 1000} 秒前",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("Relay：${telemetry.relayState}", style = MaterialTheme.typography.bodySmall)
                    if (uiTick >= 0) {
                        val keepAliveAge = RelayTunnel.keepAliveAgeSec()
                        Text("隧道流量：${RelayTunnel.trafficText()}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "隧道保活：" + when {
                                keepAliveAge < 0 -> "尚未开始（等待 Agent join）"
                                keepAliveAge > 45 -> "$keepAliveAge 秒前 —— 已在 90 秒掉线阈值附近，请保持本工具前台"
                                else -> "$keepAliveAge 秒前（<90 秒掉线阈值，安全）"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "发出 ${telemetry.sent} · 已确认 ${telemetry.accepted} · " +
                            "收到 ${telemetry.delivered} · 注入 ${telemetry.injected} · " +
                            "重连 ${telemetry.reconnects}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("我方牌型 ${telemetry.lastOutTy} / 对方牌型 ${telemetry.lastInTy}", style = MaterialTheme.typography.bodySmall)
                    Text(telemetry.healthText(), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    telemetry.fatal?.let { Text("致命：$it", color = Color(0xFFB00020)) }
                    Text("脚本：", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = scriptMode == 0,
                            onClick = { if (!busy && !gameConnected) scriptMode = 0 }
                        )
                        Text("内置遥测版", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(10.dp))
                        RadioButton(
                            selected = scriptMode == 1,
                            onClick = { if (!busy && !gameConnected) scriptMode = 1 }
                        )
                        Text("11-22 原始脚本（无遥测）", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = { startOnline() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && !gameConnected && session != null
                    ) { Text(if (busy) "处理中……" else "一键：预检 → 连接 PVZH → 启动联机") }
                    OutlinedButton(
                        onClick = {
                            pickAgentScript.launch(arrayOf("text/plain", "application/javascript", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && !gameConnected
                    ) { Text(externalAgentName?.let { "外部脚本：$it" } ?: "载入外部 Agent 脚本（可选，原样加载）") }
                    if (externalAgentSource != null) {
                        Text(
                            "将原样加载外部脚本，不会替换其中的服务器/房间配置；隧道流量计数仍然有效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { externalAgentSource = null; externalAgentName = null }) {
                            Text("改回内置带遥测脚本")
                        }
                    }
                    if (gameConnected) {
                        OutlinedButton(onClick = { stopOnline() }, modifier = Modifier.fillMaxWidth()) {
                            Text("断开联机")
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("会话日志", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { exportLogs() }) { Text("导出并分享") }
                    }
                    Text(
                        if (logs.isEmpty()) "暂无日志" else logs.takeLast(40).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        modifier = Modifier.heightIn(max = 320.dp)
                    )
                }
            }

            Text(
                "Gadget 只接受一次连接，所以顺序很重要：①从最近任务划掉 PVZH 再重新打开，等进到主界面；" +
                    "②小米/澎湃系统里把 PVZH 设成「省电策略：无限制」并锁定后台；" +
                    "③回到工具，一次把 L1→L2→L3 连点完，中途不要切回游戏（切回去游戏会被冻结，端口就不再应答）；" +
                    "④最稳的是把工具和 PVZH 分屏同屏放，两边都不进后台。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LayerCard(
    index: String,
    status: String,
    ok: Boolean,
    failed: Boolean,
    actionLabel: String,
    actionEnabled: Boolean,
    hint: String?,
    onAction: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(index, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        ok -> "通过"
                        failed -> "失败"
                        else -> "待测"
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        ok -> Color(0xFF1B5E20)
                        failed -> Color(0xFFB00020)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onAction, enabled = actionEnabled, modifier = Modifier.fillMaxWidth()) {
                Text(actionLabel)
            }
        }
    }
}

private fun isValidServerAddress(raw: String): Boolean {
    val value = raw.trim()
    if (value.isBlank()) return false
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    val host = uri.host?.lowercase() ?: return false
    if (scheme !in setOf("https", "http")) return false
    if (uri.userInfo != null || uri.query != null || uri.fragment != null) return false
    if (!uri.path.isNullOrBlank() && uri.path != "/") return false
    return uri.port == -1 || uri.port in 1..65535
}
