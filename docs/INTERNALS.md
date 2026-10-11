# Internals map and patch cookbook

Everything here is scoped to **YouTube VR 1.88.10** — the version the fingerprints target. The class
and member names come from decompiling that build, and they are *not* stable: R8 reassigns them on
every release. What survives is the **anchor** (a string, a framework reference, a structural
shape), which is why every row below names both, and why [FINGERPRINTS.md](FINGERPRINTS.md) is the
authority on what is safe to match.

Read this when you want to add a patch: find the mechanism you care about, take its anchor from the
table, and verify with the recipe in the "Verifying a change" section.

## Verified hook points

| mechanism | where at 1.88.10 | anchor that finds it | used by |
|---|---|---|---|
| app startup | `YouTubeVrTikTokRoot_Application.onCreate` | manifest-declared class (stable) | Extension (probe, task label) |
| PlayerResponse → streaming data | `oqb.<init>(zjp)` | constructor storing the generated `StreamingDataOuterClass$StreamingData` into its own field | Extension (injects the spoofed formats) |
| SABR / onesie player decision | `PlatypusEnablingManager.a`/`.b` | readable library class; the two `Z`-returning methods | Extension (returns false → legacy player) |
| DataSpec build (last mutable point) | `axe.a()` | no-arg method reading the class's `Uri`/`byte[]`/`int` fields and calling the 11-arg DataSpec constructor | Extension (normalises to GET, no body) |
| media data source + UMP rewrite flag | `pqy.<init>`, field `c` | 5 params + class holds the `"ump"` literal + `instance-of → final iput-boolean` | Extension (clears the UMP envelope rewrite) |
| innertube URL builder | `oth.l()` | the `"youtubei/v1"` literal (unique to that class) | Extension (route log; where upstream-style route blocking would go) |
| every HTTP request | `CronetUrlRequestContext.newUrlRequestBuilder` | Chromium's own names (stable) | Extension (ad-host filter, request log) |
| ad UI skip countdown | `gtu.b(int)` (VR), `fna.b(int)` (2D panel) | `void(int)` calling `TextView.setText` + `setContentDescription`, pinned per class by a framework call | Extension (the "an ad is on screen" oracle) |
| AbrState POST conversion | `axu.b(axf)` | built through `axe` (see above); this is what produced the HTTP 400 | Extension (defeated by the DataSpec normalisation) |
| immutable DataSpec | `axf` | 11-arg constructor, framework/primitive types only | — (read-only target) |
| the app's PlayerResponse proto | `zjp`: `h` streaming data, `g` video details (`g.c` = video id), `n` ad slots, `m` ad marker | proto field indices, not names | Extension (clears `n`, the client-side ad slots — the pre-roll fix) |
| ad-break renderer | `muk.a(List)` | returns `List` **and** constructs `java.util.PriorityQueue` (the `oqi.F()` name it used to read is gone) | Hide ads |
| ad-break fetch | `tyj.s(...)` | string anchor `Exception when trying to request AdBreakResponseModel: %s` | Hide ads |
| PlayerBytes slot detector | `muf.a(oqi)` | `Z` + one reference parameter + the class's `AdCpn is null or empty for CISA scheduling` literal + an `Optional.isPresent()` call in the body | Hide ads |
| PlayerBytes layout bootstrap | `mkz.a(Object)` | the `Bootstrapped layout construction resulted in non PlayerBytesLayout. PlayerAds count: ` string | Hide ads |
| ad tracking/ping loader | `rgx.g(List)` → `tyj.r(Uri)` | parses a URI and hands it to `tyj.r` | — (candidate, see below) |
| stream-stitched ads (SSAI) | `ryi` / cuepoints | the info hook: the generated `ServerStitchedDaiInfo` return type + the class's `daistate` literal + a body that uses the proto; the `Uri`/`Z(J)` hooks still match by class name on purpose | Hide stitched ads (SSAI), off by default |
| age-restriction dialogs | `sfp.f()` (adult), `sfp.k()` (controversial, backed by `sfu.c`) | the policy's own PlayabilityPolicy logger literal + the no-arg boxed-`Boolean` / `Z` shape (and "has a body", or the abstract sibling matches too) | Remove viewer discretion dialog |
| DRC audio | `PlaybackAgnosticUserPreferences.getIsDrcEnabled` | unobfuscated name | Disable DRC audio |
| version spoofing | `ocn.b(Context)` (name), `ocn.a(Context)` (code) | the `pref_override_build_version_name` literal: the class that holds it, its `String(Context)` and its `I(Context)` | Spoof app version |
| QUIC | cronet builder chain in `ltq` | `Object(Object,Object)` holding `youtubei.googleapis.com`, with `const 1` → `enableQuic` → `move-result-object` (1 match; `phn`'s second engine is excluded because it passes `false`) | Disable QUIC protocol |
| osName / device class | `ocn.c(Context)` | `Z(Context)` whose body holds `android.hardware.type.automotive`, in the class holding the version-name preference key (the literal alone occurs in 9 classes) | Spoof osName (Android Automotive) |
| package name in the Cronet User-Agent | `agtm.a(Context, agsc, String)` | `String` return + the `" (Linux; U; Android "` literal + the `getPackageName()` call the patch rewrites | Spoof package name in User-Agent |
| player wrapper seek | `pwe.o(J, ablw)` → backend `qhg.F(J, ablw)` | the `MedialibPlayer.seekTo(positionMillis=%d)` literal | SponsorBlock (mined for the seek `MethodReference`; returns void — skips confirm via `onSeeked`) |
| player wrapper loadVideo | `pwe.j(pzj)` | the full `MedialibPlayer.loadVideo(cpn=%s …)` literal (1 match; `holdsStringLiteral` is exact equality, so a prefix matches nothing) | SponsorBlock (captures the `pwe` instance, resets state, starts the fetch) |
| seek finished | `pwd.u(J, ablw)` (`onSeeked`) | the `onSeeked(positionMillis=%d)` literal | SponsorBlock (the async "the skip worked" signal) |
| ad UI countdown (2nd consumer) | `gtu.b(int)`, `fna.b(int)` | the same fingerprints as the Extension's oracle row above, second injection | SponsorBlock (its own ad gate — works even with the Extension's stream spoof off) |
| playback position / duration | `pwe.a` is the backend interface; `e()` = position ms, `g()` = duration ms. The mapping is proven by the `qkl.a(...)` argument order in the TimeInfo snapshot `pwe.A()` (`qkl`'s `toString` names the fields `currentPositionMillis`/`durationMillis`) | snapshot: wrapper literal + the exact `(J,J,J,J,I,J,J,String)→ref` static prototype (2 app-wide, class literal separates) | SponsorBlock (bridge generation — the app's own UI poller calls `pwe.A()` every 100 ms on the main thread, so tick-time access from the main looper is the app's own pattern) |
| seek-source enum | `ablw.<clinit>`; the constant's static field directly follows the `SEEK_SOURCE_UNKNOWN` const-string | the constant name (1 class app-wide; enum *field* names are obfuscated, only the constant names are dex strings) | SponsorBlock (the seek bridge loads it, so a programmatic skip carries an "unknown/no UI element" source) |
| which video is loading | `zjp.g` = video details, video id read reflectively by shape (the 11-char `[A-Za-z0-9_-]` String inside the details proto); ad responses excluded via the non-empty `zjp.m` list | the `oqb.<init>(zjp)` constructor the Extension already hooks | SponsorBlock (names the video to fetch segments for — no new class in the patched set) |
| seek-bar binder (VR player controls) | `gtw.<init>(View, …)`: binds `R.id.seekbar` (0x7f0b0a23), `seekbar_buffered` (a25), `seekbar_container` (a26), `seekbar_position` (a29), `seekbar_thumb` (a2a) | those five inlined ints, referenced together by no other method (`findint`; `R.id.seekbar` on its own also appears in `SeekBarPreference.a`) | SponsorBlock seek-bar markers (attaches the marker layer over the played bar; the two ids it needs are baked in at app start) |
| seek-bar timeline update | `gtw.e(J,J,J,J)V` = `(positionMs, windowStartMs, durationMs, bufferedMs)`; the body sets `seekbar_position.width = (position-windowStart)/(duration-windowStart) × (parentWidth − track margins)`, the buffered bar's width, and the two time labels | same class + the `(J J J J)V` shape + a `setLayoutParams` call (the class's synthetic `k(JJJJ)V` bridge only forwards and is deliberately not hooked) | SponsorBlock seek-bar markers (mirrors exactly that geometry to place the category bars) |
| the settings column | `R.id.settings_fragment_holder` (0x7f0b0a51) inside the settings card — the FrameLayout the app's settings content is added to; its siblings are `sign_in_fragment_holder` (0x7f0b0aa8) and `environment_settings_fragment_holder` (0x7f0b03af) | the ids' names (looked up in `R.java`); the layout references them, no code does | **no longer used**: the "Patch settings" rows are preferences in the app's own `res/xml/settings_prefs.xml` now (Addendum 14), and the holder was only ever a way to place a view over that list |
| where the settings rows come from | `res/xml/settings_prefs.xml` (immersive) / `panel2_settings_prefs.xml` (panel mode), inflated by the fragment `fzf` instantiates into `R.id.settings_fragment` (`fyr` for environments, `fzb` for the main screen) | the files themselves; androidx inflates them by element name | Patch settings: the section and its switches are appended to those files at the top, so the app builds them as its own rows |
| the player-controls layout | `R.layout.watch_layer_panel`, inflated by the controls fragment (`ftp.fQ`) and hosted in `R.id.player_controls_holder`; the settings panel (`fyi`, entity `SETTINGS_PANEL`) inflates `R.layout.accounts_and_settings_fragment` instead. **The controls view is built at launch**; the earlier "built lazily" note was a symptom of our own bad injection (a wide-argument `invoke` without `/range` made ART reject `gtw`, so the watch UI never built) | layout ids / the inflate call | not hooked — the "Patch settings" screen turned out not to need it (Addendum 14) |
| client identity | VISIONOS 1.03 profile (`requireSABR=false`), upstream's `ANDROID_VR_SABR` (1.73.21 / Pico 4) for comparison | our own request construction | Extension (the streaming spoof) |

