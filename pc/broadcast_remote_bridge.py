"""stdio-MCP-Bruecke zur Broadcast-Remote-App auf dem Handy.

Claude Code startet dieses Skript als lokalen MCP-Server (stdio). Jede Anfrage wird per HTTPS an die App
weitergereicht. Das Handy-Zertifikat wird hier selbst geprueft (nur diese eine Datei wird als Vertrauensanker
benutzt, nichts im Windows-Zertifikatsspeicher), das Token kommt aus der Umgebungsvariable BROADCAST_REMOTE_TOKEN
und steht in keiner Datei.

Umgebung: BROADCAST_REMOTE_TOKEN (Pflicht), BROADCAST_REMOTE_URL (Pflicht, z. B. https://192.168.1.20:8443/mcp),
BROADCAST_REMOTE_CERT (Standard ~/.broadcast-remote/cert.pem).
"""
import json
import os
import ssl
import sys
import urllib.error
import urllib.request

URL = os.environ.get("BROADCAST_REMOTE_URL", "")
CERT = os.environ.get("BROADCAST_REMOTE_CERT", os.path.join(os.path.expanduser("~"), ".broadcast-remote", "cert.pem"))
TOKEN = os.environ.get("BROADCAST_REMOTE_TOKEN", "")


def log(text):
    print("broadcast-remote-bridge: " + text, file=sys.stderr, flush=True)


def forward(raw, context):
    request = urllib.request.Request(
        URL, raw.encode("utf-8"),
        {"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json",
         "Accept": "application/json"})
    with urllib.request.urlopen(request, context=context, timeout=10) as response:
        return response.read().decode("utf-8")


def error_reply(message_id, text):
    return json.dumps({"jsonrpc": "2.0", "id": message_id, "error": {"code": -32000, "message": text}})


def main():
    sys.stdin.reconfigure(encoding="utf-8-sig")      # toleriert ein Byte-Order-Mark am Anfang
    sys.stdout.reconfigure(encoding="utf-8")
    if not TOKEN:
        log("BROADCAST_REMOTE_TOKEN ist nicht gesetzt")
    if not URL:
        log("BROADCAST_REMOTE_URL ist nicht gesetzt")
    context = ssl.create_default_context(cafile=CERT)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except ValueError:
            continue
        message_id = message.get("id") if isinstance(message, dict) else None
        try:
            body = forward(line, context)
        except urllib.error.HTTPError as e:
            reply = "HTTP %d%s" % (e.code, " (Token falsch?)" if e.code == 401 else "")
            body = error_reply(message_id, reply) if message_id is not None else ""
        except Exception as e:                      # Handy aus, Fernsteuerung aus, falsches WLAN, TLS ...
            log(repr(e))
            body = error_reply(message_id, "Handy nicht erreichbar oder Fernsteuerung aus: %s" % e) \
                if message_id is not None else ""
        if body.strip():
            sys.stdout.write(body.strip() + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
