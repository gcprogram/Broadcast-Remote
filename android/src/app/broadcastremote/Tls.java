package app.broadcastremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;

import javax.net.ssl.SSLContext;

/** Key and self-signed certificate of the app (stored privately, re-signed when the IP changes). */
final class Tls {
    static final class Identity {
        SSLContext context;
        String pem;
        String fingerprint;
        List<String> ips;
        String path;
    }

    private Tls() {
    }

    /** Private IPv4 addresses of this phone (Wi-Fi, VPN) plus loopback. */
    static List<String> localIps() {
        List<String> ips = new ArrayList<>();
        ips.add("127.0.0.1");
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e.hasMoreElements(); ) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress ia = a.nextElement();
                    if (ia instanceof Inet4Address && RemoteServer.allowed(ia) && !ia.isLinkLocalAddress()) {
                        ips.add(ia.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(ips);
        return ips;
    }

    static Identity prepare(Context c) throws Exception {
        SharedPreferences p = Config.prefs(c);
        KeyPair kp = loadKey(p);
        if (kp == null) {
            kp = Certs.newKeyPair();
            p.edit().putString("key_priv", Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP))
                    .putString("key_pub", Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP))
                    .remove("cert_pem").apply();
        }
        List<String> ips = localIps();
        String sans = ips.toString();
        X509Certificate cert = null;
        String pem = p.getString("cert_pem", null);
        if (pem != null && sans.equals(p.getString("cert_sans", ""))) {
            try {
                cert = Certs.parse(Certs.fromPem(pem));
                cert.checkValidity();
            } catch (Exception e) {
                cert = null;
            }
        }
        if (cert == null) {
            long now = System.currentTimeMillis();
            byte[] der = Certs.selfSigned(kp, Arrays.asList("localhost"), ips,
                    new Date(now - 86400000L), new Date(now + 3L * 365 * 86400000L));
            cert = Certs.parse(der);
            pem = Certs.pem(der);
            p.edit().putString("cert_pem", pem).putString("cert_sans", sans).apply();
        }
        Identity id = new Identity();
        id.context = Certs.sslContext(kp.getPrivate(), cert);
        id.pem = pem;
        id.fingerprint = Certs.fingerprint(cert.getEncoded());
        id.ips = ips;
        File dir = c.getExternalFilesDir(null);
        if (dir != null) {
            File f = new File(dir, "broadcast-remote-cert.pem");
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(pem.getBytes("US-ASCII"));
                id.path = f.getAbsolutePath();
            } catch (Exception ignored) {
            }
        }
        return id;
    }

    private static KeyPair loadKey(SharedPreferences p) {
        String priv = p.getString("key_priv", null);
        String pub = p.getString("key_pub", null);
        if (priv == null || pub == null) return null;
        try {
            KeyFactory f = KeyFactory.getInstance("EC");
            PrivateKey sk = f.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP)));
            PublicKey pk = f.generatePublic(new X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP)));
            return new KeyPair(pk, sk);
        } catch (Exception e) {
            return null;
        }
    }
}
