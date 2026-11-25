package com.pvzh.online

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.min

/**
 * Bridges the agent's loopback JSONL socket to the authenticated room Relay.
 * The game process only ever connects to 127.0.0.1; TLS is terminated here
 * with Android's trusted CA store.
 *
 * Unlike 0.4.0 every failure is reported through [onEvent] instead of being
 * swallowed, because a silent tunnel failure looked exactly like a silent
 * message-forwarding failure.
 */
object RelayTunnel : Closeable {
    const val LOCAL_HOST = "127.0.0.1"
    const val LOCAL_PORT = 18768

    @Volatile var onEvent: ((String) -> Unit)? = null
    @Volatile var lastError: String? = null
    @Volatile var agentSessions: Int = 0

    @Volatile private var listener: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var session: RoomSession? = null
    private val workers = Executors.newCachedThreadPool()
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    /*
     * Transport-level truth, independent of whatever the Agent script reports:
     * every JSONL line crossing this tunnel is counted by its op field. If the
     * game posts a play, "out:send" must move, no matter which hook fired.
     */
    private val counters = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile private var sawDeliver = false
    @Volatile private var sawSend = false
    @Volatile private var lastPingAt = 0L
    @Volatile private var sawJoin = false

    /** Seconds since the tunnel last sent its own keepalive, -1 when it never did. */
    fun keepAliveAgeSec(): Long =
        if (lastPingAt == 0L) -1L else (System.currentTimeMillis() - lastPingAt) / 1000

    fun counter(direction: String, op: String): Int =
        counters["$direction:$op"]?.get() ?: 0

    fun trafficText(): String =
        "出站 send ${counter("out", "send")} · 入站 deliver ${counter("in", "deliver")} · " +
            "ack ${counter("out", "ack")} · accepted ${counter("in", "accepted")} · " +
            "保活 ${counter("out", "ping")} 次"

    private fun countLine(direction: String, line: String) {
        val op = OP_PATTERN.find(line)?.groupValues?.get(1) ?: "?"
        counters.getOrPut("$direction:$op") { AtomicInteger() }.incrementAndGet()
        if (direction == "out" && op == "join") sawJoin = true
        if (direction == "out" && op == "send" && !sawSend) {
            sawSend = true
            report("隧道首次出站 send：本机游戏消息已进入隧道")
        }
        if (direction == "in" && op == "deliver" && !sawDeliver) {
            sawDeliver = true
            report("隧道首次入站 deliver：对方消息已到达本机")
        }
    }

