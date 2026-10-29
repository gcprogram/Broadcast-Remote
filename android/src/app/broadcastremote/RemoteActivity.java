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

/** Remote control: switch the server on/off explicitly, access token, certificate, connection hints. */
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
        warn.setText("Remote control is off by default and has to be switched on here deliberately each time. "
                + "Every request needs the access token, and only connections from private networks "
                + "(Wi-Fi, VPN, USB) are accepted. Without a new command the app switches off by itself "
                + "after the auto-off time of the profile.");
        root.addView(warn);

        toggle = new Switch(this);
        toggle.setText("Remote control (HTTPS/MCP)");
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
        tokenTitle.setText("Access token");
        tokenTitle.setTypeface(null, Typeface.BOLD);
        tokenTitle.setPadding(0, 32, 0, 0);
        root.addView(tokenTitle);
        tokenView = new TextView(this);
        tokenView.setTypeface(Typeface.MONOSPACE);
        tokenView.setTextIsSelectable(true);
        root.addView(tokenView);
        LinearLayout tokenRow = new LinearLayout(this);
        tokenRow.addView(button("Copy token", v -> copy("Broadcast-Remote-Token", Config.token(this))));
        tokenRow.addView(button("New token", v -> {
            Config.newToken(this);
            refresh();
            Toast.makeText(this, "The new token is valid immediately, the old one is void", Toast.LENGTH_LONG).show();
        }));
        root.addView(tokenRow);

        LinearLayout portRow = new LinearLayout(this);
        final EditText port = new EditText(this);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(Config.port(this)));
        portRow.addView(port, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        portRow.addView(button("Set port", v -> {
            try {
                int p = Integer.parseInt(port.getText().toString().trim());
                if (p < 1024 || p > 65535) throw new NumberFormatException();
                Config.setPort(this, p);
                Toast.makeText(this, "Takes effect the next time it is switched on", Toast.LENGTH_SHORT).show();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Port 1024-65535", Toast.LENGTH_SHORT).show();
            }
        }));
        root.addView(portRow);

        root.addView(Ui.wide(button("Copy certificate (PEM)", v -> {
            if (RemoteService.identity == null) {
                Toast.makeText(this, "Switch on once first", Toast.LENGTH_SHORT).show();
            } else {
                copy("Broadcast-Remote-certificate", RemoteService.identity.pem);
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
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
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
        if (!RemoteService.error.isEmpty()) s.append("Error: ").append(RemoteService.error).append("\n\n");
        Tls.Identity id = RemoteService.identity;
        int port = Config.port(this);
        if (RemoteService.running && id != null) {
            s.append("Running. Addresses:\n");
            List<String> ips = id.ips;
            for (String ip : ips) s.append("  https://").append(ip).append(":").append(port).append("/mcp\n");
            s.append("\nCertificate fingerprint (SHA-256):\n").append(id.fingerprint).append("\n");
            if (id.path != null) s.append("\nCertificate file:\n").append(id.path).append("\n");
            s.append("\nOn the PC (see docs/MCP.md):\n")
                    .append("1. adb pull ").append(id.path == null ? "<certificate file>" : id.path).append(" ~/.broadcast-remote/cert.pem\n")
                    .append("2. Set BROADCAST_REMOTE_TOKEN (token above) and BROADCAST_REMOTE_URL (address above)\n")
                    .append("3. Register pc/broadcast_remote_bridge.py as a stdio MCP server\n")
                    .append("\nWithout Wi-Fi, over USB: adb forward tcp:").append(port).append(" tcp:").append(port)
                    .append(" and then use https://localhost:").append(port).append("/mcp\n");
        } else {
            s.append("Off. No server, no open port.");
        }
        info.setText(s.toString());
    }
}
