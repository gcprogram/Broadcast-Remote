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

/** Listen test: shows whether the device sends anything back after a valid command (see Probe). */
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
        help.setText("First measures the radio environment for " + Probe.BASE_S + " s (device on, but without a function), then sends "
                + "the code below for " + Probe.TEST_S + " s while scanning. It shows what newly shows up and whether "
                + "anyone tries to connect to the phone. The device will be running meanwhile.");
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
        start = Ui.button(this, "LISTEN TEST", v -> startProbe());
        row.addView(start);
        row.addView(Ui.button(this, "STOP", v -> {
            probe.cancel();
            ctl.send(Controller.OFF, "listen test");
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
            Toast.makeText(this, "Please allow the permission (nearby devices or location) and start the listen test again.",
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
            info.setText("Invalid data byte, please enter hex 00-ff.");
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
        if (!RemoteService.running && !ctl.isOff()) ctl.send(Controller.OFF, "listen test paused");
    }

    @Override
    public void onState(String text, int active) {
        boolean bt = Controller.bluetoothOn(this);
        status.setText(text);
        status.setTextColor(bt ? 0xFF000000 : 0xFFC62828);
        start.setEnabled(bt);
    }
}
