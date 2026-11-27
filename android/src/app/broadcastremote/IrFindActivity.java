package app.broadcastremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.hardware.ConsumerIrManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Find the manufacturer of a device by trying the power key: for one remote of every manufacturer of a device type (for example
 * LED_Lighting) the power key is sent through the infrared emitter and the manufacturer is shown. If the device reacts, its
 * manufacturer is known and the remotes of that manufacturer can be tried in the catalog. Manufacturers go by hand (Next) or
 * automatically every few seconds.
 */
public class IrFindActivity extends Activity {
    static final String EXTRA_CLASS = "class";
    private static final int[] SPEEDS = {2, 3, 5};
    private static final int FILES_PER_BRAND = 4;            // remotes of a manufacturer tried to find a power key

    /** What is sent for a manufacturer. */
    private static final class Candidate {
        String file = "";
        String button = "";
        Ir ir;                                                // null: this manufacturer has no usable power key
    }

    private String classFolder = "";
    private List<String> paths = new ArrayList<>();
    private final List<IrCatalog.Item> brands = new ArrayList<>();
    private final Map<Integer, Candidate> loaded = new HashMap<>();          // main thread only
    private final Set<Integer> loading = new HashSet<>();
    private final Map<Integer, List<Runnable>> waiting = new HashMap<>();
    private final List<Integer> history = new ArrayList<>();                 // the manufacturers shown so far, in order
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ExecutorService irThread = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler timer = new Handler(Looper.getMainLooper());      // only the automatic steps

    private int cur = -1;
    private int generation;
    private boolean auto;
    private int speed = 1;
    private Candidate shown;

