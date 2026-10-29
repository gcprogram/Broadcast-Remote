package app.broadcastremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.Locale;

/**
 * Knoepfe des aktiven Profils bearbeiten: umsortieren (hoch/runter), Icon und Beschriftung vergeben,
 * Codes hinzufuegen oder entfernen. Aenderungen werden sofort im Profil gespeichert.
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
        help.setText("Profil " + profile.name + ". Reihenfolge mit ▲ ▼ ändern, auf das Icon tippen zum Auswählen, "
                + "auf die Beschriftung tippen zum Umbenennen, ✕ entfernt einen Knopf.");
        root.addView(help);

        LinearLayout add = new LinearLayout(this);
        add.setGravity(Gravity.CENTER_VERTICAL);
        final EditText code = new EditText(this);
        code.setHint("neuer Code hex, 00-ff");
        code.setInputType(InputType.TYPE_CLASS_TEXT);
        add.addView(code, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button addBtn = new Button(this);
        addBtn.setText("Hinzufügen");
        addBtn.setOnClickListener(v -> {
            try {
                int c = Integer.parseInt(code.getText().toString().trim().replaceFirst("^0[xX]", ""), 16);
                if (c < 0 || c > 255) throw new NumberFormatException();
                if (profile.hasButton(c)) {
                    Toast.makeText(this, "Code gibt es schon", Toast.LENGTH_SHORT).show();
                    return;
                }
                profile.buttons.add(new Profile.Btn(c, "", ""));
                code.setText("");
                save();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Bitte Hex 00-ff", Toast.LENGTH_SHORT).show();
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
            icon.setLayoutParams(new LinearLayout.LayoutParams(dp(64), dp(56)));      // gleich grosse Icons
            icon.setOnClickListener(v -> pickIcon(b));
            row.addView(icon);

            TextView label = new TextView(this);
            label.setText("  " + b.caption() + (b.label.isEmpty() ? "" : "  (" + String.format(Locale.ROOT, "%02x", b.code) + ")"));
            label.setTextSize(20);
            label.setOnClickListener(v -> rename(b));
            row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            row.addView(small("▲", v -> move(pos, -1)));
            row.addView(small("▼", v -> move(pos, 1)));
            row.addView(small("✕", v -> {
                profile.buttons.remove(pos);
                save();
            }));
            rows.addView(row);
        }
    }

    private void move(int pos, int delta) {
        int to = pos + delta;
        if (to < 0 || to >= profile.buttons.size()) return;
        Collections.swap(profile.buttons, pos, to);
        save();
    }

    private void rename(final Profile.Btn b) {
        final EditText input = new EditText(this);
        input.setHint("Beschriftung (leer = Hexcode)");
        input.setText(b.label);
        new AlertDialog.Builder(this)
                .setTitle("Beschriftung für " + String.format(Locale.ROOT, "%02x", b.code))
                .setView(input)
                .setPositiveButton("Übernehmen", (d, w) -> {
                    b.label = input.getText().toString().trim();
                    save();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
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
        own.setHint("oder eigenes Zeichen/Emoji");
        box.addView(own);

        dialog[0] = new AlertDialog.Builder(this)
                .setTitle("Icon für " + b.caption())
                .setView(box)
                .setPositiveButton("Eigenes übernehmen", (d, w) -> {
                    String t = own.getText().toString().trim();
                    if (!t.isEmpty()) {
                        // hoechstens 4 Codepoints (ein Emoji inkl. Variantenauswahl), damit alle Icons gleich gross sind
                        int end = t.offsetByCodePoints(0, Math.min(4, t.codePointCount(0, t.length())));
                        b.icon = t.substring(0, end);
                        save();
                    }
                })
                .setNeutralButton("Kein Icon", (d, w) -> {
                    b.icon = "";
                    save();
                })
                .setNegativeButton("Abbrechen", null)
                .create();
        dialog[0].show();
    }
}