## Cookbook: where the next patches would go

Marked **verified** when we measured it, **hypothesis** when it is a lead from our notes rather than
something we tested.

- **Force the `/player` route** (upstream blocks `get_watch` and `initplayback` so nothing else can
  hand the app an un-spoofed response) — **verified location**: the route name is appended in the
  innertube URL builder (`oth.l()`, the `"youtubei/v1"` anchor). Not implemented here because the
  route log showed this app only uses `/player`.
- **Ad telemetry / QOE pings** — **hypothesis**. The content ping carries a client-built
  `adcontext` blob and the QOE ping builder reads `Build.*` directly; the ad tracking/ping loader
  (`rgx.g(List)` → `tyj.r(Uri)`, verified to be a ping path rather than playback) is the entry
  point to look at. Suppressing pings is telemetry hygiene, not ad removal — the pre-roll fix does
  not depend on it.
- **Client-side ad slots** — **verified and done**: the app's own response carries them in `zjp.n`.
  Anything else that reads ad state from the response (`mgn.g` with `SLOT_TYPE_PLAYER_BYTES`, `mkz.a`,
  `mqx`) sees the same cleared list.
- **Quality / resolution preferences, autoplay, background behaviour** — **hypothesis**. These live
  in the player response and the format-selection path; start from the `adaptiveFormats` the VISIONOS
  profile returns and follow what the legacy player selects.
