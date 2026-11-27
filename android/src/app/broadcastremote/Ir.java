package app.broadcastremote;

import java.util.Arrays;
import java.util.Locale;

/**
 * One infrared signal: either a known protocol with address and command (the pulse pattern is computed) or a raw list of
 * mark/space durations in microseconds. Pure Java, no Android classes (so it can be checked on a PC).
 *
 * Patterns start with a mark (IR on) and alternate, as ConsumerIrManager expects them.
 */
final class Ir {
    static final String RAW = "raw";
    /** Protocols this app can generate (the names are the ones of the Flipper-IRDB). */
    static final String[] PROTOCOLS = {"NEC", "NECext", "Samsung32", "RC5", "RC5X", "RC6", "SIRC", "SIRC15", "SIRC20", "RCA"};
    /** ConsumerIrManager sends only patterns shorter than 2 seconds. */
    static final int MAX_TOTAL_US = 1_900_000;
    static final int MAX_PULSE_US = 200_000;

    final String protocol;      // one of PROTOCOLS or RAW
    final long address;
    final long command;
    final int carrierHz;
    final int[] data;           // RAW only

    private Ir(String protocol, long address, long command, int carrierHz, int[] data) {
        this.protocol = protocol;
        this.address = address;
        this.command = command;
        this.carrierHz = carrierHz;
        this.data = data;
    }

    static boolean supported(String protocol) {
        return canonical(protocol) != null;
    }

    private static String canonical(String protocol) {
        for (String p : PROTOCOLS) if (p.equalsIgnoreCase(protocol.trim())) return p;
        return null;
    }

    /** A signal made of a protocol, address and command; throws IllegalArgumentException for an unknown protocol or out-of-range values. */
    static Ir of(String protocol, long address, long command) {
        String p = canonical(protocol);
        if (p == null) throw new IllegalArgumentException("Unsupported infrared protocol: " + protocol);
        long maxAddress, maxCommand;
        switch (p) {
            case "NEC": maxAddress = 0xff; maxCommand = 0xff; break;
            case "NECext": maxAddress = 0xffff; maxCommand = 0xffff; break;
            case "Samsung32": maxAddress = 0xff; maxCommand = 0xffff; break;
            case "RC5": maxAddress = 0x1f; maxCommand = 0x3f; break;
            case "RC5X": maxAddress = 0x1f; maxCommand = 0x7f; break;
            case "RC6": maxAddress = 0xff; maxCommand = 0xff; break;
            case "SIRC": maxAddress = 0x1f; maxCommand = 0x7f; break;
            case "SIRC15": maxAddress = 0xff; maxCommand = 0x7f; break;
            case "SIRC20": maxAddress = 0x1fff; maxCommand = 0x7f; break;
            default: maxAddress = 0xf; maxCommand = 0xff; break;     // RCA
        }
        if (address < 0 || address > maxAddress) throw new IllegalArgumentException(p + ": address must be 0-" + Long.toHexString(maxAddress));
        if (command < 0 || command > maxCommand) throw new IllegalArgumentException(p + ": command must be 0-" + Long.toHexString(maxCommand));
        return new Ir(p, address, command, carrierOf(p), null);
    }

    /** A signal from a recorded pulse list (mark, space, mark, ...). */
    static Ir raw(int carrierHz, int[] data) {
        if (carrierHz < 10_000 || carrierHz > 100_000) throw new IllegalArgumentException("Carrier frequency must be 10000-100000 Hz");
        if (data.length < 2) throw new IllegalArgumentException("Raw signal is too short");
        long total = 0;
        for (int v : data) {
            if (v < 1 || v > MAX_PULSE_US) throw new IllegalArgumentException("Raw signal contains an impossible duration: " + v);
            total += v;
        }
        if (total > MAX_TOTAL_US) throw new IllegalArgumentException("Raw signal is longer than 2 seconds");
        return new Ir(RAW, 0, 0, carrierHz, data.clone());
    }

    boolean isRaw() {
        return protocol.equals(RAW);
    }

    private static int carrierOf(String p) {
        switch (p) {
            case "RC5": case "RC5X": case "RC6": return 36000;
            case "SIRC": case "SIRC15": case "SIRC20": return 40000;
            case "RCA": return 56000;
            default: return 38000;
        }
    }

    String describe() {
        if (isRaw()) return "raw, " + data.length + " pulses, " + carrierHz / 1000 + " kHz";
        return String.format(Locale.ROOT, "%s address %02x command %02x", protocol, address, command);
    }

    // ------------------------------------------------------------------ pulse patterns

    /** The pattern of a new key press. `toggle` is the toggle bit of RC5/RC6 (it changes with every new press). */
    int[] pattern(boolean toggle) {
        switch (protocol) {
            case "NEC": return nec(new int[]{(int) address, (int) address ^ 0xff, (int) command, (int) command ^ 0xff});
            case "NECext": return nec(new int[]{(int) (address & 0xff), (int) (address >> 8), (int) (command & 0xff), (int) (command >> 8)});
            case "Samsung32": {
                int hi = command > 0xff ? (int) (command >> 8) : (int) command ^ 0xff;
                return samsung(new int[]{(int) address, (int) address, (int) (command & 0xff), hi});
            }
            case "RC5": case "RC5X": return rc5(toggle);
            case "RC6": return rc6(toggle);
            case "SIRC": case "SIRC15": case "SIRC20": return sirc(3);
            case "RCA": return rca();
            default: return data.clone();
        }
    }

