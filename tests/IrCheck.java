package app.broadcastremote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Checks the infrared pulse patterns and the Flipper .ir reader without Android.
 * Run: javac -d out android/src/app/broadcastremote/Ir.java android/src/app/broadcastremote/FlipperIr.java tests/IrCheck.java
 *      java -cp out app.broadcastremote.IrCheck [folder with .ir files for a smoke test]
 */
public class IrCheck {
    private static int bad = 0;

    private static void check(boolean ok, String what) {
        if (!ok) {
            bad++;
            System.out.println("ERROR " + what);
        }
    }

    /** Decodes a pulse-distance pattern (mark m, space short/long) into bits, least significant first. */
    private static String distanceBits(int[] p, int from, int mark, int shortSpace, int longSpace, boolean endMark) {
        StringBuilder s = new StringBuilder();
        for (int i = from; i + 1 < p.length + (endMark ? 0 : 1); i += 2) {
            if (p[i] != mark) return s + "!mark" + p[i];
            if (i + 1 >= p.length) break;
            s.append(p[i + 1] == longSpace ? '1' : p[i + 1] == shortSpace ? '0' : '?');
        }
        return s.toString();
    }

    private static String lsb(int... bytes) {
        StringBuilder s = new StringBuilder();
        for (int b : bytes) for (int i = 0; i < 8; i++) s.append(b >> i & 1);
        return s.toString();
    }

    /** Samples a biphase pattern at the middle of each half bit and returns the levels (true = mark). */
    private static boolean[] levels(int[] p, int half, boolean startsWithSpace) {
        int total = 0;
        for (int v : p) total += v;
        int n = (total + (startsWithSpace ? half : 0)) / half;
        boolean[] out = new boolean[n];
        int pos = startsWithSpace ? half : 0;
        int idx = 0;
        long end = p[0];
        boolean mark = true;
        for (int i = 0; i < n; i++) {
            long t = (long) i * half + half / 2 - (startsWithSpace ? half : 0);
            if (startsWithSpace && t < 0) { out[i] = false; continue; }
            while (t >= end && idx + 1 < p.length) { idx++; end += p[idx]; mark = !mark; }
            out[i] = t < end && mark;
        }
        return out;
    }

