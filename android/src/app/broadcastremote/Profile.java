package app.broadcastremote;

import android.os.ParcelUuid;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Geraeteprofil: wie gesendet wird (Advertising), wie das Paket berechnet wird (Payload) und welche Knoepfe
 * es gibt. Wird als JSON gelesen und geschrieben, siehe docs/PROFILE_FORMAT.md.
 */
final class Profile {
    static final class Btn {
        int code;
        String label;       // leer = Hexcode anzeigen
        String icon;

        Btn(int code, String label, String icon) {
            this.code = code;
            this.label = label == null ? "" : label;
            this.icon = icon == null ? "" : icon;
        }

        String caption() {
            return label.isEmpty() ? String.format(Locale.ROOT, "%02x", code) : label;
        }
    }

    static final int FORMAT = 1;
    private static final String[] MODES = {"low_power", "balanced", "low_latency"};
    private static final String[] POWERS = {"ultra_low", "low", "medium", "high"};

    String name = "";
    String description = "";
    int companyId = 255;
    List<String> serviceUuids = new ArrayList<>();
    boolean connectable = true;
    String mode = "low_latency";
    String txPower = "high";
    int timeoutMs = 3000;
    int repeatMs = 1000;
    Payload payload;
    int offCode = 0;
    int offSendMs = 2000;
    int autoOffSeconds = 180;
    List<Btn> buttons = new ArrayList<>();

    int advertiseMode() {
        return indexOf(MODES, mode);
    }

    int txLevel() {
        return indexOf(POWERS, txPower);
    }

    List<ParcelUuid> uuids() {
        List<ParcelUuid> out = new ArrayList<>();
        for (String u : serviceUuids) out.add(ParcelUuid.fromString(fullUuid(u)));
        return out;
    }

    /** Wirft IllegalArgumentException, wenn das Profil das Paket nicht liefern kann. */
    byte[] packet(int code) {
        return payload.packet(code);
    }

    boolean hasButton(int code) {
        for (Btn b : buttons) if (b.code == code) return true;
        return false;
    }

    // ---------------------------------------------------------------- JSON lesen

    static Profile parse(String text) {
        try {
            return parseObject(new JSONObject(text.replaceFirst("^\\uFEFF", "")));
        } catch (JSONException e) {
            throw new IllegalArgumentException("Kein gültiges JSON: " + e.getMessage());
        }
    }

