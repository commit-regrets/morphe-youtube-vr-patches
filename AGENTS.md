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
* `buildAndroid` also runs **Spotless** (ktlint for `patches/src/main/kotlin`, google-java-format
  AOSP for `extensions/src`) and **detekt** (defaults, baselined at `config/detekt/baseline.xml`);
  both are ratcheted/baselined so pre-existing code never churns and new code must pass. After
  touching sources run `./gradlew spotlessApply detektBaseline` if the gate fails on old patterns.
  The extension's javac runs `-Xlint:all` (warnings, not errors).

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
| every injected block in Application.onCreate is inserted at index 0, so the **last** insertion runs **first** | ordering is inverted: the verbose-log gate must be the last edit this patch makes, and a dump emitted before the gate opens must be re-emitted once it does (storeReady/dumpLogged in SponsorBlockSettings) |
| the two hosts put the patcher's **session** in different places, on the same patcher version: the CLI keeps one directory holding both the decoded tree (`temporaryFilesPath/apk/resources/…`) and the workspace (`PatcherConfig`: `fileWorkspace = temporaryFilesPath.resolve("workspace")`), while Morphe Manager deliberately splits them — tree under `cacheDir/patcher` where `cacheDir = fs.tempDir = app.getDir("ephemeral")`, workspace under `context.cacheDir/patch-workspace` ("Not under tempDir because the patcher wipes that directory when it starts") | anything deriving a path from `fileWorkspace.parentFile` works from the CLI and fails under Manager. **Found from a real Manager run** (Manager 1.35.0, patcher 1.15.1): `PatchException: Patch settings: no settings preferences XML under …/cache; looked for settings_prefs.xml, panel2_settings_prefs.xml` at `SettingsXml.kt:54`, from `sponsorBlockPatch$lambda$0$11` (`SponsorBlockPatch.kt:1586`) — the whole SponsorBlock pass aborted, so the user got no settings screens. The named path is the workspace's *parent* (`…/cache`), one level below the app-data root that covers both `app_ephemeral/…` and `cache/…` — two levels above the workspace, which is what `SettingsXmlKt.restructureSettingsXmls` now walks (bounded ancestors, nearest first, every root named in the exception) |
| a plain androidx `SwitchPreference` on these screens can show the new state while `<package>_preferences` never receives the key | **explained 2026-10-11**: the screens' fragment sets its preference storage name to `"youtube"` before inflating (`fyp.W` calls `cfy.g("youtube")`; `cfy.c()` is `getSharedPreferences(name, 0)`), so every widget row commits to `youtube.xml` while the extension read `<package>_preferences`, a file that has never existed. Runtime proof: the key scan below shows all `ytvr_mod*` keys (pickers, master, markers, verbose) in `youtube.xml`. **Fixed the same day**: `SponsorBlockSettings.modPreferences` now reads and writes `youtube.xml` too, so the widgets own every row again; `CategoryRows.decorateLogGateRow` → `SponsorBlockSettings.setVerboseLogging` still writes the verbose value itself, so the gate follows the tap without waiting for the widget's apply |

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

## Logging

The extension prints nothing unless its one gate is open. `VerboseLog` owns that gate and everything
above hangs off it.

* **The row, and who writes it.** `Verbose logging`, key `ytvr_mod_verbose_logging`, a
  `SwitchPreference` on the "Patch settings" hub, off by default (in the extension and in the row's
  own `android:defaultValue`). The extension reads and writes `youtube.xml` -- the file the screens'
  widgets persist to, see the trap above -- so the widget's own write reaches the gate, and
  `CategoryRows`' own write (`VerboseLog.setEnabled`) makes the gate follow the tap immediately
  rather than after the widget's async apply. The row renders the gate's *effective* state, so a
  development build shows it ON, and the stored value is sticky across `adb install -r`.
