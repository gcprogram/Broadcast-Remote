package app.broadcastremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.content.Intent;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Find an infrared remote: browse by device type and manufacturer (or search), pick a remote, and it is downloaded and saved as a
 * profile. The list of remotes is loaded once from the Flipper-IRDB and kept; only the picked file is downloaded.
 */
public class IrCatalogActivity extends Activity {
    static final String EXTRA_FOLDER = "folder";            // open in this folder, for example "LED_Lighting/Airivo/"
    private static final int SEARCH_LIMIT = 300;

    private List<String> paths = new ArrayList<>();
    private String folder = "";                          // current folder, "" or "TVs/Samsung/"
    private final List<IrCatalog.Item> shown = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private TextView where;
    private TextView note;
    private EditText search;
    private Button findBrand;
    private boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 16);

        TextView help = new TextView(this);
        help.setText("Pick a device type and a manufacturer, or search (for example \"samsung tv\"). The chosen remote is downloaded "
                + "and saved as a profile that sends through the infrared emitter of this phone. Source: " + IrCatalog.SOURCE + ".");
        help.setTextSize(13);
        root.addView(help);

        search = new EditText(this);
        search.setHint("Search");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { refreshList(); }
        });
        root.addView(search);

        LinearLayout row = new LinearLayout(this);
        row.addView(Ui.button(this, "Up", v -> up()));
        row.addView(Ui.button(this, "Reload list", v -> load(true)));
        root.addView(row);
        findBrand = Ui.button(this, "Find the manufacturer by its power key", v -> startActivity(
                new Intent(this, IrFindActivity.class).putExtra(IrFindActivity.EXTRA_CLASS, folder)));
        findBrand.setAllCaps(false);
        findBrand.setVisibility(View.GONE);
        root.addView(findBrand);

        where = new TextView(this);
        where.setTextSize(15);
        where.setPadding(0, 8, 0, 0);
        root.addView(where);
        note = new TextView(this);
        note.setTextSize(13);
        root.addView(note);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> pick(shown.get(position)));
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        if (!Controller.irAvailable(this)) {
            Toast.makeText(this, "This phone has no infrared emitter: you can add remotes, but not send with them.", Toast.LENGTH_LONG).show();
        }
        String start = getIntent().getStringExtra(EXTRA_FOLDER);
        if (start != null) folder = start;
        load(false);
    }

    @Override
    public void onBackPressed() {
        if (search.getText().length() > 0) search.setText("");
        else if (!folder.isEmpty()) up();
        else super.onBackPressed();
    }

    private void load(final boolean refresh) {
        if (busy) return;
        busy = true;
        note.setText(refresh || !IrCatalog.hasCache(this) ? "Loading the list of remotes from the internet..." : "Loading...");
        new Thread(() -> {
            String error = null;
            List<String> loaded = null;
            try {
                loaded = IrCatalog.index(this, refresh);
            } catch (IOException e) {
                error = e.getMessage();
            }
            final List<String> result = loaded;
            final String message = error;
            runOnUiThread(() -> {
                busy = false;
                if (result != null) {
                    paths = result;
                    note.setText(paths.size() + " remotes in the list.");
                } else {
                    note.setText("Could not load the list: " + message);
                }
                refreshList();
            });
        }).start();
    }

    private void up() {
        if (folder.isEmpty()) return;
        int cut = folder.lastIndexOf('/', folder.length() - 2);
        folder = cut < 0 ? "" : folder.substring(0, cut + 1);
        refreshList();
    }

    private void refreshList() {
        String query = search.getText().toString().trim();
        shown.clear();
        if (query.length() >= 2) {
            shown.addAll(IrCatalog.search(paths, query, SEARCH_LIMIT));
            where.setText("Search: " + query + " (" + shown.size() + (shown.size() >= SEARCH_LIMIT ? "+" : "") + ")");
        } else {
            shown.addAll(IrCatalog.children(paths, folder));
            where.setText(folder.isEmpty() ? "All device types" : folder.substring(0, folder.length() - 1).replace("/", "  /  "));
        }
        // offered inside a device type ("TVs/", "LED_Lighting/"), where the manufacturers are the folders
        findBrand.setVisibility(query.length() < 2 && folder.indexOf('/') == folder.length() - 1 && !folder.isEmpty()
                && !folder.startsWith("_") ? View.VISIBLE : View.GONE);
        List<String> labels = new ArrayList<>();
        for (IrCatalog.Item i : shown) labels.add(i.folder ? "📁  " + i.label : i.label);
        adapter.clear();
        adapter.addAll(labels);
        adapter.notifyDataSetChanged();
    }

    private void pick(IrCatalog.Item item) {
        if (item.folder) {
            search.setText("");
            folder = item.path;
            refreshList();
            return;
        }
        if (busy) return;
        busy = true;
        note.setText("Downloading " + item.label + "...");
        final String path = item.path;
        new Thread(() -> {
            String text = null;
            String error = null;
            try {
                text = IrCatalog.file(path);
            } catch (IOException e) {
                error = e.getMessage();
            }
            final String body = text;
            final String message = error;
            runOnUiThread(() -> {
                busy = false;
                if (body == null) {
                    note.setText("Download failed: " + message);
                    return;
                }
                note.setText(paths.size() + " remotes in the list.");
                offer(path, IrCatalog.buildProfile(IrCatalog.defaultName(path), path, FlipperIr.parse(body)));
            });
        }).start();
    }

    private void offer(String path, IrCatalog.Result r) {
        if (r.profile.buttons.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Not usable")
                    .setMessage("This remote has no button this app can send." + (r.skippedWhy.isEmpty() ? "" : "\n\nSkipped: " + r.skippedWhy))
                    .setPositiveButton("OK", null).show();
            return;
        }
        final EditText name = new EditText(this);
        name.setText(r.profile.name);
        String text = r.profile.buttons.size() + " buttons"
                + (r.skipped > 0 ? ", " + r.skipped + " skipped (" + r.skippedWhy + ")" : "")
                + ".\n\nName of the profile:";
        new AlertDialog.Builder(this).setTitle("Add remote").setMessage(text).setView(name)
                .setPositiveButton("Add and use", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) {
                        Toast.makeText(this, "The name is empty", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    r.profile.name = n;
                    if (ProfileStore.exists(this, n)) {
                        new AlertDialog.Builder(this).setMessage("Profile \"" + n + "\" already exists. Overwrite?")
                                .setPositiveButton("Overwrite", (d2, w2) -> store(r.profile))
                                .setNegativeButton("Cancel", null).show();
                    } else {
                        store(r.profile);
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void store(Profile p) {
        try {
            Controller.get(this).send(Controller.OFF, "profile loaded");
            ProfileStore.save(this, p);
            ProfileStore.setActive(this, p.name);
            Toast.makeText(this, "Added and active: " + p.name, Toast.LENGTH_LONG).show();
            finish();
        } catch (IOException e) {
            note.setText("Saving failed: " + e.getMessage());
        }
    }
}
