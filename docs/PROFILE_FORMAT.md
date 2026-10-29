# Profile format (version 1)

A profile is one JSON file. Codes are **hex strings** (`"0b"`); a bare JSON number is read as decimal.
Example: [android/assets/profiles/zasj047.json](../android/assets/profiles/zasj047.json).

```json
{
  "format": 1,
  "name": "ZASJ047",
  "description": "free text",
  "advertising": {
    "companyId": 255,
    "serviceUuids": ["ae8f"],
    "connectable": true,
    "mode": "low_latency",
    "txPower": "high",
    "timeoutMs": 3000,
    "repeatMs": 1000
  },
  "payload": { "type": "template", "prefix": "6db643ce97fe427c", "xor": "e5",
               "crc": { "poly": "8408", "init": "0000", "reflected": true, "swap": true, "xorOut": "157d" } },
  "off": { "code": "00", "sendMs": 2000 },
  "autoOffSeconds": 180,
  "buttons": [ { "code": "01", "label": "Wave", "icon": "🌊" }, { "code": "02" } ]
}
```

## advertising

The app sends a legacy `ADV_IND` with one *manufacturer specific data* field (company ID + payload) and the listed
service UUIDs, repeated every `repeatMs`.

| field | meaning | default |
|---|---|---|
| `companyId` | 0-65535, first two bytes of the manufacturer data | required |
| `serviceUuids` | 16-bit (`"ae8f"`), 32-bit or full UUIDs added to the advertisement | `[]` |
| `connectable` | `ADV_IND` (true) or non-connectable | `true` |
| `mode` | `low_power`, `balanced`, `low_latency` (advertising interval) | `low_latency` |
| `txPower` | `ultra_low`, `low`, `medium`, `high` | `high` |
| `timeoutMs` | one advertising burst lasts this long (0-180000) | `3000` |
| `repeatMs` | the burst is restarted this often while a code is active (100-60000) | `1000` |

Legacy advertising has 31 bytes: flags (3, if connectable) + manufacturer data (4 + payload) + UUID list
(2 + 2 per 16-bit UUID). Profiles that do not fit are rejected.

## payload

**`"type": "template"`** (default): `packet = prefix + [code xor xor] + crc`

| field | meaning |
|---|---|
| `prefix` | hex bytes in front of the data byte (may be empty) |
| `xor` | the data byte is XORed with this before sending (default `00`) |
| `crc` | optional CRC-16 over the *plain* data byte, appended big-endian |

CRC: `crc = init; if reflected { crc ^= byte; 8 times: crc = crc&1 ? (crc>>1)^poly : crc>>1 }`
else `{ crc ^= byte<<8; 8 times: crc = crc&0x8000 ? (crc<<1)^poly : crc<<1 }`. Then, if `swap`, the two bytes
are exchanged, and finally the result is XORed with `xorOut`. For `reflected` the poly is given in its reflected
form (`8408` for the usual CRC-16/KERMIT polynomial `1021`). The parameters are fitted to captures, so `xorOut` also
absorbs constant parts (such as a device address) that the original algorithm puts into its CRC.

**`"type": "table"`**: `"packets": { "00": "<hex>", "01": "<hex>" }`, complete payloads per code. Use this whenever
you do not know (or do not need) the algorithm. A code without an entry cannot be sent.

## off, autoOffSeconds, buttons

- `off.code` is the code sent for **AUS** (default `00`); it is sent for `sendMs` and then advertising stops.
- `off.silent: true` (instead of `code`): the original app sends no packet for *off*, it just stops. *Off* then only stops advertising.
- `autoOffSeconds` (5-3600): no new command for this long → the app sends the off code by itself.
- `buttons`: order = order on screen. `label` (optional) replaces the hex code, `icon` (optional) is one emoji.

## Learning a profile from captures

`tools/profile_tool.py learn` reads lines `code packet` and tries, in this order: prefix + xor (no CRC), prefix + xor +
CRC-16 from a list of common polynomials (init 0/ffff, reflected or not, byte swap or not; `xorOut` is solved). If
nothing fits it writes a table. At least three different codes are needed to prefer a formula over a table.
