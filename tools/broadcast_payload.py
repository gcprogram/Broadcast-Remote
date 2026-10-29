"""Paketberechnung der Broadcast-Remote-Profile (gleiche Logik wie android/.../Payload.java).

Ein Profil (JSON, siehe docs/PROFILE_FORMAT.md) beschreibt entweder eine Vorlage
    prefix + [code ^ xor] + CRC-16 (optional, zwei Bytes Big-Endian)
oder eine Tabelle fertiger Pakete je Code.
"""
import json


def crc16(data_byte, poly, init=0, reflected=True, swap=False, xor_out=0):
    """CRC-16 ueber ein Byte. Ausgabe = (swap ? vertauschte Bytes : crc) ^ xor_out."""
    crc = init & 0xFFFF
    if reflected:
        crc ^= data_byte & 0xFF
        for _ in range(8):
            crc = (crc >> 1) ^ poly if crc & 1 else crc >> 1
    else:
        crc ^= (data_byte & 0xFF) << 8
        for _ in range(8):
            crc = ((crc << 1) ^ poly if crc & 0x8000 else crc << 1) & 0xFFFF
    if swap:
        crc = ((crc & 0xFF) << 8) | (crc >> 8)
    return (crc ^ xor_out) & 0xFFFF


def _num(value):
    """Hexstring ("0b", "0x0b") oder Zahl -> int (Zahlen im JSON sind dezimal, Strings sind hex)."""
    if isinstance(value, int):
        return value
    return int(str(value).strip().removeprefix("0x").removeprefix("0X"), 16)


def load_profile(path):
    with open(path, encoding="utf-8-sig") as f:
        return json.load(f)


def packet(profile, code):
    """Nutzbytes fuer einen Code. Wirft KeyError, wenn die Tabelle den Code nicht enthaelt."""
    pl = profile["payload"]
    if pl.get("type", "template") == "table":
        table = {_num(k): bytes.fromhex(v) for k, v in pl["packets"].items()}
        return table[code]
    prefix = bytes.fromhex(pl.get("prefix", ""))
    out = prefix + bytes([(code ^ _num(pl.get("xor", 0))) & 0xFF])
    crc = pl.get("crc")
    if crc:
        value = crc16(code, _num(crc["poly"]), _num(crc.get("init", 0)), crc.get("reflected", True),
                      crc.get("swap", False), _num(crc.get("xorOut", 0)))
        out += bytes([value >> 8, value & 0xFF])
    return out


def off_code(profile):
    return _num(profile.get("off", {}).get("code", 0))


def button_codes(profile):
    return [_num(b["code"]) for b in profile.get("buttons", [])]