    /** What is sent again while the key is held (NEC has a short repeat frame, the others send the frame again). */
    int[] repeatPattern(boolean toggle) {
        switch (protocol) {
            case "NEC": case "NECext": return new int[]{9000, 2250, 560};
            case "SIRC": case "SIRC15": case "SIRC20": return sirc(1);
            default: return pattern(toggle);
        }
    }

    /** Time between the starts of two repeats while the key is held. */
    int repeatPeriodMs() {
        switch (protocol) {
            case "SIRC": case "SIRC15": case "SIRC20": return 45;
            case "RC5": case "RC5X": return 114;
            case "RC6": return 107;
            case RAW: {
                long total = 0;
                for (int v : data) total += v;
                return (int) (total / 1000) + 40;
            }
            default: return 108;
        }
    }

    /** Total duration of a pattern in microseconds. */
    static long totalUs(int[] pattern) {
        long t = 0;
        for (int v : pattern) t += v;
        return t;
    }

    private static final class Builder {
        private int[] v = new int[64];
        private int n;
        private boolean lastMark;

        void mark(int us) {
            add(us, true);
        }

        void space(int us) {
            add(us, false);
        }

        private void add(int us, boolean mark) {
            if (n == 0 && !mark) return;                        // a pattern starts with a mark
            if (n > 0 && lastMark == mark) {
                v[n - 1] += us;
                return;
            }
            if (n == v.length) v = Arrays.copyOf(v, n * 2);
            v[n++] = us;
            lastMark = mark;
        }

        int[] toArray() {
            return Arrays.copyOf(v, n);
        }
    }

    private static int[] nec(int[] bytes) {
        Builder o = new Builder();
        o.mark(9000);
        o.space(4500);
        for (int b : bytes) {
            for (int i = 0; i < 8; i++) {                       // least significant bit first
                o.mark(560);
                o.space((b >> i & 1) == 1 ? 1690 : 560);
            }
        }
        o.mark(560);
        return o.toArray();
    }

    private static int[] samsung(int[] bytes) {
        Builder o = new Builder();
        o.mark(4500);
        o.space(4500);
        for (int b : bytes) {
            for (int i = 0; i < 8; i++) {
                o.mark(550);
                o.space((b >> i & 1) == 1 ? 1650 : 550);
            }
        }
        o.mark(550);
        return o.toArray();
    }

    /** Sony SIRC: 12, 15 or 20 bits (7 command bits, then the address), least significant bit first, repeated `frames` times every 45 ms. */
    private int[] sirc(int frames) {
        int addressBits = protocol.equals("SIRC") ? 5 : protocol.equals("SIRC15") ? 8 : 13;
        Builder one = new Builder();
        one.mark(2400);
        one.space(600);
        long bits = command | address << 7;
        for (int i = 0; i < 7 + addressBits; i++) {
            one.mark((bits >> i & 1) == 1 ? 1200 : 600);
            if (i < 6 + addressBits) one.space(600);
        }
        int[] frame = one.toArray();
        long gap = 45000 - totalUs(frame);
        Builder o = new Builder();
        for (int f = 0; f < frames; f++) {
            for (int i = 0; i < frame.length; i++) {
                if (i % 2 == 0) o.mark(frame[i]);
                else o.space(frame[i]);
            }
            if (f < frames - 1) o.space((int) gap);
        }
        return o.toArray();
    }

    /** Philips RC5 / RC5X: biphase, 889 us per half bit, 5 address bits and 6 (RC5) or 7 (RC5X) command bits, most significant bit first. */
    private int[] rc5(boolean toggle) {
        Builder o = new Builder();
        boolean x = protocol.equals("RC5X");
        int cmd = (int) command;
        biphase5(o, true);                                      // S1
        biphase5(o, x ? (cmd & 0x40) == 0 : true);              // S2 (RC5X: inverted command bit 6)
        biphase5(o, toggle);
        for (int i = 4; i >= 0; i--) biphase5(o, (address >> i & 1) == 1);
        for (int i = 5; i >= 0; i--) biphase5(o, (cmd >> i & 1) == 1);
        return o.toArray();
    }

    private static void biphase5(Builder o, boolean one) {      // RC5: 1 = space then mark, 0 = mark then space
        if (one) {
            o.space(889);
            o.mark(889);
        } else {
            o.mark(889);
            o.space(889);
        }
    }

    /** Philips RC6 mode 0: header, start bit, 3 mode bits (0), toggle bit (double width), 8 address and 8 command bits. */
    private int[] rc6(boolean toggle) {
        Builder o = new Builder();
        o.mark(2666);
        o.space(889);
        biphase6(o, true, 444);                                 // start bit
        for (int i = 0; i < 3; i++) biphase6(o, false, 444);    // mode 0
        biphase6(o, toggle, 889);                               // trailer / toggle bit: twice as long
        for (int i = 7; i >= 0; i--) biphase6(o, (address >> i & 1) == 1, 444);
        for (int i = 7; i >= 0; i--) biphase6(o, (command >> i & 1) == 1, 444);
        return o.toArray();
    }

    private static void biphase6(Builder o, boolean one, int half) {   // RC6: 1 = mark then space, 0 = space then mark
        if (one) {
            o.mark(half);
            o.space(half);
        } else {
            o.space(half);
            o.mark(half);
        }
    }

    /** RCA: 4 address bits and 8 command bits, then both inverted, most significant bit first. */
    private int[] rca() {
        long frame = (address << 20) | (command << 12) | ((address ^ 0xf) << 8) | (command ^ 0xff);
        Builder o = new Builder();
        o.mark(4000);
        o.space(4000);
        for (int i = 23; i >= 0; i--) {
            o.mark(500);
            o.space((frame >> i & 1) == 1 ? 2000 : 1000);
        }
        o.mark(500);
        return o.toArray();
    }
}
