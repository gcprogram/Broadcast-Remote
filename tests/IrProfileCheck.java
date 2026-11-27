package app.broadcastremote;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Checks infrared profiles (JSON round trip, catalog helpers) on a PC. Needs a real org.json on the class path (for example the
 * Robolectric android-all jar) in addition to the compiled app classes; see README "Tests".
 * Run: java -cp out:org.json-jar app.broadcastremote.IrProfileCheck [path of a Flipper-IRDB paths file]
 */
public class IrProfileCheck {
    private static int bad = 0;

    private static void check(boolean ok, String what) {
        if (!ok) {
            bad++;
            System.out.println("ERROR " + what);
        }
    }

    public static void main(String[] args) throws Exception {
        String flipper = "name: Power\ntype: parsed\nprotocol: Samsung32\naddress: 07 00 00 00\ncommand: 02 00 00 00\n#\n"
                + "name: Vol up\ntype: parsed\nprotocol: NECext\naddress: 04 FB 00 00\ncommand: 1A E5 00 00\n#\n"
                + "name: Vol up\ntype: parsed\nprotocol: NEC\naddress: 00 00 00 00\ncommand: 45 00 00 00\n#\n"
                + "name: Odd\ntype: parsed\nprotocol: Kaseikyo\naddress: 41 54 32 00\ncommand: 05 00 00 00\n#\n"
                + "name: Blue\ntype: raw\nfrequency: 36000\nduty_cycle: 0.330000\ndata: 9000 4500 560 560 560 1690 560\n";
        IrCatalog.Result r = IrCatalog.buildProfile("Test (IR)", "TVs/Samsung/Samsung.ir", FlipperIr.parse(flipper));
        Profile p = r.profile;
        check(p.buttons.size() == 4 && r.skipped == 1 && r.skippedWhy.contains("Kaseikyo"), "built " + p.buttons.size() + " skipped " + r.skipped);
        check(p.buttons.get(1).label.equals("Vol_up") && p.buttons.get(2).label.equals("Vol_up_2"), "unique labels " + p.buttons.get(2).label);
        check(p.isIr() && p.offSilent, "transport");

        String json = p.toJson();
        check(!json.contains("advertising") && !json.contains("payload") && json.contains("\"transport\": \"ir\""), "IR json has no radio parts");
        Profile q = Profile.parse(json);
        check(q.isIr() && q.buttons.size() == 4 && q.name.equals("Test (IR)"), "round trip");
        check(q.buttons.get(0).ir.protocol.equals("Samsung32") && q.buttons.get(0).ir.address == 7 && q.buttons.get(0).ir.command == 2, "Samsung kept");
        check(q.buttons.get(1).ir.address == 0xfb04 && q.buttons.get(1).ir.command == 0xe51a, "NECext kept");
        check(q.buttons.get(3).ir.isRaw() && q.buttons.get(3).ir.carrierHz == 36000 && q.buttons.get(3).ir.data.length == 7, "raw kept");
        check(Arrays.equals(q.buttons.get(0).ir.pattern(false), p.buttons.get(0).ir.pattern(false)), "same pattern after the round trip");
        check(q.toJson().equals(json), "stable JSON");

        // macro in an infrared profile, and bad input
        String macro = json.replace("\"buttons\": [", "\"buttons\": [ {\"code\": \"fa\", \"label\": \"Seq\", \"macro\": [ {\"code\": \"00\", \"holdMs\": 300}, {\"pauseMs\": 500}, {\"code\": \"01\"} ]},");
        Profile m = Profile.parse(macro);
        check(m.button(0xfa).isMacro() && m.button(0xfa).macro.size() == 3, "macro in IR profile");
        for (String broken : new String[]{
                json.replace("\"protocol\": \"NEC\"", "\"protocol\": \"Nope\""),
                json.replaceFirst("\"ir\": \\{[^}]*\\}", "\"x\": 1"),
                json.replace("\"transport\": \"ir\"", "\"transport\": \"smoke\"")}) {
            try {
                Profile.parse(broken);
                check(false, "broken profile accepted");
            } catch (IllegalArgumentException expected) {
                // fine
            }
        }
        // Bluetooth profiles are unchanged
        String ble = "{\"format\":1,\"name\":\"b\",\"advertising\":{\"companyId\":255},\"payload\":{\"type\":\"table\",\"packets\":{\"00\":\"00\",\"01\":\"01\"}},"
                + "\"off\":{\"code\":\"00\"},\"buttons\":[{\"code\":\"01\"}]}";
        check(!Profile.parse(ble).isIr() && Profile.parse(Profile.parse(ble).toJson()).buttons.size() == 1, "BLE profile still works");

        // catalog helpers
        List<String> paths = new ArrayList<>(Arrays.asList("ACs/Daikin/Daikin.ir", "TVs/Samsung/Samsung.ir", "TVs/Samsung/Q80.ir", "TVs/Sony/Sony_X.ir",
                "TVs/Sony/RM-PJ30/ID-1.ir", "LED_Lighting/Airivo/Northern_Lights.ir"));
        java.util.Collections.sort(paths, String.CASE_INSENSITIVE_ORDER);
        List<IrCatalog.Item> top = IrCatalog.children(paths, "");
        check(top.size() == 3 && top.get(0).label.equals("ACs") && top.get(0).folder && top.get(0).path.equals("ACs/"), "top level " + top.size());
        List<IrCatalog.Item> sony = IrCatalog.children(paths, "TVs/Sony/");
        check(sony.size() == 2 && sony.get(0).folder && sony.get(0).label.equals("RM-PJ30") && !sony.get(1).folder && sony.get(1).label.equals("Sony_X"), "folder content " + sony.size());
        check(IrCatalog.search(paths, "samsung tv", 10).size() == 2 && IrCatalog.search(paths, "northern lights", 10).size() == 1, "search");
        check(IrCatalog.defaultName("TVs/Samsung/Samsung.ir").equals("Samsung (IR)"), "name " + IrCatalog.defaultName("TVs/Samsung/Samsung.ir"));
        check(IrCatalog.defaultName("TVs/Sony/RM-PJ30/ID-1.ir").equals("Sony RM-PJ30 ID-1 (IR)"), "name " + IrCatalog.defaultName("TVs/Sony/RM-PJ30/ID-1.ir"));
        check(IrCatalog.defaultName("_Converted_/CSV/A/ABIT/DVD_WINDVD/1_-1.ir").equals("ABIT DVD WINDVD 1 -1 (IR)"), "name " + IrCatalog.defaultName("_Converted_/CSV/A/ABIT/DVD_WINDVD/1_-1.ir"));

        // manufacturer search: the power key of a remote
        List<FlipperIr.Entry> keys = FlipperIr.parse("name: Vol up\ntype: parsed\nprotocol: NEC\naddress: 00 00 00 00\ncommand: 45 00 00 00\n#\n"
                + "name: Power_On\ntype: parsed\nprotocol: NEC\naddress: 00 00 00 00\ncommand: 01 00 00 00\n#\n"
                + "name: ON/OFF\ntype: parsed\nprotocol: NEC\naddress: 00 00 00 00\ncommand: 02 00 00 00\n#\n");
        check(IrCatalog.findPower(keys).name.equals("ON/OFF"), "toggle preferred");
        check(IrCatalog.findPower(keys.subList(0, 2)).name.equals("Power_On"), "plain on key");
        check(IrCatalog.findPower(keys.subList(0, 1)) == null, "no power key");
        List<String> led = new ArrayList<>(Arrays.asList("LED_Lighting/Airivo/A.ir", "LED_Lighting/Airivo/B.ir", "LED_Lighting/Zed/Z.ir", "LED_Lighting/Top.ir", "TVs/Sony/S.ir"));
        check(IrCatalog.manufacturers(led, "LED_Lighting/").size() == 2 && IrCatalog.filesBelow(led, "LED_Lighting/Airivo/").size() == 2, "manufacturers");

        // the real tree answer of the GitHub API, if given
        if (args.length > 0) {
            String tree = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0])), "UTF-8");
            List<String> real = IrCatalog.parseTree(tree);
            check(real.size() > 8000 && !real.get(0).isEmpty(), "real tree " + real.size());
            System.out.println("real catalog: " + real.size() + " files, top level " + IrCatalog.children(real, "").size() + " folders, TVs/ has " + IrCatalog.children(real, "TVs/").size() + " brands");
            String live = IrCatalog.file("TVs/Samsung/Samsung.ir");
            System.out.println("downloaded Samsung.ir: " + FlipperIr.parse(live).size() + " buttons");
            check(FlipperIr.parse(live).size() > 10, "download parses");
        }
        System.out.println(bad == 0 ? "OK: infrared profile checks passed" : bad + " errors");
        if (bad > 0) System.exit(1);
    }
}
