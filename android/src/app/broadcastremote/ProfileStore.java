package app.broadcastremote;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Profiles as JSON files in the app's own storage (files/profiles/&lt;name&gt;.json). On first start the bundled
 * profiles from assets/profiles are copied. The active profile is kept in the settings.
 */
final class ProfileStore {
    private static Profile cached;
    private static String cachedName;

    private ProfileStore() {
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "profiles");
        d.mkdirs();
        return d;
    }

    static String fileName(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_") + ".json";
    }

    /** Copies the bundled profiles if there is none yet. */
    static void ensureDefaults(Context c) {
        File[] files = dir(c).listFiles();
        if (files != null && files.length > 0) return;
        try {
            String[] names = c.getAssets().list("profiles");
            if (names == null) return;
            for (String n : names) {
                try (InputStream in = c.getAssets().open("profiles/" + n)) {
                    save(c, Profile.parse(read(in)));
                }
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // without a default profile the list stays empty, Setup shows that
        }
    }

    static List<String> list(Context c) {
        ensureDefaults(c);
        List<String> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.getName().endsWith(".json")) continue;
                try {
                    out.add(Profile.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).name);
                } catch (IOException | IllegalArgumentException ignored) {
                    // skip a broken file
                }
            }
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    static boolean exists(Context c, String name) {
        return new File(dir(c), fileName(name)).exists();
    }

    static Profile load(Context c, String name) throws IOException {
        File f = new File(dir(c), fileName(name));
        return Profile.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    static void save(Context c, Profile p) throws IOException {
        Files.write(new File(dir(c), fileName(p.name)).toPath(), p.toJson().getBytes(StandardCharsets.UTF_8));
        if (p.name.equals(cachedName)) cached = null;
    }

    static void delete(Context c, String name) {
        new File(dir(c), fileName(name)).delete();
        if (name.equals(cachedName)) cached = null;
    }

    static String activeName(Context c) {
        String n = Config.prefs(c).getString("profile", null);
        List<String> all = list(c);
        if (n != null && all.contains(n)) return n;
        return all.isEmpty() ? null : all.get(0);
    }

    static void setActive(Context c, String name) {
        Config.prefs(c).edit().putString("profile", name).apply();
        cached = null;
    }

    /** The active profile; null if there is none or it cannot be read. */
    static synchronized Profile active(Context c) {
        String name = activeName(c);
        if (name == null) return null;
        if (cached != null && name.equals(cachedName)) return cached;
        try {
            cached = load(c, name);
            cachedName = name;
        } catch (IOException | IllegalArgumentException e) {
            cached = null;
        }
        return cached;
    }

    /** Save changes to the active profile (button list etc.). */
    static void saveActive(Context c, Profile p) {
        try {
            save(c, p);
            cached = p;
            cachedName = p.name;
        } catch (IOException ignored) {
        }
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) {
            if (buf.size() > 256 * 1024) throw new IOException("File too large");
            buf.write(chunk, 0, n);
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
}