    private static Profile parseObject(JSONObject o) throws JSONException {
        Profile p = new Profile();
        if (o.optInt("format", FORMAT) != FORMAT) throw new IllegalArgumentException("Unbekanntes Profilformat");
        p.name = o.optString("name", "").trim();
        if (p.name.isEmpty()) throw new IllegalArgumentException("\"name\" fehlt");
        p.description = o.optString("description", "");

        JSONObject adv = o.optJSONObject("advertising");
        if (adv == null) throw new IllegalArgumentException("\"advertising\" fehlt");
        p.companyId = adv.optInt("companyId", -1);
        if (p.companyId < 0 || p.companyId > 0xffff) throw new IllegalArgumentException("advertising.companyId muss 0-65535 sein");
        JSONArray uu = adv.optJSONArray("serviceUuids");
        if (uu != null) {
            for (int i = 0; i < uu.length(); i++) {
                String u = uu.getString(i).trim();
                fullUuid(u);                                    // prueft das Format
                p.serviceUuids.add(u);
            }
        }
        p.connectable = adv.optBoolean("connectable", true);
        p.mode = oneOf(adv.optString("mode", "low_latency"), MODES, "advertising.mode");
        p.txPower = oneOf(adv.optString("txPower", "high"), POWERS, "advertising.txPower");
        p.timeoutMs = range(adv.optInt("timeoutMs", 3000), 0, 180000, "advertising.timeoutMs");
        p.repeatMs = range(adv.optInt("repeatMs", 1000), 100, 60000, "advertising.repeatMs");

        JSONObject pl = o.optJSONObject("payload");
        if (pl == null) throw new IllegalArgumentException("\"payload\" fehlt");
        p.payload = parsePayload(pl);

        JSONObject off = o.optJSONObject("off");
        if (off != null) {
            p.offCode = code(off.opt("code"), "off.code");
            p.offSendMs = range(off.optInt("sendMs", 2000), 200, 30000, "off.sendMs");
        }
        p.autoOffSeconds = range(o.optInt("autoOffSeconds", 180), 5, 3600, "autoOffSeconds");

        JSONArray bs = o.optJSONArray("buttons");
        if (bs != null) {
            for (int i = 0; i < bs.length(); i++) {
                JSONObject b = bs.getJSONObject(i);
                int c = code(b.opt("code"), "buttons[" + i + "].code");
                if (p.hasButton(c)) throw new IllegalArgumentException("Knopf doppelt: " + String.format("%02x", c));
                p.buttons.add(new Btn(c, b.optString("label", ""), b.optString("icon", "")));
            }
        }

        // Legacy-Advertising hat 31 Bytes: Flags 3 + Herstellerdaten (4 + n) + UUID-Liste (2 + 2 je 16-Bit-UUID)
        int size = (p.connectable ? 3 : 0) + 4 + maxPacketLength(p.payload);
        int uuid16 = 0;
        int uuid128 = 0;
        for (String u : p.serviceUuids) {
            if (u.length() <= 4) uuid16++;
            else uuid128++;
        }
        if (uuid16 > 0) size += 2 + 2 * uuid16;
        if (uuid128 > 0) size += 2 + 16 * uuid128;
        if (size > 31) throw new IllegalArgumentException("Paket zu lang für Legacy-Advertising (" + size + " von 31 Bytes)");
        return p;
    }

    private static int maxPacketLength(Payload pl) {
        if (!pl.isTable()) return pl.prefix.length + 1 + (pl.crc != null ? 2 : 0);
        int max = 0;
        for (byte[] b : pl.table.values()) max = Math.max(max, b.length);
        return max;
    }

