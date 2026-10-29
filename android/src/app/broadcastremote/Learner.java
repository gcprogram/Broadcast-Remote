package app.broadcastremote;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Derives a payload model from recorded pairs (code, packet), same logic as tools/profile_tool.py:
 * first prefix + (code ^ xor), then prefix + (code ^ xor) + CRC-16 with common polynomials, otherwise a table.
 * Plain Java, testable on a PC (tests/PayloadCheck.java).
 */
final class Learner {
    static final class Result {
        final Payload payload;
        final String how;

        Result(Payload payload, String how) {
            this.payload = payload;
            this.how = how;
        }
    }

    private static final int[] POLYS = {0x1021, 0x8408, 0x8005, 0xA001, 0x3D65, 0xA6BC, 0x8BB7, 0x0589, 0x755B, 0x1DCF};

    private Learner() {
    }

    static Result learn(Map<Integer, byte[]> capture) {
        Map<Integer, byte[]> table = new TreeMap<>(capture);
        Set<Integer> lengths = new HashSet<>();
        for (byte[] p : capture.values()) lengths.add(p.length);
        if (lengths.size() != 1 || capture.size() < 3) {
            return new Result(Payload.table(table), "packet table (too few packets or packets of different length, at least 3 codes needed)");
        }
        int n = lengths.iterator().next();

        // without CRC: prefix + (code ^ xor)
        byte[] prefix = prefixOf(capture, n - 1);
        Integer xor = xorOf(capture, n - 1);
        if (prefix != null && xor != null) {
            return new Result(Payload.template(prefix, xor, null), "formula without checksum");
        }

        // with CRC: prefix + (code ^ xor) + crc16
        if (n >= 3) {
            prefix = prefixOf(capture, n - 3);
            xor = xorOf(capture, n - 3);
            if (prefix != null && xor != null) {
                for (int poly : POLYS) {
                    for (int init : new int[]{0, 0xFFFF}) {
                        for (boolean reflected : new boolean[]{true, false}) {
                            for (boolean swap : new boolean[]{false, true}) {
                                Payload.Crc probe = new Payload.Crc(poly, init, reflected, swap, 0);
                                Integer xorOut = null;
                                boolean fits = true;
                                for (Map.Entry<Integer, byte[]> e : capture.entrySet()) {
                                    byte[] p = e.getValue();
                                    int observed = ((p[n - 2] & 0xff) << 8) | (p[n - 1] & 0xff);
                                    int out = observed ^ probe.calc(e.getKey());
                                    if (xorOut == null) xorOut = out;
                                    else if (xorOut != out) {
                                        fits = false;
                                        break;
                                    }
                                }
                                if (fits && xorOut != null) {
                                    return new Result(Payload.template(prefix, xor, new Payload.Crc(poly, init, reflected, swap, xorOut)),
                                            "formula with CRC-16 (fits all " + capture.size() + " packets)");
                                }
                            }
                        }
                    }
                }
            }
        }
        return new Result(Payload.table(table), "packet table (no known formula fits)");
    }

    /** Common start of the first len bytes of all packets, otherwise null. */
    private static byte[] prefixOf(Map<Integer, byte[]> capture, int len) {
        byte[] first = null;
        for (byte[] p : capture.values()) {
            if (first == null) {
                first = java.util.Arrays.copyOf(p, len);
            } else if (!java.util.Arrays.equals(first, java.util.Arrays.copyOf(p, len))) {
                return null;
            }
        }
        return first;
    }

    /** The byte at position pos is code ^ xor with a fixed xor, otherwise null. */
    private static Integer xorOf(Map<Integer, byte[]> capture, int pos) {
        Integer xor = null;
        for (Map.Entry<Integer, byte[]> e : capture.entrySet()) {
            int v = (e.getValue()[pos] & 0xff) ^ e.getKey();
            if (xor == null) xor = v;
            else if (xor != v) return null;
        }
        return xor;
    }
}
