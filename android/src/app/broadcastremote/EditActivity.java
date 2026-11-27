package app.broadcastremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
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

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(10), 0, dp(4), 0);
            TextView label = new TextView(this);
            label.setText((b.isMacro() ? "⚙ " : "") + b.caption()
                    + (!b.isMacro() && !b.label.isEmpty() ? "  (" + String.format(Locale.ROOT, "%02x", b.code) + ")" : ""));
            label.setTextSize(16);
            texts.addView(label);
            if (!b.example.isEmpty()) {
                TextView ex = new TextView(this);
                ex.setText(b.example);
                ex.setTextSize(11);
                ex.setTextColor(0xFF888888);
                texts.addView(ex);
            }
            texts.setOnClickListener(v -> {
                if (b.isMacro()) editMacro(b);
                else rename(b);
            });
            row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

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
                    if (m.pressSteps() == 0) emptied.add(m);   // only pauses left: nothing to play
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
     * (a plain button of the profile, a pause or off, and how long). Played one after another, then off.
     * An overview of the steps (with their seconds) is on top; below it the one tapped step is edited, moved or removed.
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
        final String[] names = new String[plain.size() + 2];        // entry 0 = pause, entry 1 = off, then the plain buttons
        names[0] = "⏸ Pause";
        names[1] = "⏻ Off";
        for (int i = 0; i < plain.size(); i++) names[i + 2] = plain.get(i).caption();

        final List<Profile.Step> steps = new ArrayList<>();           // working copy, edited live
        if (existing != null) {
            for (Profile.Step s : existing.macro) steps.add(new Profile.Step(s.code, s.holdMs));
        } else {
            steps.add(new Profile.Step(plain.get(0).code, Profile.Step.DEFAULT_HOLD_MS));
        }
        final int[] cur = {0};                                        // the step being edited
        final boolean[] binding = {false};                            // true while the editor is filled from the step
        final boolean[] typing = {false};                             // true while the seconds are typed (the field is not rewritten)

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));
        final EditText label = new EditText(this);
        label.setHint("Name of the macro button (e.g. HDMI1)");
        label.setTextSize(15);
        label.setSingleLine(true);
        label.setText(existing == null ? "" : existing.label);
        box.addView(label);
        final EditText example = new EditText(this);
        example.setHint("Example / what it does (optional)");
        example.setTextSize(12);
        example.setText(existing == null ? "" : existing.example);
        box.addView(example);

        TextView head = new TextView(this);
        head.setText("Tap a step to change it. After the last step the device is switched off.");
        head.setTextSize(11);
        head.setPadding(0, dp(10), 0, dp(4));
        box.addView(head);

        final FlowLayout chips = new FlowLayout(this, dp(6));
        CappedScrollView chipScroll = new CappedScrollView(this, dp(150));
        chipScroll.addView(chips);
        box.addView(chipScroll);

        final TextView title = new TextView(this);
        title.setTextSize(12);
        title.setPadding(0, dp(12), 0, 0);
        box.addView(title);

        LinearLayout editRow = new LinearLayout(this);
        editRow.setGravity(Gravity.CENTER_VERTICAL);
        final Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        editRow.addView(sp, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        final EditText hold = new EditText(this);
        hold.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        hold.setTextSize(15);
        hold.setSingleLine(true);
        editRow.addView(hold, new LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView unit = new TextView(this);
        unit.setText(" s");
        editRow.addView(unit);
        box.addView(editRow);

        final Runnable[] refresh = new Runnable[1];

        refresh[0] = () -> {
            chips.removeAllViews();
            for (int i = 0; i < steps.size(); i++) {
                final int index = i;
                Profile.Step s = steps.get(i);
                String name = s.code == Profile.Step.PAUSE ? names[0] : s.code == Profile.Step.OFF ? names[1] : stepName(plain, s.code);
                String secs = s.holdMs <= 0 ? "?" : String.format(Locale.ROOT, "%.1f s", s.holdMs / 1000.0);
                SpannableString text = new SpannableString(name + "\n" + secs);
                text.setSpan(new RelativeSizeSpan(0.75f), name.length() + 1, text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new ForegroundColorSpan(0xFF888888), name.length() + 1, text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                TextView chip = new TextView(this);
                chip.setText(text);
                chip.setTextSize(14);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(12), dp(4), dp(12), dp(4));
                GradientDrawable shape = new GradientDrawable();
                shape.setCornerRadius(dp(18));
                shape.setColor(index == cur[0] ? 0x554488CC : 0x22888888);
                shape.setStroke(index == cur[0] ? dp(2) : dp(1), index == cur[0] ? 0xFF4488CC : 0xFF888888);
                chip.setBackground(shape);
                chip.setOnClickListener(v -> {
                    cur[0] = index;
                    refresh[0].run();
                });
                chips.addView(chip);
            }
            // fill the editor from the current step
            Profile.Step s = steps.get(cur[0]);
            binding[0] = true;
            title.setText("Step " + (cur[0] + 1) + " of " + steps.size());
            if (s.code == Profile.Step.PAUSE) sp.setSelection(0);
            else if (s.code == Profile.Step.OFF) sp.setSelection(1);
            else for (int i = 0; i < plain.size(); i++) if (plain.get(i).code == s.code) sp.setSelection(i + 2);
            String shown = s.holdMs <= 0 ? "" : String.format(Locale.ROOT, "%.1f", s.holdMs / 1000.0);
            if (!typing[0]) hold.setText(shown);
            binding[0] = false;
        };

        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (binding[0]) return;
                int code = position == 0 ? Profile.Step.PAUSE : position == 1 ? Profile.Step.OFF : plain.get(position - 2).code;
                if (steps.get(cur[0]).code == code) return;
                steps.get(cur[0]).code = code;
                refresh[0].run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        hold.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable s) {
                if (binding[0]) return;
                int ms;
                try {
                    ms = (int) Math.round(Double.parseDouble(s.toString().trim().replace(',', '.')) * 1000);
                } catch (NumberFormatException e) {
                    ms = -1;                                           // refused when saving
                }
                steps.get(cur[0]).holdMs = ms;
                typing[0] = true;                                      // update the chip, but leave the field alone
                refresh[0].run();
                typing[0] = false;
            }
        });

        LinearLayout moveRow = new LinearLayout(this);
        moveRow.addView(stepButton("◀", v -> {
            int i = cur[0];
            if (i == 0) return;
            Collections.swap(steps, i, i - 1);
            cur[0] = i - 1;
            refresh[0].run();
        }));
        moveRow.addView(stepButton("▶", v -> {
            int i = cur[0];
            if (i >= steps.size() - 1) return;
            Collections.swap(steps, i, i + 1);
            cur[0] = i + 1;
            refresh[0].run();
        }));
        moveRow.addView(stepButton("✕ Remove", v -> {
            if (steps.size() == 1) {
                Toast.makeText(this, "A macro needs at least one step", Toast.LENGTH_SHORT).show();
                return;
            }
            steps.remove(cur[0]);
            if (cur[0] >= steps.size()) cur[0] = steps.size() - 1;
            refresh[0].run();
        }));
        box.addView(moveRow);

        LinearLayout addRow = new LinearLayout(this);
        addRow.addView(stepButton("＋ Step after", v -> {
            if (steps.size() >= Profile.Btn.MAX_STEPS) {
                Toast.makeText(this, "At most " + Profile.Btn.MAX_STEPS + " steps", Toast.LENGTH_SHORT).show();
                return;
            }
            steps.add(cur[0] + 1, new Profile.Step(plain.get(0).code, Profile.Step.DEFAULT_HOLD_MS));
            cur[0]++;
            refresh[0].run();
        }));
        addRow.addView(stepButton("＋ Pause after", v -> {
            if (steps.size() >= Profile.Btn.MAX_STEPS) {
                Toast.makeText(this, "At most " + Profile.Btn.MAX_STEPS + " steps", Toast.LENGTH_SHORT).show();
                return;
            }
            steps.add(cur[0] + 1, new Profile.Step(Profile.Step.PAUSE, Profile.Step.DEFAULT_HOLD_MS));
            cur[0]++;
            refresh[0].run();
        }));
        box.addView(addRow);
        refresh[0].run();

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
        // the keyboard shrinks the dialog instead of covering it
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = label.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Please give the macro a name", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean anyPress = false;
            for (Profile.Step s : steps) if (s.code >= 0) anyPress = true;
            if (!anyPress) {
                Toast.makeText(this, "Please add at least one button step (not only pauses or off)", Toast.LENGTH_SHORT).show();
                return;
            }
            for (int i = 0; i < steps.size(); i++) {
                Profile.Step s = steps.get(i);
                int min = s.code == Profile.Step.PAUSE ? Profile.Step.MIN_PAUSE_MS : Profile.Step.MIN_HOLD_MS;
                int max = s.code == Profile.Step.PAUSE ? Profile.Step.MAX_PAUSE_MS : Profile.Step.MAX_HOLD_MS;
                if (s.holdMs < min || s.holdMs > max) {
                    cur[0] = i;
                    refresh[0].run();
                    Toast.makeText(this, "Step " + (i + 1) + ": " + (s.code == Profile.Step.PAUSE ? "a pause is 0.1 to 60 seconds"
                            : s.code == Profile.Step.OFF ? "off is sent for 0.2 to 30 seconds" : "0.2 to 30 seconds"), Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            Profile.Btn target = existing;
            if (target == null) {
                target = new Profile.Btn(profile.freeCode(), "", "");
                profile.buttons.add(target);
            }
            target.label = name;
            target.example = example.getText().toString().trim();
            target.macro = new ArrayList<>(steps);
            dialog.dismiss();
            save();
        });
    }

    private static String stepName(List<Profile.Btn> plain, int code) {
        for (Profile.Btn b : plain) if (b.code == code) return b.caption();
        return String.format(Locale.ROOT, "%02x", code);
    }

    private Button stepButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(13);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1));
        b.setPadding(dp(4), 0, dp(4), 0);
        return b;
    }

    /** Puts its children side by side and wraps to the next line when the row is full. */
    private static final class FlowLayout extends ViewGroup {
        private final int gap;

        FlowLayout(android.content.Context c, int gap) {
            super(c);
            this.gap = gap;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int maxW = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                ch.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
                int w = ch.getMeasuredWidth(), h = ch.getMeasuredHeight();
                if (x > 0 && x + w > maxW) {
                    x = 0;
                    y += rowH + gap;
                    rowH = 0;
                }
                x += w + gap;
                rowH = Math.max(rowH, h);
            }
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), y + rowH + getPaddingTop() + getPaddingBottom());
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxW = r - l - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                int w = ch.getMeasuredWidth(), h = ch.getMeasuredHeight();
                if (x > 0 && x + w > maxW) {
                    x = 0;
                    y += rowH + gap;
                    rowH = 0;
                }
                ch.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + w, getPaddingTop() + y + h);
                x += w + gap;
                rowH = Math.max(rowH, h);
            }
        }
    }

    /** A ScrollView that is at most `maxHeight` tall (longer content scrolls inside). */
    private static final class CappedScrollView extends ScrollView {
        private final int maxHeight;

        CappedScrollView(android.content.Context c, int maxHeight) {
            super(c);
            this.maxHeight = maxHeight;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
        }
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
