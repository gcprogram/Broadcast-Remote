package app.broadcastremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Setup: Profile auswaehlen, aus einer Datei laden, in eine Datei speichern, kopieren, loeschen und per JSON
 * bearbeiten. Das aktive Profil bestimmt, wie gesendet wird und welche Knoepfe es gibt.
 */
public class SetupActivity extends Activity {
    private static final int REQ_OPEN = 11;
    private static final int REQ_SAVE = 12;

    /** Leeres Geruest, falls noch kein Profil existiert (Werte des Beispielgeraets als Platzhalter). */
    private static final String SKELETON = "{\n"
            + "  \"format\": 1,\n"
            + "  \"name\": \"Neues Profil\",\n"
            + "  \"advertising\": {\n"
            + "    \"companyId\": 255,\n"
            + "    \"serviceUuids\": [],\n"
            + "    \"connectable\": true,\n"
            + "    \"mode\": \"low_latency\",\n"
            + "    \"txPower\": \"high\",\n"
            + "    \"timeoutMs\": 3000,\n"
            + "    \"repeatMs\": 1000\n"
            + "  },\n"
            + "  \"payload\": {\n"
            + "    \"type\": \"table\",\n"
            + "    \"packets\": { \"00\": \"00\", \"01\": \"01\" }\n"
            + "  },\n"
            + "  \"off\": { \"code\": \"00\", \"sendMs\": 2000 },\n"
            + "  \"autoOffSeconds\": 180,\n"
            + "  \"buttons\": [ { \"code\": \"01\" } ]\n"
            + "}\n";

    private Controller ctl;
    private LinearLayout list;
    private TextView info;
    private String pendingExport;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ctl = Controller.get(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);

