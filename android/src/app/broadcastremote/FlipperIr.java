package app.broadcastremote;

import java.util.ArrayList;
import java.util.List;

/**
 * Reader for the ".ir" files of the Flipper-IRDB (a text file with one block per button: name, type parsed or raw, and
 * protocol/address/command or frequency/data). Pure Java, no Android classes.
 */
final class FlipperIr {
    /** One button of the file: its name and either a usable signal or the reason why it cannot be used. */
    static final class Entry {
        final String name;
        final Ir ir;            // null if problem != null
        final String problem;

        Entry(String name, Ir ir, String problem) {
            this.name = name;
            this.ir = ir;
            this.problem = problem;
        }
    }

    private FlipperIr() {
    }

    static List<Entry> parse(String text) {
        List<Entry> out = new ArrayList<>();
        String name = null;
        String type = "", protocol = "", address = "", command = "", frequency = "", data = "";
        for (String raw : (text + "\nname: \n").split("\r?\n")) {            // the last (empty) name closes the last block
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();
            if (key.equals("name")) {
                if (name != null && !name.isEmpty()) out.add(entry(name, type, protocol, address, command, frequency, data));
                name = value;
                type = protocol = address = command = frequency = data = "";
                continue;
            }
            switch (key) {
                case "type": type = value; break;
                case "protocol": protocol = value; break;
                case "address": address = value; break;
                case "command": command = value; break;
                case "frequency": frequency = value; break;
                case "data": data = value; break;
                default: break;                                              // duty_cycle, Filetype, Version
            }
        }
        return out;
    }

    private static Entry entry(String name, String type, String protocol, String address, String command, String frequency, String data) {
        try {
            if (type.equalsIgnoreCase("parsed")) {
                if (!Ir.supported(protocol)) return new Entry(name, null, "protocol " + (protocol.isEmpty() ? "?" : protocol) + " not supported");
                return new Entry(name, Ir.of(protocol, littleEndianHex(address), littleEndianHex(command)), null);
            }
            if (type.equalsIgnoreCase("raw")) {
                String[] parts = data.trim().split("\\s+");
                int[] pulses = new int[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    long v = Long.parseLong(parts[i]);
                    if (v > Integer.MAX_VALUE) throw new IllegalArgumentException("broken raw data");
                    pulses[i] = (int) v;
                }
                return new Entry(name, Ir.raw(frequency.isEmpty() ? 38000 : Integer.parseInt(frequency.trim()), pulses), null);
            }
            return new Entry(name, null, "unknown type " + (type.isEmpty() ? "?" : type));
        } catch (IllegalArgumentException e) {
            return new Entry(name, null, e.getMessage());
        }
    }

    /** "07 00 00 00" (bytes, least significant first) -> 7. */
    static long littleEndianHex(String text) {
        String[] bytes = text.trim().split("\\s+");
        if (text.trim().isEmpty() || bytes.length > 4) throw new IllegalArgumentException("address/command missing or broken");
        long v = 0;
        for (int i = 0; i < bytes.length; i++) v |= Long.parseLong(bytes[i], 16) << (8 * i);
        return v;
    }
}