* **`-Diagnostics` also opens it.** The `Verbose diagnostics (development only)` option selects which
  hooks are installed *and* the gate's initial default, so a development build logs from its first
  line with no tap, while a build that never asks for the diagnostics (CI, a release, an end user
  applying the bundle) is silent. A stored choice outranks the option in both directions.
* **One gate.** `VerboseLog.log(tag, message)` is the only place in `extensions/src` that prints;
  every class's `log(String)` is a one-line delegation to it, and `android.util.Log` is used nowhere
  in the extension. A closed gate reads one volatile field and returns before the message is
  formatted. The one deliberate exception is `VerboseLog.logGateToggle(value)`, the ungated echo of
  the verbose row's own tap: gating it swallows it (a tap ON logs while the gate is still shut; a
  tap OFF loses the race against the preference listener closing the gate from the disk-write
  thread), and the echo is the one line that proves a tap worked.
* **Testing the silent default** on a device that has had the row on needs
  `adb shell pm clear app.morphe.android.youtube` (the sticky value survives reinstall; this wipes
  SponsorBlock settings too) or a toggle-off.
* **A development build lists its preference files** (`VerboseLog.logPreferenceFiles`, only when the
  gate is open): the *names* of everything in `shared_prefs/`. That line exists because a switch on
  these screens can show a new state while the file this extension reads never receives it, and the
  only way to tell "never written" from "written somewhere else" is to look.
* **And names the file that holds the mod's keys** (`VerboseLog.logYtvrKeyLocations`, same gate):
  one line per preference file containing keys prefixed `ytvr_mod`, key *names* only. Since the
  extension reads `youtube.xml` itself this is a cross-check rather than a hunt, but it is what
  found the original split (2026-10-11: every `ytvr_mod*` key sat in `youtube.xml` while the
  extension read a file that did not exist).

## Project state and open problems

This file is about *how to work here*, not about what the project currently does. The state of the
hard problems -- the streaming spoof's current limits (unauthenticated `youtubei/v1/player` requests
being refused for every client tried), the SponsorBlock feature set and what is
still missing -- is in **`docs/SPONSORBLOCK-PLAN.md`**. Measured
anchors and the anchors that *failed* are in `docs/FINGERPRINTS.md`.

One operational rule belongs here, because ignoring it costs an outage: the 8K path depends on a
response YouTube can withhold at any time, so the fetch fails open (playback keeps working without 8K).
Two different switches are easy to conflate here. `-Diagnostics` is the cheap one and is self-contained:
the innertube route log, the media-request log and **one** fixed-video probe fetch per launch
(`SpoofStreams.probe()`). The ~14-request identity **matrix** is its own option, `-IdentityProbe`
(default off, and the install script refuses it without `-Diagnostics`, whose route log its output
needs) -- that is unauthenticated traffic of the kind that gets an IP's innertube requests refused, so
it stays off in every default build and must not be re-hammered.

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

**Why the release does not use `cycjimmy/semantic-release-action`.** `release.yml` invokes
`npx semantic-release` from this repository's own `node_modules`, and that is load-bearing. The action
ran `npm ci --only=prod` inside its own directory and required `semantic-release` from there, so it
resolved `conventional-changelog-writer` from its private tree -- whose lockfile pins writer **8.2.0**,
with handlebars -- while the changelog *preset* was loaded from this repository. Preset v10 depends on
`@conventional-changelog/template`, whose legacy-writer guard plants a handlebars `mainTemplate` that
writer@8 compiles and writer@9 ignores, so the release died with "Missing helper:
conventional-changelog-conventionalcommits requires conventional-changelog-writer@9 or newer". That
message is a planted guard, not a version check, and `overrides` can never reach the action's tree --
which is why pinning the writer there was inert and why the preset had to drop to v9 to match writer@8.
Running our own semantic-release keeps the preset and the writer in one tree, so the manifest and
lockfile here are what decide. Two consequences worth keeping: the `conventional-changelog-writer`
override in `package.json` is still needed, because without it npm hoists commit-analyzer's writer@8 to
the top level and the v10 preset imports *that*; and the action's `new_release_published` output is now
reproduced in shell as a tag pointing at HEAD, so do not replace that with log parsing -- semantic-release
creates the version tag after publishing, and a failed release aborts the step under `set -e`.

