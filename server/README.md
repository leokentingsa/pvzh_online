# PVZH Online room API + Relay

The service exposes a FastAPI room API and a separate TCP JSONL Relay. The app uses HTTPS for the API and a TLS-wrapped TCP connection for the Relay. The Frida Agent itself only talks to `127.0.0.1:18768`; the Android companion app bridges that local socket to the public TLS Relay.

## Local development (trusted Wi-Fi LAN only)

For a first two-phone test on the same trusted Wi-Fi, bind to the PC's LAN interface and explicitly opt into plaintext development mode:

```powershell
py -3 -m pip install -r requirements.txt
$env:PVZH_ALLOW_INSECURE_LAN="true"
$env:PVZH_API_HOST="0.0.0.0"
$env:PVZH_API_PORT="8780"
$env:PVZH_RELAY_BIND_HOST="0.0.0.0"
$env:PVZH_RELAY_PORT="8768"
$env:PVZH_PUBLIC_RELAY_HOST="<你的电脑局域网 IPv4>"
$env:PVZH_PUBLIC_RELAY_PORT="8768"
$env:PVZH_PUBLIC_RELAY_TLS="false"
py -3 server.py
```

On both phones, use `http://<电脑局域网 IPv4>:8780`. Allow the two ports through Windows Firewall for **Private networks only**. This mode is intentionally insecure and must never be exposed to the public Internet. When the phones are not on the same LAN, use the TLS deployment below.

## Public deployment with TLS

Use a domain with a valid certificate whose SAN contains that domain. For a direct deployment, configure the same certificate and key for both services:

```bash
python -m pip install -r requirements.txt
set -a
. ./.env
set +a
python server.py
```

Copy `.env.example` to `.env` and replace the domain and certificate paths. Open TCP ports 8780 (HTTPS API) and 8768 (TLS Relay). The Android app's server URL is `https://your-domain:8780`.

The service refuses to bind a public interface in plaintext mode unless the explicit trusted-LAN development flag is set. Never set that flag on an Internet-facing server. For reverse-proxy deployments, bind the API and/or Relay to loopback and configure the proxy to terminate TLS. If the Relay proxy terminates TLS, set `PVZH_PUBLIC_RELAY_TLS=true` while keeping the internal Relay listener on loopback without a certificate; the app will still use TLS to the public proxy endpoint.

## API

- `GET /health`
- `POST /api/rooms` — returns a six-digit room code, role A, token and Relay endpoint.
- `POST /api/rooms/join` with `{"code":"123456"}` — claims role B.

Each room has exactly two roles. Relay connections authenticate with the per-player token. Messages are bounded, deduplicated by sender/message ID, delivered in sequence, retained until receiver ACK, and replayed after reconnect. Room state is in memory and is lost on process restart.

## Tests

```powershell
py -3 -m unittest discover -s tests -v
py -3 -m compileall -q server.py tests
```

## Production caveats

- Use one server worker: rooms are currently in-memory and not shared between workers.
- Add external rate limiting / abuse controls at the edge before public launch.
- Backups/persistence are not implemented; process restart invalidates active room codes.
- TLS protects the API and Relay in transit. Relay ACK means the relay accepted/delivered a packet, not that the remote game simulation accepted it.
