package app.broadcastremote;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** App-Einstellungen: Zugriffstoken, Port, aktives Profil. Geraeteprofile selbst liegen in ProfileStore. */
final class Config {
    private Config() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("broadcast-remote", Context.MODE_PRIVATE);
    }

    static String token(Context c) {
        String t = prefs(c).getString("token", null);
        return t != null ? t : newToken(c);
    }

    static String newToken(Context c) {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        prefs(c).edit().putString("token", s.toString()).apply();
        return s.toString();
    }

    static int port(Context c) {
        return prefs(c).getInt("port", 8443);
    }

    static void setPort(Context c, int port) {
        prefs(c).edit().putInt("port", port).apply();
    }
}
