package app.broadcastremote;

import java.util.Map;

/**
 * Computes the payload bytes of a broadcast packet for one data byte (function code). Plain Java without
 * Android classes so that it can be tested on a PC (see tests/PayloadCheck.java).
 *
 * Two kinds:
 *  - template ("template"): prefix + [code ^ xor] + optional CRC-16 (two bytes, big endian)
 *  - table ("table"): finished packets per code, for example from a capture of the original app
 */
final class Payload {
    /** CRC-16 over the plain data byte. Output = (swap ? swapped bytes : crc) ^ xorOut. */
    static final class Crc {
        final int poly;
        final int init;
        final boolean reflected;
        final boolean swap;
        final int xorOut;

        Crc(int poly, int init, boolean reflected, boolean swap, int xorOut) {
            this.poly = poly & 0xffff;
            this.init = init & 0xffff;
            this.reflected = reflected;
            this.swap = swap;
            this.xorOut = xorOut & 0xffff;
        }

        int calc(int data) {
            int crc = init;
            if (reflected) {
                crc ^= data & 0xff;
                for (int i = 0; i < 8; i++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ poly : crc >>> 1;
            } else {
                crc ^= (data & 0xff) << 8;
                for (int i = 0; i < 8; i++) crc = ((crc & 0x8000) != 0 ? (crc << 1) ^ poly : crc << 1) & 0xffff;
            }
            if (swap) crc = ((crc & 0xff) << 8) | (crc >>> 8);
            return (crc ^ xorOut) & 0xffff;
        }
    }

    final byte[] prefix;
    final int xor;
    final Crc crc;                          // null = no checksum
    final Map<Integer, byte[]> table;       // null = template

    private Payload(byte[] prefix, int xor, Crc crc, Map<Integer, byte[]> table) {
        this.prefix = prefix;
        this.xor = xor & 0xff;
        this.crc = crc;
        this.table = table;
    }

    static Payload template(byte[] prefix, int xor, Crc crc) {
        return new Payload(prefix, xor, crc, null);
    }

    static Payload table(Map<Integer, byte[]> table) {
        return new Payload(new byte[0], 0, null, table);
    }

    boolean isTable() {
        return table != null;
    }

    /** Throws IllegalArgumentException if the table does not contain the code. */
    byte[] packet(int code) {
        if (table != null) {
            byte[] p = table.get(code);
            if (p == null) throw new IllegalArgumentException(String.format("No packet for code 0x%02x in the table", code));
            return p.clone();
        }
        int extra = 1 + (crc != null ? 2 : 0);
        byte[] out = new byte[prefix.length + extra];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        out[prefix.length] = (byte) ((code & 0xff) ^ xor);
        if (crc != null) {
            int c = crc.calc(code);
            out[prefix.length + 1] = (byte) (c >> 8);
            out[prefix.length + 2] = (byte) c;
        }
        return out;
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    static byte[] fromHex(String text) {
        String t = text.replaceAll("[\\s:_-]", "");
        if (t.length() % 2 != 0) throw new IllegalArgumentException("Hex string of odd length: " + text);
        byte[] b = new byte[t.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(t.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }
}
