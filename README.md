# 📦🧩 YouTube VR Patches

Patches for YouTube VR (Meta Quest / Horizon OS), for use with
[Morphe](https://morphe.software) — an independent, unaffiliated project.

## ❓ About

Patches for the YouTube VR app for Meta Quest (`com.google.android.apps.youtube.vr.oculus`).

**Version support is pinned to 1.88.10** (`Constants.kt`). R8 reassigns every name per release, so a
store update needs re-fingerprinting, and an unlisted version is *skipped*, not failed — see
[docs/VERSIONING.md](docs/VERSIONING.md) and [docs/FINGERPRINTS.md](docs/FINGERPRINTS.md).

### How to use these patches

Click here to add these patches to Morphe: https://morphe.software/add-source?github=commit-regrets/morphe-youtube-vr-patches

The patches are used exactly like any other Morphe patch set: apply them to your
own copy of the YouTube VR APK. **The defaults are the recommended
configuration** — the ad-free streaming spoof is on and the development
diagnostics are off, so there is nothing to configure.

Getting the APK is its own step — it has to come from your own headset. The easiest
way is using an APK backup tool like **Quest Games Optimizer** directly on the device,
or pulling it via `adb`: see [docs/GETTING-THE-APK.md](docs/GETTING-THE-APK.md).

Supported app versions:

| 1.88.10 |
| :---: |

### Install on the Quest

A patched APK is signed differently from Google's, so it cannot update the store
app in place — Horizon OS detects the mismatch and asks for the app to be removed.
Install it as a **separate app** instead: this bundle's **Install as clone** patch renames the
package, and it is **on by default**, so a normal run produces that separate app with no extra
step. Its *Package name* option is the choice:

* **Clone** (the default) — `app.morphe.android.youtube`: a second app, next to the store's
  YouTube VR, which is left untouched.
* **Replace** — keeps `com.google.android.apps.youtube.vr.oculus`: the store copy has to be
  uninstalled first (the signatures differ), Horizon OS then treats this build as the YouTube VR
  app (a restore prompt, and a warning on every reboot), and the next store update collides with it.

The patched app is listed as **YouTube VR (patched)**; change it with `-AppName` or `-OappName=…`.
(The official "Custom branding" patch does not apply to this app.)

Windows, one command (uses an already-built or downloaded bundle):

```powershell
pwsh -File scripts/patch-and-install.ps1 -Apk <path-to-youtube-vr.apk> `
  -AppName "YouTube VR (patched)" `
  -PackageName app.morphe.android.youtube
```

It runs our bundle (clone included) → `adb install`, checks the *effective* option
values from the patch result — including the package id the rename actually took, so a
silently dropped option or a rename that no-ops cannot ship — and stops with a clear message if any
patch failed. Add `-NoSpoof` to apply the patches with the streaming spoof off (for A/B
comparisons only — the ad-free behaviour comes from the spoof), and
`-OutDir`/`-OurBundle`/`-MorpheCli` if your paths differ. `-OfficialClone` is the escape hatch that
renames with the official *Clone app* instead; it disables ours for that run, because two renamers
must never run together. `-NoClone` renames nothing at all, for a build that is not meant to be
installed.

By hand, it is one apply:

```bash
JAVA=java; M=morphe-desktop-all.jar
# our patches, defaults: clone included, and the id is the one the clone option defaults to
"$JAVA" -jar "$M" patch --patches patches-<version>.mpp -o out.apk youtube-vr.apk
adb install -r out.apk
```

Two traps worth knowing: **every `-O` on a command line belongs to the first `--enable`**, so a
second patch's option is silently dropped — the script passes options through a generated
`--options-file` instead and asserts the effective values from `--result-file`; and if you use the
official *Clone app* instead, it has to be its own pass with `--exclusive --enable "Clone app"`,
because **patches are filtered by package name** and anything after the rename applies 0 patches.
The rest are in [docs/INTERNALS.md](docs/INTERNALS.md).

### Installing with Morphe Manager

The [add-source link](#how-to-use-these-patches) above is all Manager needs — **including the
clone**, which this bundle now performs itself. **Install as clone** is a patch *for this app*
(`compatibleWith(…youtube.vr.oculus)`), which makes it a *default* patch, so Manager's **Simple
mode** applies it along with the rest: pick the app, patch, install, and the patched build arrives
as a second app next to the store's YouTube VR. **Expert mode is no longer needed for the clone.**

That is exactly what changed, and why it could not be done with the official patch: the clone step
used to come from the official bundle's **Clone app**, which is a *universal* patch, and the
patcher refuses a universal patch declared default-on — "Universal patches must be declared with
default false". A universal patch can therefore never be part of a default run, so Simple mode
never showed or applied it, and the only way to reach it was **Settings → Advanced → Expert mode**
(off by default), where it sits in the last, *collapsed* **Universal patches** block of the
*Morphe Patches* (official) source's page. This repository's own patch is app-specific, so
`default = true` is legal. The option this project uses is `app.morphe.android.youtube`, the id the
maintainer's own device uses; Manager badges the patch **Clone** and, once the option is set, warns
"This will install a clone … Will install as `<package>`" — it recognises any patch with an option
keyed `packageName`.

**Do not tick both renamers in one Expert-mode run.** If our **Install as clone** and the official
**Clone app** are both selected, both finalise and the **official one wins**: patches execute
alphabetically and their finalize blocks run in reverse, so the official rename is applied last.
(The local `scripts/patch-and-install.ps1` never does this: `-OfficialClone` disables ours for that
run.)

**Skipping the clone costs more than it looks.** Without it — Manager's *Replace* mode, or
`-NoClone` — the build holds the id `com.google.android.apps.youtube.vr.oculus`, which cannot
coexist with the store app: on a signature conflict Manager either shows a conflict dialog, or —
with Shizuku and "auto uninstall" enabled — uninstalls the installed app first, silently. The build
then holds the id Horizon OS has a YouTube VR record for, so the headset treats it as the store
app: no sideloading warning, a warning on every reboot (the user's report, not something this repo
measured), and a collision on the next store update, because our build is signed differently and
Android will not update across signatures.

One trap does **not** apply to Manager: it filters patches before any patch executes, so a rename
cannot filter other patches out of the same pass. The "the official *Clone app* must be applied
last" note above is about the two-pass CLI script.

### Known behaviour: the "App name unavailable" card

While a **cloned** build is running, its card in the Quest library (after pressing the Meta/Quest
button) shows `App name unavailable` plus Quit instead of the app's name. That is Horizon OS, not
the patches, and it is **not fixable from inside the APK** — the decompiled evidence is in
[docs/APP-NAME-CARD.md](docs/APP-NAME-CARD.md). In short: the library panel
(`com.oculus.panelapp.library`) takes a running *immersive* app's name from an app-manager/OCMS
record keyed by package name, and a re-signed clone under a new package has no such record, so the
panel draws its own placeholder. It does not read the task label (measured: the label is set —
`taskDescription: label="YouTube VR (patched)"` — and the card is unchanged) and it does not read
the APK's label either, which is why the library *tile* shows the name from this repo's **App name**
patch while the card does not. A clone of the *stock* APK with no patches shows the same card, and
installing through Morphe Manager changes nothing: it also produces a re-signed clone. 2D apps
installed the same way look fine because their name comes from a *local* `PackageManager` label
lookup, which works for any package name.

**That Quit *is* how you exit the app.** Immersive VR apps have no in-app exit button: press the
Meta/Quest button, and use the running app's card in the dock (or the app's tile in the library),
where Quit is the only way out. From a PC, `adb shell am force-stop app.morphe.android.youtube` does the
same thing.

**Workaround, if the placeholder is annoying:** YouTube VR declares a **2D panel** entry next to its
VR one (both on the same activity: `com.oculus.intent.category.2D` + `OVERLAY_LAUNCHER` alongside
`com.oculus.intent.category.VR`). Launched through the panel entry the app is a *panel* app, and
Horizon names panel apps from the APK label — so the window is named `YouTube VR (patched)` instead
of showing the placeholder. That is flat YouTube in a resizable panel rather than the immersive
player; which entry you get is why the launcher sometimes offers two ways to open this app. The
immersive card itself cannot be named: its label is that app-manager record, and every API that
writes or is consulted for it is signature-guarded (`prot=signature|preinstalled|knownSigner`) — see
[docs/APP-NAME-CARD.md](docs/APP-NAME-CARD.md).

### Default video quality (start every video at one resolution)

The **Default video quality** patch starts every video at one resolution instead of the app's own
per-video choice, which drifts downward over a session. The requested resolution is a *ceiling*:
a video that does not have it plays at that video's highest available format, so **Highest
available** means 8K on the videos that have it (the stream spoof supplies the 8K format) and
otherwise simply the best there is. The picker also offers the fixed values explicitly — **8K
(4320p)**, **4K (2160p)**, 1440p, 1080p Full HD, 720p, 480p, 360p — and every video then uses that
resolution, falling back to the video's own best only when the video does not have it. **8K** and
**Highest available** are the same ceiling today, because nothing taller than 4320 is served; they
are separate entries so the row shows back the one you chose.

The setting lives on the **Patch settings** hub — **Default video quality** — and applies to the
next video without a rebuild. The row shows the current choice underneath its name, and tapping it
opens the list *inside the row* (a dialog is never composited into the VR panel, so this setting —
like the SponsorBlock behaviour pickers — is not a dialog). The Manager patch option of the same
name only *seeds* the headset setting on first launch; the headset row governs from the first change
there. **Auto** hands the choice back to the app.

### SponsorBlock (skip sponsors, self-promos, intros, ...)

The **SponsorBlock** patch automatically skips crowd-sourced segments from
[sponsor.ajay.app](https://sponsor.ajay.app) while a video plays.

**It is on by default**, so a normal build includes it out of the box and installs the in-headset
**Patch settings** hub screen.

Default behaviour ships with upstream's recommended configuration (`sponsor` = `skip-once`;
`selfpromo`, `interaction`, `highlight`, `intro`, `outro`, and `music_offtopic` = `seekbar-only`;
`preview`, `hook`, and `filler` = `ignore`), with customizable build options for the API base
(a self-hosted instance works) and per-category behaviour.

How it behaves (all three measured on the headset against a real segment):

- playback that **reaches** a segment skips it the moment the playhead enters it, seeking to the
  segment's end (measured: 0.5 s into a 40.781 s segment start);
- **dragging the seek bar back before** a segment makes it skip again — the skip is per *pass*, not
  per app run, so re-watching a video skips again too;
- **dragging the seek bar into** the middle of a segment lets that segment play out, which is what
  the web extension does (a segment you asked for is not yanked away);
- `skip-once` means once per *playback*: seeking back before the segment re-arms it, and only a
  segment you were *seeked into* stays un-skipped while the playhead is inside it;
- no skip is attempted while the app's own ad UI is counting down;
- segments are fetched once per video; `404` — "no segments here" — is the normal quiet answer.

Every decision logs to logcat under the `SponsorBlock:` tag, so a session can be checked afterwards:

```powershell
adb logcat -d | rg -a "SponsorBlock:"
```

SponsorBlock segments are also drawn **on the VR seek bar**: one coloured bar per segment, in
SponsorBlock's own category colours, so you can see what is coming and where a segment was skipped.
They are drawn while the player controls are on screen and cost nothing while those are hidden.

### Patch settings (in the headset)

YouTube VR's own **Settings** list gains a **Patch settings** row at the top. Tapping it opens the
mod's own hub — the app's native fragment navigation (back returns to the list), Morphe-style: one
entry per feature, and features add rows there as they grow. Today:

- **SponsorBlock** — opens the SponsorBlock screen:
  - **SponsorBlock** — the master switch. Off means no fetching and no skipping.
  - **Seek-bar markers** — toggles drawing category markers directly on the VR seek bar.
  - **Categories** — one row per category (Sponsor, Unpaid / self-promotion, Interaction reminder,
    Intro / intermission, Outro / endcards, Highlight, Preview / recap, Hook / greetings, Filler / tangent,
    Non-music section), colour-coded to match the seek bar markers. Tapping a category opens an in-row
    behaviour picker (**Skip automatically**, **Skip automatically once**, **Show in seekbar only**, or **Disable**).
  - **Stats** — display-only counter showing total skipped segments and time saved.
  - **Minimum segment duration** — seek-bar slider to ignore segments shorter than a specified number of seconds.
- **Verbose logging** — toggles diagnostic logs to logcat for troubleshooting.

All of it is built from the app's own preferences XML, so the rows render, scroll, focus and respond
to the controller exactly like YouTube's own rows.

Changes are stored on the device and apply to the next video — no rebuild, no restart. They override
the patch options, which remain the defaults (`sponsor` = `skip-once`, `selfpromo`/`interaction`/`intro`/`outro`/`highlight`/`music_offtopic`
= `seekbar-only`, `preview`/`hook`/`filler` = `ignore`). The section is this
project's own, named so it cannot be confused with Morphe's settings.

The other patch options (the streaming spoof, the diagnostic probes) stay build-time on purpose: the
spoof is applied as *hooks*, so turning it off means a build without them rather than a switch.

One thing to know before reporting a bug:

- The switched-off categories do **nothing at all** — silently. On the phone those categories show a
  *skip button*, and VR has no button, so a video whose only segments are `filler` or
  `music_offtopic` looks like SponsorBlock is broken until you switch that category on in Patch settings.

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.24.0](https://github.com/commit-regrets/morphe-youtube-vr-patches/releases/tag/v1.24.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;12 patches total
<details open>
<summary>📦 YouTube VR&nbsp;&nbsp;•&nbsp;&nbsp;12 patches</summary>
<br>

**🎯 Supported versions:**

| 1.88.10 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [App name](#app-name) | Changes the app name shown in the headset's library and in app info. The stock name is just "YouTube", which is easy to confuse with the store app. | • App name |
| [Default video quality](#default-video-quality) | Starts every video at one resolution instead of the app's per-video choice; a resolution the video lacks falls back to its highest available (8K included when the stream spoof provides it). Changeable in the headset's Patch settings; Auto keeps the app's own behaviour. | • Default video quality |
| [Disable DRC audio](#disable-drc-audio) | Disables DRC (Dynamic Range Compression) audio. |  |
| [Disable QUIC protocol](#disable-quic-protocol) | Adds an option (off by default) to disable QUIC (Quick UDP Internet Connections) network protocol; selecting the patch alone changes nothing until the option is enabled. | • Disable QUIC protocol |
| [Extension](#extension) | Injects the extension the other patches run through: its streaming spoof is what removes the pre-roll (ad-free playback) and provides the 8K format support. Turning this patch off restores ads. | • Ad-free streaming spoof (recommended)<br>• Verbose diagnostics (development only)<br>• Force verbose logging (development builds)<br>• Probe identity matrix (~14 requests; needs Verbose diagnostics)<br>• Native formats without SABR (retired experiment)<br>• 8K: accept the AV1 8K HDR tier as SDR (experiment) |
| [Hide ads](#hide-ads) | Hides video ads. |  |
| [Hide stitched ads (SSAI)](#hide-stitched-ads-ssai) | Removes the client side ad configuration and cue points for server-stitched (SSAI) streams. It does not remove the ad itself; keeping ads out is the Extension patch's streaming spoof. Off by default. |  |
| [Install as clone](#install-as-clone) | Installs the patched app as a separate app by default, so it sits alongside the store's YouTube VR instead of replacing it. Replace mode keeps the official package name: the store copy has to be removed to satisfy the signing difference, Horizon OS then treats this build as the YouTube VR app (a restore prompt, and a warning on every reboot), and a store update will collide with it because this build is signed differently. | • Package name<br>• Update permissions<br>• Update providers |
| [Remove viewer discretion dialog](#remove-viewer-discretion-dialog) | Removes the dialog that appears when opening a video that has been age-restricted by accepting it automatically. This does not bypass the age restriction. |  |
| [SponsorBlock](#sponsorblock) | Automatically skips SponsorBlock segments (sponsors, self-promos, intros, ...). On by default (sponsors: skip once); configure per-category behaviour below or in the headset's Patch settings screen. | • SponsorBlock API base<br>• Sponsor<br>• Self-promotion<br>• Interaction reminder<br>• Intermission/intro<br>• Outro/endcards<br>• Preview/recap<br>• Hook (padding after intro)<br>• Filler tangent<br>• Music: non-music<br>• Highlight (jump to point) |
| [Spoof app version](#spoof-app-version) | Adds an option to trick the app into thinking you are running a different version; the option ships set to the app's current version, so selecting the patch alone changes nothing. | • Spoofed version name |
| [Spoof package name in User-Agent](#spoof-package-name-in-user-agent) | Adds an option to report a different app package name in the Cronet User-Agent; the option ships set to the original package name, so selecting the patch alone changes nothing. | • Spoofed package name |

</details>

<!-- PATCHES_END -->

## 🧰 Requirements and tooling

| tool | what it is for | where it is looked for |
|---|---|---|
| **JDK 21** | Gradle, and `javac` for the injected extension | `JAVA_HOME` |
| **`android.jar`** | the Android platform jar the patch build compiles against | `-PandroidJar=<path>`, `$ANDROID_JAR`, or `~/tools/android/android.jar` |
| **`r8.jar`** | shrinks and dexes the extension into `ytvr-extension.dex` | `-Pr8Jar=<path>`, `$R8_JAR`, or `~/tools/r8/r8.jar` |
| **Morphe Desktop** (`morphe-desktop-all.jar`) | applies the built bundle to the APK | see [Install on the Quest](#install-on-the-quest) |
| **Android platform-tools (`adb`)** | installing on the Quest, and reading its logs | `PATH` |
| **Android SDK build-tools (`d8`)** | dexing the extension during the build | `PATH` |
| **[jadx](https://github.com/skylot/jadx)** | reading the app's bytecode: every anchor in [docs/FINGERPRINTS.md](docs/FINGERPRINTS.md) was measured with it, and it is how a patched dex is checked after a build | `PATH` |
| **[REA](https://github.com/morluto/rea)** *(optional)* | reverse-engineering toolkit: device integration and APK/JADX analysis | `PATH` |
| **`gh`** *(optional)* | checking CI runs and releases | `PATH` |

You also need a **stock YouTube VR 1.88.10 APK** as the input; this repository does not ship one.
The build's first run needs network access to resolve the patcher dependency.

REA's device documentation is where the two workflow recipes came from — capturing the headset without
wearing it (`svc power stayon true`, then a wake, then an on-device `screencap` pulled with `adb pull`),
and wireless adb (`adb tcpip 5555` once over USB, then `adb connect`) — both written up in
[AGENTS.md](AGENTS.md#device-access). One honest caveat: `rea capture-adb-screen` did not work in this
environment (a schema error asking for an output directory, then `unreadable_output` once supplied), so
the screenshots the notes refer to came from plain `adb`.

### 🛠️ Building locally

- Run `./gradlew buildAndroid`
- The built patches .mpp file is found in `patches/build/libs/patches-*.mpp`
- Patch the mpp file using [Morphe-Desktop](https://github.com/MorpheApp/morphe-desktop) like any other patch bundle.

See the [Morphe documentation](https://github.com/MorpheApp/morphe-documentation) for more information.

### Development notes

The verified mechanisms, the experiment ledger and the re-derivation workflow for a future app
version live in [`docs/`](docs/) — start at [docs/README.md](docs/README.md). Those files are
hand-written; the patches list above, `CHANGELOG.md`, `patches-list.json`, `patches-bundle.json`
and the version in `gradle.properties` are generated by the release pipeline, so edit the prose,
not those.

**Logging is opt-in and never on by default.** A normal apply — CI, a release, or anyone using the
bundle through Morphe Manager — injects `VerboseLog.init` and nothing else, so the app prints
nothing unless the viewer turns on the in-headset **Verbose logging** row. For local work there are
two development-only options: `-VerboseLogging` (log from the first line and ignore the row's stored
value, no extra hooks and no extra network requests) and `-Diagnostics` (the same, plus the
innertube/media route logs and one probe fetch per launch). Both default to off.


## Acknowledgements

This project is a **derivative of [WalkTheEarth/morphe-ytvr-patches](https://github.com/WalkTheEarth/morphe-ytvr-patches)**
(GPL-3.0) — the original Morphe patches for YouTube VR — and builds on the
[Morphe patcher framework](https://github.com/MorpheApp/morphe-patcher) and its official patch
bundle. Full attribution, including what came from where and what changed here, is in
[ACKNOWLEDGEMENTS.md](ACKNOWLEDGEMENTS.md).

## 📜 License

YouTube VR Patches are licensed under the [GNU General Public License v3.0](LICENSE)

This repository is derived from another GPL-3.0 project and builds on Morphe's own patches —
see [ACKNOWLEDGEMENTS.md](ACKNOWLEDGEMENTS.md) for what came from where, and for the list of
changes made here.

