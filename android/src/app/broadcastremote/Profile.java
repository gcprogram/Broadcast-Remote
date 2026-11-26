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
 * Device profile: how to send (advertising), how the packet is computed (payload) and which buttons exist.
 * Read and written as JSON, see docs/PROFILE_FORMAT.md.
 */
final class Profile {
    /**
     * One step of a macro: send this code of the profile for holdMs, then the next step.
     * A pause (code PAUSE) sends nothing for holdMs (advertising is stopped, the device just gets no packet).
     * An off step (code OFF) sends the profile's off code for holdMs (like the OFF button).
     */
    static final class Step {
        static final int PAUSE = -1;
        static final int OFF = -2;
        static final int DEFAULT_HOLD_MS = 1000;
        static final int MIN_HOLD_MS = 200;
        static final int MAX_HOLD_MS = 30000;
        static final int MIN_PAUSE_MS = 100;
        static final int MAX_PAUSE_MS = 60000;
        int code;
        int holdMs;

        Step(int code, int holdMs) {
            this.code = code;
            this.holdMs = holdMs;
        }

        boolean isPause() {
            return code == PAUSE;
        }

        boolean isOff() {
            return code == OFF;
        }

        /** A step that presses a button of the profile (not a pause, not off). */
        boolean isPress() {
            return code >= 0;
        }
    }

    static final class Btn {
        static final int MAX_STEPS = 20;
        int code;
        String label;       // empty = show the hex code
        String icon;
        String example;     // optional: what the button is good for (for people and assistants), shown by the MCP tool "buttons"
        /** Not empty = macro: the code is only a name for it, nothing is sent for the code itself; the steps are played one after another. */
        List<Step> macro = new ArrayList<>();

        Btn(int code, String label, String icon) {
            this.code = code;
            this.label = label == null ? "" : label;
            this.icon = icon == null ? "" : icon;
            this.example = "";
        }

        boolean isMacro() {
            return !macro.isEmpty();
        }

