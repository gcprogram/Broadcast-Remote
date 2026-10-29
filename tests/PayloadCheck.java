package app.broadcastremote;

/** Prueft die Payload-Engine gegen die aus der Original-App mitgeschnittenen Pakete (ZASJ047, Codes 0-9). */
public class PayloadCheck {
    public static void main(String[] args) {
        String[] tails = {"e5157d", "e49c6c", "e7075e", "e68e4f", "e1313b", "e0b82a", "e32318", "e2aa09", "ed5df1", "ecd4e0"};
        Payload p = Payload.template(Payload.fromHex("6db643ce97fe427c"), 0xe5, new Payload.Crc(0x8408, 0, true, true, 0x157d));
        int bad = 0;
        for (int code = 0; code < tails.length; code++) {
            String got = Payload.hex(p.packet(code));
            String want = "6db643ce97fe427c" + tails[code];
            if (!got.equals(want)) {
                bad++;
                System.out.println("FEHLER code " + code + ": " + got + " != " + want);
            }
        }
        System.out.println(bad == 0 ? "OK: alle 10 Pakete stimmen" : bad + " Fehler");

        // Lernen: aus den 10 Paketen muss die Formel wieder herauskommen, auch fuer nie gesehene Codes
        java.util.Map<Integer, byte[]> capture = new java.util.TreeMap<>();
        for (int code = 0; code < tails.length; code++) capture.put(code, Payload.fromHex("6db643ce97fe427c" + tails[code]));
        Learner.Result r = Learner.learn(capture);
        System.out.println("Learner: " + r.how);
        if (r.payload.isTable()) {
            bad++;
            System.out.println("FEHLER: Learner hat keine Formel gefunden");
        } else {
            for (int code : new int[]{0x0b, 0x18, 0x38, 0xff}) {
                if (!Payload.hex(r.payload.packet(code)).equals(Payload.hex(p.packet(code)))) {
                    bad++;
                    System.out.println("FEHLER: gelernte Formel weicht bei Code " + code + " ab");
                }
            }
        }
        java.util.Map<Integer, byte[]> odd = new java.util.TreeMap<>();
        odd.put(0, Payload.fromHex("aabb01"));
        odd.put(1, Payload.fromHex("11cc77"));
        odd.put(2, Payload.fromHex("5e0042"));
        Learner.Result t = Learner.learn(odd);
        if (!t.payload.isTable() || !Payload.hex(t.payload.packet(1)).equals("11cc77")) {
            bad++;
            System.out.println("FEHLER: Tabellenfall");
        }
        System.out.println(bad == 0 ? "OK: Learner" : bad + " Fehler gesamt");
        System.exit(bad == 0 ? 0 : 1);
    }
}
