from __future__ import annotations

import asyncio
import json
import logging
import os
import secrets
import string
import time
import ssl
from dataclasses import dataclass, field
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field
import uvicorn

API_HOST = os.getenv("PVZH_API_HOST", "127.0.0.1")
API_PORT = int(os.getenv("PVZH_API_PORT", "8780"))
API_TLS_CERT = os.getenv("PVZH_API_TLS_CERT", "")
API_TLS_KEY = os.getenv("PVZH_API_TLS_KEY", "")
API_TLS_ENABLED = bool(API_TLS_CERT and API_TLS_KEY)
ALLOW_INSECURE_LAN = os.getenv("PVZH_ALLOW_INSECURE_LAN", "false").lower() in ("1", "true", "yes", "on")
RELAY_BIND_HOST = os.getenv("PVZH_RELAY_BIND_HOST", "127.0.0.1")
RELAY_PORT = int(os.getenv("PVZH_RELAY_PORT", "8768"))
PUBLIC_RELAY_HOST = os.getenv("PVZH_PUBLIC_RELAY_HOST", "127.0.0.1")
PUBLIC_RELAY_PORT = int(os.getenv("PVZH_PUBLIC_RELAY_PORT", str(RELAY_PORT)))
RELAY_TLS_CERT = os.getenv("PVZH_RELAY_TLS_CERT", "")
RELAY_TLS_KEY = os.getenv("PVZH_RELAY_TLS_KEY", "")
RELAY_TLS_ENABLED = bool(RELAY_TLS_CERT and RELAY_TLS_KEY)
PUBLIC_RELAY_TLS = os.getenv("PVZH_PUBLIC_RELAY_TLS", str(RELAY_TLS_ENABLED)).lower() in ("1", "true", "yes", "on")
ROOM_TTL = max(300, int(os.getenv("PVZH_ROOM_TTL_SECONDS", "7200")))
MAX_ROOMS = max(1, int(os.getenv("PVZH_MAX_ROOMS", "5000")))
MAX_LINE = 8 * 1024 * 1024
MAX_MESSAGE_BYTES = 4 * 1024 * 1024
MAX_PENDING_PER_ROLE = 256
MAX_PENDING_BYTES_PER_ROLE = 16 * 1024 * 1024
MAX_DEDUP_PER_ROOM = 4096
MAX_DEDUP_BYTES_PER_ROOM = 32 * 1024 * 1024
READ_TIMEOUT = 90

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("pvzh-online")


@dataclass
class Peer:
    token: str
    role: str
    connected: asyncio.StreamWriter | None = None


@dataclass
class Message:
    seq: int
    sender: str
    mid: str
    body: str


@dataclass
class Room:
    code: str
    created: float = field(default_factory=time.monotonic)
    last_activity: float = field(default_factory=time.monotonic)
    peers: dict[str, Peer] = field(default_factory=dict)
    pending: dict[str, dict[int, Message]] = field(default_factory=lambda: {"A": {}, "B": {}})
    next_seq: dict[str, int] = field(default_factory=lambda: {"A": 1, "B": 1})
    # sender + message id -> (sequence, exact body); retained to make retries idempotent.
    dedup: dict[tuple[str, str], tuple[int, str]] = field(default_factory=dict)
    pending_bytes: dict[str, int] = field(default_factory=lambda: {"A": 0, "B": 0})
    dedup_bytes: int = 0
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)


rooms: dict[str, Room] = {}
rooms_lock = asyncio.Lock()
app = FastAPI(title="PVZH Online Room Service", version="0.3.0")


class JoinRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    code: str = Field(min_length=6, max_length=6, pattern=r"^[0-9]{6}$")


def new_code() -> str:
    return "".join(secrets.choice(string.digits) for _ in range(6))


async def allocate_room() -> Room:
    async with rooms_lock:
        if len(rooms) >= MAX_ROOMS:
            raise HTTPException(503, "当前房间较多，请稍后再试")
        for _ in range(256):
            code = new_code()
            if code not in rooms:
                room = Room(code)
                rooms[code] = room
                return room
    raise HTTPException(503, "暂时无法分配房间码")


