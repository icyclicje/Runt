# MivimooseRips

Two apps that scan the Pokémon cards you pull, price them, and track your collection,
packs and set progress, using the MivimooseRips card database:

- **Android app** (`dist\MivimooseRips-android.apk`): scan with the phone camera.
- **Windows scanner with VR panels** (`dist\MivimooseScanner.exe`): a webcam on the PC,
  with optional panels in SteamVR.

## Android app

Install `dist\MivimooseRips-android.apk` on the phone (copy it over and open it; allow
installing from that source). `MivimooseRips-android-x86_64.apk` is for the emulator.
Android 8 or newer. The first start reads the card database bundled in the app and then
refreshes it from the site in the background.

### Scanning

Hold a card inside the guide. There's no set or pack to pick, and any card in the database
can scan first: a small index of every card is built into the app, so the likely prints are
known at once and only their images are fetched. The scanner then works out the set and
loads all of it in the background. The set it detected is shown at the top (tap it to
choose one by hand).

- **Collect** (the default, the setting *Add scanned cards to my collection automatically*):
  every scan goes into your collection by itself, nothing to tap. A card you already own is
  flagged **DUPLICATE** (Settings decides whether it's added anyway, skipped or asked).
- **Price check:** shows the price without adding anything; the result has an Add button.
- **Track packs** (Settings, off by default): each pack's cards and value are counted
  against the pack's price; the same card twice in one pack is ignored, and a full pack or
  a card from another set starts the next pack.

Only cards are outlined and scanned. Something card-shaped (a phone, a book, a sheet of
paper) gets no outline, and card artwork that isn't on a card (a booster pack, a poster, a
binder page photo) isn't scanned: a card has to show its straight edges where the
artwork says they should be, and printed text only counts when it's laid out like a card's
(name at the top, collector number at the bottom).

Each result shows the live price for the printing (normal, reverse holo, Poké Ball,
Master Ball, 1st Edition), its 30-day trend, its rarity, how far it moves your set along,
and the **PSA population** (graded copies per grade, gem rate, PSA 10 and PSA 9 prices). Expensive cards get their own effects and sounds:
a holo shimmer for hits, sparkles for big hits, and for a **jackpot** (default $100+) a
full-screen burst with a gunshot (or explosion, cash register, air horn, laser, fanfare).
A card with more than one printing shows them on its result, and tapping one changes the
copy that was just added; other cards don't ask. Undo and "wrong card?" are one tap away.
The session's scans run along the bottom; all scans are in **Recent scans**.

One physical card is one scan. Holding it, glare or a moment of blur doesn't scan it again,
and neither does taking it away, leaving for its details and coming back while it's still
there. A second copy of the same card scans once the first has been taken away (the guide
empty for about a second).

How it recognises cards, cheapest look first:

1. **Outline:** the card's edges are found, it's straightened and fingerprinted, then
   checked with keypoints against the loaded cards. Most frames stop here, in a fraction of
   the time of a full search.
2. **Artwork:** when that doesn't settle it, keypoints over the whole frame are matched
   against the loaded cards (works in sleeves, at angles and without visible edges). A
   match has to include the artwork: cards of a set share their layout, which alone gives
   dozens of matches between two different cards.
3. **Built-in index:** every card image as a tiny signature (7 MB), searched in about a
   millisecond. It suggests, never decides: compressed fingerprints confuse prints of one
   Pokémon, so the suggested cards' real images are fetched and checked by step 1.
4. **Printed text:** only the name and number strips of the card are read, enlarged, on
   the phone (ML Kit; nothing is uploaded). The prints of a name that was read are fetched
   and checked too, best-looking first. A collector number with its set code
   (`MEW 199/165`) and a matching name or look is enough by itself. One misread character
   is tolerated. Prints in another language share their artwork, so the text decides those.

