# Profile format (version 1)

A profile is one JSON file. Codes are **hex strings** (`"0b"`); a bare JSON number is read as decimal.
Example: [android/assets/profiles/zasj047.json](../android/assets/profiles/zasj047.json).

```json
{
  "format": 1,
  "name": "video",
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

- `off.code` is the code sent for **OFF** (default `00`); it is sent for `sendMs` and then advertising stops.
- `off.silent: true` (instead of `code`): the original app sends no packet for *off*, it just stops. *Off* then only stops advertising.
- `autoOffSeconds` (5-3600): no new command for this long → the app sends the off code by itself.
- `buttons`: order = order on screen. `label` (optional) replaces the hex code, `icon` (optional) is one emoji,
  `example` (optional) is a short text of what the button does (shown to assistants by the MCP tool `buttons`).
- **Macro button**: a button with `macro` is not sent itself, its `code` is only its name (the app picks an unused one, counted
  down from `ff`). The steps are played one after another, then *off* is sent:
  `{ "code": "fa", "label": "HDMI1", "macro": [ { "code": "11", "holdMs": 1000 }, { "code": "2a", "holdMs": 1000 } ] }`.
  Each step is a plain button of the profile (no macro inside a macro) sent for `holdMs` (200-30000, default 1000), at most
  20 steps. A **pause** is a step `{ "pauseMs": 2000 }` (100-60000): nothing is sent for that time (sending stops, the device
  just gets no packet), then the next step follows. An **off step** `{ "offMs": 2000 }` (200-30000) sends the profile's off
  code for that time, like the OFF button (a profile with a silent off just stops sending). A button step is not followed by
  an off by itself: put an off step after it if the device must be switched off before the next step. A macro needs at least
  one button step. Any other command stops a running macro. In the app: *Menu → Edit buttons → New macro button* (no JSON needed; "Add pause" adds a pause with its own time).

## Infrared profiles

`"transport": "ir"` (default `"ble"`) sends through the phone's infrared emitter. Such a profile has **no** `advertising` and `payload`; every
button (except macros) carries its own signal in `ir`, and `off` is always silent (infrared sends one signal per press, off only stops repeating):

```json
{ "format": 1, "name": "Samsung (IR)", "transport": "ir",
  "buttons": [
    { "code": "00", "label": "Power",  "ir": { "protocol": "Samsung32", "address": "07", "command": "02" } },
    { "code": "01", "label": "Vol_up", "ir": { "protocol": "NECext", "address": "fb04", "command": "e51a" } },
    { "code": "02", "label": "Blue",   "ir": { "protocol": "raw", "carrier": 38000, "data": [9000, 4500, 560, 560, 560, 1690, 560] } } ] }
```

- `protocol`: `NEC` (8-bit address and command), `NECext` (16/16), `Samsung32` (8-bit address, 8- or 16-bit command), `RC5` (5/6 bit), `RC5X` (5/7),
  `RC6` (mode 0, 8/8), `SIRC` (5/7), `SIRC15` (8/7), `SIRC20` (13/7), `RCA` (4/8) or `raw`. Address and command are hex strings like in the
  Flipper-IRDB (little endian, the app converts).
- `raw`: `carrier` in Hz (10000-100000) and `data` = durations in microseconds, alternating mark/space, starting with a mark (together under
  2 seconds, each at most 200 ms).
- `code` is only the name of the button (0-255), the profile may have at most 200 buttons so that macros keep free codes.
- A press sends the signal once. A **macro** step sends it, then repeats it for the hold time (NEC: the short repeat frame, SIRC: the frame, others: the
  same frame again; RC5/RC6 toggle bit changes with every new press).
- *Menu → Find infrared remote (catalog)* builds such profiles from the [Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB) (CC0).

## Learning a profile from captures

`tools/profile_tool.py learn` reads lines `code packet` and tries, in this order: prefix + xor (no CRC), prefix + xor +
CRC-16 from a list of common polynomials (init 0/ffff, reflected or not, byte swap or not; `xorOut` is solved). If
nothing fits it writes a table. At least three different codes are needed to prefer a formula over a table.
