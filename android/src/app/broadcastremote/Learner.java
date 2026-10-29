package app.broadcastremote;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Leitet aus aufgenommenen Paaren (Code, Paket) ein Payload-Modell ab, gleiche Logik wie tools/profile_tool.py:
 * erst prefix + (code ^ xor), dann prefix + (code ^ xor) + CRC-16 mit gaengigen Polynomen, sonst eine Tabelle.
 * Reines Java, am PC testbar (tests/PayloadCheck.java).
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
            return new Result(Payload.table(table), "Paketliste (zu wenige oder unterschiedlich lange Pakete, mindestens 3 Codes nötig)");
        }
        int n = lengths.iterator().next();

        // ohne CRC: prefix + (code ^ xor)
        byte[] prefix = prefixOf(capture, n - 1);
        Integer xor = xorOf(capture, n - 1);
        if (prefix != null && xor != null) {
            return new Result(Payload.template(prefix, xor, null), "Formel ohne Prüfsumme");
        }

        // mit CRC: prefix + (code ^ xor) + crc16
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
                                            "Formel mit CRC-16 (passt zu allen " + capture.size() + " Paketen)");
                                }
                            }
                        }
                    }
                }
            }
        }
        return new Result(Payload.table(table), "Paketliste (keine bekannte Formel passt)");
    }

    /** Gemeinsamer Anfang der ersten len Bytes aller Pakete, sonst null. */
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

    /** Das Byte an Position pos ist code ^ xor mit festem xor, sonst null. */
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
