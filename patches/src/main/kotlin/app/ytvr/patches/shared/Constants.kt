package app.ytvr.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

/**
 * The preference key the app reads its version name from (`AppVersionOverride`).
 *
 * It is an anchor, not just a setting: the two patches that target that class find it by this
 * literal instead of by its obfuscated name -- the version spoof matches the accessors in the class
 * that holds it, and the osName spoof uses it to tell that class apart from the other
 * `Z(Context)` feature checks. Both live in this file so they cannot drift apart.
 */
const val VERSION_NAME_OVERRIDE_KEY = "pref_override_build_version_name"

object Constants {
    val COMPATIBILITY_YOUTUBE_VR = Compatibility(
        name = "YouTube VR", // App name as it appears in the Android launcher.
        packageName = "com.google.android.apps.youtube.vr.oculus",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF0000, // Icon color in Morphe Manager. Usually the same color as the icon background.
        targets = listOf(
            // ---------------------------------------------------------------------------------
            // Support policy for app versions
            //
            // The fingerprints are re-derived per version (R8 reassigns every name on each
            // release), so only versions that were actually checked against an APK are listed
            // as normal targets. A version that is not listed is *skipped*, not failed -- the
            // patcher exits 0 and the output has none of the fixes -- which is why
            // scripts/patch-and-install.ps1 asserts that the expected patches were applied.
            //
            // When a new version appears and nobody has re-derived the fingerprints yet, add it
            // as an EXPERIMENTAL target instead of a normal one. That is the honest "you may try
            // it, it is not guaranteed" channel: it shows up in Morphe Manager (and in
            // patches-list.json) rather than hiding behind a CLI flag. Never list an untested
            // version as a normal target.
            //
            //     AppTarget(
            //         version = "1.89.0",
            //         isExperimental = true,
            //         description = "Untested: the fingerprints were not re-derived for this " +
            //             "build, so some patches are expected to fail. Patch with " +
            //             "`--force --continue-on-error` and report which patches failed " +
            //             "(see docs/FINGERPRINTS.md).",
            //     ),
            //
            // To try any unlisted build without touching this file, the CLI accepts
            // `-f/--force` (skip the compatibility check) and `--continue-on-error`, which keeps
            // whatever applied and reports the rest. Users can do the same from Morphe Manager
            // by allowing the selection of an un-suggested app version.
            //
            // If a target should only match a specific ABI's version code, pass
            // `versionCodes = mapOf(SupportedAbi.ARM64_V8A to 18810000)`; this app ships
            // arm64-v8a only.
            // ---------------------------------------------------------------------------------
            AppTarget(
                version = "1.88.10",
                description = "Verified: every fingerprint was re-derived against this build.",
            )
        )
    )
}
