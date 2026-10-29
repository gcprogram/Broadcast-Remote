package app.broadcastremote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

/**
 * Minimaler HTTPS-Server mit MCP (Streamable HTTP, nur JSON-Antworten) unter POST /mcp.
 * Jede Anfrage braucht "Authorization: Bearer <token>". Nur Verbindungen aus privaten Netzen
 * (LAN, VPN, localhost) werden angenommen.
 */
final class RemoteServer {
    interface Api {
        String send(int code);

        String off();

        String status();

        JSONArray buttons();
    }

    private static final int MAX_HEADER = 16 * 1024;
    private static final int MAX_BODY = 64 * 1024;
    private static final String TOOLS = "["
            + "{\"name\":\"send\",\"description\":\"Sendet einen Funktionscode (Datenbyte) des aktiven Profils an das Gerät "
            + "und wiederholt ihn, bis ein neuer Befehl oder off kommt. Die gültigen Codes liefert buttons. "
            + "Ohne neuen Befehl schaltet die App nach der Auto-Aus-Zeit des Profils selbst aus.\","
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"integer\",\"minimum\":0,"
            + "\"maximum\":255,\"description\":\"Datenbyte 0-255 (dezimal, oder Text wie 0x11)\"}},"
            + "\"required\":[\"code\"]}},"
            + "{\"name\":\"off\",\"description\":\"Schaltet das Gerät aus (sendet den Aus-Code des Profils).\","
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}}},"
            + "{\"name\":\"status\",\"description\":\"Zeigt das aktive Profil, ob etwas läuft, die Zeit bis zum "
            + "Auto-Aus und ob Bluetooth am Handy an ist (sonst sind alle Sendebefehle gesperrt). Das Gerät "
            + "sendet nichts zurück, ob es reagiert, ist nicht prüfbar.\","
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}}},"
            + "{\"name\":\"buttons\",\"description\":\"Listet die Knöpfe des aktiven Profils (Code dezimal und hex, "
            + "Beschriftung, in der Reihenfolge der App).\","
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]";

    private final SSLContext ssl;
    private final int port;
    private final Supplier<String> token;
    private final Api api;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running;
    private SSLServerSocket server;

    RemoteServer(SSLContext ssl, int port, Supplier<String> token, Api api) {
        this.ssl = ssl;
        this.port = port;
        this.token = token;
        this.api = api;
    }

    void start() throws IOException {
        server = (SSLServerSocket) ssl.getServerSocketFactory().createServerSocket(port, 20);
        running = true;
        new Thread(this::acceptLoop, "br-accept").start();
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                if (!allowed(s.getInetAddress())) {
                    s.close();
                    continue;
                }
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    /** Loopback, LAN (10/172.16/192.168), Link-Local, Tailscale/CGNAT 100.64/10, IPv6 ULA. */
    static boolean allowed(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 4) return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 0x40;
        return (b[0] & 0xfe) == 0xfc;
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(10000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            byte[] head = readHead(in);
            if (head == null) {
                respond(out, 400, "Bad Request", "{\"error\":\"bad request\"}");
                return;
            }
            String[] lines = new String(head, StandardCharsets.ISO_8859_1).split("\r\n");
            String[] req = lines[0].split(" ");
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) headers.put(lines[i].substring(0, c).trim().toLowerCase(), lines[i].substring(c + 1).trim());
            }
            if (!authorized(headers.get("authorization"))) {
                Thread.sleep(500);                                   // bremst Raten
                respond(out, 401, "Unauthorized", "{\"error\":\"unauthorized\"}");
                return;
            }
            String path = req.length > 1 ? req[1] : "";
            if (!path.equals("/mcp") && !path.equals("/mcp/")) {
                respond(out, 404, "Not Found", "{\"error\":\"not found\"}");
                return;
            }
            if (!"POST".equals(req[0])) {
                respond(out, 405, "Method Not Allowed", "{\"error\":\"use POST\"}");
                return;
            }
            int len = 0;
            try {
                len = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException ignored) {
            }
            if (len <= 0 || len > MAX_BODY) {
                respond(out, 400, "Bad Request", "{\"error\":\"body size\"}");
                return;
            }
            byte[] body = new byte[len];
            for (int n = 0; n < len; ) {
                int r = in.read(body, n, len - n);
                if (r < 0) return;
                n += r;
            }
            String answer = rpc(new String(body, StandardCharsets.UTF_8));
            if (answer == null) respond(out, 202, "Accepted", null);
            else respond(out, 200, "OK", answer);
        } catch (Exception ignored) {
            // Verbindung abgebrochen oder TLS-Fehler
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private boolean authorized(String header) {
        if (header == null || !header.startsWith("Bearer ")) return false;
        byte[] given = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, token.get().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int last4 = 0;
        while (buf.size() < MAX_HEADER) {
            int c = in.read();
            if (c < 0) return null;
            buf.write(c);
            last4 = (last4 << 8) | c;
            if (last4 == 0x0D0A0D0A) return buf.toByteArray();     // Ende der Header: \r\n\r\n
        }
        return null;
    }

    private static void respond(OutputStream out, int code, String reason, String body) throws IOException {
        byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: application/json; charset=utf-8\r\n"
                + (code == 405 ? "Allow: POST\r\n" : "") + "Content-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    /** JSON-RPC 2.0; null bei Notifications (keine Antwort). */
    private String rpc(String text) {
        Object id = null;
        try {
            JSONObject req = new JSONObject(text);
            id = req.opt("id");
            String method = req.optString("method");
            if (!req.has("id")) return null;
            JSONObject params = req.optJSONObject("params");
            JSONObject result = new JSONObject();
            switch (method) {
                case "initialize":
                    result.put("protocolVersion",
                            params != null ? params.optString("protocolVersion", "2025-03-26") : "2025-03-26");
                    result.put("capabilities", new JSONObject().put("tools", new JSONObject().put("listChanged", false)));
                    result.put("serverInfo", new JSONObject().put("name", "broadcast-remote").put("version", "1.0"));
                    result.put("instructions", "Steuert ein BLE-Broadcast-Gerät über ein Profil. Vor Beginn status aufrufen; "
                            + "am Ende immer off senden.");
                    break;
                case "ping":
                    break;
                case "tools/list":
                    result.put("tools", new JSONArray(TOOLS));
                    break;
                case "tools/call":
                    result = callTool(params);
                    break;
                default:
                    return error(id, -32601, "Methode unbekannt: " + method);
            }
            return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString();
        } catch (Exception e) {
            return error(id, -32700, "Ungültige Anfrage");
        }
    }

    private JSONObject callTool(JSONObject params) throws Exception {
        String name = params == null ? "" : params.optString("name");
        JSONObject args = params == null ? null : params.optJSONObject("arguments");
        String text;
        switch (name) {
            case "send": {
                Object raw = args == null ? null : args.opt("code");
                int code;
                try {
                    code = raw instanceof Number ? ((Number) raw).intValue() : Integer.decode(String.valueOf(raw));
                } catch (NumberFormatException e) {
                    code = -1;
                }
                text = code < 0 || code > 255 ? "Fehler: code muss 0-255 sein" : api.send(code);
                break;
            }
            case "off":
                text = api.off();
                break;
            case "status":
                text = api.status();
                break;
            case "buttons":
                text = api.buttons().toString();
                break;
            default:
                text = "Fehler: unbekanntes Werkzeug " + name;
        }
        return new JSONObject()
                .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", text)))
                .put("isError", text.startsWith("Fehler"));
    }

    private static String error(Object id, int code, String message) {
        try {
            return new JSONObject().put("jsonrpc", "2.0").put("id", id == null ? JSONObject.NULL : id)
                    .put("error", new JSONObject().put("code", code).put("message", message)).toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
