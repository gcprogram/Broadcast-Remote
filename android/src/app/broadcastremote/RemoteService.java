package app.broadcastremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

/** Foreground service: keeps the HTTPS/MCP server running. Only started explicitly in the app. */
public class RemoteService extends Service {
    static final String ACTION_STOP = "app.broadcastremote.STOP";
    private static final String CHANNEL = "remote";

    static volatile boolean running = false;
    static volatile String error = "";
    static volatile Tls.Identity identity;

    private RemoteServer server;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        error = "";
        try {
            startInForeground("Server starting ...");        // required shortly after startForegroundService
            identity = Tls.prepare(this);
            int port = Config.port(this);
            String host = "localhost";
            for (String ip : identity.ips) {
                if (!ip.startsWith("127.")) {
                    host = ip;
                    break;
                }
            }
            startInForeground("https://" + host + ":" + port + "/mcp");
            final Controller ctl = Controller.get(this);
            server = new RemoteServer(identity.context, port, () -> Config.token(this), new RemoteServer.Api() {
                @Override public String send(int code) { return ctl.sendSync(code, "mcp"); }
                @Override public String off() { return ctl.sendSync(Controller.OFF, "mcp"); }
                @Override public String status() { return ctl.describe(); }
                @Override public JSONArray buttons() {
                    JSONArray a = new JSONArray();
                    Profile p = ctl.profile();
                    if (p == null) return a;
                    try {
                        for (Profile.Btn b : p.buttons) {
                            JSONObject o = new JSONObject().put("code", b.code).put("hex", String.format("%02x", b.code));
                            if (!b.label.isEmpty()) o.put("label", b.label);
                            a.put(o);
                        }
                    } catch (Exception ignored) {
                    }
                    return a;
                }
            });
            server.start();
            running = true;
        } catch (Exception e) {
            error = e.toString();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startInForeground(String url) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Remote control",
                NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, RemoteService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Broadcast Remote: remote control active")
                .setContentText(url)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", pi).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(1, n);
        }
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        server = null;
        running = false;
        Controller.get(this).send(Controller.OFF, "remote control stopped");
        super.onDestroy();
    }
}