English, Japanese, Korean, Chinese (traditional and simplified), Thai and Indonesian
cards are in the database. The built-in index only fetches English and Japanese prints unless
another language is chosen with the scanner's language button (Indonesian 151 looks just
like English 151); cards in other languages are still found by their text. The text reader
switches to Japanese by itself. Simplified Chinese cards and Traditional sets from 2025 on
aren't in the built-in index yet, so they're found by their text and take longer.

### The rest of the app

- **Home:** portfolio value (live while the app is open), daily value history, cards and
  sets, most valuable cards, recent scans, set progress, packs.
- **Collection:** progress for every set you own cards from (main set and master set,
  value owned and value to complete), your cards, and every set by language with a
  9-pocket binder view.
- **Card page:** price per printing, a **market graph** from TCGplayer sales (1, 3, 6 or
  12 months, with copies sold), PSA population chart and graded price history, pull odds,
  price alerts, links to TCGplayer, eBay sold listings and PriceCharting.
- **Market:** search every card in every language, your watchlist, chase cards of the
  newest sets, and which packs are worth the most right now.
- **Tools:** pack value (average pack and box value, hit chances), pull rates with
  sources, pack opening simulator, grading calculator, **centering checker** (camera),
  trade calculator, seller fees, bulk value, binder planner, currency converter, set code
  lookup.
- **Share picture:** an image of your collection, top pulls, set progress, a single card
  or your last pack, to share or save.
- **Settings:** currency (16), refresh interval, value tiers, sounds and jackpot sound,
  pack tracking, duplicate handling, card condition, text reading, GemRate key, reduce
  motion, CSV export.

### Where the data comes from

| What | Source |
|---|---|
| Sets and cards | the MivimooseRips site (refreshed every 12 h), plus TCGplayer's catalog via tcgcsv.com for sets and cards the site doesn't have yet (e.g. ME06 Delta Reign) |
| Chinese (Traditional) | the official Trainers Website (asia.pokemon-card.com/tw): built in, and new sets checked daily |
| Chinese (Simplified) | [zsjy947/PTCG-card-data](https://github.com/zsjy947/PTCG-card-data) (CC0, a weekly copy of the community's database tcg.mik.moe, which also serves the images): built in, and changes checked daily. The site's old "Simplified" sets were its Traditional lists under another name, so they're gone. Prices of Chinese cards are estimates from the same card in Japanese or English |
| Prices | **Live from TCGplayer:** each card's latest sales and the cheapest near-mint copy for sale right now, for every printing (see below); for cards without recent sales, TCGplayer's market price capped by the cheapest listing. Everything else uses TCGplayer's daily prices (tcgcsv.com): the newest sets, the sets you collect, and a set as soon as the scanner detects it. (The site's own prices ran up to a third above what cards sell for, e.g. $861 for a Mega Gengar ex selling at $644, so the app no longer uses them.) Every 15 min while open, every 6 h in the background |
| Pack prices | Live from TCGplayer: the booster pack's latest sales and cheapest sealed listing |
| Poké Ball and Master Ball patterns | separate TCGplayer products, attached to their cards |
| Market graphs | TCGplayer sales history |
| PSA and CGC population, graded prices | PriceCharting (personal use; cached a week per card) |
| Population across PSA, CGC, Beckett and SGC (optional) | GemRate's API, with a GemRate key in Settings |
| Pull rates | TCGplayer and community opening studies for 85 sets (51 English including Sun & Moon and Sword & Shield, 34 Japanese); each set shows its source, sample size and confidence |
| Exchange rates | open.er-api.com |

**Why live prices:** TCGplayer's "market price" is only recalculated now and then, so it trails a
moving market. On 1 Nov 2026 Pikachu ex 277/217 showed a market price of $272 while its last
five sales were $229-239 and the cheapest copy was $229; a 151 booster pack showed $29 in the
old data and sold for about $26.70. A card's price is now the middle of its last five
near-mint sales (from the past 30 days) and the cheapest near-mint listing, with odd ones
out (mislisted, damaged, fake) dropped. Each price says what it's based on.

