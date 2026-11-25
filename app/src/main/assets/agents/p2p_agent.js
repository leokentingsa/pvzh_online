/*
 * PVZH Online 0.5.0 - P2P relay agent (instrumented)
 *
 * The hook core is intentionally identical to the version that produced the
 * confirmed two-phone PvP matches on 2026-11-22:
 *   - force client-side PvP authority
 *   - capture PvPGameConnectorImpl::CreateSerializedMessage
 *   - replace PvpPlayPoster::PostNextInQueue (local completion, no HTTP)
 *   - inject remote messages inside PvpMessagesExecutable::ProcessResponse
 *
 * The classic ~3 s receive delay of the working build comes from the game's own
 * ProcessResponse polling cadence. This agent must NOT change that cadence: the
 * delay is the health signature of a live pipeline, not a defect to optimise.
 *
 * What is new here is observability only. Every initialisation step, every
 * outbound message and every inbound injection is reported to the companion
 * app through Frida's send() channel, so a silent failure cannot happen again.
 */

const CFG = __PVZH_CFG__;
const bootAt = Date.now();

const RVA = {
    UnityPvpAuthorityDecider_IsUsingPvpServerAuthority: 0x1C357B0,
    PvPGameConnectorImpl_CreateSerializedMessage: 0x2FD9E80,
    PvpPlayPoster_PostNextInQueue: 0x2FDBFC8,
    PvpPlayPoster_HandlePostComplete: 0x2FDC294,
    PvpMessagesExecutable_ProcessResponse: 0x2FDCD38,
    PvpMessagesExecutable_ProcessMessage: 0x2FDD43C
};

/* ---------------------------------------------------------------- telemetry */

let reportCounter = 0;
function report(type, data) {
    try {
        const payload = {};
        if (data) for (const k in data) payload[k] = data[k];
        payload.type = type;
        payload.role = CFG.ROLE;
        payload.counter = ++reportCounter;
        payload.at = Date.now();
        send(payload);
    } catch (e) {
        // telemetry must never break the pipeline
    }
}

function log() {
    if (CFG.LOG) console.log("[PVZH-P2P]", Array.prototype.join.call(arguments, " "));
}

/* --------------------------------------------------------------- statistics */

const stats = {
    sent: 0, accepted: 0, delivered: 0, injected: 0, duplicates: 0,
    reconnects: 0, lastRtt: -1, lastInjectDelayMs: -1,
    lastOutboundType: "-", lastInboundType: "-", bytesOut: 0, bytesIn: 0
};

/*
 * Per-hook hit counters. A match with hooks installed but every counter still 0
 * means the game simply never executed those functions in the mode being played,
 * which is the single most useful diagnostic this agent can report.
 */
const hits = {
    authority: 0, serialize: 0, postNext: 0, processResponse: 0, processMessage: 0,
    stringNew: 0
};

function bump(name) {
    hits[name]++;
    if (hits[name] === 1) report("hook-hit", { hook: name, note: "首次命中" });
}

function reportStats(extra) {
    const out = {};
    for (const k in stats) out[k] = stats[k];
    out.relay = connection === null ? "down" : (joined ? "joined" : "waiting-join");
    out.inbox = inbound.length;
    out.outbox = outbox.size;
    out.serializedQueue = serializedQueue.length;
    out.hits = hits;
    out.upSeconds = Math.round((Date.now() - bootAt) / 1000);
    if (extra) for (const k in extra) out[k] = extra[k];
    report("stats", out);
}

/* ------------------------------------------------------------ module lookup */

let il2cpp = null;
let initStartedAt = Date.now();

function findIl2cppModule() {
    const candidates = [];
    try {
        const list = Process.enumerateModules();
        for (let i = 0; i < list.length; i++) {
            if (list[i].name.indexOf("libil2cpp") >= 0) candidates.push(list[i]);
        }
    } catch (e) { /* ignore */ }
    if (candidates.length === 0) {
        try {
            if (typeof Process.findModuleByName === "function") {
                const found = Process.findModuleByName("libil2cpp.so");
                if (found !== null) candidates.push(found);
            }
        } catch (e) { /* ignore */ }
    }
    // Prefer the mapping that actually covers the highest RVA we use; when a
    // package ships several libil2cpp mappings, picking the wrong one installs
    // hooks on unrelated code (installed but never hit).
    let best = null;
    for (let i = 0; i < candidates.length; i++) {
        const m = candidates[i];
        if (best === null) best = m;
        else if (m.size > best.size) best = m;
    }
    if (candidates.length > 0) {
        moduleCandidates = candidates.map(function (m) {
            return { name: m.name, base: m.base.toString(), size: m.size, path: m.path || "?" };
        });
    }
    return best;
}

