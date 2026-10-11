# Version support

The patches are fingerprinted against exact app versions. R8 reassigns every class and method name
on each release of YouTube VR, so a new version means the matchers have to be checked again — see
[FINGERPRINTS.md](FINGERPRINTS.md) for which ones anchor on stable facts and which do not.

## The rule that surprises people

A version that is **not** listed in
`patches/src/main/kotlin/app/ytvr/patches/shared/Constants.kt` is **skipped, not failed**. The
patcher prints a skip, exits 0, and produces an app with **none** of the fixes — it looks like a
successful run. So:

- Always check that the expected patches were *applied* (`Applied: Extension`, …), not just that
  the run did not error.
- `scripts/patch-and-install.ps1` does this for you: it asserts that the expected patches were
  applied (`-RequirePatches`, by default every patch that is on by default) and refuses to install a
  build where they were skipped.

## What the version list means

| declared as | meaning |
|---|---|
| `AppTarget(version = "1.88.10", description = "Verified: …")` | every fingerprint was re-derived against that build; the patches are expected to work |
| `AppTarget(version = "X", isExperimental = true, description = "Untested: …")` | nobody has re-derived the fingerprints for that build. The patches are *attempted*; some are expected to fail. This is the honest "try it, it is not guaranteed" channel, and it shows up in Morphe Manager |
| not listed at all | skipped silently — see above |

Never list an untested version as a normal target: that is how a user ends up with a silently
half-patched app.

## Trying a version that is not listed

Two ways, both of which attempt the patches anyway:

**Morphe Manager** — enable the setting that allows selecting an un-suggested app version, and pick
the APK you have.

**CLI** — `-f/--force` skips the compatibility check, and `--continue-on-error` keeps whatever
applied instead of aborting at the first failure:

```bash
java -jar morphe-desktop-all.jar patch --patches patches-<version>.mpp \
  -f --continue-on-error --result-file out.json -o out.apk youtube-vr.apk
```

Read the result honestly: with `-f`, "it produced an APK" means nothing on its own. A partially
patched build can behave worse than an unpatched one, because our patches are coupled — the
streaming spoof expects the DataSpec normalisation and the ad-slot clear to be there too.

## Reporting a broken version

That is what makes re-fingerprinting cheap. Open an issue with the app version **and version code**,
the bundle version, and the `Applied:` / `Skipping` / `FAILED:` lines (the bug-report template asks
for exactly this). A report naming the failing patch is usually enough to turn the re-anchoring
into a lookup against FINGERPRINTS.md.

## Adding support for a new version (maintainer checklist)

1. Get the APK of that exact version from your own headset (`adb pull`, see
   [GETTING-THE-APK.md](GETTING-THE-APK.md)) or with DownQuest for an older build.
2. Add it to `Constants.kt`. While the fingerprints are unverified, add it as
   `isExperimental = true` with a description saying so.
3. Apply with diagnostics on, so the diagnostics-gated fingerprints are exercised too:

   ```bash
   java -jar morphe-desktop-all.jar patch --patches patches-<version>.mpp \
     -OenableDiagnostics=true --force --continue-on-error \
     --result-file out.json -o out.apk youtube-vr-<version>.apk
   ```

   Every patch that no longer matches reports `FAILED: <patch>`. That is the work list.
4. Re-anchor those fingerprints — the anchors recorded in FINGERPRINTS.md and in
   the patch sources are the starting points.
5. Verify, in this order:
   - the bundle still applies with zero failures;
   - **the patcher modified the same methods as before** — apply the bundle, extract the last
     `classes*.dex` from the patched APK, decompile it and confirm the injected calls land in the
     expected classes/methods (`oqb` ctor, `axe.a()`, `pqy` ctor, `gtu.b(int)`, `fna.b(int)`, and
     with diagnostics on `oth` and `CronetUrlRequestContext`). A re-anchor that silently matches the
     wrong method still produces a valid APK, so this check is the one that catches it;
   - on the device: play a video and confirm `ad slots: cleared N entries` in logcat, zero
     `skip-ad countdown` lines, and `onPlaying`.
6. Flip the target from experimental to a normal (verified) target and cut a release.

## Notes

- `AppTarget.description` is compiled into the bundle (Morphe Manager can read it), but the
  release's generated `patches-list.json` does not carry target descriptions — that writer leaves
  the field empty. Do not rely on it appearing in third-party indexes.
- `./gradlew generatePatchesList` writes the patch list; the version field is stamped from
  `gradle.properties` by a `doLast` in `patches/build.gradle.kts`, and the release pipeline's `jq`
  step writes the same value. Before that stamp existed, running the task locally silently
  downgraded the published index.
- Version codes: this app ships arm64-v8a only, so a target can pin the exact code with
  `versionCodes = mapOf(SupportedAbi.ARM64_V8A to 18810000)`.