- **VR-specific UI** — the 2D panel ad UI is `fna` and the VR one `gtu` (both verified via the
  countdown anchor); the panel/immersive split is a good discriminator when a hook fires twice.
- **Anything touching the request shape** — go through the DataSpec build (`axe.a()`) or cronet
  (`CronetUrlRequestContext.newUrlRequestBuilder`); between them they cover every request the app
  makes, and the extension already demonstrates both patterns (mutate the builder; rewrite the URL).

## Verifying a change

1. Build the bundle: `./gradlew buildAndroid` (needs `JAVA_HOME`; `GITHUB_ACTOR`/`GITHUB_TOKEN` are
   read from the environment by `settings.gradle.kts`).
2. Apply with diagnostics on, so diagnostics-gated hooks are exercised too:
   `java -jar morphe-desktop-all.jar patch --patches patches/build/libs/patches-<v>.mpp -OenableDiagnostics=true --result-file out.json -o out.apk <stock apk>`
3. Trust `Applied:` only after checking *which* method changed — ambiguity is silent. Extract the
   last `classes*.dex` from `out.apk`, decompile it, and confirm the hook is in the intended method
   and that the patched-class set is unchanged ([FINGERPRINTS.md](FINGERPRINTS.md) has the list).
   Reading that dex with `smali-dexlib2` directly (every method of every dex, printing the opcode,
   registers, literal and reference of each instruction) is faster and stricter than jadx for this:
   jadx hides the `const/4` values and removes unreachable blocks, which is exactly what an injected
   early `return` creates. Keep jadx as the second opinion — one `--single-class <name>` run on the
   extracted dex, which also settles what the *enclosing* method is.