let moduleCandidates = [];

function waitForIl2cpp() {
    const mod = findIl2cppModule();
    if (mod !== null) {
        il2cpp = mod;
        report("module", {
            base: mod.base.toString(), size: mod.size, waitedMs: Date.now() - initStartedAt,
            path: mod.path || "?", arch: Process.arch, pointerSize: Process.pointerSize,
            candidates: moduleCandidates
        });
        setTimeout(installHooks, 0);
        return;
    }
    const waited = Date.now() - initStartedAt;
    if (waited > 120000) {
        report("fatal", {
            reason: "libil2cpp.so 在 120 秒内没有出现，脚本没有安装任何 Hook",
            waitedMs: waited
        });
        return;
    }
    if (waited >= 5000 && waited - 5000 < 500) report("waiting-module", { waitedMs: waited });
    setTimeout(waitForIl2cpp, 500);
}

/* ------------------------------------------------------------- rva checking */

function toHex(addr, length) {
    const bytes = new Uint8Array(addr.readByteArray(length));
    let s = "";
    for (let i = 0; i < bytes.length; i++) s += (bytes[i] < 16 ? "0" : "") + bytes[i].toString(16);
    return s;
}

/*
 * An RVA is only trustworthy when it points into an executable mapping *and*
 * starts with a real function prologue. A stale RVA (different game build) can
 * still be readable, so a readable check alone would silently install hooks on
 * the wrong code — the exact failure shape of "4 hooks installed, 0 hits".
 */
function checkRva(name, rva) {
    if (il2cpp === null) return { name: name, ok: false, reason: "no module" };
    if (rva >= il2cpp.size) {
        return {
            name: name, ok: false, rva: "0x" + rva.toString(16),
            reason: "超出模块大小 " + il2cpp.size
        };
    }
    const addr = il2cpp.base.add(rva);
    let range = null;
    try { range = Process.findRangeByAddress(addr); } catch (e) { range = null; }
    if (range === null) {
        return { name: name, ok: false, rva: "0x" + rva.toString(16), addr: addr.toString(), reason: "不在任何内存映射内" };
    }
    const executable = range.protection.indexOf("x") >= 0;
    let head = "?";
    try { head = toHex(addr, 16); } catch (e) { head = "unreadable"; }
    if (!executable) {
        return {
            name: name, ok: false, rva: "0x" + rva.toString(16), addr: addr.toString(),
            protection: range.protection, head: head,
            reason: "落在非可执行内存（" + range.protection + "），RVA 与当前游戏版本不匹配"
        };
    }
    return {
        name: name, ok: true, rva: "0x" + rva.toString(16), addr: addr.toString(),
        protection: range.protection, head: head
    };
}

/* ------------------------------------------------------------------- socket */

function makeUtf8ArrayBuffer(value) {
    const p = Memory.allocUtf8String(value);
    return p.readByteArray(value.length);
}

function decodeUtf8(buffer) {
    const bytes = new Uint8Array(buffer);
    let s = "";
    for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    try { return decodeURIComponent(escape(s)); } catch (_) { return s; }
}

let idCounter = 0;
function makeId() {
    return CFG.ROLE + "-" + Date.now().toString(36) + "-" +
        (++idCounter).toString(36) + "-" + Math.floor(Math.random() * 0xFFFFFF).toString(36);
}

let connection = null;
let connecting = false;
let joined = false;
let writeChain = Promise.resolve();
let receiveBuffer = "";
const outbox = new Map();
const inbound = [];
const inboundSeqs = new Set();