Pull rates corrected against the site's model include Temporal Forces Hyper Rare (1 in
139, not 188), Ascended Heroes Mega Attack Rare (1 in 29, not 300) and Crown Zenith
VSTAR/VMAX (no longer counted twice). Japanese sets now use measured box rates instead of
one generic estimate.

### Scanning results

Synthetic phone footage at 1080x1920: each card is brought up to the camera, held for about a
second and a half and taken away. Played in real time through the whole app on the Android
emulator (release build): frames that go by during analysis are skipped, as with a real
camera, and a card still unscanned when it's taken away counts as missed. Time is from the
moment the card is held.

| Test | Scanned | Time to scan, median | False or double scans |
|---|---|---|---|
| 151 and modern rares (Mega Evolution SIRs and more), sets loaded, 30 cards | 27-28/30 | 190-570 ms | 0 |
| The same, every card seen for the first time: no set loaded, images forgotten | 12-21/30 | 0.7-1 s | 0 |
| Japanese 151, set loaded as it goes, 12 cards | 9-11/12 | about 590 ms | 0 |
| Japanese 151, every card seen for the first time | 4-5/12 | 610-780 ms | 0 |

Ranges are repeated runs: the emulator's speed drifted between 58 and 150 ms per frame, and
its text reader between 0.6 and 3 s per read. Cards missed ran out of the hold, so holding a
card a little longer helps. Before this round the first-time test scanned 0/30 in real time,
because a card had to wait for its collector number to be read; the earlier "30/30" came from
a slow replay that gave the text reader ten times too long. This hasn't been measured on a real
phone with a real camera yet.

On this PC the emulator also crashes at random on the P-cores, with errors no app code could
cause, and the build from before this round does the same (2 of 4 runs); on the E-cores, 21 of
21 runs had no crash. That's put down to the CPU (see *This PC* below), not the app.

Recognition alone (desktop, sets loaded, at a phone's speed, 720p):

| Footage | Scanned | Median | p90 | False or double |
|---|---|---|---|---|
| Held in the guide (phone) | 30/30 (was 29) | 157 ms (was 382) | 450 ms (was 555) | 0 |
| Handheld, anywhere in the frame (normal) | 39/40 | 210 ms | 455 ms | 0 |
| Modern rares | 29/30 | 162 ms | 383 ms | 0 |
| Hard conditions (blur, glare, steep angles) | 28/30 (was 24) | 205 ms (was 337) | 535 ms (was 792) | 0 |
| Japanese 151 | 11/12 | 220 ms | 270 ms | 0 |

Non-card objects (27: booster packs, posters of card art, printed checklists, books, phones,
keyboards, card backs) with their artwork's sets loaded: outlined as a card in 9 of 712 frames,
with 2 false scans. Both have a real card picture filling the whole background with that card's
set loaded, which looks the same as a card held very close. On the emulator: no scans, and
outlined in 0 of 402 frames.

### Building

```powershell
powershell -ExecutionPolicy Bypass -File android\build.ps1 :app:assembleRelease   # -> android\app\build\outputs\apk\release
powershell -ExecutionPolicy Bypass -File android\build.ps1 :core:test              # recognition, catalog and pack-model tests
```

`build.ps1` runs Gradle on the efficiency cores with two workers, because this PC's
i9-14900K crashes under heavy all-core load (see *This PC* below). Test footage comes from
`python -m tests.export_android_bench tests\.cache --phone --hd --name hd`; on a device or
emulator, start the app with `--es frames <folder>` to run the on-device benchmark in real
time (results in logcat under `BENCH`; a file named `cold` in the folder forgets all card
images first, so every card is a first-time card).

The built-in index (`assets/data/atlas.bin`) is rebuilt with `python -m tests.fetch_all_refs
tests\.cache`, `android\build.ps1 :core:test --tests "*AtlasTools*" -Patlas=fingerprints` and
`python -m tests.build_atlas tests\.cache`; the built-in Chinese lists
(`assets/data/zh_sets.json.gz`) with `android\build.ps1 :core:test --tests "*ChineseTools*"
-Patlas=zh`. Sets newer than the index still scan, by their text.

### This PC

Windows logged six blue screens between Oct 15 and Oct 30 (0x1E, 0x3B, 0x7F) and hardware
errors, and Java crashed at random under multi-core load while building. The CPU is an
i9-14900K on BIOS H.80 (Sept 2023) with microcode 0x11D, older than Intel's 2024 fixes for
13th/14th-gen instability (0x129 and later). Update the MSI MPG Z790 EDGE WIFI BIOS to
the newest version (it carries the 0x12F microcode) and keep the "Intel Default Settings"
profile. If crashes continue after that, the CPU may already be degraded; Intel extended
the warranty for these processors.

## Windows scanner (with VR panels)

A Windows app that scans the Pokémon cards you pull, prices them using the MivimooseRips
card database, and tracks your packs and set progress. With a Steam Frame (or any SteamVR
headset) linked to the PC, its panels float in your room: touch them to press buttons and
grab them to move them.

`dist\MivimooseScanner.exe` is the whole app. There's nothing to install.

### Quick start

1. Run `MivimooseScanner.exe`. The first start downloads the card database (about 7 MB).
2. Choose the set you're opening. That set's card images download once (10–20 seconds).
3. Hold each card up to the camera, face out. It's recognised in a fraction of a second:
   - a **new card** goes into your collection,
   - a card you **already own** is flagged **DUPLICATE**,
   - the **same card shown twice in one pack** is ignored (never counted twice),
   - when the pack's card count is reached, the **pack is marked done** (with value
     against what you paid) and the next pack starts.