        /** Number of steps that press a button (a macro made of pauses and off steps only does nothing). */
        int pressSteps() {
            int n = 0;
            for (Step s : macro) if (s.isPress()) n++;
            return n;
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
    /** Off sends no packet, advertising is just stopped (the device switches off by itself). */
    boolean offSilent = false;
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

    /** Throws IllegalArgumentException if the profile cannot provide the packet. */
    byte[] packet(int code) {
        return payload.packet(code);
    }

    boolean hasButton(int code) {
        return button(code) != null;
    }

    /** The button with this code, null if there is none. */
    Btn button(int code) {
        for (Btn b : buttons) if (b.code == code) return b;
        return null;
    }

    /** An unused code for a new macro button (counted down from 0xff, never the off code); -1 if every code is taken. */
    int freeCode() {
        for (int c = 255; c >= 0; c--) {
            if (!hasButton(c) && (offSilent || c != offCode)) return c;
        }
        return -1;
    }

    // ---------------------------------------------------------------- read JSON

    static Profile parse(String text) {
        try {
            return parseObject(new JSONObject(text.replaceFirst("^\\uFEFF", "")));
        } catch (JSONException e) {
            throw new IllegalArgumentException("Not valid JSON: " + e.getMessage());
        }
    }

    private static Profile parseObject(JSONObject o) throws JSONException {
        Profile p = new Profile();
        if (o.optInt("format", FORMAT) != FORMAT) throw new IllegalArgumentException("Unknown profile format");
        p.name = o.optString("name", "").trim();
        if (p.name.isEmpty()) throw new IllegalArgumentException("\"name\" is missing");
        p.description = o.optString("description", "");

        JSONObject adv = o.optJSONObject("advertising");
        if (adv == null) throw new IllegalArgumentException("\"advertising\" is missing");
        p.companyId = adv.optInt("companyId", -1);
        if (p.companyId < 0 || p.companyId > 0xffff) throw new IllegalArgumentException("advertising.companyId must be 0-65535");
        JSONArray uu = adv.optJSONArray("serviceUuids");
        if (uu != null) {
            for (int i = 0; i < uu.length(); i++) {
                String u = uu.getString(i).trim();
                fullUuid(u);                                    // checks the format
                p.serviceUuids.add(u);
            }
        }
        p.connectable = adv.optBoolean("connectable", true);
        p.mode = oneOf(adv.optString("mode", "low_latency"), MODES, "advertising.mode");
        p.txPower = oneOf(adv.optString("txPower", "high"), POWERS, "advertising.txPower");
        p.timeoutMs = range(adv.optInt("timeoutMs", 3000), 0, 180000, "advertising.timeoutMs");
        p.repeatMs = range(adv.optInt("repeatMs", 1000), 100, 60000, "advertising.repeatMs");

        JSONObject pl = o.optJSONObject("payload");
        if (pl == null) throw new IllegalArgumentException("\"payload\" is missing");
        p.payload = parsePayload(pl);

        JSONObject off = o.optJSONObject("off");
        if (off != null) {
            p.offSilent = off.optBoolean("silent", false);
            if (!p.offSilent || off.has("code")) p.offCode = code(off.opt("code"), "off.code");
            p.offSendMs = range(off.optInt("sendMs", 2000), 200, 30000, "off.sendMs");
        }
        p.autoOffSeconds = range(o.optInt("autoOffSeconds", 180), 5, 3600, "autoOffSeconds");

        JSONArray bs = o.optJSONArray("buttons");
        if (bs != null) {
            for (int i = 0; i < bs.length(); i++) {
                JSONObject b = bs.getJSONObject(i);
                int c = code(b.opt("code"), "buttons[" + i + "].code");
                if (p.hasButton(c)) throw new IllegalArgumentException("Duplicate button: " + String.format("%02x", c));
                Btn btn = new Btn(c, b.optString("label", ""), b.optString("icon", ""));
                btn.example = b.optString("example", "").trim();
                JSONArray steps = b.optJSONArray("macro");
                if (steps != null) {
                    if (steps.length() > Btn.MAX_STEPS) throw new IllegalArgumentException("buttons[" + i + "].macro: at most " + Btn.MAX_STEPS + " steps");
                    for (int s = 0; s < steps.length(); s++) {
                        Object item = steps.get(s);
                        String where = "buttons[" + i + "].macro[" + s + "]";
                        if (item instanceof JSONObject && ((JSONObject) item).has("offMs")) {
                            btn.macro.add(new Step(Step.OFF, range(((JSONObject) item).optInt("offMs", -1),
                                    Step.MIN_HOLD_MS, Step.MAX_HOLD_MS, where + ".offMs")));
                        } else if (item instanceof JSONObject && ((JSONObject) item).has("pauseMs")) {
                            btn.macro.add(new Step(Step.PAUSE, range(((JSONObject) item).optInt("pauseMs", -1),
                                    Step.MIN_PAUSE_MS, Step.MAX_PAUSE_MS, where + ".pauseMs")));
                        } else if (item instanceof JSONObject) {
                            JSONObject so = (JSONObject) item;
                            btn.macro.add(new Step(code(so.opt("code"), where + ".code"), range(so.optInt("holdMs", Step.DEFAULT_HOLD_MS),
                                    Step.MIN_HOLD_MS, Step.MAX_HOLD_MS, where + ".holdMs")));
                        } else {
                            btn.macro.add(new Step(code(item, where), Step.DEFAULT_HOLD_MS));
                        }
                    }
                }
                p.buttons.add(btn);
            }
            for (Btn b : p.buttons) {                           // a macro plays plain buttons only, no macros inside macros
                if (b.isMacro() && b.pressSteps() == 0) throw new IllegalArgumentException("Macro " + b.caption() + " has only pauses");
                for (Step s : b.macro) {
                    if (!s.isPress()) continue;
                    Btn target = p.button(s.code);
                    if (target == null) throw new IllegalArgumentException("Macro " + b.caption() + ": step " + String.format("%02x", s.code) + " is not a button");
                    if (target.isMacro()) throw new IllegalArgumentException("Macro " + b.caption() + ": step " + target.caption() + " is itself a macro");
                }
            }
        }

        // Legacy advertising has 31 bytes: flags 3 + manufacturer data (4 + n) + UUID list (2 + 2 per 16-bit UUID)
        int size = (p.connectable ? 3 : 0) + 4 + maxPacketLength(p.payload);
        int uuid16 = 0;
        int uuid128 = 0;
        for (String u : p.serviceUuids) {
            if (u.length() <= 4) uuid16++;
            else uuid128++;
        }
        if (uuid16 > 0) size += 2 + 2 * uuid16;
        if (uuid128 > 0) size += 2 + 16 * uuid128;
        if (size > 31) throw new IllegalArgumentException("Packet too long for legacy advertising (" + size + " of 31 bytes)");
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
            if (packets == null || packets.length() == 0) throw new IllegalArgumentException("payload.packets is missing");
            Map<Integer, byte[]> table = new TreeMap<>();
            Iterator<String> keys = packets.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                byte[] bytes = hex(packets.getString(k), "payload.packets." + k);
                if (bytes.length == 0 || bytes.length > 24) throw new IllegalArgumentException("payload.packets." + k + ": length must be 1-24 bytes");
                table.put(code(k, "payload.packets." + k), bytes);
            }
            return Payload.table(table);
        }
        if (!type.equals("template")) throw new IllegalArgumentException("payload.type must be template or table");
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

