# Remote control over MCP

The app contains a small HTTPS server that speaks the [Model Context Protocol](https://modelcontextprotocol.io)
(MCP, JSON-RPC 2.0, "Streamable HTTP" with JSON replies only). An AI assistant or any script on your network can
then press the buttons of the active profile. It is **off by default** and has to be switched on in the app each
time (*Menu → Remote control*).

```
AI assistant / MCP client ──stdio──► bridge (pc/broadcast_remote_bridge.py)
                                          │  HTTPS, POST /mcp, "Authorization: Bearer <token>"
                                          ▼
                              Android phone: Broadcast-Remote app (remote control switched on)
                                          │  Bluetooth LE advertising (the profile's packets)
                                          ▼
                              the receive-only device
```

## What the AI sees

| Tool | Parameters | Result (text) |
|---|---|---|
| `status` | none | `Profile ZASJ047: off` or `Profile ZASJ047: active: code 0x05, auto-off in 120 s`; says if Bluetooth is off (then all send commands are refused) |
| `buttons` | none | JSON list of the buttons of the active profile, in the order of the app |
| `send` | `code` (integer 0-255, or text such as `"0x11"`) | what was sent, for example `Code 0x05  ->  6d b6 43 ce 97 fe 42 7c e0 b8 2a  [mcp]`; repeated every second until another command or `off` |
| `off` | none | `OFF ...`: sends the profile's off code for a short time, then stops advertising (or just stops, for a profile with a silent off) |

Server instructions given to the client: *"Controls a BLE broadcast device through a profile. Call status first;
always finish with off."*

### How buttons are addressed

A button **is its code**: one byte, `send` takes it as a number. `buttons` returns every code in decimal and as
hex, plus the label if the profile defines one:

```json
[{"code":1,"hex":"01"},{"code":2,"hex":"02"},{"code":11,"hex":"0b","label":"Wave"}]
```

The label is optional and only there for humans and assistants; it is not an address. A profile does not know what
a function *does* (the receive-only device never says), so if you want an assistant to pick functions by meaning,
give the buttons labels (app: *Menu → Edit buttons*, or `label` in the profile JSON).

### Safety behaviour

- Without a new command the app switches the device off by itself after `autoOffSeconds` of the profile
  (default 180 s).
- If Bluetooth is off, every `send`/`off` returns an error and nothing is sent.
- Only one code is active at a time; a new `send` replaces the previous one.
- The device never answers, so neither the app nor the assistant can tell whether it reacted.

## Setting it up

1. App: *Menu → Remote control*, switch on. The screen shows the address (`https://<phone-ip>:<port>/mcp`, default
   port 8443) and the **access token**. A foreground notification is shown while the server runs.
2. The app creates a self-signed certificate and writes it to
   `/sdcard/Android/data/app.broadcastremote/files/broadcast-remote-cert.pem`. Get it to the PC, for example
   `adb pull` that file to `~/.broadcast-remote/cert.pem`, or use *Copy certificate (PEM)* and paste.
   Get it again if the phone's IP address changes (the certificate is made for the addresses of the phone).
3. Set the environment variables (the token is deliberately not part of any config file):
   - `BROADCAST_REMOTE_TOKEN`: the token shown in the app
   - `BROADCAST_REMOTE_URL`: the address shown in the app
   - `BROADCAST_REMOTE_CERT`: optional, default `~/.broadcast-remote/cert.pem`
4. Register the bridge as a stdio MCP server, see [.mcp.json.example](../.mcp.json.example). In Claude Code:
   `claude mcp add broadcast-remote -- python /path/to/pc/broadcast_remote_bridge.py` (with the variables set).

*Menu → Remote control → New token* replaces the token; update `BROADCAST_REMOTE_TOKEN` afterwards.

## What the bridge is for

Many MCP clients cannot talk to a server with a self-signed certificate, and you should not weaken TLS checking or
add the phone's certificate to the operating system's trust store just for this. The bridge
([pc/broadcast_remote_bridge.py](../pc/broadcast_remote_bridge.py), Python 3, standard library only) is a tiny
stdio MCP server: it reads JSON-RPC lines from the client, forwards each one as `POST` to the app, verifying the
connection against *only* the certificate file you give it, adds the `Authorization: Bearer` header from the
environment variable, and writes the reply back. If the phone is unreachable or the remote control is off, it
answers with a JSON-RPC error that says so. It has no other function and keeps no state.

A client that can pin a certificate itself can skip the bridge and talk to `POST /mcp` directly:

```
curl --cacert cert.pem -H "Authorization: Bearer $BROADCAST_REMOTE_TOKEN" -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"status","arguments":{}}}' \
     https://192.168.1.20:8443/mcp
```

(`curl` with the Windows *schannel* backend cannot check IP addresses in a certificate; use another client there.)

## Security

- Whoever has the token can operate the device. Handle it like a password and switch the remote control off when you
  do not need it.
- The server only accepts connections whose source address is loopback, a private LAN address (10/8, 172.16/12,
  192.168/16), link-local, Tailscale/CGNAT (100.64/10) or an IPv6 unique-local address. A connection arriving
  through a port forward carries a public address and is dropped. Do not forward the port to the internet; use a
  VPN such as Tailscale if you need to be away from home.
- Over USB, `adb forward tcp:8443 tcp:8443` and the URL `https://localhost:8443/mcp` work without Wi-Fi.
- Requests need the exact path `/mcp` and a valid token, everything else gets 401/404.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| bridge: "Phone not reachable or remote control off" | remote control not switched on, phone asleep/other network, wrong URL, certificate no longer matches the phone's IP |
| bridge: `HTTP 401 (wrong token?)` | `BROADCAST_REMOTE_TOKEN` differs from the one in the app (for example after *New token*) |
| app: server cannot start, port in use | another app (or an older version) holds the port; change the port in the remote control screen |
| `send` returns "Error: Bluetooth is off ..." | switch Bluetooth on at the phone |
| the device does nothing | wrong profile active, or wrong code; try the buttons in the app first |
