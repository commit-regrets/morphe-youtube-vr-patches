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

Getting the APK is its own step — it has to come from your own headset:
[docs/GETTING-THE-APK.md](docs/GETTING-THE-APK.md).

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

### SponsorBlock (skip sponsors, self-promos, intros, ...)

The **SponsorBlock** patch automatically skips crowd-sourced segments from
[sponsor.ajay.app](https://sponsor.ajay.app) while a video plays.

**It is off by default**, so a normal build does not include it and there is no trace of it in the
app. Turn it on for a build either by ticking it in Morphe Manager or, with the local script:

```powershell
.\scripts\patch-and-install.ps1 -Apk <stock apk> -ExtraPatches SponsorBlock
```

Configuration today is the patch options: the API base (a self-hosted instance works) and one
behaviour per category — **skip**, **skip-once** or **ignore**. The defaults are
`sponsor`/`selfpromo`/`interaction`/`intro`/`outro` = `skip`, and
`preview` / `hook` / `filler` / `music_offtopic` / `poi_highlight` = `ignore`.

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
  - **Seek-bar markers** — the coloured bars on the seek bar, if you want the skipping without them.
  - **Skip each segment only once** — off, a segment is skipped every time; on, it is skipped the first
    time and plays through after that.
  - **Categories** — one switch per SponsorBlock category: Sponsor, Unpaid / self-promotion, Interaction
    reminder, Intro / intermission, Outro / endcards, Highlight, Preview / recap, Hook / greetings,
    Filler / tangent, Non-music section. The first five are on by default.

All of it is built from the app's own preferences XML, so the rows render, scroll, focus and respond
to the controller exactly like YouTube's own rows.

Changes are stored on the device and apply to the next video — no rebuild, no restart. They override
the patch options, which remain the defaults (`sponsor`, `selfpromo`, `interaction`, `intro` and `outro`
= `skip`, the rest ignored — `skip-once` is what the "only once" switch turns on). The section is this
project's own, named so it cannot be confused with Morphe's settings.

The other patch options (the streaming spoof, the diagnostic probes) stay build-time on purpose: the
spoof is applied as *hooks*, so turning it off means a build without them rather than a switch.

One thing to know before reporting a bug:

- The switched-off categories do **nothing at all** — silently. On the phone those categories show a
  *skip button*, and VR has no button, so a video whose only segments are `filler` or
  `music_offtopic` looks like SponsorBlock is broken until you switch that category on in Patch settings.

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.23.1](https://github.com/commit-regrets/morphe-youtube-vr-patches/releases/tag/v1.23.1)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;12 patches total
<details open>
<summary>📦 YouTube VR&nbsp;&nbsp;•&nbsp;&nbsp;12 patches</summary>
<br>

**🎯 Supported versions:**

| 1.88.10 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [App name](#app-name) | Changes the app name shown in the headset's library and in app info. The stock name is just "YouTube", which is easy to confuse with the store app. | • App name |
| [Disable DRC audio](#disable-drc-audio) | Disables DRC (Dynamic Range Compression) audio. |  |
| [Disable QUIC protocol](#disable-quic-protocol) | Adds an option to disable QUIC (Quick UDP Internet Connections) network protocol. | • Disable QUIC protocol |
| [Extension](#extension) | Injects the YouTube VR patch extension used by streaming patches. | • Ad-free streaming spoof (recommended)<br>• Verbose diagnostics (development only)<br>• Probe identity matrix (~14 requests; needs Verbose diagnostics)<br>• Native formats without SABR (retired experiment)<br>• 8K: accept the AV1 8K HDR tier as SDR (experiment) |
| [Hide ads](#hide-ads) | Hides video ads. |  |
| [Hide stitched ads (SSAI)](#hide-stitched-ads-ssai) | Removes the client side ad configuration and cue points for server-stitched (SSAI) streams. Does not remove the ad itself; off by default. |  |
| [Install as clone](#install-as-clone) | Installs the patched app as a separate app by default, so it sits alongside the store's YouTube VR instead of replacing it. Replace mode keeps the official package name: the store copy has to be removed to satisfy the signing difference, Horizon OS then treats this build as the YouTube VR app (a restore prompt, and a warning on every reboot), and a store update will collide with it because this build is signed differently. | • Package name<br>• Update permissions<br>• Update providers |
| [Remove viewer discretion dialog](#remove-viewer-discretion-dialog) | Removes the dialog that appears when opening a video that has been age-restricted by accepting it automatically. This does not bypass the age restriction. |  |
| [SponsorBlock](#sponsorblock) | Automatically skips SponsorBlock segments (sponsors, self-promos, intros, ...). Off by default; configure per-category behaviour below. | • SponsorBlock API base<br>• Sponsor<br>• Self-promotion<br>• Interaction reminder<br>• Intermission/intro<br>• Outro/endcards<br>• Preview/recap<br>• Hook (padding after intro)<br>• Filler tangent<br>• Music: non-music<br>• Highlight (jump to point) |
| [Spoof app version](#spoof-app-version) | Adds an option to trick the app into thinking you are running a different version. | • Spoofed version name |
| [Spoof osName (Android Automotive)](#spoof-osname-android-automotive) | Reports the app as an Android Automotive device, the way the phone patch set blocks video ads. |  |
| [Spoof package name in User-Agent](#spoof-package-name-in-user-agent) | Adds an option to report a different app package name in the Cronet User-Agent. | • Spoofed package name |

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

