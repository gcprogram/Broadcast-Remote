package app.broadcastremote;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Catalog of infrared remotes: the Flipper-IRDB on GitHub (public domain, CC0), thousands of ".ir" files sorted by device
 * type and manufacturer. Only the list of file names is loaded once (and cached); a remote's file is downloaded when the
 * user picks it. Nothing about the user is sent, the requests only contain the path of the file.
 */
final class IrCatalog {
    static final String SOURCE = "Flipper-IRDB (github.com/Lucaslhm/Flipper-IRDB, CC0)";
    private static final String TREE_URL = "https://api.github.com/repos/Lucaslhm/Flipper-IRDB/git/trees/main?recursive=1";
    private static final String RAW_URL = "https://raw.githubusercontent.com/Lucaslhm/Flipper-IRDB/main/";
    private static final int MAX_INDEX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_FILE_BYTES = 512 * 1024;
    /** A profile needs room for macro buttons (their codes count down from ff). */
    static final int MAX_BUTTONS = 200;

    private IrCatalog() {
    }

    private static File cache(Context c) {
        return new File(c.getFilesDir(), "ir-catalog.txt");
    }

    static boolean hasCache(Context c) {
        return cache(c).isFile();
    }

    /** The paths of all .ir files (for example "TVs/Samsung/Samsung.ir"), sorted; from the cache unless `refresh`. */
    static List<String> index(Context c, boolean refresh) throws IOException {
        File f = cache(c);
        if (!refresh && f.isFile()) return Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        List<String> paths = parseTree(download(TREE_URL, MAX_INDEX_BYTES));
        if (paths.isEmpty()) throw new IOException("The catalog list is empty");
        Files.write(f.toPath(), paths, StandardCharsets.UTF_8);
        return paths;
    }

    /** The paths of the .ir files in the answer of the GitHub tree API. */
    static List<String> parseTree(String json) throws IOException {
        List<String> paths = new ArrayList<>();
        try {
            JSONArray tree = new JSONObject(json).getJSONArray("tree");
            for (int i = 0; i < tree.length(); i++) {
                JSONObject e = tree.getJSONObject(i);
                String path = e.optString("path", "");
                if ("blob".equals(e.optString("type")) && path.endsWith(".ir")) paths.add(path);
            }
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from the catalog server: " + e.getMessage());
        }
        Collections.sort(paths, String.CASE_INSENSITIVE_ORDER);
        return paths;
    }

    /** Downloads one remote file. */
    static String file(String path) throws IOException {
        StringBuilder url = new StringBuilder(RAW_URL);
        for (String part : path.split("/")) {
            if (url.length() > RAW_URL.length()) url.append('/');
            url.append(URLEncoder.encode(part, "UTF-8").replace("+", "%20"));
        }
        return download(url.toString(), MAX_FILE_BYTES);
    }

    private static String download(String address, int maxBytes) throws IOException {
        if (!address.startsWith("https://")) throw new IOException("Only https is allowed");
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "broadcast-remote");
        c.setRequestProperty("Accept", "application/vnd.github+json, text/plain");
        try {
            int code = c.getResponseCode();
            if (code == 403 || code == 429) throw new IOException("The catalog server refuses more requests for now (rate limit), try again later");
            if (code != 200) throw new IOException("Catalog server answered HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    if (buf.size() + n > maxBytes) throw new IOException("Answer too large");
                    buf.write(chunk, 0, n);
                }
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ browsing

    /** One line of the list: a folder (path ends with "/") or a file. */
    static final class Item {
        final String label;
        final String path;
        final boolean folder;

        Item(String label, String path, boolean folder) {
            this.label = label;
            this.path = path;
            this.folder = folder;
        }
    }

    /** The folders and files directly inside `prefix` ("" or "TVs/Samsung/"), folders first. */
    static List<Item> children(List<String> paths, String prefix) {
        List<Item> folders = new ArrayList<>();
        List<Item> files = new ArrayList<>();
        String lastFolder = null;
        for (String p : paths) {
            if (!p.startsWith(prefix)) continue;
            String rest = p.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                files.add(new Item(stripExtension(rest), p, false));
            } else {
                String name = rest.substring(0, slash);
                if (!name.equals(lastFolder)) {                  // the list is sorted: the same folder comes in one run
                    folders.add(new Item(name, prefix + name + "/", true));
                    lastFolder = name;
                }
            }
        }
        folders.addAll(files);
        return folders;
    }

