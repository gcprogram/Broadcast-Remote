package app.broadcastremote;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Main screen: just the status, OFF and the grid of buttons of the active profile. Everything else is in the menu. */
public class MainActivity extends Activity implements Controller.Listener {
    private static final int COLUMNS = 3;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private Controller ctl;
    private TextView profileName;
    private TextView status;
    private TextView autoInfo;
    private LinearLayout grid;
    private final Map<Integer, Button> cells = new HashMap<>();
    private final List<Button> controls = new ArrayList<>();      // everything that only sends while Bluetooth is on

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ctl = Controller.get(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);

        LinearLayout top = new LinearLayout(this);
        profileName = new TextView(this);
        profileName.setTextSize(20);
        profileName.setTypeface(null, Typeface.BOLD);
        top.addView(profileName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button menu = Ui.button(this, "Menu", null);
        menu.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        menu.setOnClickListener(v -> showMenu(menu));
        top.addView(menu);
        root.addView(top);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, 16, 0, 0);
        root.addView(status);
        autoInfo = new TextView(this);
        autoInfo.setTextSize(13);
        root.addView(autoInfo);

        Button off = Ui.button(this, "OFF", v -> ctl.send(Controller.OFF, "app"));
        off.setTextSize(20);
        controls.add(off);
        root.addView(Ui.wide(off));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        root.addView(grid);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        Ui.requestSend(this);
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Edit buttons");
        m.getMenu().add(0, 2, 1, "Sweep");
        m.getMenu().add(0, 3, 2, "Listen test");
        m.getMenu().add(0, 6, 3, "Learn (profile from another device)");
        m.getMenu().add(0, 4, 4, "Remote control");
        m.getMenu().add(0, 5, 5, "Setup (profiles)");
        m.setOnMenuItemClickListener(item -> {
            Class<?> target;
            switch (item.getItemId()) {
                case 1: target = EditActivity.class; break;
                case 2: target = SweepActivity.class; break;
                case 3: target = ProbeActivity.class; break;
                case 4: target = RemoteActivity.class; break;
                case 6: target = LearnActivity.class; break;
                default: target = SetupActivity.class;
            }
            startActivity(new Intent(this, target));
            return true;
        });
        m.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuildGrid();
        ctl.setListener(this);
        ctl.refreshBluetooth();
        ticker.post(new Runnable() {
            @Override public void run() {
                long s = ctl.autoOffInSeconds();
                autoInfo.setText(ctl.isOff() ? "" : "Auto-off in " + s + " s");
                ticker.postDelayed(this, 1000);
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticker.removeCallbacksAndMessages(null);
        ctl.setListener(null);
        // without a running remote control, do not keep sending in the background
        if (!RemoteService.running && !ctl.isOff()) ctl.send(Controller.OFF, "app paused");
    }

    @Override
    public void onState(String text, int active) {
        boolean bt = Controller.bluetoothOn(this);
        status.setText(text);
        status.setTextColor(bt ? 0xFF000000 : 0xFFC62828);
        status.setTypeface(null, bt ? Typeface.NORMAL : Typeface.BOLD);
        for (Button b : controls) b.setEnabled(bt);
        for (Map.Entry<Integer, Button> e : cells.entrySet()) {
            e.getValue().setEnabled(bt);
            e.getValue().setBackgroundTintList(e.getKey() == active && active != Controller.OFF
                    ? ColorStateList.valueOf(0xFF8BC34A) : null);
        }
    }

    private void rebuildGrid() {
        grid.removeAllViews();
        cells.clear();
        Profile p = ctl.profile();
        profileName.setText(p == null ? "No profile" : p.name);
        if (p == null) {
            onState(Controller.NO_PROFILE, Controller.OFF);
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < p.buttons.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                grid.addView(row);
            }
            final Profile.Btn b = p.buttons.get(i);
            Button v = new Button(this);
            v.setAllCaps(false);
            v.setText(Ui.caption(b));
            v.setOnClickListener(x -> ctl.send(ctl.active() == b.code ? Controller.OFF : b.code, "app"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 88), 1);
            lp.setMargins(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
            v.setLayoutParams(lp);
            row.addView(v);
            cells.put(b.code, v);
        }
        if (row != null) {                          // pad the last row so that all buttons have the same width
            for (int i = p.buttons.size(); i % COLUMNS != 0; i++) {
                View pad = new View(this);
                pad.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(this, 88), 1));
                row.addView(pad);
            }
        }
        onState(ctl.describe(), ctl.active());
    }
}
