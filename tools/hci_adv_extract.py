"""Liest die Advertising-Pakete, die eine Android-App gesendet hat, aus einem Bugreport.

Android haelt im Bugreport eine Zusammenfassung des Bluetooth-HCI-Logs (BTSNOOP_LOG_SUMMARY). Darin stehen
die HCI-Kommandos "LE Set (Extended) Advertising Data" mit den Paketen, die die Original-App der Fernbedienung
gesendet hat. Ohne Root, ohne Spezial-Hardware.

Ablauf:
  1. Original-App oeffnen, Geraet/Profil waehlen, nacheinander jede Funktion kurz druecken, dazwischen Aus.
  2. Sofort danach:   adb bugreport bugreport.zip
  3. python hci_adv_extract.py bugreport.zip
Das Geraet muss nicht anwesend sein: die App sendet auch ohne Empfaenger.

Die Ausgabe zeigt je Sendevorgang die Herstellerdaten (Company-ID + Nutzbytes) in der Reihenfolge, in der die
App sie gesendet hat. Ordne sie deinen Tastendruecken zu und schreibe sie als "code paket"-Zeilen in eine
Mitschnitt-Datei fuer profile_tool.py.
"""
import base64
import collections
import struct
import sys
import zipfile
import zlib

MARKER_BEGIN = "BEGIN:BTSNOOP_LOG_SUMMARY"
MARKER_END = "END:BTSNOOP_LOG_SUMMARY"
OP_SET_ADV_DATA = 0x2008
OP_SET_EXT_ADV_DATA = 0x2037


def read_text(path):
    if path.lower().endswith(".zip"):
        with zipfile.ZipFile(path) as z:
            for name in z.namelist():
                if name.endswith(".txt"):
                    text = z.read(name).decode("utf-8", "replace")
                    if MARKER_BEGIN in text:
                        return text
        raise SystemExit("Im Bugreport steht kein BTSNOOP_LOG_SUMMARY (Bluetooth-Log leer?).")
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read()


def decode_summary(text):
    lines = text.split("\n")
    start = next(i for i, l in enumerate(lines) if MARKER_BEGIN in l)
    end = next(i for i, l in enumerate(lines) if MARKER_END in l)
    raw = base64.b64decode("".join(l.strip() for l in lines[start + 1:end]))
    return zlib.decompressobj().decompress(raw[9:])        # Byte 0 Version, 1-8 Zeitstempel


def hci_commands(data):
    """Liefert (Millisekunden, Opcode, Parameter) aller HCI-Kommandos."""
    i, t = 0, 0
    while i + 9 <= len(data):
        length, _orig, delta, kind = struct.unpack_from("<HHIB", data, i)
        body = data[i + 9:i + 8 + length]
        i += 8 + length
        t += delta
        if kind == 0x20 and len(body) >= 3:
            yield t, body[0] | body[1] << 8, body[3:]


def ad_structures(adv):
    i = 0
    while i < len(adv) and adv[i]:
        n = adv[i]
        yield adv[i + 1], adv[i + 2:i + 1 + n]
        i += 1 + n


def advertisements(data):
    for t, op, params in hci_commands(data):
        if op == OP_SET_EXT_ADV_DATA and len(params) >= 4:
            yield t, params[4:4 + params[3]]
        elif op == OP_SET_ADV_DATA and params:
            yield t, params[1:1 + params[0]]


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    data = decode_summary(read_text(sys.argv[1]))
    counts = collections.Counter()
    print("   Zeit s  Company  Nutzbytes                    weitere AD-Typen")
    last = None
    for t, adv in advertisements(data):
        mfg, others = None, []
        for kind, value in ad_structures(adv):
            if kind == 0xFF and len(value) >= 2:
                mfg = (value[0] | value[1] << 8, value[2:])
            else:
                others.append("%02x:%s" % (kind, value.hex()))
        if mfg is None:
            continue
        key = (mfg[0], mfg[1])
        counts[key] += 1
        if key != last:                                     # Wiederholungen derselben Nutzbytes nur einmal zeigen
            print("%9.2f  0x%04x   %-27s  %s" % (t / 1000, mfg[0], mfg[1].hex(" "), " ".join(others)))
            last = key
    print("\nHaeufigkeit je Nutzbytes (Wiederholungen alle ~1 s):")
    for (company, payload), n in counts.most_common():
        print("  0x%04x  %s  x%d" % (company, payload.hex(" "), n))


if __name__ == "__main__":
    main()
