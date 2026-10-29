"""Werkzeug fuer Broadcast-Remote-Profile.

  python profile_tool.py packet  profil.json 01 0b        Pakete fuer Codes berechnen (hex)
  python profile_tool.py check   profil.json mitschnitt.txt   Berechnung gegen Mitschnitt pruefen
  python profile_tool.py learn   mitschnitt.txt --name X       Profil aus Mitschnitt ableiten

Mitschnitt-Datei: eine Zeile je Paket, "code paket" in hex, z. B.
  00 6db643ce97fe427ce5157d
  01 6db643ce97fe427ce49c6c
Zeilen mit # sind Kommentare. Die Pakete liefert tools/hci_adv_extract.py.
"""
import argparse
import itertools
import json
import sys

import broadcast_payload as bp

KNOWN_POLYS = [0x1021, 0x8408, 0x8005, 0xA001, 0x3D65, 0xA6BC, 0x8BB7, 0x0589, 0x755B, 0x1DCF]


def read_capture(path):
    out = {}
    with open(path, encoding="utf-8-sig") as f:
        for line in f:
            line = line.split("#")[0].strip()
            if not line:
                continue
            code, data = line.split(None, 1)
            out[int(code, 16)] = bytes.fromhex(data.replace(" ", ""))
    return out


def learn(capture, name):
    """Leitet ein Profil aus Paaren (Code, Paket) ab. Vorlage, wenn Prefix/XOR/CRC passen, sonst Tabelle."""
    lengths = {len(p) for p in capture.values()}
    base = {
        "format": 1, "name": name,
        "advertising": {"companyId": 255, "serviceUuids": [], "connectable": True, "mode": "low_latency",
                        "txPower": "high", "timeoutMs": 3000, "repeatMs": 1000},
        "off": {"code": "00", "sendMs": 2000}, "autoOffSeconds": 180,
        "buttons": [{"code": "%02x" % c} for c in sorted(capture) if c != 0],
    }
    table = {"type": "table", "packets": {"%02x" % c: p.hex() for c, p in sorted(capture.items())}}
    if len(lengths) != 1 or len(capture) < 3:
        base["payload"] = table
        return base, "Tabelle (zu wenige oder unterschiedlich lange Pakete)"
    n = lengths.pop()
    codes = sorted(capture)

    # Variante ohne CRC: prefix + (code ^ xor)
    prefix = capture[codes[0]][:n - 1]
    xors = {capture[c][n - 1] ^ c for c in codes}
    if all(capture[c][:n - 1] == prefix for c in codes) and len(xors) == 1:
        base["payload"] = {"type": "template", "prefix": prefix.hex(), "xor": "%02x" % xors.pop()}
        return base, "Vorlage ohne CRC"

    # Variante mit CRC: prefix + (code ^ xor) + crc16
    if n >= 3:
        prefix = capture[codes[0]][:n - 3]
        xors = {capture[c][n - 3] ^ c for c in codes}
        if all(capture[c][:n - 3] == prefix for c in codes) and len(xors) == 1:
            xor = xors.pop()
            observed = {c: (capture[c][n - 2] << 8) | capture[c][n - 1] for c in codes}
            for poly, init, reflected, swap in itertools.product(KNOWN_POLYS, (0, 0xFFFF), (True, False), (False, True)):
                outs = {observed[c] ^ bp.crc16(c, poly, init, reflected, swap, 0) for c in codes}
                if len(outs) == 1:
                    base["payload"] = {
                        "type": "template", "prefix": prefix.hex(), "xor": "%02x" % xor,
                        "crc": {"poly": "%04x" % poly, "init": "%04x" % init, "reflected": reflected,
                                "swap": swap, "xorOut": "%04x" % outs.pop()}}
                    return base, "Vorlage mit CRC-16 (Modell passt zu allen %d Paketen)" % len(codes)
    base["payload"] = table
    return base, "Tabelle (kein bekanntes Modell passt)"


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("packet")
    p.add_argument("profile")
    p.add_argument("codes", nargs="+")
    c = sub.add_parser("check")
    c.add_argument("profile")
    c.add_argument("capture")
    l = sub.add_parser("learn")
    l.add_argument("capture")
    l.add_argument("--name", default="Neues Profil")
    l.add_argument("-o", "--output")
    a = ap.parse_args(argv)

    if a.cmd == "packet":
        prof = bp.load_profile(a.profile)
        for code in a.codes:
            n = int(code, 16)
            try:
                print("%02x  %s" % (n, bp.packet(prof, n).hex(" ")))
            except KeyError:
                print("%02x  FEHLT in der Tabelle" % n)
        return 0
    if a.cmd == "check":
        prof = bp.load_profile(a.profile)
        bad = 0
        for code, want in sorted(read_capture(a.capture).items()):
            try:
                got = bp.packet(prof, code)
            except KeyError:
                got = None
            ok = got == want
            bad += not ok
            print("%02x  %s  %s" % (code, "ok " if ok else "FEHLER", want.hex(" ") + ("" if ok else "  berechnet: " + (got.hex(" ") if got else "-"))))
        print("alle Pakete stimmen" if not bad else "%d Abweichungen" % bad)
        return 1 if bad else 0
    prof, how = learn(read_capture(a.capture), a.name)
    text = json.dumps(prof, indent=2, ensure_ascii=False)
    print("# " + how, file=sys.stderr)
    if a.output:
        with open(a.output, "w", encoding="utf-8") as f:
            f.write(text + "\n")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