def session_payload(room: Room, role: str, token: str) -> dict[str, Any]:
    return {
        "code": room.code,
        "role": role,
        "token": token,
        "relay_host": PUBLIC_RELAY_HOST,
        "relay_port": PUBLIC_RELAY_PORT,
        "relay_tls": PUBLIC_RELAY_TLS,
    }


@app.post("/api/rooms")
async def create_room(request: Request):
    room = await allocate_room()
    async with room.lock:
        token = secrets.token_urlsafe(32)
        room.peers["A"] = Peer(token, "A")
        room.last_activity = time.monotonic()
    log.info("room created code=%s client=%s", room.code, request.client.host if request.client else "unknown")
    return session_payload(room, "A", token)


@app.post("/api/rooms/join")
async def join_room(req: JoinRequest, request: Request):
    async with rooms_lock:
        room = rooms.get(req.code)
    if room is None:
        raise HTTPException(404, "房间不存在或已过期")
    async with room.lock:
        if time.monotonic() - room.last_activity > ROOM_TTL:
            raise HTTPException(404, "房间已过期")
        if "A" not in room.peers:
            raise HTTPException(404, "房间不存在或已过期")
        if "B" in room.peers:
            raise HTTPException(409, "房间已满")
        token = secrets.token_urlsafe(32)
        room.peers["B"] = Peer(token, "B")
        room.last_activity = time.monotonic()
    log.info("room joined code=%s client=%s", room.code, request.client.host if request.client else "unknown")
    return session_payload(room, "B", token)


@app.get("/health")
async def health():
    async with rooms_lock:
        count = len(rooms)
    return {"ok": True, "service": "pvzh-online", "version": "0.3.0", "rooms": count}


async def send_json(writer: asyncio.StreamWriter, obj: dict[str, Any]):
    data = json.dumps(obj, separators=(",", ":"), ensure_ascii=False).encode("utf-8") + b"\n"
    writer.write(data)
    await asyncio.wait_for(writer.drain(), timeout=10)


async def read_packet(reader: asyncio.StreamReader, timeout: float) -> dict[str, Any] | None:
    """Read one bounded JSONL packet; return None on clean EOF."""
    try:
        raw = await asyncio.wait_for(reader.readuntil(b"\n"), timeout=timeout)
    except asyncio.IncompleteReadError as exc:
        if not exc.partial:
            return None
        raise ValueError("unterminated packet") from exc
    if len(raw) > MAX_LINE:
        raise ValueError("packet too large")
    packet = json.loads(raw)
    if not isinstance(packet, dict):
        raise ValueError("packet must be a JSON object")
    return packet


