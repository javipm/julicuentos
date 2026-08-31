# Apply Progress — migrate-to-native-android

## Slice 1 — Scaffold + device spike (implemented 2026-08-30)

Status: **DONE (host-verifiable scope)**. `./gradlew assembleDebug` green on the first run;
on-device spike items deferred to the next Fire HD 10 session (see Deferred below).

### What was done

1. Gradle wrapper bootstrapped from the local distribution
   (`tools/gradle-dist/gradle-8.7/bin/gradle wrapper --gradle-version 8.7`), then
   `distributionUrl` pointed at the local zip. `./gradlew --version` verified
   (Gradle 8.7, JVM 17.0.19 Zulu, no network needed for the distribution).
   Bootstrap notes: Gradle 8.7 refuses the `wrapper` task without a settings file,
   so `settings.gradle.kts`/`gradle/libs.versions.toml` were written first; the dist
   zip was renamed `gradle.zip` → `gradle-8.7-bin.zip` to match the spec'd URL.
2. Root build files + version catalog pins per design D9.
3. App module skeleton: manifest (4 permissions, no INTERNET), `JulicuentosApp`,
   `MainActivity` stub (TextView "Julicuentos — Slice 1"), colors/themes/strings.
4. Fonts copied from the RN repo: `fredoka_semibold.ttf` (Fredoka_600SemiBold),
   `nunitosans_regular.ttf` (NunitoSans_400Regular), `nunitosans_bold.ttf`
   (NunitoSans_700Bold). fontFamily XMLs + TextAppearance ladder are NOT part of
   the slice-1 delegation (see Deviations).
5. Launcher mipmaps composited with PIL: RN `android-icon-foreground.png`
   (1024² RGBA) over #17152E, foreground at ~66 % center, exported as
   `ic_launcher.png` at mdpi 48 / hdpi 72 / xhdpi 96 / xxhdpi 144 (no adaptive
   icons — API 22 target).
6. `tools/port-catalog.py` (stdlib only) ported the catalog: 20/20 entries in
   `stories.json` (alphabetical, validator-checked, round-trip verified),
   20 covers + 20 thumbnails copied to `assets/covers/<id>/`. Content fixes
   applied and output-validated (assertions added post-review, R1-002): bambi "ambi es…" → "Bambi es…", "Los Increibles" →
   "Los Increíbles", whitespace stripped, `fechaPublicacion` dropped.
7. Build gate + APK verification (see evidence).
8. tasks.md updated: S1.1–S1.4, S1.8, S1.9, DV2 checked; spike/gate tasks
   annotated `deferred-device`.

### Files created

```
julicuentos-native/
├── settings.gradle.kts                  pluginManagement repos, :app include
├── build.gradle.kts                     pinned plugin declarations (aliases)
├── gradle.properties                    useAndroidX, 4g daemon heap, UTF-8
├── gradle/
│   ├── libs.versions.toml               all D9 pins (agp, kotlin, media3, androidx…)
│   └── wrapper/{gradle-wrapper.jar, gradle-wrapper.properties}
├── gradlew · gradlew.bat                distributionUrl=file:/…/tools/gradle-dist/gradle-8.7-bin.zip
├── app/
│   ├── build.gradle.kts                 minSdk 22, compileSdk/targetSdk 34, noCompress mp3
│   └── src/main/
│       ├── AndroidManifest.xml          4 permissions, no INTERNET, launcher activity
│       ├── java/com/julicuentos/app/
│       │   ├── JulicuentosApp.kt        Application stub
│       │   └── MainActivity.kt          slice-1 placeholder screen
│       ├── assets/
│       │   ├── audio/                   (pre-existing, gitignored, untouched)
│       │   ├── covers/<id>/cover.jpg + thumbnail.jpg   (20×2 files, 27 MB)
│       │   └── stories.json             20 entries, alphabetical
│       └── res/
│           ├── font/{fredoka_semibold,nunitosans_regular,nunitosans_bold}.ttf
│           ├── mipmap-{mdpi,hdpi,xhdpi,xxhdpi}/ic_launcher.png
│           └── values/{colors,strings,themes}.xml
└── tools/port-catalog.py                re-runnable catalog port script
```

### Build evidence

- Gate command: `./gradlew assembleDebug` (first run, deps fetched once)
- Exact success line: **`BUILD SUCCESSFUL in 51s`** — `35 actionable tasks: 35 executed`
- APK: `app/build/outputs/apk/debug/app-debug.apk` — **721,575,153 bytes (~688 MiB)**
- Badging: `package: name='com.julicuentos.app' versionCode='1' versionName='1.0'`,
  `sdkVersion:'22'`, `targetSdkVersion:'34'`, compileSdk 34, label `Julicuentos`
