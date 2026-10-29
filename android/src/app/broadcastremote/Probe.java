package app.broadcastremote;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lauschtest: misst erst das Funkumfeld (Grundrauschen), sendet dann einen Code und scannt dabei weiter. Alles, was waehrend des Sendens neu auftaucht oder sich aendert, wird
 * aufgelistet. Zusaetzlich lauscht ein leerer GATT-Server auf Verbindungsversuche (unser Advertising ist
 * verbindbar). Zeigt, ob das Gerät irgendetwas zurueckfunkt.
 */
final class Probe {
    interface Ui {
        void update(String text, boolean running);
    }

    private static final String TAG = "br-probe";
    static final int BASE_S = 8;
    static final int TEST_S = 12;

    private static final class Dev {
        String addr;
        String name;
        int rssi;
        final Set<String> records = new LinkedHashSet<>();
    }

    private final Context ctx;
    private final Controller ctl;
    private final Handler h = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private ScanCallback scanCb;
    private BluetoothGattServer gatt;
    private Map<String, Dev> base;
    private Map<String, Dev> test;
    private Map<String, Dev> current;
    private final List<String> events = new ArrayList<>();
    private Ui ui;
    private int code;
    private int phase;                 // 0 = aus, 1 = Grundrauschen, 2 = senden + lauschen
    private int left;

    Probe(Context ctx, Controller ctl) {
        this.ctx = ctx;
        this.ctl = ctl;
    }

    boolean running() {
        return phase != 0;
    }