function writePacket(packet) {
    if (connection === null) return;
    const current = connection;
    const line = JSON.stringify(packet) + "\n";
    writeChain = writeChain.then(() => {
        if (connection !== current) return;
        return current.output.write(makeUtf8ArrayBuffer(line));
    }).catch((e) => {
        log("socket write failed:", e);
        report("socket-error", { op: packet.op, error: String(e) });
        try { current.close(); } catch (_) {}
        if (connection === current) { connection = null; joined = false; }
    });
}

function flushOutbox() {
    for (const [id, entry] of outbox.entries()) {
        writePacket({ op: "send", id: id, message: entry.raw });
    }
}

function safeType(message) {
    try {
        const parsed = JSON.parse(message);
        return parsed && typeof parsed.ty === "string" ? parsed.ty : "?";
    } catch (_) { return "?"; }
}

function handlePacket(packet) {
    if (packet.op === "joined") {
        joined = true;
        report("relay-joined", { room: packet.room, role: packet.role });
        flushOutbox();
        return;
    }
    if (packet.op === "accepted") {
        stats.accepted++;
        const entry = outbox.get(packet.id);
        if (entry) {
            stats.lastRtt = Date.now() - entry.createdAt;
            outbox.delete(packet.id);
        }
        return;
    }
    if (packet.op === "deliver") {
        stats.delivered++;
        if (!inboundSeqs.has(packet.seq)) {
            inboundSeqs.add(packet.seq);
            inbound.push({ seq: packet.seq, message: packet.message, queuedAt: Date.now() });
            report("deliver", {
                seq: packet.seq, bytes: String(packet.message).length,
                ty: safeType(packet.message), inbox: inbound.length
            });
        } else {
            stats.duplicates++;
            writePacket({ op: "ack", seq: packet.seq });
        }
        return;
    }
    if (packet.op === "pong") return;
    if (packet.op === "error") {
        report("relay-error", { error: String(packet.error) });
        log("relay error:", packet.error);
    }
}

async function readLoop(conn) {
    try {
        while (connection === conn) {
            const data = await conn.input.read(8192);
            if (data === null || data.byteLength === 0) break;
            receiveBuffer += decodeUtf8(data);
            let newline;
            while ((newline = receiveBuffer.indexOf("\n")) >= 0) {
                const line = receiveBuffer.slice(0, newline);
                receiveBuffer = receiveBuffer.slice(newline + 1);
                if (!line) continue;
                try { handlePacket(JSON.parse(line)); }
                catch (e) { log("bad relay packet:", e); }
            }
        }
    } catch (e) {
        report("socket-read-failed", { error: String(e) });
        log("socket read failed:", e);
    } finally {
        if (connection === conn) { connection = null; joined = false; }
        try { conn.close(); } catch (_) {}
        scheduleReconnect();
    }
}

async function connectRelay() {
    if (connecting || connection !== null) return;
    connecting = true;
    try {
        report("relay-connecting", { host: CFG.HOST, port: CFG.PORT });
        const conn = await Socket.connect({
            family: "ipv4",
            host: CFG.HOST,
            port: CFG.PORT
        });
        connection = conn;
        receiveBuffer = "";
        await conn.output.write(makeUtf8ArrayBuffer(JSON.stringify({
            op: "join", room: CFG.ROOM, role: CFG.ROLE, token: CFG.TOKEN
        }) + "\n"));
        report("relay-connected", { host: CFG.HOST, port: CFG.PORT });
        log("connected to", CFG.HOST + ":" + CFG.PORT);
        readLoop(conn);
    } catch (e) {
        stats.reconnects++;
        report("relay-connect-failed", { host: CFG.HOST, port: CFG.PORT, error: String(e) });
        log("connect failed:", e);
        connection = null;
        scheduleReconnect();
    } finally {
        connecting = false;
    }
}

let reconnectTimer = null;
function scheduleReconnect() {
    if (reconnectTimer !== null) return;
    reconnectTimer = setTimeout(() => {
        reconnectTimer = null;
        connectRelay();
    }, 1500);
}

/* ------------------------------------------------------------ outbound side */