- Permissions (`aapt dump permissions`, build-tools 34.0.0): exactly
  FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, WAKE_LOCK,
  POST_NOTIFICATIONS — **no INTERNET**. (Plus androidx.core's auto-generated
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; see note in Deviations.)
- MP3 packaging: `unzip -v` shows all **20** `assets/audio/*.mp3` entries **Stored**
  (0 % compression) — `noCompress "mp3"` working.
- Catalog validation: 20 entries, ids unique + alphabetical (ASCII order == Java
  `String.compareTo` for the `^[a-z0-9-]+$` id charset), all 40 image paths exist,
  JSON round-trip equal; bambi/Los Increíbles fixes asserted via validate() (added post-review, R1-002).
- Audio integrity: 20 MP3s confirmed on disk and none staged in git
  (`.gitignore` covers `app/src/main/assets/audio/`, `audio-download.log`,
  `audio-download.pid`); file contents untouched per delegation.

### Deferred (device) items

| Item | Why deferred |
|---|---|
| S1.10 on-device spike build (single-asset play-button APK) | Superseded in part: full-catalog APK is green, all 20 entries verified STORED, APK size recorded. On-device play/seek/screen-off checks need the Fire HD 10. |
| S1.11 spike verdict (start <2 s, seek 46:00/51:00, ENDED, screen-off +600 s, logcat clean) | Needs Fire HD 10 session; verdict gates slice 2 (D1 fallback decision). |
| S1.12 `adb install -r` OK | Needs device. `versionCode` stays 1. |
| DV1 | Same as S1.11. |

### Deviations from design (each flagged)

1. **`distributionUrl` uses the absolute `file:` URL** (delegation instruction)
   instead of design D9's preferred relative `file:../tools/…`. D9 pre-authorized
   the absolute form as a documented fallback for this single-machine project.
   Also: the dist zip was renamed `gradle.zip` → `gradle-8.7-bin.zip` so the URL
   matches the wrapper dist name.
2. **Plugin versions declared in root `build.gradle.kts`, not the settings
   plugins block.** Gradle 8.7 cannot resolve version-catalog aliases from the
   settings plugins block (`Unresolved reference: libs`; `versionCatalogs` is not
   part of `pluginManagement` in the Kotlin DSL). Pins remain single-sourced in
   `gradle/libs.versions.toml`.
3. **androidx.core pinned `1.12.0`** (delegation) vs tasks S1.2's `1.12.1`-first
   pin. Resolved cleanly at 1.12.0; the 1.12.1 fallback was not needed.
4. **Cover/thumbnail asset layout** = `assets/covers/<id>/cover.jpg` +
   `assets/covers/<id>/thumbnail.jpg` (delegation) instead of design §4's
   `covers/<id>.jpg` + separate `thumbs/<id>.jpg`. `stories.json` carries explicit
   `cover`/`thumbnail` path fields, so slice 3's bitmap pipeline follows the JSON
   and no consumer code hardcodes either layout.
5. **Duration probe (±2 s vs MP3) not run** — the audio directory was declared
   off-limits for this slice (do not touch or list). Catalog durations remain
   placeholders by design (specs/playback: real metadata wins post-prepare).
6. **S1.5/S1.6/S1.7 partially scoped**: `styles.xml` TextAppearance ladder,
   fontFamily XMLs, dimens and the dark splash drawable were NOT in the slice-1
   delegation and are left unchecked in tasks.md with `partial` annotations;
   they land with the UI slices. Slice 1 ships theme/colors/strings base +
   fonts + mipmaps only.
7. **Merged-manifest permission note**: androidx.core 1.12 auto-adds the
   app-defined `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (normal-protection,
   no network). The delivery spec's "exactly {4 permissions}" aapt check should
   account for this inherent library addition when DV2/acceptance runs.
8. **strings.xml base set**: the theme-spec registry has no separate catalog
   header title, so `catalog_title` = "Julicuentos" (verbatim RN copy);
   `play`/`pause`/`next` base labels ("Reproducir"/"Pausar"/"Siguiente") are
   slice-1 placeholders to be reconciled with the verbatim registry pass in S5.8.
   The slice-1 placeholder string `slice1_placeholder` is dev-only and removed
   in slice 3.

### Next slice readiness

Slice 2 (catalog data + playback core) can start once the S1.11 spike verdict is
recorded on the Fire HD 10 (tasks: "Spike verdict gates slice 2"). Everything it
needs from the scaffold is in place: pinned toolchain, green build, catalog asset
format (JSON-carried paths), manifest permission set, and the `noCompress`
STORED-entry guarantee the `AudioSourceResolver` seam relies on.
