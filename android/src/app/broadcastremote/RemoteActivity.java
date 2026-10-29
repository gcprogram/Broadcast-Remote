package app.broadcastremote;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Fernsteuerung: Server ausdruecklich ein-/ausschalten, Zugriffstoken, Zertifikat, Verbindungshinweise. */
public class RemoteActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Switch toggle;
    private TextView info;
    private TextView tokenView;
    private boolean updating = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);

        TextView warn = new TextView(this);
        warn.setText("Die Fernsteuerung ist standardmäßig aus und muss hier jedes Mal bewusst eingeschaltet "
                + "werden. Jede Anfrage braucht das Zugriffstoken, es werden nur Verbindungen aus privaten "
                + "Netzen (WLAN, VPN, USB) angenommen. Ohne neuen Befehl schaltet die App nach 3 Minuten "
                + "automatisch aus.");
        root.addView(warn);

        toggle = new Switch(this);
        toggle.setText("Fernsteuerung (HTTPS/MCP)");
        toggle.setTextSize(18);
        toggle.setPadding(0, 32, 0, 16);
        toggle.setOnCheckedChangeListener((b, on) -> {
            if (!updating) setServer(on);
        });
        root.addView(toggle);

        info = new TextView(this);
        info.setTextIsSelectable(true);
        root.addView(info);

        TextView tokenTitle = new TextView(this);
        tokenTitle.setText("Zugriffstoken");
        tokenTitle.setTypeface(null, Typeface.BOLD);
        tokenTitle.setPadding(0, 32, 0, 0);
        root.addView(tokenTitle);
        tokenView = new TextView(this);
        tokenView.setTypeface(Typeface.MONOSPACE);
        tokenView.setTextIsSelectable(true);
        root.addView(tokenView);
        LinearLayout tokenRow = new LinearLayout(this);
        tokenRow.addView(button("Token kopieren", v -> copy("Broadcast-Remote-Token", Config.token(this))));
        tokenRow.addView(button("Neues Token", v -> {
            Config.newToken(this);
            refresh();
            Toast.makeText(this, "Neues Token gilt sofort, das alte ist ungültig", Toast.LENGTH_LONG).show();
        }));
        root.addView(tokenRow);

        LinearLayout portRow = new LinearLayout(this);
        final EditText port = new EditText(this);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(Config.port(this)));
        portRow.addView(port, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        portRow.addView(button("Port setzen", v -> {
            try {
                int p = Integer.parseInt(port.getText().toString().trim());
                if (p < 1024 || p > 65535) throw new NumberFormatException();
                Config.setPort(this, p);
                Toast.makeText(this, "Gilt beim nächsten Einschalten", Toast.LENGTH_SHORT).show();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Port 1024-65535", Toast.LENGTH_SHORT).show();
            }
        }));
        root.addView(portRow);

        root.addView(Ui.wide(button("Zertifikat (PEM) kopieren", v -> {
            if (RemoteService.identity == null) {
                Toast.makeText(this, "Erst einmal einschalten", Toast.LENGTH_SHORT).show();
            } else {
                copy("Broadcast-Remote-Zertifikat", RemoteService.identity.pem);
            }
        })));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return b;
    }

    private void copy(String label, String text) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, "Kopiert", Toast.LENGTH_SHORT).show();
    }

    private void setServer(boolean on) {
        if (on) {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
            }
            startForegroundService(new Intent(this, RemoteService.class));
        } else {
            stopService(new Intent(this, RemoteService.class));
        }
        handler.postDelayed(this::refresh, 800);
    }

    private void refresh() {
        updating = true;
        toggle.setChecked(RemoteService.running);
        updating = false;
        tokenView.setText(Config.token(this));
        StringBuilder s = new StringBuilder();
        if (!RemoteService.error.isEmpty()) s.append("Fehler: ").append(RemoteService.error).append("\n\n");
        Tls.Identity id = RemoteService.identity;
        int port = Config.port(this);
        if (RemoteService.running && id != null) {
            s.append("Läuft. Adressen:\n");
            List<String> ips = id.ips;
            for (String ip : ips) s.append("  https://").append(ip).append(":").append(port).append("/mcp\n");
            s.append("\nZertifikat-Fingerabdruck (SHA-256):\n").append(id.fingerprint).append("\n");
            if (id.path != null) s.append("\nZertifikatsdatei:\n").append(id.path).append("\n");
            s.append("\nAm PC (Claude Code):\n")
                    .append("1. adb pull ").append(id.path == null ? "<Zertifikatsdatei>" : id.path).append(" broadcast-remote-cert.pem\n")
                    .append("2. Umgebungsvariable NODE_EXTRA_CA_CERTS auf den Pfad dieser Datei setzen\n")
                    .append("3. claude mcp add --transport http broadcast-remote https://<IP>:").append(port)
                    .append("/mcp --header \"Authorization: Bearer <Token>\"\n")
                    .append("\nOhne WLAN über USB: adb forward tcp:").append(port).append(" tcp:").append(port)
                    .append(" und dann https://localhost:").append(port).append("/mcp\n");
        } else {
            s.append("Aus. Kein Server, kein offener Port.");
        }
        info.setText(s.toString());
    }
}