async def relay_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
    room: Room | None = None
    role: str | None = None
    peername = writer.get_extra_info("peername")
    try:
        hello = await read_packet(reader, 15)
        if hello is None or hello.get("op") != "join":
            await send_json(writer, {"op": "error", "error": "first packet must be join"})
            return

        code, role_value, token = hello.get("room"), hello.get("role"), hello.get("token")
        if (not isinstance(code, str) or len(code) != 6 or not code.isdigit()
                or role_value not in ("A", "B") or not isinstance(token, str)):
            await send_json(writer, {"op": "error", "error": "invalid room or role"})
            return
        async with rooms_lock:
            room = rooms.get(code)
        if room is None:
            await send_json(writer, {"op": "error", "error": "room expired"})
            return

        async with room.lock:
            expected = room.peers.get(role_value)
            if expected is None or not secrets.compare_digest(expected.token, token):
                await send_json(writer, {"op": "error", "error": "invalid room credential"})
                return
            if expected.connected is not None:
                await send_json(writer, {"op": "error", "error": "role already connected"})
                return
            expected.connected = writer
            role = role_value
            room.last_activity = time.monotonic()
            await send_json(writer, {"op": "joined", "room": code, "role": role})
            # Flush backlog while holding the room lock so newer sends cannot overtake it.
            for message in sorted(room.pending[role].values(), key=lambda item: item.seq):
                await send_json(writer, {
                    "op": "deliver", "seq": message.seq, "from": message.sender,
                    "id": message.mid, "message": message.body
                })

        log.info("relay joined room=%s role=%s peer=%s", code, role, peername)
        while True:
            packet = await read_packet(reader, READ_TIMEOUT)
            if packet is None:
                break
            op = packet.get("op")
            if op == "send":
                await handle_send(room, role, writer, packet)
            elif op == "ack":
                seq = packet.get("seq")
                if isinstance(seq, int) and not isinstance(seq, bool) and seq > 0:
                    async with room.lock:
                        removed = room.pending[role].pop(seq, None)
                        if removed is not None:
                            room.pending_bytes[role] = max(
                                0, room.pending_bytes[role] - len(removed.body.encode("utf-8"))
                            )
                        room.last_activity = time.monotonic()
            elif op == "ping":
                await send_json(writer, {"op": "pong"})
            else:
                await send_json(writer, {"op": "error", "error": "unknown operation"})
    except asyncio.CancelledError:
        raise
    except (ValueError, asyncio.LimitOverrunError, asyncio.TimeoutError, json.JSONDecodeError) as exc:
        log.info("relay client closed peer=%s reason=%s", peername, exc)
        try:
            await send_json(writer, {"op": "error", "error": str(exc)[:120]})
        except Exception:
            pass
    except Exception:
        log.exception("relay client error peer=%s", peername)
    finally:
        if room is not None and role is not None:
            async with room.lock:
                peer = room.peers.get(role)
                if peer is not None and peer.connected is writer:
                    peer.connected = None
                    room.last_activity = time.monotonic()
        writer.close()
        try:
            await writer.wait_closed()
        except Exception:
            pass


async def handle_send(room: Room, sender: str, writer: asyncio.StreamWriter, packet: dict[str, Any]):
    mid, body = packet.get("id"), packet.get("message")
    if not isinstance(mid, str) or not mid or len(mid) > 160 or not isinstance(body, str):
        await send_json(writer, {"op": "error", "error": "invalid message"})
        return
    body_size = len(body.encode("utf-8"))
    if body_size > MAX_MESSAGE_BYTES:
        await send_json(writer, {"op": "error", "error": "message too large"})
        return
    try:
        parsed = json.loads(body)
    except (TypeError, json.JSONDecodeError):
        await send_json(writer, {"op": "error", "error": "message is not JSON"})
        return
    if not isinstance(parsed, dict) or not isinstance(parsed.get("ty"), str):
        await send_json(writer, {"op": "error", "error": "message envelope missing ty"})
        return

    recipient = "B" if sender == "A" else "A"
    async with room.lock:
        peer = room.peers.get(sender)
        if peer is None or peer.connected is not writer:
            await send_json(writer, {"op": "error", "error": "connection is no longer active"})
            return
        dedup_key = (sender, mid)
        previous = room.dedup.get(dedup_key)
        if previous is not None:
            seq, previous_body = previous
            if previous_body != body:
                await send_json(writer, {"op": "error", "error": "message id reused with different payload"})
                return
        else:
            if len(room.pending[recipient]) >= MAX_PENDING_PER_ROLE or (
                room.pending_bytes[recipient] + body_size > MAX_PENDING_BYTES_PER_ROLE
            ):
                await send_json(writer, {"op": "error", "error": "recipient queue is full; wait for ACK"})
                return
            if len(room.dedup) >= MAX_DEDUP_PER_ROOM or (
                room.dedup_bytes + body_size > MAX_DEDUP_BYTES_PER_ROOM
            ):
                await send_json(writer, {"op": "error", "error": "room message limit reached"})
                return
            seq = room.next_seq[recipient]
            room.next_seq[recipient] += 1
            room.pending[recipient][seq] = Message(seq, sender, mid, body)
            room.pending_bytes[recipient] += body_size
            room.dedup[dedup_key] = (seq, body)
            room.dedup_bytes += body_size
        room.last_activity = time.monotonic()
        # Keep acceptance and delivery ordered with sequence allocation.
        await send_json(writer, {"op": "accepted", "id": mid, "seq": seq})
        target = room.peers.get(recipient)
        if target is not None and target.connected is not None and seq in room.pending[recipient]:
            await send_json(target.connected, {
                "op": "deliver", "seq": seq, "from": sender, "id": mid, "message": body
            })


