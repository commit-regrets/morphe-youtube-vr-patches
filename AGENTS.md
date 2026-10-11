# AGENTS.md — how to work on this repo

A Morphe/ReVanced patch bundle for **YouTube VR 1.88.10** on Quest 3 (`eureka`, Snapdragon XR2 Gen 2),
plus a Java extension injected into the app. Read this before touching anything; the traps in
"Measured traps" have each cost a full build-install-device cycle.

Deep detail lives in `docs/`: **`FINGERPRINTS.md`** (measured anchors and anchors measured *not* to
work — read it first), `INTERNALS.md`, `SPONSORBLOCK-PLAN.md`.

## Layout

| path | what it is |
|---|---|
| `patches/src/main/kotlin` | the patches (Morphe patcher API, Kotlin) |
| `extensions/src` | Java injected into the app: compiled with `javac` against `android.jar`, then `d8` into `ytvr-extension.dex` inside the `.mpp` |
| `scripts/patch-and-install.ps1` | the one-command pipeline: build → patch → install |
| `build/probe/` | gitignored scratch: headless harnesses, probe scripts, screenshots |
| `apk/` | gitignored: the stock APK, patched builds, build logs |

## Requirements

The full table (with where each tool is expected) is in
[README.md](README.md#-requirements-and-tooling); the short version, because a missing one fails in a
confusing place:

| tool | why it matters | where it is looked for |
|---|---|---|
| JDK 21 | Gradle, and `javac` for the extension | `JAVA_HOME` |
| `android.jar` | the patch build compiles against it | `-PandroidJar`, `$ANDROID_JAR`, else `~/tools/android/android.jar` |
| `r8.jar` | dexes the extension into the `.mpp` | `-Pr8Jar`, `$R8_JAR`, else `~/tools/r8/r8.jar` |
| Morphe Desktop jar | applies the bundle to the APK | see the pipeline below |
| `adb` | the only way to test anything | `PATH` |
| jadx | reading the app's bytecode and checking the patched dex | `PATH` |
| REA | device integration + APK analysis (optional) | `PATH`, see [README](README.md#-requirements-and-tooling) |

## Working loop

```powershell
$env:JAVA_HOME="C:\Users\<your-user>\tools\jdk21"
$env:GITHUB_ACTOR=(gh api user -q .login); $env:GITHUB_TOKEN=(gh auth token)   # for dependency fetch
.\gradlew.bat buildAndroid --no-daemon --console=plain
pwsh -NoProfile -File scripts\patch-and-install.ps1 -Apk apk\com.google.android.apps.youtube.vr.oculus.apk `
    -PackageName app.morphe.android.youtube -AppName "YouTube VR (patched)"
```

* The script uses the newest `patches/build/libs/patches-*.mpp`. If `gradlew` did not actually print
  `BUILD SUCCESSFUL`, the install silently uses the **previous** bundle — check the file's timestamp.
* The script fails loudly if the applied patch set is missing a patch you need (`-ExtraPatches`).
  `SponsorBlock` is in the default set; it also builds the "Patch settings" hub screen.
* A green `Applied:` list is **not** proof the right method was patched. Verify on the device.

## Device access

Both recipes below come from **[REA](https://github.com/morluto/rea)**'s device integration
documentation (listed with the other tooling in
[README.md](README.md#-requirements-and-tooling)); plain `adb` is what actually executes them. One
caveat if you reach for REA's CLI here: `rea capture-adb-screen` failed in this environment (a schema
error demanding an output directory, then `unreadable_output` once given one), so use the `screencap`
route below instead.

**Wireless adb** (removes the cable):

```powershell
adb tcpip 5555                    # once, over USB
adb connect <quest-ip>:5555       # then untethered on the same Wi-Fi
$env:ANDROID_SERIAL="<quest-ip>:5555"   # pin it: two transports make bare adb ambiguous
```
The env var is inherited by child processes, so the install script keeps working. `adb tcpip` resets
on reboot and needs one USB moment to re-enable.

**Capture without wearing the headset** (verified on this device):

```powershell
adb shell svc power stayon true        # flag alone is NOT enough
adb shell input keyevent KEYCODE_WAKEUP # ...the display must be woken AFTER setting it
adb shell screencap -p /sdcard/shot.png # write ON DEVICE: host-side redirection can mangle bytes
adb pull /sdcard/shot.png
```
Frames are 4128x2208 stereoscopic (both eyes; crop one half if useful).

**You cannot inject input.** `adb shell input tap` at panel coordinates is ignored (the panel is
hit-tested in 3D space, not at display coordinates), and key events go to the VR shell — a
`KEYCODE_DPAD_DOWN` opens the shell's control bar rather than moving focus in the app. So: rendering
and log verification are self-service; anything that needs a *tap* needs the human. Ask for one
specific tap, then verify the rest yourself.

**Privacy.** Screenshots contain the account name and avatar. Keep them in `build/probe/shots/`
(gitignored) and never commit or share them. The same goes for the app's preference file contents.

Other device notes: the original activity path is what still resolves for `am start -n <pkg>/<activity>`
on this clone; `com.oculus.vrpowermanager.prox_close` is accepted but answers `result=0` (no receiver —
do not rely on it); the app is not debuggable, so `run-as` cannot read its preferences.

## Verification discipline

This repo's central lesson, paid for repeatedly: **make the feature say what it did**, then read that
from the device.

* Prefer log signatures over assumptions: `SponsorBlock: effective: sponsor=skip-once …` reports the
  behaviour actually in force, `behaviour set: <category>=<value>` reports a write, `coloured the
  'Sponsor' row #ff00d400` reports a paint. Each of those has verified a change with no taps at all.
* Read the **patched dex** when a hook matters:
  `jadx --single-class androidx.preference.Preference -d out apk/ytvr-final.apk`, then confirm the
  injected call is where you think it is.
* Headless harnesses for logic that needs no device: `build/probe/SbModelCheck.java` (behaviour model)
  and `build/probe/SbPickCheck.java` (picker precedence, plus a guard that the picker key never equals
  the legacy switch key). Compile them together with `extensions/src` against `android.jar` and run.
  A fake `Context` is not possible — `android.jar`'s `ContextWrapper` constructor is a stub — so keep
  decision logic in pure functions and test those.
* Never narrow a configuration that was verified: one "tidy-up" that relabelled a single itag made the
  8K quality option disappear from the menu.
* Diff the applied patch set against the previous build before installing over the user's app.

## Measured traps (each verified on the device — do not re-try)

| trap | consequence |
|---|---|
| XML inflation never calls `setTitle`: `Preference`'s constructor reads `android:title` from the `TypedArray` and assigns the field | a title-setter hook fires for nothing |
| `Preference.b(View)` is a shell that calls `D()`, and `SwitchPreference.b` overrides it as **`final`** without calling the base | hooking either as "the row binder" paints nothing |
| the row binder is `a(<holder>)`; find it by the fact that it calls `TextView.setText(CharSequence)`, and its class by the literal `"Preference already has a SummaryProvider set."` | the only reason the category colouring works |
| a `ListPreference` must not share a key with a Boolean-writing switch: `SharedPreferencesImpl.getString` throws `ClassCastException` | opening the screen crashed; the pickers use `ytvr_mod_sb_behaviour_*` and the switch values are migrated |
| dialogs are never composited into the VR panel (`AlertDialog.show()` succeeds, nothing appears) | picker UI must not be a window |
| views added to the window's **decor** are not drawn either — only the app's own content subtree is rendered | picker UI must live *inside a row* |
| the row's text column is a **`RelativeLayout`** (title, then summary with `layout_below="@android:id/title"`) | `LinearLayout.LayoutParams` are ignored and the view lands on top of the summary |
| the row hook runs for **every row of every settings screen** | clearing a click listener on a row you do not recognise removes the app's own handler (this broke the "Patch settings" row once); return immediately for foreign rows |
| R8 renames androidx **members** but keeps **class names** (XML inflation needs them) | androidx types cannot be used from the extension, and reflective member names silently do nothing |

## SponsorBlock notes

* Preference keys: `ytvr_mod_sb_behaviour_<category>` (picker strings: `skip`, `skip-once`,
  `seekbar-only`, `manual-skip`→normalised to `seekbar-only`, `ignore`), `ytvr_mod_cat_<category>`
  (legacy switches, migrated at start-up), `ytvr_mod_sb_enabled`, `ytvr_mod_sb_markers`,
  `ytvr_mod_sb_skip_once` (legacy), `ytvr_mod_sb_min_duration`, `ytvr_mod_sb_stats_segments`/`_millis`.
* The palette lives in `CategoryColours` and is shared by the seek-bar overlay and the settings rows,
  so a marker and its row cannot disagree.
* `CategoryRows` owns the row UI: colouring, the in-row behaviour list, and the summaries of the two
  display-only rows (`Stats`, `Minimum segment duration`).
* Upstream's default map is what ships (`sponsor=skip-once`; selfpromo/interaction/highlight/intro/
  outro/music_offtopic = `seekbar-only`; preview/hook/filler = `ignore`), and `migrateOldDefaults()`
  moves rows still holding this port's older defaults so the change is actually seen.

## Project state and open problems

This file is about *how to work here*, not about what the project currently does. The state of the
hard problems -- the streaming spoof's current limits (unauthenticated `youtubei/v1/player` requests
being refused for every client tried), the SponsorBlock feature set and what is
still missing -- is in **`docs/SPONSORBLOCK-PLAN.md`**. Measured
anchors and the anchors that *failed* are in `docs/FINGERPRINTS.md`.

