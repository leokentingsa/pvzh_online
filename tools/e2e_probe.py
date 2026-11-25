#!/usr/bin/env python3
"""End-to-end probe for the PVZH Online room API + TLS relay.

Runs entirely on the PC, without any phone: it creates a room through the
public API, joins it twice, opens both TLS sockets to the relay, exchanges
PvP-shaped messages in both directions, ACKs them and checks that a third
player is refused.

Usage:
    python tools/e2e_probe.py                       # uses the deployed default
    python tools/e2e_probe.py --base https://host   # any other deployment

Exit code 0 means the whole server side is healthy, so any remaining failure
during a phone test is on the phone side only.
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import ssl
import sys
import time
import urllib.error
import urllib.request


def post(base: str, path: str, payload: dict | None = None) -> dict:
    body = json.dumps(payload).encode() if payload is not None else b""
    req = urllib.request.Request(
        base + path, data=body,
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode())


def connect(room: dict, role: str, token: str) -> socket.socket:
    raw = socket.create_connection((room["relay_host"], room["relay_port"]), timeout=20)
    if room.get("relay_tls", True):
        ctx = ssl.create_default_context()
        raw = ctx.wrap_socket(raw, server_hostname=room["relay_host"])
    raw.sendall(json.dumps({"op": "join", "room": room["code"], "role": role,
                            "token": token}).encode() + b"\n")
    return raw


def read_packets(sock: socket.socket, wait: float = 2.5) -> list[dict]:
    sock.settimeout(wait)
    buf, out, deadline = b"", [], time.time() + wait
    while time.time() < deadline:
        try:
            chunk = sock.recv(4096)
        except socket.timeout:
            break
        if not chunk:
            break
        buf += chunk
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            if line.strip():
                out.append(json.loads(line))
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default=os.environ.get("PVZH_BASE", ""),
                        help="房间 API 地址，例如 https://your-node.ts.net")
    args = parser.parse_args()
    if not args.base:
        parser.error("请用 --base https://your-node.ts.net 或设置环境变量 PVZH_BASE")
    base = args.base.rstrip("/")
    failures: list[str] = []

    def check(ok: bool, label: str, detail: str = "") -> None:
        print(("PASS  " if ok else "FAIL  ") + label + ("" if not detail else "  " + detail))
        if not ok:
            failures.append(label + " " + detail)

    try:
        with urllib.request.urlopen(base + "/health", timeout=20) as r:
            health = json.loads(r.read().decode())
        check(health.get("ok") is True, "健康检查", json.dumps(health, ensure_ascii=False))
    except Exception as exc:  # noqa: BLE001
        check(False, "健康检查", repr(exc))
        print("\n服务端不可用，先运行 Start-PVZH-Online.ps1")
        return 1

    a = post(base, "/api/rooms")
    check(len(a.get("code", "")) == 6 and a.get("role") == "A", "创建房间", a.get("code", ""))
    b = post(base, "/api/rooms/join", {"code": a["code"]})
    check(b.get("role") == "B", "加入房间", b.get("role", ""))

    sa = connect(a, "A", a["token"])
    sb = connect(b, "B", b["token"])
    check("TLS" if sa.cipher() else "TCP", "A 建立通道", sa.cipher()[0] if sa.cipher() else "")
    joined_a = read_packets(sa)
    joined_b = read_packets(sb)
    check(any(p.get("op") == "joined" for p in joined_a), "A 加入 Relay")
    check(any(p.get("op") == "joined" for p in joined_b), "B 加入 Relay")

    started = time.time()
    payload = json.dumps({"ty": "PvPPlay", "playId": "probe-a", "pad": "x" * 256})
    sa.sendall(json.dumps({"op": "send", "id": "a-1", "message": payload}).encode() + b"\n")
    got_a = read_packets(sa)
    got_b = read_packets(sb)
    check(any(p.get("op") == "accepted" for p in got_a), "A 收到 accepted")
    delivered = [p for p in got_b if p.get("op") == "deliver"]
    check(bool(delivered), "B 收到 deliver", f"{(time.time() - started) * 1000:.0f} ms")
    if delivered:
        sb.sendall(json.dumps({"op": "ack", "seq": delivered[0]["seq"]}).encode() + b"\n")

    payload2 = json.dumps({"ty": "PvPPlay", "playId": "probe-b"})
    sb.sendall(json.dumps({"op": "send", "id": "b-1", "message": payload2}).encode() + b"\n")
    got_b2 = read_packets(sb)
    got_a2 = read_packets(sa)
    check(any(p.get("op") == "accepted" for p in got_b2), "B 收到 accepted")
    check(any(p.get("op") == "deliver" for p in got_a2), "A 收到 deliver")

    try:
        post(base, "/api/rooms/join", {"code": a["code"]})
        check(False, "第三人被拒绝", "却成功了")
    except urllib.error.HTTPError as exc:
        check(exc.code == 409, "第三人被拒绝", f"HTTP {exc.code}")

    try:
        post(base, "/api/rooms", None)
        bad_token = connect(a, "A", "not-a-real-token")
        reply = read_packets(bad_token, 2.0)
        check(any(p.get("op") == "error" for p in reply), "错误 token 被拒绝",
              json.dumps(reply, ensure_ascii=False)[:80])
        bad_token.close()
    except Exception as exc:  # noqa: BLE001
        check(False, "错误 token 被拒绝", repr(exc))

    sa.close()
    sb.close()

    print()
    if failures:
        print(f"{len(failures)} 项未通过")
        return 1
    print("服务端链路全部通过：手机端如有问题，问题一定在手机侧")
    return 0


if __name__ == "__main__":
    sys.exit(main())