    public static void main(String[] args) throws IOException {
        // NEC: address 00, command 45 -> bytes 00 ff 45 ba
        int[] nec = Ir.of("NEC", 0x00, 0x45).pattern(false);
        check(nec.length == 67 && nec[0] == 9000 && nec[1] == 4500 && nec[66] == 560, "NEC shape " + nec.length);
        check(distanceBits(nec, 2, 560, 560, 1690, true).equals(lsb(0x00, 0xff, 0x45, 0xba) + ""), "NEC bits");
        int[] rep = Ir.of("NEC", 1, 2).repeatPattern(false);
        check(rep.length == 3 && rep[0] == 9000 && rep[1] == 2250, "NEC repeat frame");
        // NECext: 16-bit address and command
        int[] ext = Ir.of("NECext", 0x1234, 0xabcd).pattern(false);
        check(distanceBits(ext, 2, 560, 560, 1690, true).equals(lsb(0x34, 0x12, 0xcd, 0xab)), "NECext bits");
        // Samsung32: address 07 command 02 -> 07 07 02 fd
        int[] sam = Ir.of("Samsung32", 0x07, 0x02).pattern(false);
        check(sam[0] == 4500 && sam[1] == 4500, "Samsung header");
        check(distanceBits(sam, 2, 550, 550, 1650, true).equals(lsb(0x07, 0x07, 0x02, 0xfd)), "Samsung bits");
        // Sony SIRC 12 bit: command 0x15, address 1 -> 3 frames of 45 ms
        int[] sirc = Ir.of("SIRC", 1, 0x15).pattern(false);
        int frames = 0;
        for (int i = 0; i < sirc.length; i++) if (sirc[i] == 2400 && i % 2 == 0) frames++;
        check(frames == 3, "SIRC sends 3 frames, got " + frames);
        check(sirc[0] == 2400 && sirc[1] == 600, "SIRC header");
        int[] sircOne = Ir.of("SIRC", 1, 0x15).repeatPattern(false);
        check(Ir.totalUs(sircOne) <= 45000 && sircOne.length == 25, "SIRC12 frame length " + sircOne.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < sircOne.length; i += 2) sb.append(sircOne[i] == 1200 ? '1' : '0');
        check(sb.length() == 12, "SIRC12 has 12 bits, got " + sb.length());
        // command LSB first (7 bits), then address LSB first (5 bits)
        String expect = "";
        for (int i = 0; i < 7; i++) expect += (0x15 >> i & 1);
        for (int i = 0; i < 5; i++) expect += (1 >> i & 1);
        check(sb.toString().equals(expect), "SIRC bits " + sb + " vs " + expect);
        check(Ir.of("SIRC15", 0x54, 0x2f).repeatPattern(false).length == 31, "SIRC15 length");
        // RC5: address 0, command 12 (power on many TVs); 14 bits of 2 half bits, S1 first, toggle as given
        for (boolean toggle : new boolean[]{false, true}) {
            int[] rc5 = Ir.of("RC5", 0x00, 0x0c).pattern(toggle);
            boolean[] lv = levels(rc5, 889, true);                       // leading space of the first bit was dropped
            check(lv.length >= 28, "RC5 levels " + lv.length);
            StringBuilder bits = new StringBuilder();
            for (int i = 0; i + 1 < 28; i += 2) bits.append(!lv[i] && lv[i + 1] ? '1' : lv[i] && !lv[i + 1] ? '0' : '?');
            String exp = "11" + (toggle ? "1" : "0") + "00000" + "001100";
            check(bits.toString().equals(exp), "RC5 bits " + bits + " vs " + exp);
        }
        int[] rc5x = Ir.of("RC5X", 0x05, 0x45).pattern(false);       // command bit 6 set -> S2 = 0
        boolean[] lx = levels(rc5x, 889, true);
        check(lx[2] && !lx[3], "RC5X S2 must be 0 when command bit 6 is set");
        // RC6 mode 0: address 0x10 command 0x0c
        int[] rc6 = Ir.of("RC6", 0x10, 0x0c).pattern(false);
        check(rc6[0] == 2666 && rc6[1] == 889, "RC6 header");
        boolean[] l6 = levels(java.util.Arrays.copyOfRange(rc6, 2, rc6.length), 444, false);
        StringBuilder b6 = new StringBuilder();
        // start bit, 3 mode bits, trailer (2 half bits wide: 4 samples), address, command
        int pos = 0;
        for (int k = 0; k < 4; k++, pos += 2) b6.append(l6[pos] && !l6[pos + 1] ? '1' : !l6[pos] && l6[pos + 1] ? '0' : '?');
        String trailer = "" + (!l6[pos] && !l6[pos + 1] && l6[pos + 2] && l6[pos + 3] ? '0' : '?');
        pos += 4;
        for (int k = 0; k < 16; k++, pos += 2) b6.append(l6[pos] && !l6[pos + 1] ? '1' : !l6[pos] && l6[pos + 1] ? '0' : '?');
        check(b6.toString().equals("1000" + "00010000" + "00001100") && trailer.equals("0"), "RC6 bits " + b6 + " trailer " + trailer);
        // RCA: address 0xf command 0x54
        int[] rca = Ir.of("RCA", 0x0f, 0x54).pattern(false);
        StringBuilder br = new StringBuilder();
        for (int i = 2; i + 1 < rca.length; i += 2) br.append(rca[i + 1] == 2000 ? '1' : '0');
        check(br.toString().equals("1111" + "01010100" + "0000" + "10101011"), "RCA bits " + br);

        // limits and unknown protocols
        for (Runnable r : new Runnable[]{() -> Ir.of("NEC", 0x100, 1), () -> Ir.of("Kaseikyo", 1, 1), () -> Ir.raw(38000, new int[]{1, 2, 300000})}) {
            try {
                r.run();
                check(false, "should have been rejected");
            } catch (IllegalArgumentException expected) {
                // fine
            }
        }

        // Flipper file
        String text = "Filetype: IR signals file\nVersion: 1\n# comment\nname: Power\ntype: parsed\nprotocol: Samsung32\naddress: 07 00 00 00\ncommand: 02 00 00 00\n"
                + "# \nname: Vol up\ntype: parsed\nprotocol: NECext\naddress: 04 FB 00 00\ncommand: 1A E5 00 00\n"
                + "# \nname: Odd\ntype: parsed\nprotocol: Kaseikyo\naddress: 41 54 32 00\ncommand: 05 00 00 00\n"
                + "# \nname: Blue\ntype: raw\nfrequency: 38000\nduty_cycle: 0.330000\ndata: 9000 4500 560 560 560 1690 560\n"
                + "# \nname: Junk\ntype: raw\nfrequency: 38000\nduty_cycle: 0.330000\ndata: 1072098130 526 524\n";
        List<FlipperIr.Entry> e = FlipperIr.parse(text);
        check(e.size() == 5, "5 entries, got " + e.size());
        check(e.get(0).ir != null && e.get(0).ir.address == 7 && e.get(0).ir.command == 2 && e.get(0).ir.protocol.equals("Samsung32"), "Power parsed");
        check(e.get(1).ir != null && e.get(1).ir.address == 0xfb04 && e.get(1).ir.command == 0xe51a, "NECext address/command little endian");
        check(e.get(2).ir == null && e.get(2).problem.contains("Kaseikyo"), "Kaseikyo unsupported");
        check(e.get(3).ir != null && e.get(3).ir.isRaw() && e.get(3).ir.data.length == 7, "raw parsed");
        check(e.get(4).ir == null, "junk raw rejected");

        // smoke test over a folder of real files
        if (args.length > 0) {
            int files = 0, buttons = 0, usable = 0;
            TreeMap<String, Integer> skipped = new TreeMap<>();
            try (Stream<Path> s = Files.walk(Paths.get(args[0]))) {
                for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".ir"))::iterator) {
                    files++;
                    for (FlipperIr.Entry en : FlipperIr.parse(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))) {
                        buttons++;
                        if (en.ir != null) {
                            usable++;
                            int[] pt = en.ir.pattern(false);
                            check(pt.length > 1 && Ir.totalUs(pt) < 2_000_000, p + " " + en.name + " pattern");
                        } else {
                            String why = en.problem.replaceAll("[0-9]+", "#");
                            skipped.merge(why, 1, Integer::sum);
                        }
                    }
                }
            }
            System.out.println(files + " files, " + buttons + " buttons, " + usable + " usable; skipped: " + skipped);
        }
        System.out.println(bad == 0 ? "OK: infrared checks passed" : bad + " errors");
        if (bad > 0) System.exit(1);
    }
}