async def relay_server():
    tls_context = None
    if RELAY_TLS_ENABLED:
        tls_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls_context.minimum_version = ssl.TLSVersion.TLSv1_2
        tls_context.load_cert_chain(RELAY_TLS_CERT, RELAY_TLS_KEY)
    elif RELAY_BIND_HOST not in ("127.0.0.1", "::1", "localhost") and not ALLOW_INSECURE_LAN:
        raise RuntimeError(
            "Refusing to expose plaintext Relay publicly. Configure "
            "PVZH_RELAY_TLS_CERT and PVZH_RELAY_TLS_KEY, or bind to loopback."
        )
    server = await asyncio.start_server(
        relay_client, RELAY_BIND_HOST, RELAY_PORT, limit=MAX_LINE + 1,
        ssl=tls_context, ssl_handshake_timeout=15 if tls_context else None
    )
    log.info(
        "TCP relay listening on %s tls=%s public=%s:%d",
        ", ".join(str(sock.getsockname()) for sock in server.sockets or []),
        bool(tls_context), PUBLIC_RELAY_HOST, PUBLIC_RELAY_PORT
    )
    async with server:
        await server.serve_forever()


@app.on_event("startup")
async def startup():
    if API_HOST not in ("127.0.0.1", "::1", "localhost") and not API_TLS_ENABLED and not ALLOW_INSECURE_LAN:
        raise RuntimeError("Public room API requires TLS; use PVZH_ALLOW_INSECURE_LAN only on a trusted LAN")
    if RELAY_BIND_HOST not in ("127.0.0.1", "::1", "localhost") and not RELAY_TLS_ENABLED and not ALLOW_INSECURE_LAN:
        raise RuntimeError("Public Relay requires TLS; use PVZH_ALLOW_INSECURE_LAN only on a trusted LAN")
    app.state.relay_task = asyncio.create_task(relay_server(), name="pvzh-relay")
    app.state.cleanup_task = asyncio.create_task(cleanup_rooms(), name="pvzh-room-cleanup")


@app.on_event("shutdown")
async def shutdown():
    tasks = [getattr(app.state, name, None) for name in ("relay_task", "cleanup_task")]
    for task in tasks:
        if task is not None:
            task.cancel()
    await asyncio.gather(*(task for task in tasks if task is not None), return_exceptions=True)


async def cleanup_rooms():
    while True:
        await asyncio.sleep(60)
        now = time.monotonic()
        async with rooms_lock:
            snapshot = list(rooms.items())
        expired: list[str] = []
        for code, room in snapshot:
            async with room.lock:
                if now - room.last_activity > ROOM_TTL and all(
                    peer.connected is None for peer in room.peers.values()
                ):
                    expired.append(code)
        if expired:
            async with rooms_lock:
                for code in expired:
                    room = rooms.get(code)
                    if room is not None and now - room.last_activity > ROOM_TTL:
                        rooms.pop(code, None)
                        log.info("room expired code=%s", code)


if __name__ == "__main__":
    if API_HOST not in ("127.0.0.1", "::1", "localhost") and not API_TLS_ENABLED and not ALLOW_INSECURE_LAN:
        raise RuntimeError(
            "Refusing to expose the room API publicly without TLS. Configure "
            "PVZH_API_TLS_CERT and PVZH_API_TLS_KEY, bind to loopback behind a TLS reverse proxy, "
            "or explicitly opt into trusted-LAN-only development with PVZH_ALLOW_INSECURE_LAN=true."
        )
    uvicorn.run(
        app, host=API_HOST, port=API_PORT,
        ssl_certfile=API_TLS_CERT or None,
        ssl_keyfile=API_TLS_KEY or None,
    )
