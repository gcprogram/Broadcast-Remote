package app.broadcastremote;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.text.InputType;
import android.util.SparseArray;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Learn mode: listens while ANOTHER device operates the original app and derives a profile from that.
 * This phone cannot hear its own broadcasts and cannot read the broadcasts of other apps on the same phone,
 * so the commands have to come from a second device.
 *
 * Steps: 1. Measure the background noise. 2. Record the first function (the group in which new packets
 * show up is remembered: company ID, UUIDs, packet length). 3. Record further commands, each time the most
 * frequent new packet of that group. 4. Derive the profile (formula, otherwise packet table) and check it in the editor.
 */
public class LearnActivity extends Activity {
    private static final int BASE_S = 6;
    private static final int FIRST_S = 8;
    private static final int STEP_S = 6;

    /** Packets of one group of senders: same company ID, same service UUIDs, same packet length. */
    private static final class Group {
        String key;
        int companyId;
        List<String> uuids = new ArrayList<>();
        boolean connectable = true;
        int total;
        int bestRssi = -127;
        final Map<String, Integer> counts = new HashMap<>();    // payload bytes (hex) -> frequency
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private final Set<String> base = new HashSet<>();           // "company:payload" from the background noise
    private Map<String, Group> groups = new HashMap<>();
    private Group chosen;
    private final TreeMap<Integer, byte[]> recorded = new TreeMap<>();
    private int phase;                                          // 0 = idle, 1 = background noise, 2 = first function, 3 = further
    private int left;
    private boolean baseDone;

    private TextView status;
    private TextView recordedView;
    private EditText code;
    private EditText name;
    private Button baseBtn;
    private Button firstBtn;
    private Button nextBtn;
    private Button offBtn;
    private Button deriveBtn;
    private CheckBox silentOff;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);

        TextView warn = new TextView(this);
        warn.setText("ANOTHER device has to send the Bluetooth commands: operate the original app on a "
                + "second phone or tablet. This phone only listens; it can record neither its own broadcasts nor "
                + "those of another app on this phone.");
        warn.setTypeface(null, Typeface.BOLD);
        warn.setTextColor(0xFFC62828);
        root.addView(warn);

        TextView help = new TextView(this);
        help.setText("Put the sending device close by (a few metres). Open the original app there and choose the "
                + "device/profile. The device itself does not have to be present.");
        help.setTextSize(13);
        help.setPadding(0, 16, 0, 16);
        root.addView(help);

        status = new TextView(this);
        status.setPadding(0, 8, 0, 16);
        root.addView(status);

        baseBtn = Ui.button(this, "1. Measure surroundings (" + BASE_S + " s)", v -> startWindow(1, BASE_S));
        root.addView(Ui.wide(baseBtn));
        TextView t1 = new TextView(this);
        t1.setText("Do not let the original app send anything meanwhile.");
        t1.setTextSize(12);
        root.addView(t1);

        firstBtn = Ui.button(this, "2. Record first function (" + FIRST_S + " s)", v -> startWindow(2, FIRST_S));
        firstBtn.setEnabled(false);
        root.addView(Ui.wide(firstBtn));
        TextView t2 = new TextView(this);
        t2.setText("After tapping, start the function in the original app and let it run. The code below is "
                + "the number under which it is stored.");
        t2.setTextSize(12);
        root.addView(t2);

        code = new EditText(this);
        code.setHint("Code (hex)");
        code.setInputType(InputType.TYPE_CLASS_TEXT);
        code.setText("01");
        root.addView(code);

        nextBtn = Ui.button(this, "3. Record another command (" + STEP_S + " s)", v -> startWindow(3, STEP_S));
        nextBtn.setEnabled(false);
        root.addView(Ui.wide(nextBtn));
        TextView t3 = new TextView(this);
        t3.setText("Enter the code above, then trigger the command in the original app after tapping. The number is free to choose "
                + "and changes by itself after each step. Off has its own button below. Packets that are already recorded are skipped. "
                + "If nothing suitable is heard for off, or the device does something else later: tick \"Off sends no "
                + "packet\" below and do not record code 00.");
        t3.setTextSize(12);
        root.addView(t3);

