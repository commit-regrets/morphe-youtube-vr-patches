# Acknowledgements

This repository is a **derivative of someone else's GPL-3.0 work**. Everything below is credited
accordingly; see [LICENSE](LICENSE) (GNU GPL-3.0, kept from upstream).

## The patch set this is derived from

- **[WalkTheEarth/morphe-ytvr-patches](https://github.com/WalkTheEarth/morphe-ytvr-patches)**
  (GPL-3.0) — the original "Morphe patches for YouTube VR (Meta Quest)" project. This repository
  started as a clone of it and is **31 commits ahead** of its `main`, with the same license and
  the same copyright holders.

  What changed here, so nobody has to guess:
  - **Playback fix:** the spoofed VISIONOS formats were refused with HTTP 400 because the app
    rewrote the request shape (a UMP `?ump=1&range=` envelope from `pqy`, and an AbrState
    protobuf POST body from `axu`). The DataSpec build is normalised back to a plain GET with no
    body, and `pqy.c` is cleared.
  - **Pre-roll fix:** the ad is a *client-side ad slot* in the app's **own** player response
    (the spoof only ever replaced `streamingData`), so the slot list is cleared on the app's own
    response. A/B verified: with the clear, the skip-ad countdown never ticks and no ad request
    is made.
  - **`App name` patch** (new): the official *Custom branding* patch is a resource patch for the
    phone YouTube/Music apps and applies **zero** patches to YouTube VR, so this repo sets
    `youtube_vr_app_name` itself through a `values-v24` overlay.
  - `scripts/patch-and-install.ps1` (new): the whole Quest pipeline in one command.
  - Extension hooks, probes and the development notes — development
    scaffolding, not upstream material.

## Framework and patch ecosystem

- **[MorpheApp/morphe-patcher](https://github.com/MorpheApp/morphe-patcher)** and
  **[MorpheApp/morphe-patches](https://github.com/MorpheApp/morphe-patches)** — the patcher API
  and the official patch bundle this repo builds against. The official bundle's **Clone app**
  patch is required to install any of this on a Quest, because a differently signed APK cannot
  update the store app.
- **[ReVanced](https://github.com/ReVanced)** — the upstream project Morphe derives from, and the
  source of several approaches this work measured against or adapted, notably
  `removeVideoPlaybackPostBody`, `blockGetWatchRequest` and `blockInitPlaybackRequest`.

## Reference repos mined during this work

The ad investigation explicitly mined these
projects. They solve the *phone* app's variant of the problem; the techniques below were ported
or measured against here, and each is credited for what it actually contributed.

- **[Akramx15/youtube-vr-adfilter](https://github.com/Akramx15/youtube-vr-adfilter)** — an
  experimental ad filter for YouTube VR **1.87.13 only** (its `TargetPolicy` hard-requires version
  name `1.87.13` and version code `18713000`), shipped two ways: a standalone modified APK, and an
  LSPosed/Vector module for a rooted headset. It hooks exactly two void instance methods —
  `mhp.m(List)` (their `loadAds`) and `mjz.a(Object)` (their `adLayout`) — and deliberately refuses
  to run on any other version. Mapping those onto the version this repo targets (**our inference,
  not their claim**): `adLayout` is the PlayerBytes-layout bootstrap our *Hide ads* patch also
  neutralises (`mkz.a` in 1.88.10, the class holding the `Bootstrapped layout construction…`
  anchor), and `loadAds` corresponds to `rgx.g(List)`, which parses a URI and hands it to
  `tyj.r(Uri)` — the ad tracking/ping path in our notes, not playback. Both hooks were therefore
  already covered by our own ad layers and **no code was copied** from it; its LSPosed route
  (runtime hooking instead of repackaging, so nothing is re-signed) is the main alternative
  approach to what this repo does. Their project in turn credits NexAlloy for its ad-path
  fingerprints and QuestPatcher for its fallback launch method.
- **[MorpheApp/morphe-patches](https://github.com/MorpheApp/morphe-patches)** — the official
  flat-YouTube patch set. Its `ClientType.ANDROID_VR_SABR` profile shaped the client-spoof
  experiments, `SpoofOSNamePatch` inspired one of them, and its
  `PlayerBytesAdLayoutFingerprint` string anchor (`Bootstrapped layout construction…`) is shared
  with this app and became one of the ad layers here. Same licence (GPL-3.0).
- **[NexAlloy/NexAlloy](https://github.com/NexAlloy/NexAlloy)** — an actively maintained
  Morphe-format Xposed patchset for the phone app; its device-class levers corroborated one
  experiment's direction.
- **[MorpheApp/morphe-desktop](https://github.com/MorpheApp/morphe-desktop)** — the patcher CLI
  used throughout (`patch`, `list-patches`, `-Okey=value` option passing, `--result-file`).
## Tooling

Credited here as well as listed in the README's [Requirements](README.md#-requirements-and-tooling),
because "what came from where" is what this file is for:

- **[jadx](https://github.com/skylot/jadx)** — every decompilation these patches are anchored on. An
  anchor is only trustworthy when the bytecode has actually been read, and jadx is what reads it.
- **[REA](https://github.com/morluto/rea)** — reverse-engineering toolkit. Its device documentation is
  where the two workflow recipes in [AGENTS.md](AGENTS.md#device-access) come from: capturing the
  headset without wearing it, and wireless adb. Caveat recorded there: `rea capture-adb-screen` failed
  in this environment, so frames came from plain `adb`.
- **Android platform-tools (`adb`)** — installing on the headset, reading its logs, and every
  screenshot the notes refer to.
- **[Morphe Desktop](https://github.com/MorpheApp/morphe-desktop)** and the **Android SDK
  build-tools** — applying the bundles and dexing the extension.
