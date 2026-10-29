package app.broadcastremote;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.location.LocationManager;
import android.provider.Settings;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

/** Kleine Hilfen fuer die programmatisch gebauten Oberflaechen. */
final class Ui {
    private Ui() {
    }

    static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    /** Knopf, der sich im horizontalen LinearLayout die Breite teilt. */
    static Button button(Context c, String text, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return b;
    }

    /** Knopf in voller Breite fuer senkrechte Layouts (Ui.button ist fuer waagerechte Reihen gedacht). */
    static Button wide(Button b) {
        b.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return b;
    }

    /** Knopfbeschriftung: gleich grosses Icon ueber der Beschriftung, ohne Icon nur die Beschriftung. */
    static CharSequence caption(Profile.Btn b) {
        String label = b.caption();
        if (b.icon.isEmpty()) {
            SpannableString s = new SpannableString(label);
            s.setSpan(new AbsoluteSizeSpan(22, true), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return s;
        }
        SpannableString s = new SpannableString(b.icon + "\n" + label);
        s.setSpan(new AbsoluteSizeSpan(30, true), 0, b.icon.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new AbsoluteSizeSpan(14, true), b.icon.length(), s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    static boolean has(Activity a, String permission) {
        return Build.VERSION.SDK_INT < 31 || a.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** Scannen: ab Android 12 BLUETOOTH_SCAN, davor die Standort-Berechtigung. */
    static boolean hasScan(Activity a) {
        if (Build.VERSION.SDK_INT >= 31) return a.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        return Build.VERSION.SDK_INT < 23
                || a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static void requestScan(Activity a) {
        a.requestPermissions(new String[]{Build.VERSION.SDK_INT >= 31 ? Manifest.permission.BLUETOOTH_SCAN
                : Manifest.permission.ACCESS_FINE_LOCATION}, 3);
    }

    /** Vor Android 12 liefert ein Bluetooth-Scan nur dann Ergebnisse, wenn die Ortungsdienste an sind. */
    static boolean locationOff(Activity a) {
        if (Build.VERSION.SDK_INT >= 31) return false;
        if (Build.VERSION.SDK_INT >= 28) return !a.getSystemService(LocationManager.class).isLocationEnabled();
        try {
            return Settings.Secure.getInt(a.getContentResolver(), Settings.Secure.LOCATION_MODE) == 0;
        } catch (Settings.SettingNotFoundException e) {
            return false;
        }
    }

    static final String LOCATION_HINT = "Dieses Android braucht für Bluetooth-Scans eingeschaltete Ortungsdienste "
            + "(Standort). Bitte einschalten und nochmal starten.";

    static void requestSend(Activity a) {
        if (Build.VERSION.SDK_INT >= 31 && !has(a, Manifest.permission.BLUETOOTH_ADVERTISE)) {
            a.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT}, 1);
        }
    }
}
