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
 * Lern-Modus: hoert zu, waehrend ein ANDERES Geraet die Original-App bedient, und leitet daraus ein Profil ab.
 * Dieses Handy kann seine eigenen Sendungen nicht hoeren und die Sendungen anderer Apps auf demselben Handy
 * nicht lesen - die Kommandos muessen von einem zweiten Geraet kommen.
 *
 * Ablauf: 1. Grundrauschen messen. 2. Erste Funktion aufnehmen (die Gruppe, in der neue Pakete auftauchen,
 * wird gemerkt: Company-ID, UUIDs, Paketlaenge). 3. Weitere Kommandos aufnehmen, jeweils das haeufigste neue
 * Paket der Gruppe. 4. Profil ableiten (Formel, sonst Paketliste) und im Editor pruefen.
 */
public class LearnActivity extends Activity {
    private static final int BASE_S = 6;
    private static final int FIRST_S = 8;
    private static final int STEP_S = 6;

    /** Pakete einer Sendergruppe: gleiche Company-ID, gleiche Service-UUIDs, gleiche Paketlaenge. */
    private static final class Group {
        String key;
        int companyId;
        List<String> uuids = new ArrayList<>();
        boolean connectable = true;
        int total;
        int bestRssi = -127;
        final Map<String, Integer> counts = new HashMap<>();    // Nutzbytes (hex) -> Haeufigkeit
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private final Set<String> base = new HashSet<>();           // "company:payload" aus dem Grundrauschen
    private Map<String, Group> groups = new HashMap<>();
    private Group chosen;
    private final TreeMap<Integer, byte[]> recorded = new TreeMap<>();
    private int phase;                                          // 0 = ruht, 1 = Grundrauschen, 2 = erste Funktion, 3 = weiteres
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
        warn.setText("Ein ANDERES Gerät muss die Bluetooth-Kommandos senden: bediene die Original-App auf einem "
                + "zweiten Handy oder Tablet. Dieses Handy hört nur zu, es kann weder seine eigenen Sendungen noch "
                + "die einer anderen App auf diesem Handy aufnehmen.");
        warn.setTypeface(null, Typeface.BOLD);
        warn.setTextColor(0xFFC62828);
        root.addView(warn);

        TextView help = new TextView(this);
        help.setText("Das sendende Gerät in die Nähe legen (wenige Meter). Die Original-App dort öffnen und das "
                + "Gerät/Profil wählen. Das Gerät selbst muss nicht dabei sein.");
        help.setTextSize(13);
        help.setPadding(0, 16, 0, 16);
        root.addView(help);

        status = new TextView(this);
        status.setPadding(0, 8, 0, 16);
        root.addView(status);

        baseBtn = Ui.button(this, "1. Umgebung messen (" + BASE_S + " s)", v -> startWindow(1, BASE_S));
        root.addView(Ui.wide(baseBtn));
        TextView t1 = new TextView(this);
        t1.setText("Original-App währenddessen nichts senden lassen.");
        t1.setTextSize(12);
        root.addView(t1);

        firstBtn = Ui.button(this, "2. Erste Funktion aufnehmen (" + FIRST_S + " s)", v -> startWindow(2, FIRST_S));
        firstBtn.setEnabled(false);
        root.addView(Ui.wide(firstBtn));
        TextView t2 = new TextView(this);
        t2.setText("Nach dem Tippen in der Original-App die Funktion starten und laufen lassen. Der Code unten ist "
                + "die Nummer, unter der sie gespeichert wird.");
        t2.setTextSize(12);
        root.addView(t2);

        code = new EditText(this);
        code.setHint("Code hex");
        code.setInputType(InputType.TYPE_CLASS_TEXT);
        code.setText("01");
        root.addView(code);