        TextView help = new TextView(this);
        help.setText("Ein Profil beschreibt, wie ein Gerät angesprochen wird (Funkparameter, Paketberechnung, Knöpfe). "
                + "Es liegt als JSON-Datei vor und kann geladen, gespeichert und hier bearbeitet werden. Beim Wechsel "
                + "wird erst Aus gesendet.");
        help.setTextSize(13);
        root.addView(help);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 16, 0, 16);
        root.addView(list);

        LinearLayout row1 = new LinearLayout(this);
        row1.addView(Ui.button(this, "Bearbeiten", v -> edit()));
        row1.addView(Ui.button(this, "Kopieren", v -> duplicate()));
        row1.addView(Ui.button(this, "Löschen", v -> delete()));
        root.addView(row1);
        LinearLayout row2 = new LinearLayout(this);
        row2.addView(Ui.button(this, "Aus Datei laden", v -> openFile()));
        row2.addView(Ui.button(this, "In Datei speichern", v -> saveFile()));
        root.addView(row2);
        root.addView(Ui.wide(Ui.button(this, "Neues Profil eingeben", v -> create())));

        info = new TextView(this);
        info.setPadding(0, 16, 0, 0);
        root.addView(info);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    private void rebuild() {
        list.removeAllViews();
        List<String> names = ProfileStore.list(this);
        String active = ProfileStore.activeName(this);
        if (names.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("Keine Profile vorhanden. Lade eines aus einer Datei oder gib ein neues ein.");
            list.addView(none);
            return;
        }
        RadioGroup group = new RadioGroup(this);
        for (String n : names) {
            RadioButton rb = new RadioButton(this);
            rb.setText(n);
            rb.setTextSize(18);
            rb.setChecked(n.equals(active));
            rb.setOnClickListener(v -> activate(n));
            group.addView(rb);
        }
        list.addView(group);
    }

    private void activate(String name) {
        if (name.equals(ProfileStore.activeName(this))) return;
        ctl.send(Controller.OFF, "profilwechsel");
        ProfileStore.setActive(this, name);
        info.setText("Aktiv: " + name);
        rebuild();
    }

    private String active() {
        String a = ProfileStore.activeName(this);
        if (a == null) Toast.makeText(this, "Kein Profil ausgewählt", Toast.LENGTH_SHORT).show();
        return a;
    }

    private void edit() {
        String a = active();
        if (a == null) return;
        try {
            startEditor(ProfileStore.load(this, a).toJson(), false);
        } catch (IOException | IllegalArgumentException e) {
            info.setText("Profil nicht lesbar: " + e.getMessage());
        }
    }

    private void create() {
        startEditor(SKELETON, true);
    }

    private void startEditor(String json, boolean isNew) {
        startActivity(new Intent(this, ProfileEditorActivity.class)
                .putExtra(ProfileEditorActivity.EXTRA_JSON, json)
                .putExtra(ProfileEditorActivity.EXTRA_NEW, isNew));
    }

    private void duplicate() {
        final String a = active();
        if (a == null) return;
        final EditText input = new EditText(this);
        input.setText(a + " Kopie");
        new AlertDialog.Builder(this).setTitle("Name der Kopie").setView(input)
                .setPositiveButton("Kopieren", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || ProfileStore.exists(this, name)) {
                        info.setText("Name leer oder schon vergeben.");
                        return;
                    }
                    try {
                        Profile p = ProfileStore.load(this, a);
                        p.name = name;
                        ProfileStore.save(this, p);
                        info.setText("Kopie angelegt: " + name);
                        rebuild();
                    } catch (IOException | IllegalArgumentException e) {
                        info.setText("Kopieren fehlgeschlagen: " + e.getMessage());
                    }
                })
                .setNegativeButton("Abbrechen", null).show();
    }

    private void delete() {
        final String a = active();
        if (a == null) return;
        new AlertDialog.Builder(this).setMessage("Profil \"" + a + "\" löschen?")
                .setPositiveButton("Löschen", (d, w) -> {
                    ctl.send(Controller.OFF, "profil geloescht");
                    ProfileStore.delete(this, a);
                    ProfileStore.setActive(this, "");
                    info.setText("Gelöscht: " + a);
                    rebuild();
                })
                .setNegativeButton("Abbrechen", null).show();
    }

    // ---------------------------------------------------------------- Dateien (Storage Access Framework)

    private void openFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(i, REQ_OPEN);
    }

    private void saveFile() {
        String a = active();
        if (a == null) return;
        try {
            pendingExport = ProfileStore.load(this, a).toJson();
        } catch (IOException | IllegalArgumentException e) {
            info.setText("Profil nicht lesbar: " + e.getMessage());
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, ProfileStore.fileName(a));
        startActivityForResult(i, REQ_SAVE);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == REQ_OPEN) {
            importProfile(uri);
        } else if (request == REQ_SAVE && pendingExport != null) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                out.write(pendingExport.getBytes(StandardCharsets.UTF_8));
                info.setText("Profil gespeichert.");
            } catch (IOException | NullPointerException e) {
                info.setText("Speichern fehlgeschlagen: " + e);
            }
            pendingExport = null;
        }
    }

    private void importProfile(Uri uri) {
        final Profile p;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            p = Profile.parse(ProfileStore.read(in));
        } catch (IOException | IllegalArgumentException | NullPointerException e) {
            info.setText("Datei nicht lesbar: " + e.getMessage());
            return;
        }
        if (ProfileStore.exists(this, p.name)) {
            new AlertDialog.Builder(this).setMessage("Profil \"" + p.name + "\" gibt es schon. Überschreiben?")
                    .setPositiveButton("Überschreiben", (d, w) -> store(p))
                    .setNegativeButton("Abbrechen", null).show();
        } else {
            store(p);
        }
    }

    private void store(Profile p) {
        try {
            ctl.send(Controller.OFF, "profil geladen");
            ProfileStore.save(this, p);
            ProfileStore.setActive(this, p.name);
            info.setText("Geladen und aktiv: " + p.name);
            rebuild();
        } catch (IOException e) {
            info.setText("Speichern fehlgeschlagen: " + e.getMessage());
        }
    }
}