    private static Payload parsePayload(JSONObject pl) throws JSONException {
        String type = pl.optString("type", "template");
        if (type.equals("table")) {
            JSONObject packets = pl.optJSONObject("packets");
            if (packets == null || packets.length() == 0) throw new IllegalArgumentException("payload.packets fehlt");
            Map<Integer, byte[]> table = new TreeMap<>();
            Iterator<String> keys = packets.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                byte[] bytes = hex(packets.getString(k), "payload.packets." + k);
                if (bytes.length == 0 || bytes.length > 24) throw new IllegalArgumentException("payload.packets." + k + ": Länge 1-24 Bytes");
                table.put(code(k, "payload.packets." + k), bytes);
            }
            return Payload.table(table);
        }
        if (!type.equals("template")) throw new IllegalArgumentException("payload.type muss template oder table sein");
        byte[] prefix = hex(pl.optString("prefix", ""), "payload.prefix");
        int xor = pl.has("xor") ? code(pl.get("xor"), "payload.xor") : 0;
        Payload.Crc crc = null;
        JSONObject c = pl.optJSONObject("crc");
        if (c != null) {
            crc = new Payload.Crc(code16(c.opt("poly"), "payload.crc.poly"), c.has("init") ? code16(c.get("init"), "payload.crc.init") : 0,
                    c.optBoolean("reflected", true), c.optBoolean("swap", false),
                    c.has("xorOut") ? code16(c.get("xorOut"), "payload.crc.xorOut") : 0);
        }
        return Payload.template(prefix, xor, crc);
    }

    // ---------------------------------------------------------------- JSON schreiben

    String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("format", FORMAT);
            o.put("name", name);
            if (!description.isEmpty()) o.put("description", description);
            JSONObject adv = new JSONObject();
            adv.put("companyId", companyId);
            adv.put("serviceUuids", new JSONArray(serviceUuids));
            adv.put("connectable", connectable);
            adv.put("mode", mode);
            adv.put("txPower", txPower);
            adv.put("timeoutMs", timeoutMs);
            adv.put("repeatMs", repeatMs);
            o.put("advertising", adv);

            JSONObject pl = new JSONObject();
            if (payload.isTable()) {
                pl.put("type", "table");
                JSONObject packets = new JSONObject();
                for (Map.Entry<Integer, byte[]> e : payload.table.entrySet()) {
                    packets.put(String.format(Locale.ROOT, "%02x", e.getKey()), Payload.hex(e.getValue()));
                }
                pl.put("packets", packets);
            } else {
                pl.put("type", "template");
                pl.put("prefix", Payload.hex(payload.prefix));
                pl.put("xor", String.format(Locale.ROOT, "%02x", payload.xor));
                if (payload.crc != null) {
                    JSONObject c = new JSONObject();
                    c.put("poly", String.format(Locale.ROOT, "%04x", payload.crc.poly));
                    c.put("init", String.format(Locale.ROOT, "%04x", payload.crc.init));
                    c.put("reflected", payload.crc.reflected);
                    c.put("swap", payload.crc.swap);
                    c.put("xorOut", String.format(Locale.ROOT, "%04x", payload.crc.xorOut));
                    pl.put("crc", c);
                }
            }
            o.put("payload", pl);
            o.put("off", new JSONObject().put("code", String.format(Locale.ROOT, "%02x", offCode)).put("sendMs", offSendMs));
            o.put("autoOffSeconds", autoOffSeconds);
            JSONArray bs = new JSONArray();
            for (Btn b : buttons) {
                JSONObject j = new JSONObject().put("code", String.format(Locale.ROOT, "%02x", b.code));
                if (!b.label.isEmpty()) j.put("label", b.label);
                if (!b.icon.isEmpty()) j.put("icon", b.icon);
                bs.put(j);
            }
            o.put("buttons", bs);
            return o.toString(2);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- Hilfen

    /** 16-Bit-, 32-Bit- oder volle UUID -> volle UUID mit Bluetooth-Basis. */
    static String fullUuid(String u) {
        String s = u.trim().toLowerCase(Locale.ROOT);
        if (s.matches("[0-9a-f]{4}")) return "0000" + s + "-0000-1000-8000-00805f9b34fb";
        if (s.matches("[0-9a-f]{8}")) return s + "-0000-1000-8000-00805f9b34fb";
        if (s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return s;
        throw new IllegalArgumentException("Ungültige UUID: " + u);
    }

    private static int indexOf(String[] values, String v) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return i;
        return values.length - 1;
    }

    private static String oneOf(String v, String[] allowed, String field) {
        for (String a : allowed) if (a.equals(v)) return v;
        throw new IllegalArgumentException(field + " muss einer von " + String.join(", ", allowed) + " sein");
    }

    private static int range(int v, int lo, int hi, String field) {
        if (v < lo || v > hi) throw new IllegalArgumentException(field + " muss " + lo + "-" + hi + " sein");
        return v;
    }

    /** Hexstring ("0b") oder Zahl -> 0-255. */
    private static int code(Object v, String field) {
        int n = number(v, field);
        if (n < 0 || n > 255) throw new IllegalArgumentException(field + " muss 00-ff sein");
        return n;
    }

    private static int code16(Object v, String field) {
        int n = number(v, field);
        if (n < 0 || n > 0xffff) throw new IllegalArgumentException(field + " muss 0000-ffff sein");
        return n;
    }

    private static int number(Object v, String field) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim().replaceFirst("^0[xX]", ""), 16);
            } catch (NumberFormatException ignored) {
            }
        }
        throw new IllegalArgumentException(field + " fehlt oder ist keine Hexzahl");
    }

    private static byte[] hex(String v, String field) {
        try {
            return Payload.fromHex(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + ": " + e.getMessage());
        }
    }
}