**Release commits.** The workflow commits its version bump (`CHANGELOG.md`, `gradle.properties`,
`patches-bundle.json`, `patches-list.json`, `README.md`) and back-merges into `develop`, so **always
`git fetch` and rebase before pushing** and never edit those generated files by hand.

**Settings the workflows need:** Settings → Actions → General → Workflow permissions → *Allow GitHub
Actions to create and approve pull requests*. Without it `open_pull_request.yml` fails with a 403.

**The build needs no PAT.** `GITHUB_TOKEN` plus `permissions: packages: read` is enough to read the
Morphe registry from GitHub Packages. The failures that looked like an auth problem were a YAML error
(`steps:` sharing a line with an env value), which makes the workflow unparseable and fails the run in
0s with no log at all -- a zero-second run without a log means a broken workflow file, not a credential.

**Artifact globs.** `actions/upload-artifact` uses `@actions/glob`, which understands only a leading `!`
negation, not `!(a|b)` extglobs. Use a `path: |` list with `!...` exclusion lines.

**Attest.** `release.yml`'s `Attest` step fails on a private repository ("feature not available for
user-owned private repositories") — cosmetic, and it should pass once the repository is public.

**Morphe Manager: the mechanics that are not visible from here.** None of the four below can be read
off this repository, and each one has already looked like a bug report ("there is no clone option",
"my stock app is gone"):

* **Simple mode never draws a patch list for this app.** Simple mode applies only the patches selected
  by default, and for YouTube VR the official bundle contributes none, so a Simple-mode run applies
  this bundle alone -- which is enough since this bundle has its own **Install as clone** patch, and
  that patch is one of those defaults (it is app-specific, so `default = true` is legal). It is the
  *official* **Clone app** that is reachable only in **Settings → Advanced → Expert mode** (off by
  default), in the last, *collapsed* **Universal patches** block of the *Morphe Patches* (official)
  source's page. Nothing here needs it any more.
* **A universal patch can never be default-selected.** The patcher enforces it: a universal patch
  declared default-on fails with "Universal patches must be declared with default false". That is why
  no universal patch -- `Clone app` included -- appears in a default or Simple-mode run, and why the
  clone step had to be re-implemented here as an app-specific patch before Simple mode could apply it.
* **A signature conflict can silently remove the already-installed app.** With Shizuku and "auto
  uninstall" enabled, Manager uninstalls the conflicting package first rather than only warning about
  it. That is how a stock YouTube VR disappears and a patched build ends up owning the official id,
  which Horizon OS then treats as the store app: no sideloading warning, a warning on every reboot
  (the user's report, not measured here), and a collision on the next store update because the build
  is signed differently.
* **Manager recognises a renamer by one option key.** Any patch with an option keyed exactly
  `packageName` is treated as a package renamer, which is what earns the "Clone" badge and the
  post-patch "will install as `<pkg>`" warning. `Clone app` defaults that option to `"Default"`, which
  resolves to `<original>.morphe`; this project uses `app.morphe.android.youtube`.

Manager filters patches before any patch executes within a pass, so a rename there cannot filter other
patches out of that pass. The README's note that the *official* `Clone app` has to be its own pass
belongs to our two-pass CLI script, not to Manager.

**Never select both renamers in one run.** With this bundle's **Install as clone** and the official
**Clone app** both ticked, both finalise and the **official one wins**: patches execute alphabetically
and their finalize blocks run in reverse. `scripts/patch-and-install.ps1` cannot walk into it --
`-OfficialClone` disables ours for that run.

Verify CI in the right repo: `gh run list -R commit-regrets/morphe-youtube-vr-patches`.