    private TextView brandView;
    private TextView posView;
    private TextView infoView;
    private TextView noteView;
    private Button autoButton;
    private Button speedButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        classFolder = getIntent().getStringExtra(EXTRA_CLASS);
        if (classFolder == null) classFolder = "";
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 16));

        TextView help = new TextView(this);
        help.setText("Aim the infrared emitter of the phone (top edge) at the device. For each manufacturer of \""
                + classFolder.replace("/", "") + "\" the power key of one remote is sent and the manufacturer is shown. "
                + "When the device reacts, tap \"It reacted\".");
        help.setTextSize(13);
        root.addView(help);

        brandView = new TextView(this);
        brandView.setTextSize(28);
        brandView.setGravity(Gravity.CENTER);
        brandView.setPadding(0, Ui.dp(this, 28), 0, Ui.dp(this, 4));
        brandView.setText("Loading...");
        root.addView(brandView);
        posView = new TextView(this);
        posView.setTextSize(13);
        posView.setGravity(Gravity.CENTER);
        root.addView(posView);
        infoView = new TextView(this);
        infoView.setTextSize(13);
        infoView.setGravity(Gravity.CENTER);
        infoView.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 20));
        root.addView(infoView);

        LinearLayout nav = new LinearLayout(this);
        nav.addView(weighted(Ui.button(this, "◀ Previous", v -> step(-1))));
        nav.addView(weighted(Ui.button(this, "Send again", v -> sendAgain())));
        nav.addView(weighted(Ui.button(this, "Next ▶", v -> step(1))));
        root.addView(nav);

        LinearLayout autoRow = new LinearLayout(this);
        autoButton = weighted(Ui.button(this, "", v -> toggleAuto()));
        speedButton = Ui.button(this, "", v -> {
            speed = (speed + 1) % SPEEDS.length;
            updateAutoButtons();
        });
        autoRow.addView(autoButton);
        autoRow.addView(speedButton);
        root.addView(autoRow);

        Button reacted = Ui.button(this, "It reacted!", v -> reacted());
        reacted.setTextSize(18);
        root.addView(reacted, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 64)));

        noteView = new TextView(this);
        noteView.setTextSize(13);
        noteView.setPadding(0, Ui.dp(this, 12), 0, 0);
        root.addView(noteView);
        setContentView(root);
        updateAutoButtons();

        if (!Controller.irAvailable(this)) {
            noteView.setText("This phone has no infrared emitter: nothing can be sent.");
        }
        net.execute(() -> {
            String error = null;
            List<String> all = null;
            try {
                all = IrCatalog.index(this, false);
            } catch (IOException e) {
                error = e.getMessage();
            }
            final List<String> result = all;
            final String message = error;
            ui.post(() -> {
                if (result == null) {
                    brandView.setText("-");
                    noteView.setText("Could not load the list: " + message);
                    return;
                }
                paths = result;
                brands.addAll(IrCatalog.manufacturers(paths, classFolder));
                if (brands.isEmpty()) {
                    brandView.setText("-");
                    noteView.setText("This device type has no manufacturer folders.");
                    return;
                }
                go(0, 1);
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        timer.removeCallbacksAndMessages(null);
        net.shutdownNow();
        irThread.shutdownNow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAuto();
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    private Button weighted(Button b) {
        b.setLayoutParams(weight());
        b.setAllCaps(false);
        return b;
    }

    private void updateAutoButtons() {
        autoButton.setText(auto ? "■ Stop automatic" : "▶ Automatic");
        speedButton.setText("every " + SPEEDS[speed] + " s");
    }

    private void toggleAuto() {
        if (auto) {
            stopAuto();
            return;
        }
        if (brands.isEmpty()) return;
        auto = true;
        updateAutoButtons();
        go(cur < 0 ? 0 : cur + 1, 1);
    }

    private void stopAuto() {
        auto = false;
        timer.removeCallbacksAndMessages(null);
        if (autoButton != null) updateAutoButtons();
    }

    private void step(int direction) {
        stopAuto();
        if (cur < 0) return;
        go(cur + direction, direction);
    }

    private void sendAgain() {
        if (shown != null && shown.ir != null) transmit(shown.ir);
    }

    /** Shows the manufacturer `index` and sends its power key; manufacturers without one are skipped in the same direction. */
    private void go(int index, final int direction) {
        timer.removeCallbacksAndMessages(null);
        final int gen = ++generation;
        if (index < 0 || index >= brands.size()) {
            stopAuto();
            noteView.setText(index < 0 ? "This is the first manufacturer." : "That was the last manufacturer.");
            return;
        }
        cur = index;
        final int here = index;
        brandView.setText(brands.get(index).label.replace('_', ' '));
        posView.setText((index + 1) + " / " + brands.size());
        infoView.setText("Loading...");
        request(index, () -> {
            if (gen != generation) return;
            Candidate c = loaded.get(here);
            if (c == null) {                                       // download failed
                stopAuto();
                infoView.setText("");
                noteView.setText("Download failed. Check the internet connection and tap Next.");
                return;
            }
            if (c.ir == null) {
                go(here + direction, direction);
                return;
            }
            shown = c;
            if (history.isEmpty() || history.get(history.size() - 1) != here) history.add(here);
            infoView.setText("Sent: " + c.button + "  (remote " + c.file + ")");
            noteView.setText("");
            transmit(c.ir);
            if (auto) timer.postDelayed(() -> go(here + 1, 1), SPEEDS[speed] * 1000L);
            if (here + direction >= 0 && here + direction < brands.size()) request(here + direction, null);   // load the next one ahead
        });
    }

    /** Runs `then` (main thread) when the candidate of manufacturer `index` is loaded (or failed to load, then it is not in `loaded`). */
    private void request(final int index, Runnable then) {
        if (loaded.containsKey(index)) {
            if (then != null) then.run();
            return;
        }
        if (then != null) {
            List<Runnable> list = waiting.get(index);
            if (list == null) waiting.put(index, list = new ArrayList<>());
            list.add(then);
        }
        if (!loading.add(index)) return;
        final String folder = brands.get(index).path;
        final List<String> files = IrCatalog.filesBelow(paths, folder);
        net.execute(() -> {
            Candidate found = new Candidate();
            boolean failed = false;
            try {
                for (int i = 0; i < files.size() && i < FILES_PER_BRAND && found.ir == null; i++) {
                    FlipperIr.Entry power = IrCatalog.findPower(FlipperIr.parse(IrCatalog.file(files.get(i))));
                    if (power != null) {
                        found.ir = power.ir;
                        found.button = power.name;
                        String f = files.get(i);
                        found.file = f.substring(f.lastIndexOf('/') + 1).replaceAll("\\.ir$", "").replace('_', ' ');
                    }
                }
            } catch (IOException e) {
                failed = true;
            }
            final boolean bad = failed;
            ui.post(() -> {
                loading.remove(index);
                if (!bad) loaded.put(index, found);
                List<Runnable> callbacks = waiting.remove(index);
                if (callbacks != null) for (Runnable r : callbacks) r.run();
            });
        });
    }

    private void transmit(final Ir ir) {
        final ConsumerIrManager m = (ConsumerIrManager) getSystemService(CONSUMER_IR_SERVICE);
        if (m == null || !m.hasIrEmitter()) return;
        irThread.execute(() -> {
            try {
                m.transmit(ir.carrierHz, ir.pattern(false));
            } catch (RuntimeException e) {
                ui.post(() -> noteView.setText("Infrared sending failed: " + e.getMessage()));
            }
        });
    }

    private void reacted() {
        boolean wasAuto = auto;
        stopAuto();
        if (cur < 0) return;
        // the device may take a moment to react: when the automatic run was going, the one before may be the right one
        final int now = cur;
        final int before = history.size() >= 2 ? history.get(history.size() - 2) : -1;
        if (wasAuto && before >= 0 && history.get(history.size() - 1) == now) {
            final String[] items = {brands.get(now).label.replace('_', ' '), brands.get(before).label.replace('_', ' ') + " (the one before)"};
            new AlertDialog.Builder(this).setTitle("Which manufacturer reacted?")
                    .setItems(items, (d, which) -> open(which == 0 ? now : before))
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        open(now);
    }

    private void open(int index) {
        Toast.makeText(this, "Pick a remote of " + brands.get(index).label.replace('_', ' ') + " and try its buttons.", Toast.LENGTH_LONG).show();
        startActivity(new Intent(this, IrCatalogActivity.class).putExtra(IrCatalogActivity.EXTRA_FOLDER, brands.get(index).path));
    }
}
