package app.broadcastremote;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Shared sending logic for the buttons, the sweep and the remote control. Everything that determines sending
 * (company ID, service UUIDs, mode, repeat, packet computation, auto-off) comes from the active profile.
 * Safety: after profile.autoOffSeconds without a new command (neither function nor off) it switches off by itself.
 */
final class Controller {
    interface Listener {
        void onState(String text, int active);
    }

    /** Pseudo code for "off"; what is actually sent is profile.offCode. */
    static final int OFF = -1;
    static final String BT_OFF = "Bluetooth is off - buttons and remote control are locked. Please switch Bluetooth on.";
    static final String NO_PROFILE = "No profile loaded - please load a profile under Setup.";

    private static Controller instance;

    static synchronized Controller get(Context c) {
        if (instance == null) instance = new Controller(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Handler timer = new Handler(Looper.getMainLooper());     // repeat, auto-off
    private final Handler commands = new Handler(Looper.getMainLooper());  // commands from other threads
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback callback;
    private Listener listener;
    private int active = OFF;
    private long autoOffAt = 0;
    private String lastText = "Ready.";

    private Controller(Context ctx) {
        this.ctx = ctx;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                refreshBluetooth();
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, filter);
        }
    }

    Profile profile() {
        return ProfileStore.active(ctx);
    }

    void setListener(Listener l) {
        listener = l;
        if (l != null) l.onState(lastText, active);
    }

    boolean isOff() {
        return active == OFF;
    }

    int active() {
        return active;
    }

    /** Seconds left until auto-off, 0 if nothing is running. */
    long autoOffInSeconds() {
        return active == OFF ? 0 : Math.max(0, (autoOffAt - SystemClock.elapsedRealtime() + 999) / 1000);
    }

    static boolean bluetoothOn(Context c) {
        BluetoothAdapter adapter = c.getSystemService(BluetoothManager.class).getAdapter();
        return adapter != null && adapter.isEnabled();
    }

    String describe() {
        if (!bluetoothOn(ctx)) return BT_OFF;
        Profile p = profile();
        if (p == null) return NO_PROFILE;
        String head = "Profile " + p.name + ": ";
        return head + (active == OFF ? "off"
                : String.format("active: code 0x%02x, auto-off in %d s", active, autoOffInSeconds()));
    }

    /** With Bluetooth off: stop everything and report it; clear the message when it is switched on again. */
    void refreshBluetooth() {
        if (!bluetoothOn(ctx)) {
            timer.removeCallbacksAndMessages(null);
            advertiser = null;
            callback = null;
            active = OFF;
            autoOffAt = 0;
            report(BT_OFF);
        } else if (BT_OFF.equals(lastText)) {
            report("Ready.");
        }
    }

    /** Callable from any thread. */
    String sendSync(final int code, final String source) {
        if (Looper.myLooper() == Looper.getMainLooper()) return send(code, source);
        FutureTask<String> task = new FutureTask<>(() -> send(code, source));
        commands.post(task);
        try {
            return task.get(4, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Error: " + e;
        }
    }

    /** Main thread only. OFF or the profile's off code = off (sent briefly), anything else is repeated. */
    String send(int code, String source) {
        timer.removeCallbacksAndMessages(null);
        final Profile p = profile();
        if (p == null) return "Error: " + NO_PROFILE;
        BluetoothAdapter adapter = ctx.getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            refreshBluetooth();
            return "Error: " + BT_OFF;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) return fail("This phone cannot do BLE advertising.");
        final boolean off = code == OFF || (!p.offSilent && code == p.offCode);
        if (off && p.offSilent) {
            stopAdvertising();
            active = OFF;
            autoOffAt = 0;
            return report("OFF  ->  sending stopped (this profile sends no packet for off)  [" + source + "]");
        }
        final int data = off ? p.offCode : code;
        byte[] packet;
        try {
            packet = p.packet(data);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
        String err = burst(p, packet);
        if (err != null) return fail(err);
        if (off) {
            active = OFF;
            autoOffAt = 0;
            timer.postDelayed(() -> {
                stopAdvertising();
                report("Ready.");
            }, p.offSendMs);
        } else {
            active = code;
            autoOffAt = SystemClock.elapsedRealtime() + p.autoOffSeconds * 1000L;
            timer.postDelayed(new Repeat(p, packet), p.repeatMs);
            timer.postDelayed(() -> {
                send(OFF, "auto");
                report("Auto-off: " + p.autoOffSeconds + " s without a new command.");
            }, p.autoOffSeconds * 1000L);
        }
        return report((off ? "OFF" : String.format("Code 0x%02x", code))
                + "  ->  " + hexSpaced(packet) + "  [" + source + "]");
    }

    private final class Repeat implements Runnable {
        private final Profile profile;
        private final byte[] packet;

        Repeat(Profile profile, byte[] packet) {
            this.profile = profile;
            this.packet = packet;
        }

        @Override
        public void run() {
            if (!bluetoothOn(ctx)) {
                refreshBluetooth();
                return;
            }
            String err = burst(profile, packet);
            if (err != null) {
                fail(err);
                return;
            }
            timer.postDelayed(this, profile.repeatMs);
        }
    }

    private String burst(Profile p, byte[] packet) {
        stopAdvertising();
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(p.advertiseMode())
                .setTxPowerLevel(p.txLevel())
                .setConnectable(p.connectable)
                .setTimeout(p.timeoutMs)
                .build();
        AdvertiseData.Builder data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addManufacturerData(p.companyId, packet);
        for (ParcelUuid u : p.uuids()) data.addServiceUuid(u);
        callback = new AdvertiseCallback() {
            @Override
            public void onStartFailure(final int errorCode) {
                timer.post(() -> report("Sending failed, error " + errorCode));
            }
        };
        try {
            advertiser.startAdvertising(settings, data.build(), callback);
        } catch (SecurityException e) {
            return "Permission missing (Bluetooth: nearby devices).";
        } catch (IllegalStateException | IllegalArgumentException e) {
            return "Bluetooth is not ready or the profile is invalid: " + e.getMessage();
        }
        return null;
    }

    private void stopAdvertising() {
        if (advertiser != null && callback != null) {
            try {
                advertiser.stopAdvertising(callback);
            } catch (SecurityException | IllegalStateException ignored) {
            }
        }
        callback = null;
    }

    static String hexSpaced(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x ", x));
        return s.toString().trim();
    }

    private String fail(String text) {
        active = OFF;
        autoOffAt = 0;
        return report("Error: " + text);
    }

    private String report(String text) {
        lastText = text;
        if (listener != null) listener.onState(text, active);
        return text;
    }
}
