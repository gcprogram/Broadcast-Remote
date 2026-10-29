"""stdio MCP bridge to the Broadcast-Remote app on the phone.

An MCP client (for example Claude Code) starts this script as a local MCP server (stdio). Every request is
forwarded to the app over HTTPS. The phone's certificate is checked here (only this one file is used as the trust
anchor, nothing is added to the operating system's certificate store), and the token comes from the environment
variable BROADCAST_REMOTE_TOKEN, so it is not stored in any file.

Environment: BROADCAST_REMOTE_TOKEN (required), BROADCAST_REMOTE_URL (required, e.g. https://192.168.1.20:8443/mcp),
BROADCAST_REMOTE_CERT (default ~/.broadcast-remote/cert.pem).
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
    sys.stdin.reconfigure(encoding="utf-8-sig")      # tolerates a byte order mark at the start
    sys.stdout.reconfigure(encoding="utf-8")
    if not TOKEN:
        log("BROADCAST_REMOTE_TOKEN is not set")
    if not URL:
        log("BROADCAST_REMOTE_URL is not set")
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
            reply = "HTTP %d%s" % (e.code, " (wrong token?)" if e.code == 401 else "")
            body = error_reply(message_id, reply) if message_id is not None else ""
        except Exception as e:                      # phone off, remote control off, wrong network, TLS ...
            log(repr(e))
            body = error_reply(message_id, "Phone not reachable or remote control off: %s" % e) \
                if message_id is not None else ""
        if body.strip():
            sys.stdout.write(body.strip() + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