    // ---------------------------------------------------------------- write JSON

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
            JSONObject offJson = new JSONObject();
            if (offSilent) offJson.put("silent", true);
            else offJson.put("code", String.format(Locale.ROOT, "%02x", offCode)).put("sendMs", offSendMs);
            o.put("off", offJson);
            o.put("autoOffSeconds", autoOffSeconds);
            JSONArray bs = new JSONArray();
            for (Btn b : buttons) {
                JSONObject j = new JSONObject().put("code", String.format(Locale.ROOT, "%02x", b.code));
                if (!b.label.isEmpty()) j.put("label", b.label);
                if (!b.icon.isEmpty()) j.put("icon", b.icon);
                if (!b.example.isEmpty()) j.put("example", b.example);
                if (b.isMacro()) {
                    JSONArray steps = new JSONArray();
                    for (Step s : b.macro) {
                        steps.put(s.isPause() ? new JSONObject().put("pauseMs", s.holdMs)
                                : s.isOff() ? new JSONObject().put("offMs", s.holdMs)
                                : new JSONObject().put("code", String.format(Locale.ROOT, "%02x", s.code)).put("holdMs", s.holdMs));
                    }
                    j.put("macro", steps);
                }
                bs.put(j);
            }
            o.put("buttons", bs);
            return o.toString(2);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** 16-bit, 32-bit or full UUID -> full UUID with the Bluetooth base. */
    static String fullUuid(String u) {
        String s = u.trim().toLowerCase(Locale.ROOT);
        if (s.matches("[0-9a-f]{4}")) return "0000" + s + "-0000-1000-8000-00805f9b34fb";
        if (s.matches("[0-9a-f]{8}")) return s + "-0000-1000-8000-00805f9b34fb";
        if (s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return s;
        throw new IllegalArgumentException("Invalid UUID: " + u);
    }

    private static int indexOf(String[] values, String v) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return i;
        return values.length - 1;
    }

    private static String oneOf(String v, String[] allowed, String field) {
        for (String a : allowed) if (a.equals(v)) return v;
        throw new IllegalArgumentException(field + " must be one of " + String.join(", ", allowed));
    }

    private static int range(int v, int lo, int hi, String field) {
        if (v < lo || v > hi) throw new IllegalArgumentException(field + " must be " + lo + "-" + hi);
        return v;
    }

    /** Hex string ("0b") or number -> 0-255. */
    private static int code(Object v, String field) {
        int n = number(v, field);
        if (n < 0 || n > 255) throw new IllegalArgumentException(field + " must be 00-ff");
        return n;
    }

    private static int code16(Object v, String field) {
        int n = number(v, field);
        if (n < 0 || n > 0xffff) throw new IllegalArgumentException(field + " must be 0000-ffff");
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
        throw new IllegalArgumentException(field + " is missing or not a hex number");
    }

    private static byte[] hex(String v, String field) {
        try {
            return Payload.fromHex(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + ": " + e.getMessage());
        }
    }
}
