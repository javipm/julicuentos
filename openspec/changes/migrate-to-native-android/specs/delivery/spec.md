# Delivery Specification

## Purpose

How the app is built, what ships inside the APK, and how it is installed and accepted on the single target device (Amazon Fire HD 10 2015, Fire OS 5.3, API 22) via adb. Maps to the proposal's success criteria (a)–(e).

## Requirements

### Requirement: Project scaffold and build

The repo MUST be a Gradle project building a **single debug-signed APK** with `./gradlew assembleDebug`: module `app`, package `com.julicuentos.app`, `minSdk 22`, `compileSdk 34`, Kotlin + XML views (no Compose), toolchain versions (AGP/Gradle/Kotlin/JDK) pinned via wrapper and version catalog. The build MUST be green from the first slice and stay green (no slice leaves the tree unbuildable). `versionCode` MUST be monotonically increasing so `adb install -r` upgrades keep working.

#### Scenario: Clean build from a fresh clone

- GIVEN a machine with the pinned JDK and no local state
- WHEN `./gradlew assembleDebug` is run
- THEN it succeeds and produces a single debug-signed APK of package `com.julicuentos.app` with `minSdk 22` / `compileSdk 34`, and repeat builds are reproducible from the pinned wrapper.

### Requirement: Manifest and permissions

The merged manifest MUST declare exactly: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK`, `POST_NOTIFICATIONS` (harmless no-op on API 22) — and MUST NOT declare `INTERNET` or any other network permission. The service must be declared with `android:foregroundServiceType="mediaPlayback"`. Audio assets MUST be excluded from AAPT compression (`noCompress "mp3"`). MP3 asset paths keep folder-id dashes (hence `assets/audio/`, not `res/raw`).

#### Scenario: No INTERNET permission in the merged manifest

- GIVEN the built APK
- WHEN its merged manifest is inspected (`aapt dump permissions` / `apkanalyzer`)
- THEN the permission list is exactly {FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, WAKE_LOCK, POST_NOTIFICATIONS} and `android.permission.INTERNET` is absent — the app cannot make a network request even by accident.

#### Scenario: MP3s are stored uncompressed

- GIVEN the built APK
- WHEN the asset entries are inspected
- THEN `assets/audio/<id>.mp3` files are stored uncompressed (STORED method) and all 20 ids resolve to files.

### Requirement: Bundled assets and long-file spike

The APK MUST bundle all 20 stories' MP3s at `app/src/main/assets/audio/<id>.mp3` (~650 MB total, longest single file ~60 MB) plus grid thumbnails (512 px) and player covers ported from the source `content/<id>/` folders. **Before UI work, a device spike MUST verify that a ≥ 50 MB MP3 plays from `assets/` through Media3 on the real Fire HD 10** (old-kernel `AssetFileDescriptor` limits are the known unknown). If assets-direct fails, the pre-agreed fallback is: copy audio to app-private storage on first run (still zero network), keeping the same player code.

#### Scenario: Longest asset plays from assets/ on device (spike)

- GIVEN a debug build containing only the longest MP3 (~60 MB, `rompe-ralph`)
- WHEN it is played through Media3 from `assets/` on the Fire HD 10
- THEN audio plays correctly including seeks near the end of the file
- AND IF the direct-asset read fails on this kernel, THEN the first-run copy-to-private-storage fallback is exercised and the same playback works with no network permission present.

### Requirement: Install procedure and versioning

The documented delivery flow MUST be: build the APK → `adb install -r <apk>` on the Fire HD 10, with the streamed fallback **`adb install --no-local`** documented for low-storage situations (the 2× staging footprint of plain install on a 16 GB device). The source RN app (`com.julicuentos.app`, different signing key) requires a documented one-time uninstall before the first native install. No data migration from the RN build is promised.

#### Scenario: Upgrade install works (success criterion e)

- GIVEN the native app is already installed on the Fire HD 10
- WHEN a new APK with a higher versionCode is installed with `adb install -r`
- THEN the upgrade succeeds without uninstalling, and stored player state survives the upgrade.

#### Scenario: First install replaces the RN build safely

- GIVEN the RN build (same package, different signature) is still installed
- WHEN the documented flow is followed (one-time `adb uninstall com.julicuentos.app`, then install)
- THEN the native app installs and launches, and the old app's leftover data is irrelevant (nothing is migrated, by design).

### Requirement: Device acceptance checklist (maps success criteria a–e)

The implementation is accepted only when demonstrated **on the Fire HD 10** (device serial documented in explore.md), never in an emulator alone:

- **(a) Background reliability** — ≥ 30 min screen-off playback from a full story (Playback spec).
- **(b) Seek quality** — no backward snapping, live target timestamp while dragging, exactly one seek on release landing where the finger left, no gesture theft in any orientation (Playback spec).
- **(c) Cold restore** — force-stop + reopen resumes paused at last position with queue intact, no autoplay (Persistence spec).
- **(d) Feature parity** — every catalog/queue/timer capability of the RN app exists per these specs, including the two deliberate deltas: play-now keeps the queue; timer expiry fades ~10 s (all capability specs).
- **(e) Build and install** — `./gradlew assembleDebug` green and `adb install -r` succeeds on the device.

Supporting (non-blocking): ~700 MB APK installs with the documented procedure (with `--no-local` fallback); notification shows artwork and responds to play/pause/±15/next; no `INTERNET` in the merged manifest; no screen uses the system font; every card shows a correct duration chip.

#### Scenario: Full acceptance pass on the Fire HD 10

- GIVEN the final APK on the Fire HD 10
- WHEN the five-point checklist is executed end-to-end (30-min screen-off run; drag-seek with preview; force-stop cold restore; a scripted catalog/queue/timer parity walk including play-now-keeps-queue and timer fade; `assembleDebug` + `adb install -r`)
- THEN every criterion passes with observed evidence (timestamps, screenshots/screen recordings), and the app is left in a daily-usable state.
