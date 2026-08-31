# Delivery — Julicuentos native (Android)

How the app is built, installed and accepted on the single target device (Amazon
Fire HD 10 2015, Fire OS 5.3, API 22). Maps to the proposal's success criteria
(a)–(e); authoritative requirement text lives in
`openspec/changes/migrate-to-native-android/specs/delivery/spec.md` — this doc is
the operator-facing runbook.

## Build flow

```bash
./gradlew assembleDebug
```

- Produces `app/build/outputs/apk/debug/app-debug.apk` (single debug-signed APK,
  package `com.julicuentos.app`, minSdk 22 / compileSdk 34 / targetSdk 34).
- Unit tests: `./gradlew testDebugUnitTest` (pure JVM logic: queue, timer,
  persistence, restore, time formatting).
- No network involvement anywhere: no `INTERNET` permission, no external
  dependencies beyond the pinned version catalog.
- The build must stay green from any slice and versionCode must move forward
  monotonically (see Versioning).

## Device install (adb)

The APK is asset-dominated (~1.25 GB: 20 MP3s bundled STORED + covers/thumbs), so
free space on the device matters. The Fire HD 10 has 16 GB storage.

```bash
# Upgrade / fresh install (keeps stored player state across upgrades):
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Low-storage fallback — plain install stages the APK twice (2× footprint);
# --no-local streams the package straight to the package manager:
adb install --no-local app/build/outputs/apk/debug/app-debug.apk
```

Free-space note: with an APK of ~1.25 GB, keep ~2.5 GB free on the device for
adb staging before installing. If install fails with insufficient space, use
`--no-local`, prune old movies/music on the device, or both.

> **One-time uninstall (signature mismatch) — first native install only.**
> The previous RN build of `com.julicuentos.app` is signed with a different key,
> so `adb install -r` over it will fail with a signature mismatch (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
> Before the FIRST native install, uninstall the RN build once:
>
> ```bash
> adb uninstall com.julicuentos.app
> ```
>
> No data migration from the RN build is provided (or promised) — its leftover
> data is irrelevant by design. After the one-time uninstall, all subsequent
> native installs are plain `adb install -r` upgrades.

## Versioning policy

- `versionCode` MUST increase monotonically per slice so `adb install -r`
  upgrades keep working (spec "Install procedure and versioning").
- Current baseline: `versionCode = 4` (slice 4). This slice (5 — queue, timer,
  persistence, polish) bumps it to **5**.
- `versionName` stays `"1.0"` (informational only; the code is the version).

## Content pipeline (owner-managed, gitignored)

- The app's audio, covers and the catalog (`app/src/main/assets/`) are **owned by
  the user and gitignored** — never generated/modified by agent work.
- `app/src/main/assets/stories.json` must stay consistent with the bundled
  audio/covers for the catalog to work.
- `tools/port-catalog.py` (stdlib only) regenerates `stories.json` + the
  `assets/covers/*` thumbnails from the RN repo's `content/` directory
  (`content/cuentos.tsv` + `content/<id>/cover.jpg|thumbnail.jpg`) if the
  content is ever re-exported.

## Device acceptance checklist (pending on-device run)

Accepted **only on the Fire HD 10** (serial documented in
`openspec/changes/migrate-to-native-android/explore.md`), never emulator-only.
Checklist verbatim from `specs/delivery/spec.md` "Device acceptance checklist":

### Five-point acceptance checklist (success criteria a–e)

- **(a) Background reliability** — ≥ 30 min screen-off playback from a full story (Playback spec).
- **(b) Seek quality** — no backward snapping, live target timestamp while dragging, exactly one seek on release landing where the finger left, no gesture theft in any orientation (Playback spec).
- **(c) Cold restore** — force-stop + reopen resumes paused at last position with queue intact, no autoplay (Persistence spec).
- **(d) Feature parity** — every catalog/queue/timer capability of the RN app exists per these specs, including the two deliberate deltas: play-now keeps the queue; timer expiry fades ~10 s (all capability specs).
- **(e) Build and install** — `./gradlew assembleDebug` green and `adb install -r` succeeds on the device.

### Supporting (non-blocking)

- ~700 MB-class APK installs with the documented procedure (current build is
  ~1.25 GB; `--no-local` fallback documented above); notification shows artwork
  and responds to play/pause/±15/next; no `INTERNET` in the merged manifest; no
  screen uses the system font; every card shows a correct duration chip.

### Pending on-device items (task ids, per tasks.md)

Host-verified already: DV2 (STORED mp3 entries + no INTERNET permission). The
remaining on-device items are open until the device session:

- [ ] DV1 Spike: longest asset plays/seeks/ends from `assets/`, screen-off 10 min (S1.11)
- [ ] DV3 Notification interactive: play/pause, −15, +15, next, artwork; survives pause at shade (S2.9)
- [ ] DV4 Screen-off playback ≥30 min, process alive (S5.10)
- [ ] DV5 Seek: no snap-back, preview visible, one seekTo on release, portrait + landscape (S4.8)
- [ ] DV6 Cold restore: force-stop → reopen paused at position, queue intact, unknown ids dropped (S5.10)
- [ ] DV7 Timer: 15/30/45 countdown survives screen-off; fade ≈10 s to pause; queue preserved; end-of-story suppresses advance (S5.10)
- [ ] DV8 Queue parity: append+dedup, reorder, remove, clear, manual next wraps, auto-advance stops at empty (S5.10)
- [ ] DV9 Grid 5/3/2 columns + no OOM fling + "Sonando" pill tracking (S3.8)
- [ ] DV10 Flat/fonts/touch audit: no elevation/blur, no system font, 52 dp targets, duration chips correct (S5.8)
- [ ] DV11 Install procedure incl. one-time RN uninstall + `--no-local` fallback documented and exercised (S5.9)