You can also lay several new cards under the camera at once: they're all scanned together.

### The camera

**Use a webcam** pointed at where you open packs, such as a Logitech C270 on a stand or a
phone used as a webcam. It's the steadiest setup and leaves your hands free.

**The Steam Frame's own cameras can't be used yet.** SteamVR says the headset has a
camera, but it sends no pictures to PC apps. The `camera/enableCamera` setting doesn't
change that. This has been reported to Valve
([OpenVR issue #1926](https://github.com/ValveSoftware/openvr/issues/1926)). The
scanner checks for real frames, and on *Automatic* it uses your webcam instead and says
why. Headsets that do share their camera through SteamVR, like the Valve Index, work.
Settings → VR headset → **Run VR check** shows what your headset offers.

### In VR

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

### Sounds

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

### The app

- **Scan:** live camera with card outlines, last pull, pack slots, session totals.
- **Binder:** every set as a flip-through 9-pocket binder (missing cards ghosted), or as
  a grid; set and master-set progress, rarity breakdown, value owned and missing.
- **Packs:** totals, a chart of every pack's value against its cost, and the pack history.
- **Collection:** everything you've pulled, sortable and searchable; CSV export.
- **Settings:** camera, scanning, packs, sound, currency (USD, CAD, EUR, GBP, AUD, JPY,
  MXN, NZD), VR panels, data.

Keyboard: `Space` pause · `Z` undo · `R` next card is reverse holo · `N` next pack ·
`A` add by hand · `S` choose set · `1`–`5` pages · `←` `→` turn binder pages.

### How recognition works

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

Each card is brought up to the camera, held about a second, and taken away (`tests/bench_seq.py`).

| Test | Scanned | False scans | Double scans |
|---|---|---|---|
| 151, all rarities (40 cards) | 39/40 (97.5%) | 0 | 0 |
| Modern special cards (ex, full art, IR, SIR, gold) with 7 sets loaded at once (~1,600 cards, 50 cards) | 49/50 (98%) | 0 | 0 |
| Look-alikes: cards with a same-name sibling, reprints across sets (40 cards) | 39/40 (97.5%) | 0 | 0 |
| Demo video (11 cards, duplicate and repeat rules) | 11/11 | 0 | 0 |

Most cards are confirmed the moment they're fully in view. The misses are extreme cases:
small, blurry holo cards on very busy backgrounds with fingers over them.

### Building and testing

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
