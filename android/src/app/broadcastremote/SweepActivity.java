package app.broadcastremote;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Suchlauf: sendet jeden Code des gewaehlten Bereichs kurz (2,5 s, danach 1 s Aus). Was am Geraet reagiert,
 * merkt man mit MERKEN: der Code wird sofort als Knopf im aktiven Profil angelegt.
 */
public class SweepActivity extends Activity implements Controller.Listener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Controller ctl;
    private TextView status;
    private TextView sweepInfo;
    private TextView found;
    private EditText from;
    private EditText to;
    private EditText manual;
    private final List<Button> controls = new ArrayList<>();
    private final StringBuilder foundList = new StringBuilder();
    private boolean sweeping = false;
    private int value = 0;
    private int last = 0xff;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ctl = Controller.get(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);

        TextView help = new TextView(this);
        help.setText("Sendet die Codes des Bereichs nacheinander, je 2,5 s, dann 1 s Aus. Reagiert das Gerät, "
                + "tippe MERKEN: der Code wird als Knopf im aktiven Profil angelegt. Das Gerät muss in "
                + "Reichweite sein und läuft währenddessen.");
        help.setTextSize(13);
        root.addView(help);

        status = new TextView(this);
        status.setPadding(0, 16, 0, 16);
        root.addView(status);

        TextView t1 = new TextView(this);
        t1.setText("Einzelnen Code senden (hex)");
        t1.setTypeface(null, Typeface.BOLD);
        root.addView(t1);
        LinearLayout one = new LinearLayout(this);
        one.setGravity(Gravity.CENTER_VERTICAL);
        manual = hexField("z. B. 1a");
        one.addView(manual, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button send = Ui.button(this, "Senden", v -> {
            Integer c = parse(manual);
            if (c != null) ctl.send(c, "suchlauf");
        });
        Button off = Ui.button(this, "AUS", v -> ctl.send(Controller.OFF, "suchlauf"));
        controls.add(send);
        controls.add(off);
        one.addView(send);
        one.addView(off);
        root.addView(one);

        TextView t2 = new TextView(this);
        t2.setText("Suchlauf (hex)");
        t2.setTypeface(null, Typeface.BOLD);
        t2.setPadding(0, 32, 0, 0);
        root.addView(t2);
        LinearLayout range = new LinearLayout(this);
        range.setGravity(Gravity.CENTER_VERTICAL);
        from = hexField("von");
        from.setText("00");
        to = hexField("bis");
        to.setText("ff");
        range.addView(from, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        range.addView(to, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(range);

        sweepInfo = new TextView(this);
        sweepInfo.setTextSize(28);
        sweepInfo.setGravity(Gravity.CENTER);
        sweepInfo.setPadding(0, 16, 0, 16);
        root.addView(sweepInfo);

        LinearLayout row = new LinearLayout(this);
        Button start = Ui.button(this, "SUCHLAUF", v -> startSweep());
        controls.add(start);
        row.addView(start);
        row.addView(Ui.button(this, "STOPP", v -> {
            stopSweep();
            ctl.send(Controller.OFF, "suchlauf");
        }));
        root.addView(row);
        LinearLayout step = new LinearLayout(this);
        step.addView(Ui.button(this, "< ZURÜCK", v -> jump(-1)));
        step.addView(Ui.button(this, "MERKEN", v -> remember()));
        step.addView(Ui.button(this, "WEITER >", v -> jump(1)));
        root.addView(step);

        found = new TextView(this);
        found.setPadding(0, 16, 0, 0);
        root.addView(found);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        Ui.requestSend(this);
    }

    private EditText hexField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        return e;
    }

    private Integer parse(EditText f) {
        try {
            int v = Integer.parseInt(f.getText().toString().trim().replaceFirst("^0[xX]", ""), 16);
            if (v < 0 || v > 255) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            status.setText("Bitte einen Hexwert 00-ff eingeben.");
            return null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ctl.setListener(this);
        ctl.refreshBluetooth();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopSweep();
        ctl.setListener(null);
        if (!RemoteService.running && !ctl.isOff()) ctl.send(Controller.OFF, "suchlauf pausiert");
    }

    @Override
    public void onState(String text, int active) {
        boolean bt = Controller.bluetoothOn(this);
        if (!bt && sweeping) stopSweep();
        status.setText(text);
        status.setTextColor(bt ? 0xFF000000 : 0xFFC62828);
        for (Button b : controls) b.setEnabled(bt);
    }

    private void startSweep() {
        Integer a = parse(from);
        Integer b = parse(to);
        if (a == null || b == null) return;
        if (a > b) {
            status.setText("\"von\" muss kleiner als \"bis\" sein.");
            return;
        }
        sweeping = true;
        value = a;
        last = b;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        step();
    }

    private void step() {
        handler.removeCallbacksAndMessages(null);
        if (!sweeping) return;
        if (value < 0) value = 0;
        if (value > last) {
            stopSweep();
            ctl.send(Controller.OFF, "suchlauf");
            sweepInfo.setText("Suchlauf zu Ende");
            return;
        }
        final int v = value;
        sweepInfo.setText(String.format(Locale.ROOT, "0x%02x  (%d)", v, v));
        String r = ctl.send(v, "suchlauf");
        if (r.startsWith("Fehler")) {                // z. B. Tabellenprofil ohne diesen Code: weiter zum naechsten
            handler.postDelayed(() -> {
                value = v + 1;
                step();
            }, 300);
            return;
        }
        handler.postDelayed(() -> {
            ctl.send(Controller.OFF, "suchlauf");
            handler.postDelayed(() -> {
                value = v + 1;
                step();
            }, 1000);
        }, 2500);
    }

    private void jump(int delta) {
        if (!sweeping) return;
        value += delta;
        step();
    }

    private void remember() {
        if (!sweeping) return;
        Profile p = ctl.profile();
        if (p == null) return;
        String hex = String.format(Locale.ROOT, "%02x", value);
        if (p.hasButton(value)) {
            foundList.append(hex).append(" (schon vorhanden)  ");
        } else {
            p.buttons.add(new Profile.Btn(value, "", ""));
            ProfileStore.saveActive(this, p);
            foundList.append(hex).append(" (als Knopf angelegt)  ");
        }
        found.setText("Gemerkt: " + foundList);
    }

    private void stopSweep() {
        sweeping = false;
        handler.removeCallbacksAndMessages(null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
