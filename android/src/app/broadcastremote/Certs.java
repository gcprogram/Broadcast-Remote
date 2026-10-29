package app.broadcastremote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;

/** Selbstsigniertes EC-Zertifikat (mit SAN fuer IPs/Namen) von Hand in DER gebaut - ohne Zusatzbibliothek. */
final class Certs {
    private static final byte[] SIG_ALG = tlv(0x30, tlv(0x06,
            new byte[]{0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x04, 0x03, 0x02}));   // ecdsa-with-SHA256

    private Certs() {
    }

    static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    private static byte[] tlv(int tag, byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        if (n < 128) {
            o.write(n);
        } else if (n < 256) {
            o.write(0x81);
            o.write(n);
        } else {
            o.write(0x82);
            o.write(n >> 8);
            o.write(n & 0xff);
        }
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private static byte[] time(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    /** Zertifikat (DER) fuer den Schluessel; Namen als DNS-, IPv4-Adressen als iPAddress-Eintrag. */
    static byte[] selfSigned(KeyPair kp, List<String> dnsNames, List<String> ips, Date from, Date to)
            throws GeneralSecurityException, java.io.IOException {
        byte[] serial = new byte[8];
        new SecureRandom().nextBytes(serial);
        serial[0] = (byte) ((serial[0] & 0x3f) | 0x40);      // positiv, kein fuehrendes Nullbyte

        byte[] name = tlv(0x30, tlv(0x31, tlv(0x30, tlv(0x06, new byte[]{0x55, 0x04, 0x03}),
                tlv(0x0C, "Broadcast Remote".getBytes(StandardCharsets.UTF_8)))));

        ByteArrayOutputStream san = new ByteArrayOutputStream();
        for (String d : dnsNames) {
            byte[] e = tlv(0x82, d.getBytes(StandardCharsets.US_ASCII));
            san.write(e, 0, e.length);
        }
        for (String ip : ips) {
            byte[] e = tlv(0x87, InetAddress.getByName(ip).getAddress());
            san.write(e, 0, e.length);
        }
        byte[] basic = tlv(0x30, tlv(0x06, new byte[]{0x55, 0x1D, 0x13}), tlv(0x01, new byte[]{(byte) 0xFF}),
                tlv(0x04, tlv(0x30, tlv(0x01, new byte[]{(byte) 0xFF}))));
        byte[] sanExt = tlv(0x30, tlv(0x06, new byte[]{0x55, 0x1D, 0x11}),
                tlv(0x04, tlv(0x30, san.toByteArray())));

        byte[] tbs = tlv(0x30,
                tlv(0xA0, tlv(0x02, new byte[]{0x02})),             // Version 3
                tlv(0x02, serial),
                SIG_ALG,
                name,
                tlv(0x30, time(from), time(to)),
                name,
                kp.getPublic().getEncoded(),
                tlv(0xA3, tlv(0x30, basic, sanExt)));

        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        return tlv(0x30, tbs, SIG_ALG, tlv(0x03, bits));
    }

    static X509Certificate parse(byte[] der) throws GeneralSecurityException {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    static String pem(byte[] der) {
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END CERTIFICATE-----\n";
    }

    static byte[] fromPem(String pem) {
        String body = pem.replace("-----BEGIN CERTIFICATE-----", "").replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    static String fingerprint(byte[] der) throws GeneralSecurityException {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(der);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < h.length; i++) s.append(i > 0 ? ":" : "").append(String.format("%02X", h[i]));
        return s.toString();
    }

    static SSLContext sslContext(final PrivateKey key, final X509Certificate cert) throws GeneralSecurityException {
        KeyManager km = new X509ExtendedKeyManager() {
            @Override public String[] getClientAliases(String t, Principal[] i) { return null; }
            @Override public String chooseClientAlias(String[] t, Principal[] i, Socket s) { return null; }
            @Override public String[] getServerAliases(String t, Principal[] i) { return new String[]{"k"}; }
            @Override public String chooseServerAlias(String t, Principal[] i, Socket s) { return "k"; }
            @Override public String chooseEngineServerAlias(String t, Principal[] i, SSLEngine e) { return "k"; }
            @Override public X509Certificate[] getCertificateChain(String a) { return new X509Certificate[]{cert}; }
            @Override public PrivateKey getPrivateKey(String a) { return key; }
        };
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(new KeyManager[]{km}, null, null);
        return c;
    }
}