4. On the device: **only ever install a cloned build** — one with the *Clone app* patch applied. A
   verification APK that keeps the original package name must never be installed on a headset: it
   replaces the store's app identity, and Horizon OS answers with a "Restore app" prompt for YouTube
   (measured, by installing one by accident). Then play a video and look for
   `ad slots: cleared N entries`, zero `skip-ad countdown` lines and `onPlaying`; the headset has to
   be *worn* for playback to start.

   Confirmed once on 1.88.10 with the cloned build installed, and it is the shape to compare against:
   `DataSpec builder fields resolved by type on axe: url=a method=d body=e`, zero
   "could not resolve"/"NOT being normalised" lines, `ad slots: cleared 2 entries`, zero countdown
   ticks, zero HTTP 400s, and `onPlaying`.
5. Re-confirmed 2026-10-10 on the build from the re-anchoring pass (cloned to `app.morphe.android.youtube`,
   diagnostics on), from a real playback in the headset: `onPlaying()`, one
   `DataSpec builder fields resolved by type on axe: url=a method=d body=e`, **zero**
   "could not resolve"/"NOT being normalised" lines, 60 `media #n … googlevideo.com/videoplayback`
   requests with 15 `normalising to GET with no body` lines, zero `skip-ad countdown` ticks and zero
   `rc.400`/`staleconfig`. The ad-slot line came out as its other branch,
   `ad slots: the app's response carries none (size=0)`: that response had an empty slot list, so
   there was nothing to clear. Both branches are logged by the same `augmentPlayerResponse` call, so
   that line is itself proof the hook ran on the live player response -- `cleared N entries` needs a
   response that actually carries slots (a monetized video / a fresh session), not a different code
   path.
   A video can be opened from adb without touching the headset:
   `am start -n <clone>/com.google.android.apps.youtube.vr.activities.YouTubeVrActivity -a
   android.intent.action.VIEW -d "https://www.youtube.com/watch?v=<id>"` -- the data *and* the
   component together start playback. With the package alone (`-p <clone>`) or via `vnd.youtube:` it
   does not (the latter resolves to the phone app's `UrlActivity`), and link intents land on the home
   feed.

## Traps that cost real time

- **androidx preference members are R8-renamed; the class names are not.** `androidx.preference.Preference`
  in this APK has `setTitle`/`setKey`/`setSummary`/`addPreference` renamed to `L(...)`, `X(...)`, and so
  on (dumped from the dex), while the class names survive because the app inflates preferences *by class
  name* from `res/xml/*_prefs.xml`. Consequence for patches: **add preferences through the XML**, never
  through reflection by name — and when a reflection call is unavoidable, resolve the member name by
  descriptor from the dex rather than assuming it.
- **The settings list is `res/xml/settings_prefs.xml`** (immersive) and `panel2_settings_prefs.xml`
  (panel mode), inflated by the fragment `fzf` instantiates into `R.id.settings_fragment`
  (`fyr` for environments, `fzb` for the main screen). Adding rows there is how a mod adds a setting.
- **Sub-screen navigation is key-based fragment swaps, not nested screens.** The click handler
  `fzb.aC(Preference)` compares the clicked key and calls `fzf.aH(fyp.class, null)`;
  `fzf.aH(Class, Bundle)` instantiates the class reflectively, passes the Bundle as fragment
  arguments, replaces `R.id.settings_fragment` and adds the transaction to the back stack. Nothing
  implements androidx's start-screen callback, so a nested `<PreferenceScreen>` is inert — and this
  androidx build's `cfq` drops the `ARG_PREFERENCE_ROOT` key it reads, so arguments alone can never
  display a nested screen. 1.88.10 has a **second, parallel settings stack** (`fqg`/`fqa`/`fnj`,
  sharing the same XMLs) — anything re-keying or relabelling the settings UIs must handle both
  stacks. Two more dead surfaces make a two-level hub possible without minting anything (the
  SponsorBlock patch's Mod-settings hub, SPONSORBLOCK-PLAN.md Addendum 18): the `developer_settings`
  screen (its row is removed by `fzb.aD` at startup) and the generic preferences XML of the app's
  unreferenced framework `PreferenceFragment` (its id maps back to a file via
  `res/values/public.xml`). `fzf.aH` skips a swap to the class already showing — a same-class
  re-open (hub -> feature screen) needs a guard override; see the patch's `YtvrSbScreenClass`
  bridge. Watch out: `PreferenceGroup.ab(null)` is **not** null-safe (`preference.A` is
  dereferenced before the guarded list remove). The generalizable recipe — discovery, edits,
  traps (register counts!), verification — lives in [MOD-SETTINGS-STRATEGY.md](MOD-SETTINGS-STRATEGY.md).
- **A view you add must live in a `VrCardView` to be drawn.** The VR app composites only its own
  `VrCardView`s (`framework/view/VrCardView`, `VrViewFacade`, `GlRender`); a view added to the
  window's `DecorView` is laid out and measured (a real 4128x2208) but never rendered into the
  headset. Input is the opposite: clicks reach any child of a card (measured: our injected row logs
  every tap). So "it is in the hierarchy and laid out" proves nothing about being visible — check the
  parent is a card.- **The dock's `App name unavailable` card cannot be fixed from the APK.** Horizon draws it from an
  app-manager/OCMS record keyed by package name, and every API that writes or is consulted for it is
  signature-guarded (`prot=signature|preinstalled|knownSigner`). Manifest metadata, labels, branding,
  task labels and `com.oculus.app_id` are all measured failures; the data path, the guards and the
  reproduction commands are in [APP-NAME-CARD.md](APP-NAME-CARD.md). The one thing that does get a
  name is the app's 2D **panel** entry, because panel apps are named from the APK label.
- **Patched classes move to the last dex** of the patched APK, so grepping the first dexes finds
  nothing. Grep the last one, or use `jadx --single-class`.
- **`defpackage` is a jadx artifact.** At runtime those classes are in the default package, so
  `Class.forName("defpackage.wxe")` throws. Reach them through an instance.
- **`-O` options must precede `--enable`**, and the CLI rejects `-O` without an `-e` in the same
  group. `--result-file` reports the *effective* values; check it.
- **Every `-O` on a command line belongs to the FIRST `--enable`; later patches get no options at
  all.** Measured: `-OenableDiagnostics=true -e Extension` sets *both*
  options (one patch does receive several), but
  `-OenableDiagnostics=true -e Extension -OdisableQuic=true -e "Disable QUIC protocol"` leaves
  `disableQuic=false`, and so does putting both `-O`s before the first `-e`. The second patch's
  option is dropped, not rejected, so the patch reports `Applied:` and then does nothing when the
  option gates its `execute` (which `disableQuic` does). This is not theoretical:
  `scripts/patch-and-install.ps1 -NoSpoof` also passes an app name, which was silently ignored until
  the script switched to an options file. Pass `--options-file` instead -- its root **must be a JSON
  array**, patches it does not mention keep their defaults (the CLI says which on stdout), and it can
  set any number of patches at once -- or patch that one patch in its own run
  (`--exclusive -Okey=value -e <patch>`). **Quote the `-O` argument**: an unquoted
  `-OpackageName=app.morphe.android.youtube` reaches the CLI as `-OpackageName=app` plus a positional
  `.ytvr.youtubevr` and fails with `APK file .ytvr.youtubevr does not exist` (the README's by-hand
  example quotes it, which is why it works there).
- **A fingerprint's `custom` predicate must not assume the method has a body** (or must check it).
  An abstract sibling with the same signature matches the shape, and `addInstructions` on it is not
  the method you meant: `sfp` declares both the concrete `Z k()` and the abstract `Z l()`.
- **References must be spelled with the owner the dex actually uses.** The chained cronet builder is
  typed `ExperimentalCronetEngine$Builder`, so `…ExperimentalCronetEngine$Builder;->setLibraryLoader(…)`
  matches while the "obvious" `CronetEngine$Builder;->setLibraryLoader(…)` matches **zero** methods —
  a silent false negative, which is why an anchor is measured rather than eyeballed.
- **An unlisted app version is skipped, not failed** — exit 0 and an app with none of the fixes.
- **Opcode comparisons must use the enum** (`Opcode.IPUT_OBJECT`), never `Opcode.name` (lowercase
  smali mnemonics).
- **Omitting `parameters` means "any parameter list"**; `parameters = emptyList()` means zero.
- **Inline smali register lists are 4-bit** (v0–v15): in a large method copy the receiver into `v0`
  first; in a small method `p0` *is* `v0`, so copying clobbers `this` (VerifyError).
- **`ad_playback` in the QOE ping is a late signal** and reads "no ad" in short sessions; the skip-ad
  countdown is the immediate one.

## Where the raw material is

`apk/` is gitignored: the stock APK, the decompiled sources (`apk/jadx-out`), the decompiled
official bundles used as reference (`apk/mpp-*`), logs, and patched builds. It is all reproducible:
re-decompile with jadx, and re-derive anchors with the workflow in
.

Local scratch — candidate anchors, hypotheses, name dumps — goes in `docs/LOCAL-NOTES.md`, which is
gitignored on purpose.