    /** Liefert eine Fehlermeldung oder null. Berechtigung BLUETOOTH_SCAN muss vorher erteilt sein. */
    String start(int code, Ui ui) {
        if (phase != 0) return "Lauschtest läuft schon.";
        BluetoothAdapter adapter = ctx.getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null || !adapter.isEnabled()) return "Bluetooth ist aus.";
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) return "Kein BLE-Scanner verfügbar.";
        this.code = code;
        this.ui = ui;
        base = new LinkedHashMap<>();
        test = new LinkedHashMap<>();
        events.clear();
        current = base;
        scanCb = new ScanCallback() {
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
            scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb);
        } catch (SecurityException e) {
            return "Berechtigung fehlt (Bluetooth-Geräte in der Nähe).";
        }
        phase = 1;
        left = BASE_S;
        tick();
        return null;
    }

    /** Abbrechen; sendet Aus, falls schon gesendet wurde. */
    void cancel() {
        if (phase == 0) return;
        boolean sent = phase == 2;
        finishScan();
        if (sent) ctl.send(Controller.OFF, "lauschtest");
        ui.update("Lauschtest abgebrochen.", false);
    }

    private void abort(String why) {
        if (phase == 0) return;
        boolean sent = phase == 2;
        finishScan();
        if (sent) ctl.send(Controller.OFF, "lauschtest");
        ui.update("Lauschtest abgebrochen: " + why, false);
    }

    private void tick() {
        h.removeCallbacksAndMessages(null);
        if (!Controller.bluetoothOn(ctx)) {
            abort("Bluetooth ist aus.");
            return;
        }
        if (left <= 0) {
            if (phase == 1) {
                startSending();
            } else {
                finish();
            }
            return;
        }
        if (phase == 1) {
            ui.update(String.format("Phase 1/2: Grundrauschen messen, noch %d s ... (%d Geräte) - Gerät bitte AUS lassen",
                    left, base.size()), true);
        } else {
            ui.update(String.format("Phase 2/2: Code 0x%02x wird gesendet, Gerät beobachten. Noch %d s ... (%d Geräte, %d Verbindungsereignisse)",
                    code, left, test.size(), events.size()), true);
        }
        left--;
        h.postDelayed(this::tick, 1000);
    }

    private void startSending() {
        phase = 2;
        left = TEST_S;
        current = test;
        try {
            gatt = ctx.getSystemService(BluetoothManager.class).openGattServer(ctx, new BluetoothGattServerCallback() {
                @Override
                public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
                    String line = "Verbindung " + (newState == 2 ? "AUFGEBAUT" : "getrennt") + " von " + device.getAddress()
                            + " (status " + status + ")";
                    Log.i(TAG, line);
                    h.post(() -> events.add(line));
                }
            });
        } catch (SecurityException | IllegalStateException e) {
            gatt = null;
        }
        String r = ctl.send(code, "lauschtest");
        Log.i(TAG, "senden: " + r);
        if (r.startsWith("Fehler")) {
            abort(r);
            return;
        }
        tick();
    }

    private void finishScan() {
        h.removeCallbacksAndMessages(null);
        try {
            if (scanner != null && scanCb != null) scanner.stopScan(scanCb);
        } catch (SecurityException | IllegalStateException ignored) {
        }
        scanCb = null;
        if (gatt != null) {
            try {
                gatt.close();
            } catch (SecurityException ignored) {
            }
            gatt = null;
        }
        phase = 0;
    }

    private void finish() {
        finishScan();
        ctl.send(Controller.OFF, "lauschtest");
        ui.update(report(), false);
    }

    private void record(ScanResult r) {
        if (current == null) return;
        String addr = r.getDevice().getAddress();
        Dev d = current.get(addr);
        if (d == null) {
            d = new Dev();
            d.addr = addr;
            current.put(addr, d);
        }
        d.rssi = r.getRssi();
        if (d.name == null) {
            try {
                d.name = r.getDevice().getName();
            } catch (SecurityException ignored) {
            }
        }
        ScanRecord sr = r.getScanRecord();
        if (sr != null && sr.getBytes() != null) d.records.add(trimmedHex(sr.getBytes()));
    }

    private static String trimmedHex(byte[] b) {
        int n = b.length;
        while (n > 0 && b[n - 1] == 0) n--;
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append(String.format("%02x", b[i]));
        return s.toString();
    }

    private String report() {
        List<Dev> fresh = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (Dev d : test.values()) {
            Dev before = base.get(d.addr);
            if (before == null) {
                fresh.add(d);
            } else {
                for (String rec : d.records) {
                    if (!before.records.contains(rec)) {
                        changed.add(String.format("%s (%s) %d dBm: %s", d.addr, d.name == null ? "-" : d.name, d.rssi, rec));
                    }
                }
            }
        }
        Collections.sort(fresh, (a, b) -> b.rssi - a.rssi);          // naechste (staerkste) zuerst

        StringBuilder s = new StringBuilder();
        s.append(String.format("Fertig. Grundrauschen: %d Geräte, beim Senden von 0x%02x: %d Geräte.%n", base.size(), code, test.size()));
        s.append("Verbindungsversuche zum Handy: ").append(events.isEmpty() ? "keine" : events.size()).append("\n");
        for (String e : events) s.append("  ").append(e).append("\n");
        s.append("\nNEU beim Senden (").append(fresh.size()).append(", stärkstes Signal zuerst):\n");
        Log.i(TAG, "--- Ergebnis, code " + code);
        int shown = 0;
        for (Dev d : fresh) {
            String line = String.format("%d dBm  %s  %s  %s", d.rssi, d.addr, d.name == null ? "-" : d.name,
                    d.records.isEmpty() ? "" : d.records.iterator().next());
            Log.i(TAG, "NEU " + line);
            if (shown++ < 12) s.append("  ").append(line.length() > 110 ? line.substring(0, 110) + "..." : line).append("\n");
        }
        if (fresh.isEmpty()) s.append("  keine\n");
        s.append("\nGEÄNDERTE Pakete bekannter Geräte (").append(changed.size()).append("):\n");
        shown = 0;
        for (String c : changed) {
            Log.i(TAG, "GEAENDERT " + c);
            if (shown++ < 8) s.append("  ").append(c.length() > 110 ? c.substring(0, 110) + "..." : c).append("\n");
        }
        if (changed.isEmpty()) s.append("  keine\n");
        return s.toString();
    }
}
