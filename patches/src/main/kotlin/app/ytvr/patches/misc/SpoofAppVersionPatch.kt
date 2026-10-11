package app.ytvr.patches.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import app.ytvr.patches.shared.Constants.COMPATIBILITY_YOUTUBE_VR
import app.ytvr.patches.shared.VERSION_NAME_OVERRIDE_KEY
import app.ytvr.patches.shared.holdsStringLiteral

/**
 * Matches the method that returns the app version name.
 *
 * The class and method names are obfuscated and expected to change between
 * app versions, so only the signature and the preference override key are matched.
 */
private val appVersionFingerprint = Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf(VERSION_NAME_OVERRIDE_KEY),
)

/**
 * Matches the accessor returning the app version code used in requests.
 * The server's client gating (streaming behavior and ad insertion) reads
 * the version code, which is independent of the spoofed version name.
 *
 * It lives in the same class as the name accessor, which is what the anchor
 * uses: an obfuscated-name-free `I(Context)` in the class holding the
 * version-name preference key. Measured over the stock 1.88.10 dex: that class
 * declares exactly one such method, and no other class of the app holds the
 * key. The class-level fact is what replaces the old `Locn;` / `a`.
 */
private val appVersionCodeFingerprint = Fingerprint(
    returnType = "I",
    parameters = listOf("Landroid/content/Context;"),
    custom = { _, classDef -> classDef.holdsStringLiteral(VERSION_NAME_OVERRIDE_KEY) },
)

@Suppress("unused")
val spoofAppVersionPatch = bytecodePatch(
    name = "Spoof app version",
    description = "Adds an option to trick the app into thinking you are running a different version.",
    default = true
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_VR)

    val spoofedVersionName by stringOption(
        key = "spoofedVersionName",
        default = "1.88.10",
        title = "Spoofed version name",
    )

    execute {
        // Escape the configured version name for use in a smali string literal.
        val versionName = (spoofedVersionName ?: "1.88.10")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        // Always return the configured version name,
        // regardless of the actual version of the installed app.
        appVersionFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "$versionName"
                return-object v0
            """
        )

        // Also override the version code with the value the configured version
        // name implies (major*10^7 + minor*10^5 + patch*10^3, matching the
        // scheme of the app's own version codes), so version-dependent server
        // behavior sees a consistent version instead of the real build's code.
        val codeParts = (spoofedVersionName ?: "1.88.10").split(".")
        val major = codeParts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = codeParts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = codeParts.getOrNull(2)?.toIntOrNull() ?: 0
        val versionCode = major * 10_000_000 + minor * 100_000 + patch * 1_000

        appVersionCodeFingerprint.method.addInstructions(
            0,
            """
                const v0, $versionCode
                return v0
            """
        )
    }
}
