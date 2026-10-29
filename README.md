# Broadcast-Remote

An Android app that remote-controls **receive-only Bluetooth LE devices**: gadgets that never advertise a name,
never accept a connection and simply listen for a broadcast packet from their original remote app. The app sends
those packets from the phone, driven by a **device profile** (a small JSON file), and can optionally be controlled
over HTTPS from a PC or from an AI assistant through an [MCP](https://modelcontextprotocol.io) interface.

> Status: working with one device (ZASJ047, from the LS-Remote app family). Support for other devices is a matter
> of adding a profile. See [docs/CAPTURE_GUIDE.md](docs/CAPTURE_GUIDE.md).
> The user interface is currently in German.

## What it does

- **Buttons screen** (main page): one button per function code of the active profile, a big **AUS** button, status
  line. Buttons are re-orderable, can have equal-size icons and a label (default: the hex code).
- **Profiles**: how to send (company ID, service UUIDs, advertising mode, repeat rate), how to compute the packet,
  which buttons exist. Load from / save to a file, copy, delete, or type one in (JSON editor with packet preview).
- **Learn mode** (own screen): listens while a **second device** runs the original app, records the packet of every
  function you press and derives a profile (formula if one fits, otherwise a table of packets). A phone cannot hear
  its own broadcasts, so the commands have to come from another phone or tablet. The device itself need not be there.
- **Sweep** (own screen): sends every code in a range, one after the other. Tap *MERKEN* when the device reacts: the
  code becomes a button in the active profile.
- **Listen test** (own screen): sends a code and scans at the same time to see whether the device sends *anything*
  back (new advertisers, connection attempts). For the supported device the answer was: nothing, it only receives.
- **Remote control** (own screen, **off by default**): HTTPS + MCP server inside the app, protected by an access
  token. Reachable from the local network, VPN and USB only. After a configurable time without a new command the app
  switches the device off by itself (default 3 minutes).
- **Safety**: no Bluetooth, no sending. Remote control must be switched on explicitly each time.

## Build

Needs the Android SDK (build-tools 35, platform 35), a JDK and PowerShell. No Gradle.

```powershell
cd android
powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1   # -> android/broadcast-remote.apk
adb install -r broadcast-remote.apk
```

`build.ps1` creates a debug keystore on first run (`android/debug.keystore`, not committed).

## Control from a PC / AI assistant

1. In the app: *Menü → Fernsteuerung*, switch it on. Note the address, copy the token. The certificate is written to
   `/sdcard/Android/data/app.broadcastremote/files/broadcast-remote-cert.pem`.
2. `adb pull` that file, for example to `~/.broadcast-remote/cert.pem`.
3. Set `BROADCAST_REMOTE_TOKEN` (and use `BROADCAST_REMOTE_URL`) and register the bridge as an MCP server, see
   [.mcp.json.example](.mcp.json.example). The bridge ([pc/broadcast_remote_bridge.py](pc/broadcast_remote_bridge.py))
   pins the app's self-signed certificate itself, so nothing is added to the operating system's trust store, and
   the token never has to be written into a config file.

Tools exposed over MCP: `send(code)`, `off`, `status`, `buttons`.
Without USB, use the phone's Wi-Fi address; over the internet use a VPN such as Tailscale, never a port forward.

## Adding a device

A profile either describes the packet by a formula (`prefix + (code xor k) + CRC-16`) or lists complete packets per
code (a *table*, always works). Capture what the original app sends (works without the device present), then:

```
python tools/hci_adv_extract.py bugreport.zip              # what did the original app send?
python tools/profile_tool.py learn capture.txt --name X    # derive a profile (formula if one fits, else a table)
python tools/profile_tool.py check profile.json capture.txt
```

Format: [docs/PROFILE_FORMAT.md](docs/PROFILE_FORMAT.md). Tests: `python -I -m unittest discover -s tests` and
`tests/PayloadCheck.java` (checks the Java engine against the same captured packets).

## Legal and safety

Use it only with devices you own. This project is not affiliated with any device or app vendor and contains no code
or files from vendor apps: the protocol notes were obtained by observing the Bluetooth packets such an app sends,
for interoperability. Treat the access token like a password.

## License

MIT, see [LICENSE](LICENSE).
