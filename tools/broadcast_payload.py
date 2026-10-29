"""Packet computation for Broadcast-Remote profiles (same logic as android/.../Payload.java).

A profile (JSON, see docs/PROFILE_FORMAT.md) describes either a template
    prefix + [code ^ xor] + CRC-16 (optional, two bytes big endian)
or a table of finished packets per code.
"""
import json


def crc16(data_byte, poly, init=0, reflected=True, swap=False, xor_out=0):
    """CRC-16 over one byte. Output = (swap ? swapped bytes : crc) ^ xor_out."""
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
    """Hex string ("0b", "0x0b") or number -> int (numbers in the JSON are decimal, strings are hex)."""
    if isinstance(value, int):
        return value
    return int(str(value).strip().removeprefix("0x").removeprefix("0X"), 16)


def load_profile(path):
    with open(path, encoding="utf-8-sig") as f:
        return json.load(f)


def packet(profile, code):
    """Payload bytes for one code. Raises KeyError if the table does not contain the code."""
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
