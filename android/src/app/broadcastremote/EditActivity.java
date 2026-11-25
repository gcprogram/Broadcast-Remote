package app.broadcastremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Edit the buttons of the active profile: reorder (up/down), set icon and label, add or remove codes.
 * Changes are saved to the profile immediately.
 */
public class EditActivity extends Activity {
    private static final String[] ICONS = {
            "🔥", "💧", "⚡", "🌊", "💨", "❤️", "💜", "💙", "⭐", "🌙", "☀️", "🌀",
            "🎵", "🔔", "🍀", "🎯", "👍", "🔼", "🔽", "▶️", "⏸️", "🔴", "🟠", "🟡",
            "🟢", "🔵", "🟣", "⚫", "🌸", "🦋", "🐢", "🐇", "🚀", "💎", "🎈", "🌈"};

    private Profile profile;
    private LinearLayout rows;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        profile = Controller.get(this).profile();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);

        if (profile == null) {
            TextView none = new TextView(this);
            none.setText(Controller.NO_PROFILE);
            root.addView(none);
            setContentView(root);
            return;
        }

        TextView help = new TextView(this);
        help.setText("Profile " + profile.name + ". Change the order with ▲ ▼, tap the icon to choose one, "
                + "tap the label to rename it or to add an example text for assistants, ✕ removes a button. "
                + "A macro is a button that presses several buttons one after another (e.g. Input, then right arrow).");
        root.addView(help);

        Button newMacro = new Button(this);
        newMacro.setAllCaps(false);
        newMacro.setText("＋ New macro button");
        newMacro.setOnClickListener(v -> editMacro(null));
        root.addView(newMacro);

        LinearLayout add = new LinearLayout(this);
        add.setGravity(Gravity.CENTER_VERTICAL);
        final EditText code = new EditText(this);
        code.setHint("new code, hex 00-ff");
        code.setInputType(InputType.TYPE_CLASS_TEXT);
        add.addView(code, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button addBtn = new Button(this);
        addBtn.setText("Add");
        addBtn.setOnClickListener(v -> {
            try {
                int c = Integer.parseInt(code.getText().toString().trim().replaceFirst("^0[xX]", ""), 16);
                if (c < 0 || c > 255) throw new NumberFormatException();
                if (profile.hasButton(c)) {
                    Toast.makeText(this, "That code already exists", Toast.LENGTH_SHORT).show();
                    return;
                }
                profile.buttons.add(new Profile.Btn(c, "", ""));
                code.setText("");
                save();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Please enter hex 00-ff", Toast.LENGTH_SHORT).show();
            }
        });
        add.addView(addBtn);
        root.addView(add);

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        root.addView(rows);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        rebuild();
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }

    private void save() {
        ProfileStore.saveActive(this, profile);
        rebuild();
    }

    private Button small(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(48)));
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private void rebuild() {
        rows.removeAllViews();
        for (int i = 0; i < profile.buttons.size(); i++) {
            final int pos = i;
            final Profile.Btn b = profile.buttons.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);

            Button icon = new Button(this);
            icon.setAllCaps(false);
            icon.setTextSize(24);
            icon.setText(b.icon.isEmpty() ? "＋" : b.icon);
            icon.setLayoutParams(new LinearLayout.LayoutParams(dp(64), dp(56)));      // equal-size icons
            icon.setOnClickListener(v -> pickIcon(b));
            row.addView(icon);

            TextView label = new TextView(this);
            label.setText("  " + (b.isMacro() ? "⚙ " : "") + b.caption()
                    + (b.isMacro() ? "  (macro, " + b.macro.size() + (b.macro.size() == 1 ? " step)" : " steps)")
                    : b.label.isEmpty() ? "" : "  (" + String.format(Locale.ROOT, "%02x", b.code) + ")")
                    + (b.example.isEmpty() ? "" : "\n  " + b.example));
            label.setTextSize(20);
            label.setOnClickListener(v -> {
                if (b.isMacro()) editMacro(b);
                else rename(b);
            });
            row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            row.addView(small("▲", v -> move(pos, -1)));
            row.addView(small("▼", v -> move(pos, 1)));
            row.addView(small("✕", v -> removeButton(pos)));
            rows.addView(row);
        }
    }

    /** Removes a button; macro steps that pressed it go too, and a macro left without steps goes with them. */
    private void removeButton(int pos) {
        Profile.Btn gone = profile.buttons.remove(pos);
        if (!gone.isMacro()) {
            List<Profile.Btn> emptied = new ArrayList<>();
            for (Profile.Btn m : profile.buttons) {
                if (!m.isMacro()) continue;
                List<Profile.Step> keep = new ArrayList<>();
                for (Profile.Step s : m.macro) if (s.code != gone.code) keep.add(s);
                if (keep.size() != m.macro.size()) {
                    m.macro = keep;
                    if (keep.isEmpty()) emptied.add(m);
                }
            }
            profile.buttons.removeAll(emptied);
            if (!emptied.isEmpty()) Toast.makeText(this, emptied.size() + " macro(s) removed (they only used this button)", Toast.LENGTH_LONG).show();
        }
        save();
    }

    private void move(int pos, int delta) {
        int to = pos + delta;
        if (to < 0 || to >= profile.buttons.size()) return;
        Collections.swap(profile.buttons, pos, to);
        save();
    }

    private void rename(final Profile.Btn b) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText input = new EditText(this);
        input.setHint("Label (empty = hex code)");
        input.setText(b.label);
        box.addView(input);
        final EditText example = new EditText(this);
        example.setHint("Example / what it does (optional, shown to assistants)");
        example.setText(b.example);
        box.addView(example);
        new AlertDialog.Builder(this)
                .setTitle("Button " + String.format(Locale.ROOT, "%02x", b.code))
                .setView(box)
                .setPositiveButton("Apply", (d, w) -> {
                    b.label = input.getText().toString().trim();
                    b.example = example.getText().toString().trim();
                    save();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Create (existing == null) or change a macro button: a name, an optional example and a list of steps
     * (a plain button of the profile and how long it is sent). Played one after another, then off.
     */
    private void editMacro(final Profile.Btn existing) {
        final List<Profile.Btn> plain = new ArrayList<>();
        for (Profile.Btn x : profile.buttons) if (!x.isMacro()) plain.add(x);
        if (plain.isEmpty()) {
            Toast.makeText(this, "Please add plain buttons first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (existing == null && profile.freeCode() < 0) {
            Toast.makeText(this, "No free code left", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] names = new String[plain.size()];
        for (int i = 0; i < names.length; i++) names[i] = plain.get(i).caption();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText label = new EditText(this);
        label.setHint("Name of the macro button (e.g. HDMI1)");
        label.setText(existing == null ? "" : existing.label);
        box.addView(label);
        final EditText example = new EditText(this);
        example.setHint("Example / what it does (optional)");
        example.setText(existing == null ? "" : existing.example);
        box.addView(example);
        TextView head = new TextView(this);
        head.setText("Steps: button and how many seconds it is sent. After the last step the device is switched off.");
        head.setPadding(0, dp(12), 0, dp(4));
        box.addView(head);

        final LinearLayout stepRows = new LinearLayout(this);
        stepRows.setOrientation(LinearLayout.VERTICAL);
        box.addView(stepRows);
        final List<Spinner> spinners = new ArrayList<>();
        final List<EditText> holds = new ArrayList<>();

        final Runnable[] addStepNow = new Runnable[1];
        final int[] nextCode = {plain.get(0).code};
        final int[] nextHold = {Profile.Step.DEFAULT_HOLD_MS};
        addStepNow[0] = () -> {
            final LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            final Spinner sp = new Spinner(this);
            sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
            for (int i = 0; i < plain.size(); i++) if (plain.get(i).code == nextCode[0]) sp.setSelection(i);
            row.addView(sp, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            final EditText hold = new EditText(this);
            hold.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            hold.setText(String.format(Locale.ROOT, "%.1f", nextHold[0] / 1000.0));
            row.addView(hold, new LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView unit = new TextView(this);
            unit.setText(" s");
            row.addView(unit);
            row.addView(small("✕", v -> {
                stepRows.removeView(row);
                spinners.remove(sp);
                holds.remove(hold);
            }));
            stepRows.addView(row);
            spinners.add(sp);
            holds.add(hold);
        };
        if (existing != null) {
            for (Profile.Step s : existing.macro) {
                nextCode[0] = s.code;
                nextHold[0] = s.holdMs;
                addStepNow[0].run();
            }
        }
        nextCode[0] = plain.get(0).code;
        nextHold[0] = Profile.Step.DEFAULT_HOLD_MS;
        if (existing == null) addStepNow[0].run();

        Button addStep = new Button(this);
        addStep.setAllCaps(false);
        addStep.setText("＋ Add step");
        addStep.setOnClickListener(v -> {
            if (spinners.size() >= Profile.Btn.MAX_STEPS) {
                Toast.makeText(this, "At most " + Profile.Btn.MAX_STEPS + " steps", Toast.LENGTH_SHORT).show();
                return;
            }
            addStepNow[0].run();
        });
        box.addView(addStep);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "New macro" : "Macro " + existing.caption())
                .setView(scroll)
                .setPositiveButton("Save", null)               // set below: stays open while the input is wrong
                .setNegativeButton("Cancel", null);
        if (existing != null) {
            builder.setNeutralButton("Delete", (d, w) -> {
                profile.buttons.remove(existing);
                save();
            });
        }
        final AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = label.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Please give the macro a name", Toast.LENGTH_SHORT).show();
                return;
            }
            if (spinners.isEmpty()) {
                Toast.makeText(this, "Please add at least one step", Toast.LENGTH_SHORT).show();
                return;
            }
            List<Profile.Step> steps = new ArrayList<>();
            for (int i = 0; i < spinners.size(); i++) {
                double seconds;
                try {
                    seconds = Double.parseDouble(holds.get(i).getText().toString().trim().replace(',', '.'));
                } catch (NumberFormatException e) {
                    seconds = -1;
                }
                int ms = (int) Math.round(seconds * 1000);
                if (ms < Profile.Step.MIN_HOLD_MS || ms > Profile.Step.MAX_HOLD_MS) {
                    Toast.makeText(this, "Step " + (i + 1) + ": 0.2 to 30 seconds", Toast.LENGTH_SHORT).show();
                    return;
                }
                steps.add(new Profile.Step(plain.get(spinners.get(i).getSelectedItemPosition()).code, ms));
            }
            Profile.Btn target = existing;
            if (target == null) {
                target = new Profile.Btn(profile.freeCode(), "", "");
                profile.buttons.add(target);
            }
            target.label = name;
            target.example = example.getText().toString().trim();
            target.macro = steps;
            dialog.dismiss();
            save();
        });
    }

    private void pickIcon(final Profile.Btn b) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        final AlertDialog[] dialog = new AlertDialog[1];

        LinearLayout row = null;
        for (int i = 0; i < ICONS.length; i++) {
            if (i % 6 == 0) {
                row = new LinearLayout(this);
                box.addView(row);
            }
            final String icon = ICONS[i];
            Button cell = new Button(this);
            cell.setAllCaps(false);
            cell.setTextSize(24);
            cell.setText(icon);
            cell.setPadding(0, 0, 0, 0);
            cell.setLayoutParams(new LinearLayout.LayoutParams(0, dp(52), 1));
            cell.setOnClickListener(v -> {
                b.icon = icon;
                dialog[0].dismiss();
                save();
            });
            row.addView(cell);
        }

        final EditText own = new EditText(this);
        own.setHint("or your own character/emoji");
        box.addView(own);

        dialog[0] = new AlertDialog.Builder(this)
                .setTitle("Icon for " + b.caption())
                .setView(box)
                .setPositiveButton("Use own", (d, w) -> {
                    String t = own.getText().toString().trim();
                    if (!t.isEmpty()) {
                        // at most 4 code points (one emoji incl. variation selector) so that all icons have the same size
                        int end = t.offsetByCodePoints(0, Math.min(4, t.codePointCount(0, t.length())));
                        b.icon = t.substring(0, end);
                        save();
                    }
                })
                .setNeutralButton("No icon", (d, w) -> {
                    b.icon = "";
                    save();
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog[0].show();
    }
}