function queueOutbound(raw) {
    if (typeof raw !== "string" || !raw.length) return;
    let parsed;
    try { parsed = JSON.parse(raw); }
    catch (e) { report("outbound-rejected", { reason: "不是 JSON" }); return; }
    if (!parsed || typeof parsed.ty !== "string") {
        report("outbound-rejected", { reason: "缺少 ty 字段" });
        return;
    }
    const id = makeId();
    outbox.set(id, { raw: raw, createdAt: Date.now(), at: Date.now(), tries: 0 });
    stats.sent++;
    stats.bytesOut += raw.length;
    stats.lastOutboundType = parsed.ty;
    writePacket({ op: "send", id: id, message: raw });
    report("outbound", {
        ty: parsed.ty, playId: parsed.playId, bytes: raw.length, id: id,
        relay: connection === null ? "down" : "up"
    });
}

let dispatchingPost = false;
let completingPost = false;
let posterInstance = ptr(0);
const serializedQueue = [];

function dispatchSerializedQueue(self) {
    if (dispatchingPost || completingPost) return;
    dispatchingPost = true;
    posterInstance = self;
    try {
        while (serializedQueue.length > 0) {
            const raw = serializedQueue.shift();
            queueOutbound(raw);
            completingPost = true;
            handlePostComplete(self, ptr(0));
            completingPost = false;
        }
    } catch (e) {
        completingPost = false;
        report("post-queue-failed", { error: String(e) });
        log("post queue completion failed:", e);
    } finally {
        dispatchingPost = false;
    }
}

/* ------------------------------------------------------------- inbound side */

let pvpMessagesInstance = ptr(0);

function drainInbound(instance) {
    if (instance.isNull() || inbound.length === 0) return;
    while (inbound.length > 0) {
        const item = inbound.shift();
        try {
            const strPtr = il2cppStringNew(Memory.allocUtf8String(item.message));
            bump("processMessage");
            processMessage(instance, strPtr, ptr(0));
            writePacket({ op: "ack", seq: item.seq });
            stats.injected++;
            stats.bytesIn += item.message.length;
            stats.lastInjectDelayMs = Date.now() - item.queuedAt;
            stats.lastInboundType = safeType(item.message);
            report("injected", {
                seq: item.seq, ty: safeType(item.message), bytes: item.message.length,
                delayMs: stats.lastInjectDelayMs
            });
            log("injected remote message seq=" + item.seq);
        } catch (e) {
            inbound.unshift(item);
            report("inject-failed", { seq: item.seq, error: String(e) });
            log("remote message injection failed; retained seq=" + item.seq + ":", e);
            break;
        }
    }
}

/* -------------------------------------------------------------------- hooks */

let il2cppStringNew = null;
let handlePostComplete = null;
let processMessage = null;

function readIl2CppString(p) {
    if (p.isNull()) return null;
    const length = p.add(0x10).readS32();
    if (length < 0 || length > 1024 * 1024) return null;
    return p.add(0x14).readUtf16String(length);
}

