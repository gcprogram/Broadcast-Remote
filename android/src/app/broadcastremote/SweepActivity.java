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
 * Sweep: briefly sends every code of the chosen range (2.5 s, then 1 s off). Whatever the device reacts to
 * is kept with REMEMBER: the code immediately becomes a button of the active profile.
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
        help.setText("Sends the codes of the range one after the other, 2.5 s each, then 1 s off. If the device reacts, "
                + "tap REMEMBER: the code is added as a button of the active profile. The device has to be in "
                + "range and will be running meanwhile.");
        help.setTextSize(13);
        root.addView(help);

        status = new TextView(this);
        status.setPadding(0, 16, 0, 16);
        root.addView(status);

        TextView t1 = new TextView(this);
        t1.setText("Send a single code (hex)");
        t1.setTypeface(null, Typeface.BOLD);
        root.addView(t1);
        LinearLayout one = new LinearLayout(this);
        one.setGravity(Gravity.CENTER_VERTICAL);
        manual = hexField("e.g. 1a");
        one.addView(manual, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button send = Ui.button(this, "Send", v -> {
            Integer c = parse(manual);
            if (c != null) ctl.send(c, "sweep");
        });
        Button off = Ui.button(this, "OFF", v -> ctl.send(Controller.OFF, "sweep"));
        controls.add(send);
        controls.add(off);
        one.addView(send);
        one.addView(off);
        root.addView(one);

        TextView t2 = new TextView(this);
        t2.setText("Sweep (hex)");
        t2.setTypeface(null, Typeface.BOLD);
        t2.setPadding(0, 32, 0, 0);
        root.addView(t2);
        LinearLayout range = new LinearLayout(this);
        range.setGravity(Gravity.CENTER_VERTICAL);
        from = hexField("from");
        from.setText("00");
        to = hexField("to");
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
        Button start = Ui.button(this, "SWEEP", v -> startSweep());
        controls.add(start);
        row.addView(start);
        row.addView(Ui.button(this, "STOP", v -> {
            stopSweep();
            ctl.send(Controller.OFF, "sweep");
        }));
        root.addView(row);
        LinearLayout step = new LinearLayout(this);
        step.addView(Ui.button(this, "< BACK", v -> jump(-1)));
        step.addView(Ui.button(this, "REMEMBER", v -> remember()));
        step.addView(Ui.button(this, "NEXT >", v -> jump(1)));
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
            status.setText("Please enter a hex value 00-ff.");
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
        if (!RemoteService.running && !ctl.isOff()) ctl.send(Controller.OFF, "sweep paused");
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
            status.setText("\"from\" must not be greater than \"to\".");
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
            ctl.send(Controller.OFF, "sweep");
            sweepInfo.setText("Sweep finished");
            return;
        }
        final int v = value;
        sweepInfo.setText(String.format(Locale.ROOT, "0x%02x  (%d)", v, v));
        String r = ctl.send(v, "sweep");
        if (r.startsWith("Error")) {                // e.g. a table profile without this code: go on to the next one
            handler.postDelayed(() -> {
                value = v + 1;
                step();
            }, 300);
            return;
        }
        handler.postDelayed(() -> {
            ctl.send(Controller.OFF, "sweep");
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
            foundList.append(hex).append(" (already exists)  ");
        } else {
            p.buttons.add(new Profile.Btn(value, "", ""));
            ProfileStore.saveActive(this, p);
            foundList.append(hex).append(" (added as a button)  ");
        }
        found.setText("Remembered: " + foundList);
    }

    private void stopSweep() {
        sweeping = false;
        handler.removeCallbacksAndMessages(null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
