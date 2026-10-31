# MivimooseRips Scanner

A Windows app that scans the Pokémon cards you pull from packs, prices them using the
MivimooseRips card database, and tracks your packs and set progress. While you wear a
Steam Frame (or any SteamVR headset) linked to the PC, it shows a floating panel in VR.

`dist\MivimooseScanner.exe` is the whole app. There's nothing to install.

## Quick start

1. Run `MivimooseScanner.exe`. The first start downloads the card database (about 7 MB).
2. Choose the set you're opening. The scanner then downloads that set's card images once
   (about 10–20 seconds), so it can recognise every card in it.
3. Hold each card up to the camera, face out, until you hear the chime. That's it:
   - a **new card** is added to your collection,
   - a card you **already own** is flagged **DUPLICATE**, with its own sound,
   - the **same card shown twice in one pack** is ignored, so it's never counted twice,
   - when the pack's card count is reached, the **pack is marked done** (with value
     against what you paid), and the next pack starts.

## Using it with the Steam Frame

1. Install **SteamVR** on this PC (Steam → Library → Tools → SteamVR). It isn't installed yet.
2. Link the Steam Frame to the PC (Steam Link / PC VR streaming) so SteamVR is running.
3. Start the scanner, before or after SteamVR. Within a few seconds you'll see:
   - a **floating panel**: last card, price, NEW/DUPLICATE, pack progress, set progress,
     camera view, and buttons (Undo · Reverse · Next pack · Pause),
   - a **"MivimooseRips Scanner" tab in the SteamVR dashboard**: a bigger control panel
     (panel position, recenter, camera view, voice on/off).
4. Prices can also be read aloud (Settings → Sounds & voice), so you don't have to look.

### Which camera does the scanning?

- **Headset camera:** chosen automatically if SteamVR offers the headset's camera to apps.
  Turn it on in SteamVR Settings → Video → Camera. **It's not confirmed that the
  Steam Frame's passthrough cameras are available to PC apps.** If the scanner says
  "SteamVR reports no headset camera", use a webcam instead. The Steam Frame's built-in
  cameras are black-and-white; the scanner works in greyscale, so that's fine if they're
  available.
- **Any webcam:** for example your Logi C270 on a stand, pointing at where you open packs.
  This is often the most reliable setup: steady, sharp, and your hands are free. Pick it in
  Settings → Camera.

## Tips for fast, accurate scans

- Fill roughly a third to half of the camera view with the card, and hold it still for half a second.
- A plain, darker background behind the card works best. Avoid strong glare on holos
  (Settings → Camera → Exposure can help).
- If your camera struggles to find card edges, set **Finding the card** to
  *Automatic, else guide box* and hold the card so it fills the box.
- **Reverse holos** can't be told apart reliably by a camera. Press **Reverse** (or `R`)
  before scanning one, or fix it afterwards with the Normal / Reverse holo / Holo buttons.
- Wrong card? Click **Wrong card?** or pick one of the suggested look-alikes.

### Keyboard shortcuts (desktop window)

`Space` pause · `Z` undo · `R` next card is reverse holo · `N` next pack · `A` add a card by hand ·
`S` choose set · `1`–`5` switch tabs

## Options (Settings tab)

Camera choice, resolution, rotate/mirror, exposure, which headset eye to read ·
scan sensitivity (relaxed / balanced / strict) · automatic or guide-box detection ·
which sets to look for · how long a card must leave view before rescanning ·
what to do with duplicates · cards per pack and your pack price · auto-start next pack ·
every sound on/off with volume, hit thresholds and voice announcements · currency
(USD, CAD, EUR, GBP, AUD, JPY, MXN, NZD) · VR panel position (in front, follows head,
left or right hand), size, distance, height, opacity, refresh rate · start with SteamVR ·
data source and refresh interval · open the dashboard from other devices on your network.

## Where your data lives

`%APPDATA%\MivimooseScanner\`: `collection.db` (every scan and pack), `settings.json`,
the card database cache, per-set scan data, and `logs\scanner.log`. Export everything as
CSV from the Collection tab.

## How recognition works

For each camera frame, the scanner:

1. finds the card's outline, snapping it to the straight edges, so fingers over the
   corners don't matter;
2. straightens the card and compares it against every card in the set at once, using
   lighting-independent fingerprints;
3. confirms the best candidates by checking that their keypoints line up with the card's
   real scan. The right card typically lines up 80–300 keypoints; any other card about 10.

A card is recorded only after several frames in a row agree.

On synthetic test footage (tilted cards, fingers, glare, blur, black-and-white sensor),
it runs at about 25 frames a second on this PC. One test video had 11 different cards:
10 were scanned automatically and none were recorded wrongly. The miss was a small,
blurry card on a very noisy background.

## Building from source

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1     # → dist\MivimooseScanner.exe
```

Run from source with `.venv\Scripts\python.exe run.py`. Useful flags: `--browser` (open
in your browser), `--headless`, `--camera file:C:\clip.mp4` (scan a video), `--data-dir`.

Tests:

```powershell
.venv\Scripts\python.exe -m tests.test_engine <cache dir>              # pack / duplicate / undo rules
.venv\Scripts\python.exe -m tests.bench_vision <cache dir> sv3pt5 base1 # recognition benchmark
.venv\Scripts\python.exe -m tests.make_demo_video <cache dir> sv3pt5 demo.mp4
```

`<cache dir>` is a folder containing `data\bundle.js` and `data\pack-art.js`
(`%APPDATA%\MivimooseScanner` works once the app has run).
