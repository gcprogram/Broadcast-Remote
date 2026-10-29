# Capturing what the original app sends

## Easiest: Learn mode in the app (needs a second phone or tablet)

*Menü → Lernen* on the phone running Broadcast Remote; on a **second** device run the original app and choose the
device. This phone only listens (a phone cannot hear its own broadcasts or read another app's). Follow the steps on
screen: measure the surroundings, then record one function after the other, then *Profil ableiten* and check the
result in the editor. Record at least *off* (code 00) and three functions so that a formula can be derived.

## Alternative: HCI log from a bugreport (one phone + PC)

You need: an Android phone with the original remote app, USB debugging, `adb` on the PC. No root.
The device itself does **not** have to be present: the original app broadcasts regardless.

1. Optional but helpful: toggle Bluetooth off and on once so the Bluetooth log starts clean.
2. Open the original app, choose the device/profile, then press each function **once for a few seconds** and press
   *off* in between. Keep a note of the order (for example: "1, off, 2, off, 3, off, ...").
3. Right afterwards, on the PC:

   ```
   adb bugreport bugreport.zip
   python tools/hci_adv_extract.py bugreport.zip
   ```

   The listing shows, in order, the manufacturer data the app sent (company ID + payload bytes). Match it to your
   key presses. Identical consecutive entries are shown once.
4. Write the pairs into `capture.txt`, one per line, `code packet`:

   ```
   00 6db643ce97fe427ce5157d
   01 6db643ce97fe427ce49c6c
   ```

   (`code` is whatever number you want to call the function; using the function number is natural.)
5. `python tools/profile_tool.py learn capture.txt --name "My device" -o my-device.json`, then load that file in the
   app under *Menü → Setup → Aus Datei laden*, or paste it into *Neues Profil eingeben*.

Notes

- The bugreport only keeps the most recent part of the Bluetooth log, so capture right after the key presses.
- Some apps compute the packet from a device ID (a barcode, a model number); a second device may therefore have a
  different prefix with the same formula, or a different formula altogether. A table profile always works.
- Try the sweep screen afterwards: some devices react to more codes than the original app offers.
- Not every receive-only device works this way. If the original app *connects* to the device (GATT), this project
  does not apply; see the notes in the README.