        nextBtn = Ui.button(this, "3. Weiteres Kommando aufnehmen (" + STEP_S + " s)", v -> startWindow(3, STEP_S));
        nextBtn.setEnabled(false);
        root.addView(Ui.wide(nextBtn));
        TextView t3 = new TextView(this);
        t3.setText("Code oben eintragen, nach dem Tippen das Kommando in der Original-App auslösen. Die Nummer ist frei wählbar "
                + "und ändert sich nach jedem Schritt von selbst. Aus hat unten einen eigenen Knopf. Schon aufgenommene Pakete werden übersprungen. "
                + "Hört man dabei nichts Passendes, oder macht das Gerät später etwas anderes: unten \"Aus sendet kein "
                + "Paket\" ankreuzen und Code 00 nicht aufnehmen.");
        t3.setTextSize(12);
        root.addView(t3);

        silentOff = new CheckBox(this);
        silentOff.setText("Aus sendet kein Paket (die Original-App hört bei Aus nur auf zu senden)");
        silentOff.setOnCheckedChangeListener((b, on) -> setButtons(phase == 0));
        root.addView(silentOff);

        offBtn = Ui.button(this, "Aus aufnehmen (Code 00, " + STEP_S + " s)", v -> {
            code.setText("00");
            startWindow(3, STEP_S);
        });
        offBtn.setEnabled(false);
        root.addView(Ui.wide(offBtn));
        TextView t4 = new TextView(this);
        t4.setText("Nach dem Tippen in der Original-App Aus drücken. Aus wird nicht automatisch vorgeschlagen.");
        t4.setTextSize(12);
        root.addView(t4);

        recordedView = new TextView(this);
        recordedView.setTypeface(Typeface.MONOSPACE);
        recordedView.setTextSize(12);
        recordedView.setTextIsSelectable(true);
        recordedView.setPadding(0, 24, 0, 8);
        root.addView(recordedView);

        name = new EditText(this);
        name.setHint("Profilname");
        name.setText("Gelerntes Profil");
        root.addView(name);
        LinearLayout row = new LinearLayout(this);
        deriveBtn = Ui.button(this, "4. Profil ableiten", v -> derive());
        deriveBtn.setEnabled(false);
        row.addView(deriveBtn);
        row.addView(Ui.button(this, "Neu beginnen", v -> reset()));
        root.addView(row);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        show("Bereit. Schritt 1: Umgebung messen.");
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

    // ---------------------------------------------------------------- Scan