        silentOff = new CheckBox(this);
        silentOff.setText("Off sends no packet (the original app just stops sending for off)");
        silentOff.setOnCheckedChangeListener((b, on) -> setButtons(phase == 0));
        root.addView(silentOff);

        offBtn = Ui.button(this, "Record off (code 00, " + STEP_S + " s)", v -> {
            code.setText("00");
            startWindow(3, STEP_S);
        });
        offBtn.setEnabled(false);
        root.addView(Ui.wide(offBtn));
        TextView t4 = new TextView(this);
        t4.setText("After tapping, press off in the original app. Off is never suggested automatically.");
        t4.setTextSize(12);
        root.addView(t4);

        recordedView = new TextView(this);
        recordedView.setTypeface(Typeface.MONOSPACE);
        recordedView.setTextSize(12);
        recordedView.setTextIsSelectable(true);
        recordedView.setPadding(0, 24, 0, 8);
        root.addView(recordedView);

        name = new EditText(this);
        name.setHint("Profile name");
        name.setText("Learned profile");
        root.addView(name);
        LinearLayout row = new LinearLayout(this);
        deriveBtn = Ui.button(this, "4. Derive profile", v -> derive());
        deriveBtn.setEnabled(false);
        row.addView(deriveBtn);
        row.addView(Ui.button(this, "Start over", v -> reset()));
        root.addView(row);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        show("Ready. Step 1: measure the surroundings.");
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopScan();
        phase = 0;
        h.removeCallbacksAndMessages(null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void show(String text) {
        status.setText(text);
    }

    // ---------------------------------------------------------------- scan

    private void startWindow(int newPhase, int seconds) {
        if (phase != 0) return;
        BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            show(Controller.BT_OFF);
            return;
        }
        if (!Ui.hasScan(this)) {
            Ui.requestScan(this);
            show("Please allow the permission (nearby devices or location) and start the step again.");
            return;
        }
        if (Ui.locationOff(this)) {
            show(Ui.LOCATION_HINT);
            return;
        }
        if (newPhase == 3 && !parseCode()) return;
        if (newPhase == 2 && !parseCode()) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            show("No BLE scanner available.");
            return;
        }
        groups = new HashMap<>();
        callback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                record(r);
            }

            @Override public void onBatchScanResults(List<ScanResult> rs) {
                for (ScanResult r : rs) record(r);
            }

            @Override public void onScanFailed(int errorCode) {
                abort("Scan failed, error " + errorCode);
            }
        };
        try {
            scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback);
        } catch (SecurityException e) {
            show("Permission missing (nearby devices).");
            return;
        }
        phase = newPhase;
        left = seconds;
        setButtons(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        tick();
    }

    private void tick() {
        h.removeCallbacksAndMessages(null);
        if (!Controller.bluetoothOn(this)) {
            abort("Bluetooth is off.");
            return;
        }
        if (left <= 0) {
            endWindow();
            return;
        }
        String what = phase == 1 ? "measuring the surroundings, keep the original app idle"
                : phase == 2 ? "now start the first function in the original app"
                : String.format(Locale.ROOT, "now trigger the command for code %02x in the original app", currentCode());
        show(String.format(Locale.ROOT, "%d s left: %s. (%d sender groups heard)", left, what, groups.size()));
        left--;
        h.postDelayed(this::tick, 1000);
    }

    private void abort(String why) {
        stopScan();
        phase = 0;
        h.removeCallbacksAndMessages(null);
        setButtons(true);
        show("Aborted: " + why);
    }

    private void stopScan() {
        try {
            if (scanner != null && callback != null) scanner.stopScan(callback);
        } catch (SecurityException | IllegalStateException ignored) {
        }
        callback = null;
    }

    private void record(ScanResult r) {
        ScanRecord sr = r.getScanRecord();
        if (sr == null) return;
        SparseArray<byte[]> mfg = sr.getManufacturerSpecificData();
        List<String> uuids = new ArrayList<>();
        if (sr.getServiceUuids() != null) {
            for (ParcelUuid u : sr.getServiceUuids()) uuids.add(shortUuid(u.toString()));
        }
        java.util.Collections.sort(uuids);
        for (int i = 0; i < mfg.size(); i++) {
            int company = mfg.keyAt(i);
            byte[] payload = mfg.valueAt(i);
            String hex = Payload.hex(payload);
            if (phase == 1) base.add(company + ":" + hex);
            String key = company + "|" + uuids + "|" + payload.length;
            Group g = groups.get(key);
            if (g == null) {
                g = new Group();
                g.key = key;
                g.companyId = company;
                g.uuids = uuids;
                groups.put(key, g);
            }
            g.connectable = r.isConnectable();
            g.total++;
            g.bestRssi = Math.max(g.bestRssi, r.getRssi());
            Integer n = g.counts.get(hex);
            g.counts.put(hex, n == null ? 1 : n + 1);
        }
    }

    /** 0000xxxx-0000-1000-8000-00805f9b34fb -> xxxx, otherwise the full UUID. */
    private static String shortUuid(String u) {
        String s = u.toLowerCase(Locale.ROOT);
        if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) return s.substring(4, 8);
        return s;
    }

    // ---------------------------------------------------------------- evaluation

    private void endWindow() {
        stopScan();
        int finished = phase;
        phase = 0;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setButtons(true);
        if (finished == 1) {
            baseDone = true;
            setButtons(true);
            show("Background noise: " + base.size() + " different packets. Now step 2.");
        } else if (finished == 2) {
            chooseGroup();
        } else {
            addToChosen();
        }
        refreshList();
    }

    private void chooseGroup() {
        Group best = null;
        int bestScore = 0;
        for (Group g : groups.values()) {
            int score = 0;
            for (Map.Entry<String, Integer> e : g.counts.entrySet()) {
                if (!base.contains(g.companyId + ":" + e.getKey())) score += e.getValue();
            }
            if (score > bestScore) {
                bestScore = score;
                best = g;
            }
        }
        if (best == null) {
            show("Nothing new heard. Is the other device sending, is it close by and has the function been started? "
                    + "Then repeat step 2.");
            return;
        }
        chosen = best;
        String payload = topNew(best);
        recorded.put(currentCode(), Payload.fromHex(payload));
        show(String.format(Locale.ROOT, "Sender found: company 0x%04x, UUIDs %s, %d bytes, %d dBm. Code %02x stored.",
                best.companyId, best.uuids, payload.length() / 2, best.bestRssi, currentCode()));
        suggestNext();
    }

    private void addToChosen() {
        if (chosen == null) {
            show("Step 2 first.");
            return;
        }
        Group g = groups.get(chosen.key);
        String payload = g == null ? null : topNew(g);
        if (payload == null) {
            show("No new packet heard from the chosen sender (packets already recorded are skipped). Try again.");
            return;
        }
        recorded.put(currentCode(), Payload.fromHex(payload));
        Integer heard = g.counts.get(payload);
        show(String.format(Locale.ROOT, "Code %02x stored (heard %d times).%s", currentCode(), heard == null ? 0 : heard,
                currentCode() == 0 && !silentOff.isChecked() && heard != null && heard < 3
                        ? " Note: heard only rarely, off may not send a packet at all (then tick \"Off sends no packet\" and record 00 again)." : ""));
        suggestNext();
    }

    /** Most frequent packet of the group that has not been recorded yet (and was not part of the background noise). */
    private String topNew(Group g) {
        String best = null;
        int bestN = 0;
        for (Map.Entry<String, Integer> e : g.counts.entrySet()) {
            byte[] bytes = Payload.fromHex(e.getKey());
            boolean known = false;
            for (byte[] r : recorded.values()) known |= java.util.Arrays.equals(r, bytes);
            if (known || base.contains(g.companyId + ":" + e.getKey())) continue;
            if (e.getValue() > bestN) {
                bestN = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    private void suggestNext() {
        int[] order = {1, 2, 3, 4, 5, 6, 7, 8, 9};      // never suggest off (00): it has its own button
        int next = -1;
        for (int c : order) {
            if (!recorded.containsKey(c)) {
                next = c;
                break;
            }
        }
        if (next < 0) {
            next = recorded.lastKey() + 1;
            while (recorded.containsKey(next)) next++;
            if (next > 255) next = 1;
        }
        code.setText(String.format(Locale.ROOT, "%02x", next & 0xff));
    }

    private int currentCode() {
        try {
            return Integer.parseInt(code.getText().toString().trim().replaceFirst("^0[xX]", ""), 16) & 0xff;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean parseCode() {
        try {
            int v = Integer.parseInt(code.getText().toString().trim().replaceFirst("^0[xX]", ""), 16);
            if (v < 0 || v > 255) throw new NumberFormatException();
            return true;
        } catch (NumberFormatException e) {
            show("Please enter the code as a hex value 00-ff.");
            return false;
        }
    }

    private void refreshList() {
        StringBuilder s = new StringBuilder();
        if (recorded.isEmpty()) {
            s.append("Nothing recorded yet.");
        } else {
            s.append("Recorded:\n");
            for (Map.Entry<Integer, byte[]> e : recorded.entrySet()) {
                s.append(String.format(Locale.ROOT, "  %02x%s  %s\n", e.getKey(), e.getKey() == 0 ? " (off)" : "",
                        Controller.hexSpaced(e.getValue())));
            }
        }
        recordedView.setText(s.toString());
        setButtons(phase == 0);
    }

    private void setButtons(boolean idle) {
        baseBtn.setEnabled(idle);
        firstBtn.setEnabled(idle && baseDone && chosen == null);
        nextBtn.setEnabled(idle && chosen != null);
        offBtn.setEnabled(idle && chosen != null);
        deriveBtn.setEnabled(idle && chosen != null && (silentOff.isChecked() ? !recorded.isEmpty() : recorded.containsKey(0) && recorded.size() >= 2));
    }

    private void reset() {
        if (phase != 0) return;
        base.clear();
        recorded.clear();
        chosen = null;
        baseDone = false;
        code.setText("01");
        refreshList();
        show("Started over. Step 1: measure the surroundings.");
    }

    // ---------------------------------------------------------------- derive the profile

    private void derive() {
        boolean silent = silentOff.isChecked();
        if (chosen == null || (!silent && !recorded.containsKey(0))) {
            show("At least off (code 00) and one function must be recorded, or tick \"Off sends no packet\".");
            return;
        }
        TreeMap<Integer, byte[]> usable = new TreeMap<>(recorded);
        if (silent) usable.remove(0);                           // a recorded 00 was not a real off packet
        if (usable.isEmpty()) {
            show("At least one function besides 00 is missing.");
            return;
        }
        Learner.Result r = Learner.learn(usable);
        Profile p = new Profile();
        p.name = name.getText().toString().trim().isEmpty() ? "Learned profile" : name.getText().toString().trim();
        p.description = "Learned from a capture: " + r.how;
        p.companyId = chosen.companyId;
        p.serviceUuids = new ArrayList<>(chosen.uuids);
        p.connectable = chosen.connectable;
        p.payload = r.payload;
        p.offCode = 0;
        p.offSilent = silent;
        for (int c : recorded.keySet()) {
            if (usable.containsKey(c) && c != 0) p.buttons.add(new Profile.Btn(c, "", ""));
        }
        try {
            startActivity(new Intent(this, ProfileEditorActivity.class)
                    .putExtra(ProfileEditorActivity.EXTRA_JSON, p.toJson())
                    .putExtra(ProfileEditorActivity.EXTRA_NEW, true));
            show("Profile derived: " + r.how + ". Check it in the editor and save.");
        } catch (RuntimeException e) {
            show("Deriving failed: " + e.getMessage());
        }
    }
}
