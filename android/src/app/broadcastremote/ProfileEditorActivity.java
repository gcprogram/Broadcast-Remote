package app.broadcastremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.Locale;

/**
 * Profil als JSON eingeben oder aendern. "Pruefen" zeigt die berechneten Pakete (zum Vergleich mit Mitschnitten),
 * "Speichern" legt das Profil an und macht es aktiv. Ein geaenderter Name erzeugt ein neues Profil.
 */
public class ProfileEditorActivity extends Activity {
    static final String EXTRA_JSON = "json";
    static final String EXTRA_NEW = "new";

    private EditText editor;
    private TextView result;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);

        TextView help = new TextView(this);
        help.setText("Profil als JSON. Das Format steht in docs/PROFILE_FORMAT.md im Projekt. "
                + "Codes als Hexstrings, z. B. \"0b\".");
        help.setTextSize(13);
        root.addView(help);

        editor = new EditText(this);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(12);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        editor.setGravity(android.view.Gravity.TOP);
        editor.setMinLines(16);
        editor.setHorizontallyScrolling(true);
        editor.setText(getIntent().getStringExtra(EXTRA_JSON));
        root.addView(editor, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.addView(Ui.button(this, "Prüfen", v -> check()));
        row.addView(Ui.button(this, "Speichern", v -> save()));
        root.addView(row);

        result = new TextView(this);
        result.setTypeface(Typeface.MONOSPACE);
        result.setTextSize(12);
        result.setTextIsSelectable(true);
        root.addView(result);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private Profile parse() {
        try {
            return Profile.parse(editor.getText().toString());
        } catch (IllegalArgumentException e) {
            result.setTextColor(0xFFC62828);
            result.setText("Fehler: " + e.getMessage());
            return null;
        }
    }

    private void check() {
        Profile p = parse();
        if (p == null) return;
        result.setTextColor(0xFF2E7D32);
        StringBuilder s = new StringBuilder("OK, Profil \"" + p.name + "\", " + p.buttons.size() + " Knöpfe.\n\nPakete:\n");
        s.append(preview(p, p.offCode, "Aus"));
        int shown = 0;
        for (Profile.Btn b : p.buttons) {
            if (b.code == p.offCode) continue;
            if (shown++ >= 4) break;
            s.append(preview(p, b.code, "Knopf"));
        }
        result.setText(s.toString());
    }

    private static String preview(Profile p, int code, String what) {
        try {
            return String.format(Locale.ROOT, "  %-5s %02x  %s\n", what, code, Controller.hexSpaced(p.packet(code)));
        } catch (IllegalArgumentException e) {
            return String.format(Locale.ROOT, "  %-5s %02x  FEHLT: %s\n", what, code, e.getMessage());
        }
    }

    private void save() {
        final Profile p = parse();
        if (p == null) return;
        if (ProfileStore.exists(this, p.name)) {
            new AlertDialog.Builder(this).setMessage("Profil \"" + p.name + "\" überschreiben?")
                    .setPositiveButton("Überschreiben", (d, w) -> store(p))
                    .setNegativeButton("Abbrechen", null).show();
        } else {
            store(p);
        }
    }

    private void store(Profile p) {
        try {
            Controller ctl = Controller.get(this);
            if (!p.name.equals(ProfileStore.activeName(this))) ctl.send(Controller.OFF, "profilwechsel");
            ProfileStore.save(this, p);
            ProfileStore.setActive(this, p.name);
            finish();
        } catch (IOException e) {
            result.setTextColor(0xFFC62828);
            result.setText("Speichern fehlgeschlagen: " + e.getMessage());
        }
    }
}