    private void startWindow(int newPhase, int seconds) {
        if (phase != 0) return;
        BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            show(Controller.BT_OFF);
            return;
        }
        if (!Ui.hasScan(this)) {
            Ui.requestScan(this);
            show("Bitte die Berechtigung erlauben (Geräte in der Nähe bzw. Standort) und den Schritt danach nochmal starten.");
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
            show("Kein BLE-Scanner verfügbar.");
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
                abort("Scan fehlgeschlagen, Fehler " + errorCode);
            }
        };
        try {
            scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback);
        } catch (SecurityException e) {
            show("Berechtigung fehlt (Geräte in der Nähe).");
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
            abort("Bluetooth ist aus.");
            return;
        }
        if (left <= 0) {
            endWindow();
            return;
        }
        String what = phase == 1 ? "Umgebung messen, Original-App ruhen lassen"
                : phase == 2 ? "jetzt in der Original-App die erste Funktion starten"
                : String.format(Locale.ROOT, "jetzt in der Original-App Kommando für Code %02x auslösen", currentCode());
        show(String.format(Locale.ROOT, "Noch %d s: %s. (%d Sender-Gruppen gehört)", left, what, groups.size()));
        left--;
        h.postDelayed(this::tick, 1000);
    }

    private void abort(String why) {
        stopScan();
        phase = 0;
        h.removeCallbacksAndMessages(null);
        setButtons(true);
        show("Abgebrochen: " + why);
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

    /** 0000xxxx-0000-1000-8000-00805f9b34fb -> xxxx, sonst die volle UUID. */
    private static String shortUuid(String u) {
        String s = u.toLowerCase(Locale.ROOT);
        if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) return s.substring(4, 8);
        return s;
    }

    // ---------------------------------------------------------------- Auswertung

    private void endWindow() {
        stopScan();
        int finished = phase;
        phase = 0;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setButtons(true);
        if (finished == 1) {
            baseDone = true;
            setButtons(true);
            show("Grundrauschen: " + base.size() + " verschiedene Pakete. Jetzt Schritt 2.");
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
            show("Nichts Neues gehört. Sendet das andere Gerät, ist es in der Nähe und die Funktion gestartet? "
                    + "Dann Schritt 2 wiederholen.");
            return;
        }
        chosen = best;
        String payload = topNew(best);
        recorded.put(currentCode(), Payload.fromHex(payload));
        show(String.format(Locale.ROOT, "Sender gefunden: Company 0x%04x, UUIDs %s, %d Bytes, %d dBm. Code %02x gespeichert.",
                best.companyId, best.uuids, payload.length() / 2, best.bestRssi, currentCode()));
        suggestNext();
    }

    private void addToChosen() {
        if (chosen == null) {
            show("Erst Schritt 2.");
            return;
        }
        Group g = groups.get(chosen.key);
        String payload = g == null ? null : topNew(g);
        if (payload == null) {
            show("Kein neues Paket vom gewählten Sender gehört (schon aufgenommene werden übersprungen). Nochmal versuchen.");
            return;
        }
        recorded.put(currentCode(), Payload.fromHex(payload));
        Integer heard = g.counts.get(payload);
        show(String.format(Locale.ROOT, "Code %02x gespeichert (%d mal gehört).%s", currentCode(), heard == null ? 0 : heard,
                currentCode() == 0 && !silentOff.isChecked() && heard != null && heard < 3
                        ? " Achtung: nur selten gehört, vielleicht sendet Aus gar kein Paket (dann \"Aus sendet kein Paket\" ankreuzen und 00 neu beginnen)." : ""));
        suggestNext();
    }

    /** Haeufigstes Paket der Gruppe, das nicht schon aufgenommen wurde (und beim ersten Schritt nicht im Grundrauschen war). */
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
        int[] order = {1, 2, 3, 4, 5, 6, 7, 8, 9};      // Aus (00) nie vorschlagen: dafuer gibt es einen eigenen Knopf
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
            show("Bitte den Code als Hexwert 00-ff eintragen.");
            return false;
        }
    }

    private void refreshList() {
        StringBuilder s = new StringBuilder();
        if (recorded.isEmpty()) {
            s.append("Noch nichts aufgenommen.");
        } else {
            s.append("Aufgenommen:\n");
            for (Map.Entry<Integer, byte[]> e : recorded.entrySet()) {
                s.append(String.format(Locale.ROOT, "  %02x%s  %s\n", e.getKey(), e.getKey() == 0 ? " (Aus)" : "",
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
        show("Neu begonnen. Schritt 1: Umgebung messen.");
    }

    // ---------------------------------------------------------------- Profil ableiten

    private void derive() {
        boolean silent = silentOff.isChecked();
        if (chosen == null || (!silent && !recorded.containsKey(0))) {
            show("Aufgenommen werden muss mindestens Aus (Code 00) und eine Funktion, oder \"Aus sendet kein Paket\" ankreuzen.");
            return;
        }
        TreeMap<Integer, byte[]> usable = new TreeMap<>(recorded);
        if (silent) usable.remove(0);                           // ein evtl. aufgenommenes 00 war kein echtes Aus-Paket
        if (usable.isEmpty()) {
            show("Es fehlt mindestens eine Funktion außer 00.");
            return;
        }
        Learner.Result r = Learner.learn(usable);
        Profile p = new Profile();
        p.name = name.getText().toString().trim().isEmpty() ? "Gelerntes Profil" : name.getText().toString().trim();
        p.description = "Gelernt aus Mitschnitt: " + r.how;
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
            show("Profil abgeleitet: " + r.how + ". Im Editor prüfen und speichern.");
        } catch (RuntimeException e) {
            show("Ableiten fehlgeschlagen: " + e.getMessage());
        }
    }
}