    /** Files whose path contains every word (case does not matter), at most `limit`. */
    static List<Item> search(List<String> paths, String query, int limit) {
        String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<Item> out = new ArrayList<>();
        for (String p : paths) {
            String lower = p.toLowerCase(Locale.ROOT).replace('_', ' ');
            boolean all = true;
            for (String w : words) {
                if (!lower.contains(w)) {
                    all = false;
                    break;
                }
            }
            if (!all) continue;
            out.add(new Item(stripExtension(p).replace("/", " / "), p, false));
            if (out.size() >= limit) break;
        }
        return out;
    }

    private static String stripExtension(String name) {
        return name.endsWith(".ir") ? name.substring(0, name.length() - 3) : name;
    }

    /** Suggested profile name: the path without the device type and bucket folders, e.g. "Samsung (IR)". */
    static String defaultName(String path) {
        List<String> parts = new ArrayList<>();
        String[] segments = stripExtension(path).split("/");
        for (int i = 1; i < segments.length; i++) {
            String s = segments[i].replace('_', ' ').trim();
            if (s.isEmpty() || s.equals("CSV") || s.equals("Pronto") || s.equals("IR Plus") || s.equals("0-9") || (s.length() == 1 && i < segments.length - 2)) continue;
            if (!parts.isEmpty() && parts.get(parts.size() - 1).equalsIgnoreCase(s)) continue;
            parts.add(s);
        }
        if (parts.isEmpty()) parts.add(stripExtension(segments[segments.length - 1]).replace('_', ' '));
        return String.join(" ", parts) + " (IR)";
    }

    // ------------------------------------------------------------------ profile

    /** What became of a downloaded file. */
    static final class Result {
        final Profile profile;
        final int skipped;
        final String skippedWhy;

        Result(Profile profile, int skipped, String skippedWhy) {
            this.profile = profile;
            this.skipped = skipped;
            this.skippedWhy = skippedWhy;
        }
    }

    /** Builds an infrared profile from the buttons of a Flipper file: codes are numbered from 00, names are kept (spaces become "_"). */
    static Result buildProfile(String name, String path, List<FlipperIr.Entry> entries) {
        Profile p = new Profile();
        p.name = name;
        p.transport = Profile.IR;
        p.offSilent = true;
        p.description = "Infrared remote from the " + SOURCE + ": " + path;
        java.util.Set<String> used = new java.util.HashSet<>();
        int skipped = 0;
        java.util.TreeMap<String, Integer> why = new java.util.TreeMap<>();
        for (FlipperIr.Entry e : entries) {
            if (e.ir == null || p.buttons.size() >= MAX_BUTTONS) {
                skipped++;
                String reason = e.ir == null ? e.problem : "more than " + MAX_BUTTONS + " buttons";
                why.merge(reason, 1, Integer::sum);
                continue;
            }
            String label = e.name.trim().replaceAll("\\s+", "_");
            String unique = label;
            for (int n = 2; !used.add(unique.toLowerCase(Locale.ROOT)); n++) unique = label + "_" + n;
            Profile.Btn b = new Profile.Btn(p.buttons.size(), unique, "");
            b.ir = e.ir;
            p.buttons.add(b);
        }
        StringBuilder reasons = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> r : why.entrySet()) {
            if (reasons.length() > 0) reasons.append("; ");
            reasons.append(r.getValue()).append("x ").append(r.getKey());
        }
        return new Result(p, skipped, reasons.toString());
    }
}