    private fun pump(
        input: InputStream,
        output: OutputStream,
        direction: String,
        done: CountDownLatch,
        writeLock: Any
    ) {
        val buffer = ByteArray(8192)
        var pending = ""
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                synchronized(writeLock) {
                    output.write(buffer, 0, read)
                    output.flush()
                }
                var start = 0
                for (i in 0 until read) {
                    if (buffer[i] == '\n'.code.toByte()) {
                        countLine(direction, pending + String(buffer, start, i - start, Charsets.UTF_8))
                        pending = ""
                        start = i + 1
                    }
                }
                pending += String(buffer, start, read - start, Charsets.UTF_8)
                if (pending.length > 8192) pending = pending.takeLast(64)
            }
        } catch (_: Exception) {
        } finally {
            done.countDown()
        }
    }

    /*
     * The relay drops a connection after 90 s without traffic. The Agent can only
     * ping while the game process is actually running, and the game is frozen as
     * soon as the tool app comes to the foreground — so the old design lost the
     * relay silently every time the user looked at the tool for more than 90 s.
     * This heartbeat keeps the relay alive independently of the game.
     */
    private fun startKeepAlive(
        upstream: Socket,
        upstreamOut: OutputStream,
        writeLock: Any,
        local: Socket
    ) {
        workers.execute {
            try {
                while (running && !local.isClosed && !upstream.isClosed) {
                    Thread.sleep(PING_INTERVAL_MS)
                    if (!running || local.isClosed || upstream.isClosed) break
                    synchronized(writeLock) {
                        upstreamOut.write(PING_LINE)
                        upstreamOut.flush()
                    }
                    lastPingAt = System.currentTimeMillis()
                    counters.getOrPut("out:ping") { AtomicInteger() }.incrementAndGet()
                    if (counter("out", "ping") == 1) report("隧道保活已启动（每 20 秒 ping 一次，游戏被冻结也不会掉线）")
                }
            } catch (e: Exception) {
                report("隧道保活写入失败：${describe(e)}，关闭本地连接让 Agent 重连")
                runCatching { local.close() }
            }
        }
    }

    private val OP_PATTERN = Regex("\"op\"\\s*:\\s*\"([A-Za-z]+)\"")
    private val PING_LINE = "{\"op\":\"ping\"}\n".toByteArray(Charsets.UTF_8)
    private const val PING_INTERVAL_MS = 20_000L

    private fun report(message: String) {
        runCatching { onEvent?.invoke(message) }
    }

    private fun describe(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(4)
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
        return chain
    }

    @Synchronized
    fun start(roomSession: RoomSession) {
        val current = listener
        if (current != null && !current.isClosed && running) {
            session = roomSession
            report("本地隧道已在运行，房间 ${roomSession.code} 已更新")
            return
        }
        session = roomSession
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName(LOCAL_HOST), LOCAL_PORT), 4)
        listener = server
        running = true
        lastError = null
        report("本地隧道已监听 $LOCAL_HOST:$LOCAL_PORT")
        workers.execute {
            while (running) {
                try {
                    val local = server.accept()
                    activeSockets.add(local)
                    agentSessions++
                    report("游戏端 Agent 已连入本地隧道（第 $agentSessions 次）")
                    workers.execute { bridge(local, session ?: roomSession) }
                } catch (e: Exception) {
                    if (running) {
                        report("接受隧道连接失败：${describe(e)}")
                        Thread.sleep(500)
                    }
                }
            }
        }
    }

    private fun bridge(local: Socket, room: RoomSession) {
        var remote: Socket? = null
        try {
            local.tcpNoDelay = true
            val upstream: Socket = if (room.relayTls) {
                report("正在建立 TLS 连接到 ${room.relayHost}:${room.relayPort}")
                val tcp = Socket()
                remote = tcp
                tcp.connect(InetSocketAddress(room.relayHost, room.relayPort), 12_000)
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = factory.createSocket(tcp, room.relayHost, room.relayPort, true) as SSLSocket
                remote = ssl
                ssl.soTimeout = 0
                ssl.sslParameters = ssl.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                ssl.startHandshake()
                report("TLS 握手成功（${ssl.session.protocol} ${ssl.session.cipherSuite}）")
                ssl
            } else {
                Socket().apply {
                    remote = this
                    tcpNoDelay = true
                    connect(InetSocketAddress(room.relayHost, room.relayPort), 12_000)
                }
            }
            remote = upstream
            activeSockets.add(upstream)
            upstream.tcpNoDelay = true
            val done = CountDownLatch(2)
            val writeLock = Any()
            val upstreamOut = upstream.getOutputStream()
            workers.execute {
                try {
                    pump(local.getInputStream(), upstreamOut, "out", done, writeLock)
                } finally {
                    runCatching { upstream.shutdownOutput() }
                }
            }
            workers.execute {
                try {
                    pump(upstream.getInputStream(), local.getOutputStream(), "in", done, writeLock)
                } finally {
                    runCatching { local.shutdownOutput() }
                }
            }
            // Keepalive only makes sense once the Agent's join reached the relay.
            workers.execute {
                var waited = 0
                while (running && !local.isClosed && waited < 30_000 && !sawJoin) {
                    Thread.sleep(500)
                    waited += 500
                }
                if (sawJoin && running && !local.isClosed) {
                    startKeepAlive(upstream, upstreamOut, writeLock, local)
                }
            }
            done.await()
        } catch (e: Exception) {
            lastError = describe(e)
            report("隧道转发异常：$lastError")
            // The agent owns reconnection; closing both sockets triggers it.
        } finally {
            runCatching { remote?.close() }
            runCatching { local.close() }
            remote?.let { activeSockets.remove(it) }
            activeSockets.remove(local)
        }
    }

    @Synchronized
    override fun close() {
        running = false
        runCatching { listener?.close() }
        listener = null
        activeSockets.forEach { runCatching { it.close() } }
        activeSockets.clear()
        session = null
        agentSessions = 0
        counters.clear()
        sawDeliver = false
        sawSend = false
        sawJoin = false
        lastPingAt = 0L
    }
}
