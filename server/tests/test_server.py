import asyncio
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import server
from fastapi import HTTPException
from starlette.requests import Request


def fake_request():
    return Request({
        "type": "http", "method": "POST", "path": "/",
        "headers": [], "client": ("127.0.0.1", 12345),
        "server": ("test", 80), "scheme": "http", "query_string": b"",
    })


class FakeWriter:
    def __init__(self):
        self.output = []

    def write(self, data):
        self.output.append(data)

    async def drain(self):
        return None

    def packets(self):
        return [json.loads(item) for item in self.output]


class RoomServiceTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        async with server.rooms_lock:
            server.rooms.clear()

    async def test_create_returns_six_digit_code_and_a_role(self):
        result = await server.create_room(fake_request())
        self.assertRegex(result["code"], r"^\d{6}$")
        self.assertEqual(result["role"], "A")
        self.assertGreaterEqual(len(result["token"]), 40)
        self.assertEqual(result["relay_port"], server.PUBLIC_RELAY_PORT)
        self.assertEqual(result["relay_tls"], server.PUBLIC_RELAY_TLS)

    async def test_only_one_player_can_claim_role_b(self):
        created = await server.create_room(fake_request())
        request = server.JoinRequest(code=created["code"])
        outcomes = await asyncio.gather(
            server.join_room(request, fake_request()),
            server.join_room(request, fake_request()),
            return_exceptions=True,
        )
        successes = [item for item in outcomes if isinstance(item, dict)]
        failures = [item for item in outcomes if isinstance(item, HTTPException)]
        self.assertEqual(len(successes), 1)
        self.assertEqual(successes[0]["role"], "B")
        self.assertEqual(len(failures), 1)
        self.assertEqual(failures[0].status_code, 409)

    async def test_relay_send_is_ordered_and_retry_is_idempotent(self):
        created = await server.create_room(fake_request())
        room = server.rooms[created["code"]]
        sender = FakeWriter()
        room.peers["A"].connected = sender
        payload = json.dumps({"ty": "play", "value": 1})
        packet = {"id": "message-1", "message": payload}

        await server.handle_send(room, "A", sender, packet)
        await server.handle_send(room, "A", sender, packet)

        self.assertEqual(len(room.pending["B"]), 1)
        self.assertEqual(room.pending["B"][1].body, payload)
        accepted = [p for p in sender.packets() if p["op"] == "accepted"]
        self.assertEqual([p["seq"] for p in accepted], [1, 1])

    async def test_live_tcp_relay_delivers_and_acknowledges(self):
        created = await server.create_room(fake_request())
        joined = await server.join_room(server.JoinRequest(code=created["code"]), fake_request())
        listener = await asyncio.start_server(
            server.relay_client, "127.0.0.1", 0, limit=server.MAX_LINE + 1
        )
        port = listener.sockets[0].getsockname()[1]
        try:
            a_reader, a_writer = await asyncio.open_connection("127.0.0.1", port)
            b_reader, b_writer = await asyncio.open_connection("127.0.0.1", port)
            a_writer.write((json.dumps({
                "op": "join", "room": created["code"], "role": "A", "token": created["token"]
            }) + "\n").encode())
            b_writer.write((json.dumps({
                "op": "join", "room": joined["code"], "role": "B", "token": joined["token"]
            }) + "\n").encode())
            await a_writer.drain(); await b_writer.drain()
            self.assertEqual(json.loads(await a_reader.readline())["op"], "joined")
            self.assertEqual(json.loads(await b_reader.readline())["op"], "joined")

            body = json.dumps({"ty": "play", "card": 123})
            a_writer.write((json.dumps({"op": "send", "id": "live-1", "message": body}) + "\n").encode())
            await a_writer.drain()
            accepted = json.loads(await a_reader.readline())
            delivered = json.loads(await b_reader.readline())
            self.assertEqual(accepted["op"], "accepted")
            self.assertEqual(delivered["op"], "deliver")
            self.assertEqual(delivered["message"], body)

            b_writer.write((json.dumps({"op": "ack", "seq": delivered["seq"]}) + "\n").encode())
            await b_writer.drain()
            await asyncio.sleep(0.02)
            self.assertEqual(server.rooms[created["code"]].pending["B"], {})
            a_writer.close(); b_writer.close()
            await a_writer.wait_closed(); await b_writer.wait_closed()
        finally:
            listener.close()
            await listener.wait_closed()

    async def test_reusing_message_id_with_different_body_is_rejected(self):
        created = await server.create_room(fake_request())
        room = server.rooms[created["code"]]
        sender = FakeWriter()
        room.peers["A"].connected = sender
        await server.handle_send(room, "A", sender, {
            "id": "same-id", "message": json.dumps({"ty": "play", "n": 1})
        })
        await server.handle_send(room, "A", sender, {
            "id": "same-id", "message": json.dumps({"ty": "play", "n": 2})
        })
        self.assertEqual(len(room.pending["B"]), 1)
        self.assertTrue(any(p.get("error") == "message id reused with different payload" for p in sender.packets()))

    async def test_ack_removes_only_the_recipient_queue_entry(self):
        created = await server.create_room(fake_request())
        room = server.rooms[created["code"]]
        sender = FakeWriter()
        room.peers["A"].connected = sender
        await server.handle_send(room, "A", sender, {
            "id": "ack-test", "message": json.dumps({"ty": "play"})
        })
        self.assertIn(1, room.pending["B"])
        removed = room.pending["B"].pop(1)
        room.pending_bytes["B"] -= len(removed.body.encode("utf-8"))
        self.assertNotIn(1, room.pending["B"])
        self.assertEqual(room.pending_bytes["B"], 0)
        self.assertIn(("A", "ack-test"), room.dedup)


if __name__ == "__main__":
    unittest.main(verbosity=2)