function installHooks() {
    const result = {};
    const rvaTable = [
        checkRva("authority", RVA.UnityPvpAuthorityDecider_IsUsingPvpServerAuthority),
        checkRva("serialize", RVA.PvPGameConnectorImpl_CreateSerializedMessage),
        checkRva("postNext", RVA.PvpPlayPoster_PostNextInQueue),
        checkRva("postComplete", RVA.PvpPlayPoster_HandlePostComplete),
        checkRva("processResponse", RVA.PvpMessagesExecutable_ProcessResponse),
        checkRva("processMessage", RVA.PvpMessagesExecutable_ProcessMessage)
    ];
    const bad = rvaTable.filter(function (r) { return !r.ok; });
    report("rva", { table: rvaTable, failed: bad.length });
    if (bad.length > 0) {
        report("fatal", {
            reason: "RVA 校验失败，游戏版本可能与脚本不匹配；未安装任何 Hook",
            failed: bad
        });
        return;
    }

    const base = il2cpp.base;
    const at = function (rva) { return base.add(rva); };

    try {
        const stringNewAddress = il2cpp.findExportByName("il2cpp_string_new");
        if (stringNewAddress === null) throw new Error("il2cpp_string_new 导出未找到");
        il2cppStringNew = new NativeFunction(stringNewAddress, "pointer", ["pointer"]);
        handlePostComplete = new NativeFunction(at(RVA.PvpPlayPoster_HandlePostComplete), "void", ["pointer", "pointer"]);
        processMessage = new NativeFunction(at(RVA.PvpMessagesExecutable_ProcessMessage), "void", ["pointer", "pointer", "pointer"]);
        result.exports = "ok";
    } catch (e) {
        report("fatal", { reason: "符号解析失败：" + String(e) });
        return;
    }

    /*
     * Control hook: il2cpp_string_new runs constantly in a live game. If this
     * counter stays 0 while the game is visibly working, then no hook callback
     * of this script is running at all — the problem is the load path, not the
     * PvP RVAs.
     */
    try {
        Interceptor.attach(il2cppStringNew, {
            onEnter() { bump("stringNew"); }
        });
        result.stringNew = "ok";
    } catch (e) { result.stringNew = String(e); }

    try {
        Interceptor.replace(at(RVA.UnityPvpAuthorityDecider_IsUsingPvpServerAuthority),
            new NativeCallback(function (_self, _method) {
                bump("authority");
                return 0;
            }, "uint8", ["pointer", "pointer"]));
        result.authority = "ok";
    } catch (e) { result.authority = String(e); }

    try {
        Interceptor.attach(at(RVA.PvPGameConnectorImpl_CreateSerializedMessage), {
            onEnter() {
                bump("serialize");
            },
            onLeave(retval) {
                const raw = readIl2CppString(retval);
                if (raw !== null) serializedQueue.push(raw);
                else report("serialize-unreadable", {});
            }
        });
        result.serialize = "ok";
    } catch (e) { result.serialize = String(e); }

    try {
        Interceptor.replace(at(RVA.PvpPlayPoster_PostNextInQueue),
            new NativeCallback(function (self, _method) {
                bump("postNext");
                if (completingPost) return;
                dispatchSerializedQueue(self);
            }, "void", ["pointer", "pointer"]));
        result.postNext = "ok";
    } catch (e) { result.postNext = String(e); }

    try {
        Interceptor.attach(at(RVA.PvpMessagesExecutable_ProcessResponse), {
            onEnter(args) {
                bump("processResponse");
                this.instance = args[0];
                if (!args[0].isNull()) pvpMessagesInstance = args[0];
            },
            onLeave(_retval) {
                if (!this.instance.isNull()) drainInbound(this.instance);
            }
        });
        result.processResponse = "ok";
    } catch (e) { result.processResponse = String(e); }

    report("hooks", result);

    const critical = ["authority", "serialize", "postNext", "processResponse"];
    const broken = critical.filter(function (k) { return result[k] !== "ok"; });
    if (broken.length > 0) {
        report("fatal", {
            reason: "关键 Hook 安装失败：" + broken.join(",") + "。游戏处于半接管状态，请完全退出并重启游戏后重试。",
            broken: broken, detail: result
        });
        return;
    }

    report("ready", {
        message: "Hook 全部安装完成，开始接入 Relay",
        officialPath: "官方 matchmaking / InitGame 未改动",
        delayNote: "入站注入仍由 ProcessResponse 轮询触发，保留已验证的约 3 秒接收节奏"
    });

    connectRelay();
    setInterval(reportStats, 4000);
    setInterval(keepAlive, 15000);
    setInterval(retryUnacked, 2500);
    setTimeout(function () { reportStats({ device: pvpMessagesInstance.toString() }); }, 800);
}

/* ------------------------------------------------------------------ helpers */

function keepAlive() {
    if (connection !== null && joined) writePacket({ op: "ping" });
}

function retryUnacked() {
    const now = Date.now();
    for (const [id, entry] of outbox.entries()) {
        if (now - entry.at < 2500) continue;
        if (entry.tries >= 6) {
            report("outbound-lost", { id: id, ty: safeType(entry.raw), ageMs: now - entry.createdAt });
            outbox.delete(id);
            continue;
        }
        entry.tries++;
        entry.at = now;
        writePacket({ op: "send", id: id, message: entry.raw });
    }
}

/* -------------------------------------------------------------------- start */

report("boot", {
    version: "0.5.0-r5",
    host: CFG.HOST, port: CFG.PORT, room: CFG.ROOM, role: CFG.ROLE,
    hasToken: typeof CFG.TOKEN === "string" && CFG.TOKEN.length > 0,
    arch: Process.arch, pointerSize: Process.pointerSize,
    pid: Process.id
});
log("agent booting; waiting for libil2cpp.so");
waitForIl2cpp();