One operational rule belongs here, because ignoring it costs an outage: the 8K path depends on a
response YouTube can withhold at any time, so the fetch fails open (playback keeps working without 8K)
and the identity-matrix probe stays gated behind `-Diagnostics`. Do not re-hammer that endpoint.

## Releases and CI

**Branches.** Work on `develop`. `main` is the stable branch and takes changes only through a pull
request, which `open_pull_request.yml` opens (and keeps updated) automatically on every push to
`develop`. Never commit to `main` directly.

**Nothing is built or released by a push to `main`.** `release.yml` is `workflow_dispatch` only, so a
release happens when someone asks for one: dispatch it from `main`, and semantic-release decides whether
the commits since the last tag justify one (`.releaserc` also allows pre-releases from `develop`).
`build.yml` compiles the bundle on every push to `develop` that touches code (its `paths` filter skips
docs-only pushes) and cancels superseded runs, so a burst of pushes costs one build. That check is the
gate before merging, and it shows on the pull request because the PR's head commit is the one it built.

**Release commits.** The workflow commits its version bump (`CHANGELOG.md`, `gradle.properties`,
`patches-bundle.json`, `patches-list.json`, `README.md`) and back-merges into `develop`, so **always
`git fetch` and rebase before pushing** and never edit those generated files by hand.

**Settings the workflows need:** Settings → Actions → General → Workflow permissions → *Allow GitHub
Actions to create and approve pull requests*. Without it `open_pull_request.yml` fails with a 403.

**Attest.** `release.yml`'s `Attest` step fails on a private repository ("feature not available for
user-owned private repositories") — cosmetic, and it should pass once the repository is public.

Verify CI in the right repo: `gh run list -R commit-regrets/morphe-youtube-vr-patches`.
