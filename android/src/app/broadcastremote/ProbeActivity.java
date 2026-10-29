package app.broadcastremote;

import android.Manifest;
import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Lauschtest: zeigt, ob das Geraet nach einem gueltigen Kommando etwas zurueckfunkt (siehe Probe). */
public class ProbeActivity extends Activity implements Controller.Listener {
    private Controller ctl;
    private Probe probe;
    private TextView status;
    private TextView info;
    private EditText code;
    private Button start;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ctl = Controller.get(this);
        probe = new Probe(this, ctl);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);

        TextView help = new TextView(this);
        help.setText("Misst " + Probe.BASE_S + " s das Funkumfeld (Gerät an, aber ohne Funktion), sendet dann "
                + Probe.TEST_S + " s lang den Code unten und scannt dabei. Gezeigt wird, was neu auftaucht und ob "
                + "sich jemand mit dem Handy verbinden will. Das Gerät läuft währenddessen.");
        help.setTextSize(13);
        root.addView(help);

        status = new TextView(this);
        status.setPadding(0, 16, 0, 16);
        root.addView(status);

        TextView t = new TextView(this);
        t.setText("Code (hex)");
        t.setTypeface(null, Typeface.BOLD);
        root.addView(t);
        code = new EditText(this);
        code.setInputType(InputType.TYPE_CLASS_TEXT);
        Profile p = ctl.profile();
        code.setText(p != null && !p.buttons.isEmpty() ? String.format(Locale.ROOT, "%02x", p.buttons.get(0).code) : "01");
        root.addView(code);

        LinearLayout row = new LinearLayout(this);
        start = Ui.button(this, "LAUSCHTEST", v -> startProbe());
        row.addView(start);
        row.addView(Ui.button(this, "STOPP", v -> {
            probe.cancel();
            ctl.send(Controller.OFF, "lauschtest");
        }));
        root.addView(row);

        info = new TextView(this);
        info.setTextSize(12);
        info.setTypeface(Typeface.MONOSPACE);
        info.setTextIsSelectable(true);
        root.addView(info);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        Ui.requestSend(this);
    }

    private void startProbe() {
        if (!Ui.hasScan(this)) {
            Ui.requestScan(this);
            Toast.makeText(this, "Bitte die Berechtigung erlauben (Geräte in der Nähe bzw. Standort) und den Lauschtest danach nochmal starten.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (Ui.locationOff(this)) {
            info.setText(Ui.LOCATION_HINT);
            return;
        }
        int c;
        try {
            c = Integer.parseInt(code.getText().toString().trim().replaceFirst("^0[xX]", ""), 16);
            if (c < 0 || c > 255) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            info.setText("Ungültiges Datenbyte, bitte hex 00-ff.");
            return;
        }
        String err = probe.start(c, (text, running) -> {
            info.setText(text);
            if (running) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        if (err != null) info.setText(err);
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
        probe.cancel();
        ctl.setListener(null);
        if (!RemoteService.running && !ctl.isOff()) ctl.send(Controller.OFF, "lauschtest pausiert");
    }

    @Override
    public void onState(String text, int active) {
        boolean bt = Controller.bluetoothOn(this);
        status.setText(text);
        status.setTextColor(bt ? 0xFF000000 : 0xFFC62828);
        start.setEnabled(bt);
    }
}
