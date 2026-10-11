# Handoff: make "Patch settings" open a SponsorBlock sub-screen

> **RESOLVED** (2026-10-10) — the three blockers below are real but **not fatal**: the app's own
> developer-settings screen is dead UI whose navigation (`fzb.aC` → `fzf.aH(fyp.class, null)`) is
> fully reusable. The SponsorBlock patch now hijacks it: the click handler's key literal is swapped
> to `ytvr_mod_settings`, `developer_settings_prefs.xml`'s children are replaced with the
> SponsorBlock rows, and the screen's own row-wiring is skipped (it would NPE on the slimmed XML).
> No class, resource id or navigation code had to be created. Read SPONSORBLOCK-PLAN.md
> **Addenda 17–18** for the evidence (18 is the current Morphe-style hub), the two near-misses (`PreferenceGroup.ab(null)` is not
> null-safe; this androidx build drops `ARG_PREFERENCE_ROOT`) and the verification status. The rest
> of this document is kept as the record of why the earlier attempt stopped at a flat list.

**Asked for by the user:** in Settings, tapping **Patch settings** should open a **sub-screen** (like the
app's own `developer_settings` / `environments_menu` rows do) titled **SponsorBlock**, with the
SponsorBlock settings *inside* it. Today everything is flat on the settings screen itself:

```
Patch settings          <- PreferenceCategory header
  SponsorBlock              [switch]
  Seek-bar markers          [switch]
  Skip each segment only once [switch]
Categories            <- PreferenceCategory header
  Sponsor … Non-music section   (10 switches)
```

Wanted:

```
Patch settings          <- the only thing on the main settings list
  └─ tap ─▶ SponsorBlock          (sub-screen)
              SponsorBlock              [switch]
              Seek-bar markers          [switch]
              Skip each segment only once [switch]
              Categories                (header)
              Sponsor … Non-music section   (10 switches)
```

Nothing about the *behaviour* needs to change: the switches keep the same `android:key`s
(`ytvr_mod_sb_enabled`, `ytvr_mod_sb_markers`, `ytvr_mod_sb_skip_once`, `ytvr_mod_cat_<category>`) and
the extension keeps reading them from `youtube.xml` (the screens' preference file; measured 2026-10-11). This is a UI-structure change plus
whatever the navigation needs.

---

## Where the code is

| what | where |
|---|---|
| the rows that get written into the app's prefs XML | `patches/src/main/kotlin/app/ytvr/patches/sponsorblock/SettingsXml.kt` — `addModSettingsRows(patchWorkspace)`, `SECTION_ROWS`, `CATEGORIES` |
| the call site (inside the SponsorBlock patch's `execute`) | `patches/src/main/kotlin/app/ytvr/patches/sponsorblock/SponsorBlockPatch.kt`, end of `execute` |
| what reads the switches | `extensions/src/SponsorBlockSettings.java` — `enabled()`, `markersEnabled()`, `behaviour()`, `modBoolean()`, `modPreferences()`, `logModSettings()` |
| design record (read Addenda 8–16 first) | `docs/SPONSORBLOCK-PLAN.md` |
| app internals worth knowing | `docs/INTERNALS.md`, `docs/FINGERPRINTS.md` |

`addModSettingsRows` finds the two files by name — `settings_prefs.xml` and
`panel2_settings_prefs.xml` — under the patcher's session root, inserts the rows **before the app's own
first child** (top of the list), and throws if it finds nothing. Idempotence is by the
`ytvr_mod_settings` key on our header.

## How the app's settings actually work (verified this session)

- The settings list is **androidx preferences**: `res/xml/settings_prefs.xml` (immersive) and
  `panel2_settings_prefs.xml` (panel mode) are inflated one `Preference` per element. The holder
  fragment is `fzf`; it instantiates `fyr` (inflates `R.xml.environments_prefs`) or `fzb` (inflates
  `R.xml.settings_prefs` / `panel2_settings_prefs`) into `R.id.settings_fragment`, and its
  `aH(Class, Bundle)` method is the fragment-swap used for navigation. There is also a
  `developer_settings_prefs.xml` in the APK, i.e. the app does have a second settings screen.
- `fzb` overrides the preference-click handler (`aC(Preference)Z`) — **its body was not read in full**;
  that is where "which row opens which screen" lives, so read it first.
- A plain `androidx.preference.SwitchPreference` renders exactly like the app's rows:
  `ProtoDataStoreSwitchPreference extends SwitchPreference` and overrides neither
  `getLayoutResource` nor a view holder. Category headers use the app's
  `@layout/settings_pref_title_with_divider` (what we use).
- **Do not use `ListPreference`**: `fzf.aI(PreferenceScreen, Set)` walks the tree and removes *every*
  `ListPreference` (plus keys in a small hide set: `user_initiated_feedback`, `keyboard_settings`,
  `environments_menu`, `invert_scroll`). Our keys are not in it, so our switches survive.

## Hard constraint that shapes the fix

**R8 renames androidx's *members* while keeping its *class names*.** In this APK
`androidx.preference.Preference`'s `setTitle` / `setKey` / `setSummary` / `addPreference` are `L(…)`,
`X(…)`, `addPreference` etc. — verified by dumping the class. Class names survive only because XML
inflation needs them.

Consequence: **configuration through XML works; reflection by name silently does nothing** (an earlier
attempt did exactly that and produced "I don't find patch settings anywhere"). If you ever must call an
androidx method, resolve its name by *descriptor* from the dex and pass it in, or find it structurally.

---

## What was checked, and why a real sub-screen is not reachable

Three independent blockers, all verified in this session's tool results:

1. **A nested `<PreferenceScreen>` would be inert.** The app's own sub-screens are *separate
   fragments* swapped into `R.id.settings_fragment`: `fzb.aC(Preference)` compares the clicked key and
   calls `fzf.aH(fyp.class, null)` for `developer_settings`, `fzf.aH(lzs.class, null)` for licenses, and
   so on, then `return super.aC(preference)` for anything else. androidx's default navigation into a
   nested screen needs the host to implement its start-screen callback, and **nothing in the app
   implements `OnPreferenceStartScreen`** (searched the decompiled sources). `android:fragment` has the
   same requirement.
2. **A patch cannot add the fragment class** such a screen would need. The patcher's bytecode context
   exposes class *lookup* and mutation (`classDefBy`, `mutableClassDefBy`) but no way to create a class;
   the extension dex can add classes, but they are compiled against `android.jar` only -- no androidx,
   and androidx's members are renamed anyway (see the constraint above), so a hand-written
   `PreferenceFragmentCompat` subclass cannot work.
3. **A patch cannot mint the resource id** a new preferences XML would need. The fragment copy would
   have to inflate a *new* `res/xml/*.xml` whose numeric id only exists after resource compilation,
   and nothing in the patch API resolves an id by name. Reusing an existing preferences XML instead
   means our rows land on one of the app's own screens (developer settings, licenses, environments),
   which is worse than a flat list.

So the shipped answer is the closest a flat list gets: a **"SponsorBlock" header** under
"Patch settings", with the SponsorBlock switches and the "Categories" header beneath it, so the list
reads as SponsorBlock's settings rather than as loose switches:

```
Patch settings            (header, key ytvr_mod_settings)
SponsorBlock            (header)
  Enable SponsorBlock            [switch]   key ytvr_mod_sb_enabled
  Seek-bar markers               [switch]   key ytvr_mod_sb_markers
  Skip each segment only once    [switch]   key ytvr_mod_sb_skip_once
  Categories             (header)
  Sponsor … Non-music section    (10 switches)  key ytvr_mod_cat_<category>
```

If a genuine tap-to-open sub-screen is ever required, the only route is to make the whole settings
surface our own (a `PreferenceFragment`-shaped extension compiled against a *kept* androidx API, which
this APK's R8 run does not provide) -- i.e. not a patch-level change.

## If you do try anyway

- The one cheap experiment left: give the "SponsorBlock" `PreferenceCategory` a child
  `<PreferenceScreen>` and see whether androidx's version here happens to navigate. Expect nothing; the
  app does not implement the callback.
- Everything else in the "Build, install, verify" section below still applies, as do the traps.

---

## Build, install, verify

```powershell
$env:JAVA_HOME='C:\Users\<your-user>\tools\jdk21'
$env:GITHUB_ACTOR='commit-regrets'; $env:GITHUB_TOKEN=(gh auth token -u commit-regrets)
.\gradlew buildAndroid --console=plain

.\scripts\patch-and-install.ps1 -Apk apk\com.google.android.apps.youtube.vr.oculus.apk `
  -PackageName app.morphe.android.youtube `
  -ExtraPatches SponsorBlock -Adb C:\adb\platform-tools\adb.exe
```

Notes that cost time this session:

- The `.mpp` is version-named and `buildAndroid` writes a **new** name; the install script picks the
  newest, but a hand-written `morphe patch` command pinned to an old file silently tests stale code.
- `apk/` accumulates ~116 MB builds: check free space, it fails with a bare
  `IOException: There is not enough space on the disk` late in the run.
- A bytecode patch's `fileWorkspace` is **not** the decoded resource tree (that is why
  `addModSettingsRows` searches from `fileWorkspace.parentFile`).
- `default = false` patches are **not** pulled in by `dependsOn` ("Skipping disabled" in the log).

**Verify the rows are really in the build** (the APK shortens resource paths and deflate-compresses
the XML, so grepping the archive proves nothing):

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[System.IO.Compression.ZipFile]::OpenRead((Resolve-Path 'apk\ytvr-final.apk'))
foreach ($e in $zip.Entries) { if ($e.FullName -match '\.xml$' -and $e.Length -lt 400000) {
  $ms=New-Object System.IO.MemoryStream; $e.Open().CopyTo($ms)
  if ([Text.Encoding]::UTF8.GetString($ms.ToArray()).Contains('ytvr_mod_sb_enabled')) { $e.FullName } } }
$zip.Dispose()
```

**On the headset:** launch, open Settings, tap the row, and check the log:

```powershell
adb logcat -d | rg -a "SponsorBlock: patch settings" | Select-Object -Last 2
```

`patch settings (youtube.xml): sponsorblock=… markers=… skipOnce=…, N categor(y/ies) on` means the
app stored a toggle and the extension read it. `the screen's switches are not there yet` means no
toggle has been written yet (or the file differs — then `logModSettings()` in
`SponsorBlockSettings.java` is where to widen the search).

adb serial `<headset serial>`; the test build is the cloned package
`app.morphe.android.youtube`; a reliable SponsorBlock test video is
`https://www.youtube.com/watch?v=P2JjlayovG8&t=36s` (sponsor segment 40.781 s → 125.339 s).

## Acceptance criteria

1. Settings shows the **Patch settings** header with a **SponsorBlock** header under it, and the
   SponsorBlock switches grouped beneath that (shipped; verified in the built APK).
2. A genuine tap-to-open SponsorBlock sub-screen would need the three blockers above solved first.
3. Back/controller navigation returns to the settings list; the app does not crash (check
   `adb logcat -d -b crash`).
4. Flipping a switch is reflected in the log line above, and skipping honours it on the next video.
5. `docs/SPONSORBLOCK-PLAN.md` gets an addendum describing what was tried and what shipped;
   `README.md`'s "Patch settings" section describes the sub-screen; `FINGERPRINTS.md` / `INTERNALS.md`
   get any new anchor (with a note if it is name-free or hand-kept).

## Standing rules in this repo

Never make the repo public. Do not hand-edit `CHANGELOG.md`, `patches-list.json`,
`gradle.properties`, or README's generated patch-list block (the release pipeline owns them). Do not
cut a release without explicit permission. Release version bumps come from the pipeline.
