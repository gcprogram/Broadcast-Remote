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
 * Setup: choose profiles, load from a file, save to a file, copy, delete and edit as JSON.
 * The active profile determines how to send and which buttons exist.
 */
public class SetupActivity extends Activity {
    private static final int REQ_OPEN = 11;
    private static final int REQ_SAVE = 12;

    /** Empty skeleton for entering a new profile (placeholder values). */
    private static final String SKELETON = "{\n"
            + "  \"format\": 1,\n"
            + "  \"name\": \"New profile\",\n"
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
        help.setText("A profile describes how a device is addressed (radio parameters, packet computation, buttons). "
                + "It is a JSON file and can be loaded, saved and edited here. When you switch profiles "
                + "off is sent first.");
        help.setTextSize(13);
        root.addView(help);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 16, 0, 16);
        root.addView(list);

        LinearLayout row1 = new LinearLayout(this);
        row1.addView(Ui.button(this, "Edit", v -> edit()));
        row1.addView(Ui.button(this, "Copy", v -> duplicate()));
        row1.addView(Ui.button(this, "Delete", v -> delete()));
        root.addView(row1);
        LinearLayout row2 = new LinearLayout(this);
        row2.addView(Ui.button(this, "Load from file", v -> openFile()));
        row2.addView(Ui.button(this, "Save to file", v -> saveFile()));
        root.addView(row2);
        root.addView(Ui.wide(Ui.button(this, "Enter new profile", v -> create())));

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
            none.setText("No profiles. Load one from a file or enter a new one.");
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
        ctl.send(Controller.OFF, "profile change");
        ProfileStore.setActive(this, name);
        info.setText("Active: " + name);
        rebuild();
    }

    private String active() {
        String a = ProfileStore.activeName(this);
        if (a == null) Toast.makeText(this, "No profile selected", Toast.LENGTH_SHORT).show();
        return a;
    }

    private void edit() {
        String a = active();
        if (a == null) return;
        try {
            startEditor(ProfileStore.load(this, a).toJson(), false);
        } catch (IOException | IllegalArgumentException e) {
            info.setText("Profile not readable: " + e.getMessage());
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
        input.setText(a + " copy");
        new AlertDialog.Builder(this).setTitle("Name of the copy").setView(input)
                .setPositiveButton("Copy", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || ProfileStore.exists(this, name)) {
                        info.setText("Name empty or already taken.");
                        return;
                    }
                    try {
                        Profile p = ProfileStore.load(this, a);
                        p.name = name;
                        ProfileStore.save(this, p);
                        info.setText("Copy created: " + name);
                        rebuild();
                    } catch (IOException | IllegalArgumentException e) {
                        info.setText("Copying failed: " + e.getMessage());
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void delete() {
        final String a = active();
        if (a == null) return;
        new AlertDialog.Builder(this).setMessage("Delete profile \"" + a + "\"?")
                .setPositiveButton("Delete", (d, w) -> {
                    ctl.send(Controller.OFF, "profile deleted");
                    ProfileStore.delete(this, a);
                    ProfileStore.setActive(this, "");
                    info.setText("Deleted: " + a);
                    rebuild();
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---------------------------------------------------------------- files (Storage Access Framework)

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
            info.setText("Profile not readable: " + e.getMessage());
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
                info.setText("Profile saved.");
            } catch (IOException | NullPointerException e) {
                info.setText("Saving failed: " + e);
            }
            pendingExport = null;
        }
    }

    private void importProfile(Uri uri) {
        final Profile p;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            p = Profile.parse(ProfileStore.read(in));
        } catch (IOException | IllegalArgumentException | NullPointerException e) {
            info.setText("File not readable: " + e.getMessage());
            return;
        }
        if (ProfileStore.exists(this, p.name)) {
            new AlertDialog.Builder(this).setMessage("Profile \"" + p.name + "\" already exists. Overwrite?")
                    .setPositiveButton("Overwrite", (d, w) -> store(p))
                    .setNegativeButton("Cancel", null).show();
        } else {
            store(p);
        }
    }

    private void store(Profile p) {
        try {
            ctl.send(Controller.OFF, "profile loaded");
            ProfileStore.save(this, p);
            ProfileStore.setActive(this, p.name);
            info.setText("Loaded and active: " + p.name);
            rebuild();
        } catch (IOException e) {
            info.setText("Saving failed: " + e.getMessage());
        }
    }
}
