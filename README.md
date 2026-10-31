# MivimooseRips Scanner

A Windows app that scans the Pokémon cards you pull, prices them using the
MivimooseRips card database, and tracks your packs and set progress. With a Steam
Frame (or any SteamVR headset) linked to the PC, its panels float in your room: touch
them to press buttons and grab them to move them.

`dist\MivimooseScanner.exe` is the whole app. There's nothing to install.

## Quick start

1. Run `MivimooseScanner.exe`. The first start downloads the card database (about 7 MB).
2. Choose the set you're opening. That set's card images download once (10–20 seconds).
3. Hold each card up to the camera, face out. It's recognised in a fraction of a second:
   - a **new card** goes into your collection,
   - a card you **already own** is flagged **DUPLICATE**,
   - the **same card shown twice in one pack** is ignored (never counted twice),
   - when the pack's card count is reached, the **pack is marked done** (with value
     against what you paid) and the next pack starts.

You can also lay several new cards under the camera at once: they're all scanned together.

## The camera

**Use a webcam** pointed at where you open packs, such as a Logitech C270 on a stand or a
phone used as a webcam. It's the steadiest setup and leaves your hands free.

**The Steam Frame's own cameras can't be used yet.** SteamVR says the headset has a
camera, but it sends no pictures to PC apps. The `camera/enableCamera` setting doesn't
change that. This has been reported to Valve
([OpenVR issue #1926](https://github.com/ValveSoftware/openvr/issues/1926)). The
scanner checks for real frames, and on *Automatic* it uses your webcam instead and says
why. Headsets that do share their camera through SteamVR, like the Valve Index, work.
Settings → VR headset → **Run VR check** shows what your headset offers.

## In VR

1. Install SteamVR, link the Steam Frame to the PC, and start the scanner (before or
   after SteamVR).
2. Turn on the headset's passthrough to see your room. The panels then float in it:
   - **Scanner panel:** last pull, price, NEW/DUPLICATE, live reading progress, tips,
     camera view, and the Undo · Reverse · Pause · Next pack buttons.
   - **Pack panel:** the current pack's cards, large, with value against cost. Put it on
     the table.
   - **Spotlight:** each card you pull pops up large above the scanner panel. Big hits
     and jackpots stay longer.
   - **SteamVR dashboard tab:** a full control panel.
3. **Touch** a button with the controller tip to press it (you'll feel a buzz).
   **Grab** a panel by its top bar, or squeeze grip on it, then move it. Let go, or hold
   still for a second, to put it down. Panels stay where you put them, even next time.
   *Bring panels here* puts them all back in front of you.

## Sounds

Each pull plays a sound by value. Every threshold and sound can be changed in Settings →
Sound & voice.

- new card: a chime
- duplicate: a soft double note
- **hit** ($5+): sparkles
- **big hit** ($50+): a fanfare
- **jackpot** ($100+): a **gunshot** by default, or choose an explosion, cash register,
  air horn, laser or fanfare

There's also a tick when a card starts being read, a shuffle for each new pack, and
chimes for milestones, pause, resume and button presses. Prices can be read aloud too.

## The app

- **Scan:** live camera with card outlines, last pull, pack slots, session totals.
- **Binder:** every set as a flip-through 9-pocket binder (missing cards ghosted), or as
  a grid; set and master-set progress, rarity breakdown, value owned and missing.
- **Packs:** totals, a chart of every pack's value against its cost, and the pack history.
- **Collection:** everything you've pulled, sortable and searchable; CSV export.
- **Settings:** camera, scanning, packs, sound, currency (USD, CAD, EUR, GBP, AUD, JPY,
  MXN, NZD), VR panels, data.

Keyboard: `Space` pause · `Z` undo · `R` next card is reverse holo · `N` next pack ·
`A` add by hand · `S` choose set · `1`–`5` pages · `←` `→` turn binder pages.

## How recognition works

Every frame goes through two independent methods:

1. **Artwork matching.** The card's keypoints are matched against every card in the
   active sets. A fast approximate lookup shortlists about 15 cards, then exact matching
   and a fitted homography find each card's exact outline. This works without visible
   edges (busy tables, sleeves, toploaders, fingers) and finds several cards at once.
2. **Outline matching.** The card's straight edges are found, the card is straightened,
   and it's fingerprinted. This catches cards with plain artwork.

Each sighting gets a confidence between 0 and 1, from logistic models fitted on
calibration footage (`tests/calibrate.py`). A card is confirmed once its evidence builds
up over a few frames.

Reprints that use the same artwork in two loaded sets can't be told apart by any camera.
They're reported as the copy from the set you're opening.

Measured on synthetic camera footage: moving cards, fingers, glare, holo shimmer,
sleeves, toploaders, motion blur, a black-and-white sensor, and cluttered tables.

| Test | Result |
|---|---|
| 151, all rarities, 40 cards brought up and taken away | 95% scanned, usually the frame they came fully into view |
| Demo video (11 cards, duplicate and repeat rules) | all scanned, no false or double scans |

## Building and testing

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1      # -> dist\MivimooseScanner.exe
```

Run from source with `.venv\Scripts\python.exe run.py`. Useful options:

- `--browser` opens the app in your browser
- `--headless` runs with no window
- `--camera file:C:\clip.mp4` scans a video
- `--vr-check` prints the VR check
- `--data-dir` keeps data in another folder

The tests take a cache folder that has `data\bundle.js` (for example `tests\.cache`):

| Command | What it checks |
|---|---|
| `python -m tests.test_engine <cache>` | pack, duplicate, undo and correction rules |
| `python -m tests.test_vrgeom` | panel geometry, touch and grab logic |
| `python -m tests.test_vr_sim` | the VR panels against a simulated SteamVR |
| `python -m tests.make_demo_video <cache> sv3pt5 demo.mp4` | makes a synthetic test video |
| `python -m tests.test_app <cache> demo.mp4 [--exe dist\MivimooseScanner.exe]` | the whole app, end to end |
| `python -m tests.bench_seq <cache> [--sets …] [--mix] [--rare] [--lookalike] [--hard] [--multi]` | scanning benchmark |
| `python -m tests.bench_vision <cache>` | single-frame detection |
| `python -m tests.calibrate <cache>` | refits sighting confidence |
| `python -m tests.screenshots <cache> demo.mp4 shots` | screenshots of every page |
| `python -m tests.render_vr_panels <cache> demo.mp4 shots` | renders the VR panels |

OpenCV is pinned below 5 because 5.0.0's brute-force matcher corrupts memory.

Your data lives in `%APPDATA%\MivimooseScanner\`: `collection.db`, `settings.json`,
caches and `logs\scanner.log`.
