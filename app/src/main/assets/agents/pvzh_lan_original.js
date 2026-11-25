/*
 * PVZH direct LAN message agent.
 * Runtime hooks are based on the project's IL2CPP/Ghidra report.
 *
 * This agent intentionally does NOT rewrite MultiplayerUrlFactory URLs.
 * Official matchmaking and InitGame stay on the official path.
 *
 * VERBATIM COPY of the script that produced the confirmed two-phone matches
 * (pvzh_p2p_agent_A.js / _B.js, identical except CFG.ROLE). Only the CFG
 * literal is replaced by the placeholder so the tool can inject room/role.
 */
const CFG = __PVZH_CFG__;
const RVA = {
    UnityPvpAuthorityDecider_IsUsingPvpServerAuthority: 0x1C357B0,
    PvPGameConnectorImpl_CreateSerializedMessage: 0x2FD9E80,
    PvpPlayPoster_PostNextInQueue: 0x2FDBFC8,
    PvpPlayPoster_HandlePostComplete: 0x2FDC294,
    PvpMessagesExecutable_ProcessResponse: 0x2FDCD38,
    PvpMessagesExecutable_ProcessMessage: 0x2FDD43C
};
const log = (...args) => {
    if (CFG.LOG) console.log("[PVZH-P2P]", ...args);
};
const module = Process.getModuleByName("libil2cpp.so");
const base = module.base;
const at = (rva) => base.add(rva);
function readIl2CppString(p) {
    if (p.isNull()) return null;
    const length = p.add(0x10).readS32();
    if (length < 0 || length > 1024 * 1024) return null;
    return p.add(0x14).readUtf16String(length);
}
function makeUtf8ArrayBuffer(value) {
    const p = Memory.allocUtf8String(value);
    return p.readByteArray(value.length); // all wire framing is UTF-8; JSON/base64 payloads are ASCII
}
function decodeUtf8(buffer) {
    const bytes = new Uint8Array(buffer);
    let s = "";
    for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    try { return decodeURIComponent(escape(s)); } catch (_) { return s; }
}
function makeId() {
    return CFG.ROLE + "-" + Date.now().toString(36) + "-" +
        (++idCounter).toString(36) + "-" + Math.floor(Math.random() * 0xFFFFFF).toString(36);
}
let idCounter = 0;
let connection = null;
let connecting = false;
let writeChain = Promise.resolve();
let receiveBuffer = "";
const outbox = new Map();
const inbound = [];
const inboundSeqs = new Set();
let pvpMessagesInstance = ptr(0);
let posterInstance = ptr(0);
let completingPost = false;
let dispatchingPost = false;
const serializedQueue = [];
function writePacket(packet) {
    if (connection === null) return;
    const current = connection;
    const line = JSON.stringify(packet) + "\n";
    writeChain = writeChain.then(() => {
        if (connection !== current) return;
        return current.output.write(makeUtf8ArrayBuffer(line));
    }).catch((e) => {
        log("socket write failed:", e);
        try { current.close(); } catch (_) {}
        if (connection === current) connection = null;
    });
}
function flushOutbox() {
    for (const [id, message] of outbox.entries()) {
        writePacket({ op: "send", id, message });
    }
}
function handlePacket(packet) {
    if (packet.op === "joined") {
        log("joined room", packet.room, "as", packet.role);
        flushOutbox();
        return;
    }
    if (packet.op === "accepted") {
        outbox.delete(packet.id);
        return;
    }
    if (packet.op === "deliver") {
        if (!inboundSeqs.has(packet.seq)) {
            inboundSeqs.add(packet.seq);
            inbound.push({ seq: packet.seq, message: packet.message });
            log("queued remote message seq=" + packet.seq);
        } else {
            // Re-ACK duplicate delivery after reconnect; never inject twice.
            writePacket({ op: "ack", seq: packet.seq });
        }
        return;
    }
    if (packet.op === "error") log("relay error:", packet.error);
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
        log("socket read failed:", e);
    } finally {
        if (connection === conn) connection = null;
        try { conn.close(); } catch (_) {}
        scheduleReconnect();
    }
}
async function connectRelay() {
    if (connecting || connection !== null) return;
    connecting = true;
    try {
        const conn = await Socket.connect({
            family: "ipv4",
            host: CFG.HOST,
            port: CFG.PORT
        });
        connection = conn;
        receiveBuffer = "";
        await conn.output.write(makeUtf8ArrayBuffer(JSON.stringify({
            op: "join",
            room: CFG.ROOM,
            role: CFG.ROLE,
            token: CFG.TOKEN
        }) + "\n"));
        log("connected to", CFG.HOST + ":" + CFG.PORT);
        readLoop(conn);
    } catch (e) {
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
function queueOutbound(raw) {
    if (typeof raw !== "string" || !raw.length) return;
    let parsed;
    try { parsed = JSON.parse(raw); }
    catch (e) { log("CreateSerializedMessage did not return JSON:", e); return; }
    if (!parsed || typeof parsed.ty !== "string") {
        log("serialized message has no ty; refusing to forward");
        return;
    }
    const id = makeId();
    outbox.set(id, raw);
    writePacket({ op: "send", id, message: raw });
    log("local message queued type=" + parsed.ty + " playId=" + parsed.playId);
}
function dispatchSerializedQueue(self) {
    if (dispatchingPost || completingPost) return;
    dispatchingPost = true;
    posterInstance = self;
    try {
        while (serializedQueue.length > 0) {
            const raw = serializedQueue.shift();
            queueOutbound(raw);
            // Treat the local LAN queue as the successful remote transport.
            // HandlePostComplete advances the game's own PvpPlayPoster queue.
            completingPost = true;
            handlePostComplete(self, ptr(0));
            completingPost = false;
            // HandlePostComplete may re-enter PostNextInQueue; the re-entrant
            // call is intentionally ignored. Continue with the captured FIFO.
        }
    } catch (e) {
        completingPost = false;
        log("post queue completion failed:", e);
    } finally {
        dispatchingPost = false;
    }
}
function drainInbound(instance) {
    if (instance.isNull() || inbound.length === 0) return;
    while (inbound.length > 0) {
        const item = inbound.shift();
        try {
            const strPtr = il2cppStringNew(Memory.allocUtf8String(item.message));
            processMessage(instance, strPtr, ptr(0));
            writePacket({ op: "ack", seq: item.seq });
            // Keep the seq in inboundSeqs for this process lifetime so a
            // duplicated TCP delivery can never execute the same Play twice.
            log("injected remote message seq=" + item.seq);
        } catch (e) {
            // Keep it queued for the next ProcessResponse instead of ACKing it.
            inbound.unshift(item);
            log("remote message injection failed; retained seq=" + item.seq + ":", e);
            break;
        }
    }
}
const stringNewAddress = module.findExportByName("il2cpp_string_new");
if (stringNewAddress === null) throw new Error("il2cpp_string_new export not found");
const il2cppStringNew = new NativeFunction(
    stringNewAddress, "pointer", ["pointer"]
);
const handlePostComplete = new NativeFunction(
    at(RVA.PvpPlayPoster_HandlePostComplete), "void", ["pointer", "pointer"]
);
const processMessage = new NativeFunction(
    at(RVA.PvpMessagesExecutable_ProcessMessage), "void",
    ["pointer", "pointer", "pointer"]
);
// Force the existing client-authoritative simulator selection.
// This does not itself disable RemotePlayService; Send is routed through LAN
// below by replacing the PvpPlayPoster network queue entry point.
Interceptor.replace(
    at(RVA.UnityPvpAuthorityDecider_IsUsingPvpServerAuthority),
    new NativeCallback(function (_self, _method) {
        return 0;
    }, "uint8", ["pointer", "pointer"])
);
// Capture the exact JSON envelope generated by the game's own serializer.
Interceptor.attach(at(RVA.PvPGameConnectorImpl_CreateSerializedMessage), {
    onLeave(retval) {
        const raw = readIl2CppString(retval);
        if (raw !== null) serializedQueue.push(raw);
        else log("could not read CreateSerializedMessage return value");
    }
});
// Replace the official HTTP queue sender. The original request is never sent
// to the official PvP endpoint; the game's own queue is completed locally.
Interceptor.replace(
    at(RVA.PvpPlayPoster_PostNextInQueue),
    new NativeCallback(function (self, _method) {
        if (completingPost) return;
        dispatchSerializedQueue(self);
    }, "void", ["pointer", "pointer"])
);
// Capture the live PvpMessagesExecutable instance. ProcessResponse runs on the
// game's normal response-processing thread; remote messages are injected there.
Interceptor.attach(at(RVA.PvpMessagesExecutable_ProcessResponse), {
    onEnter(args) {
        this.instance = args[0];
        pvpMessagesInstance = args[0];
    },
    onLeave(_retval) {
        if (!this.instance.isNull()) drainInbound(this.instance);
    }
});
connectRelay();
log("agent installed; official matchmaking/InitGame URLs are untouched");
log("authority forced to client-side; PvP Play HTTP queue replaced by LAN transport");
