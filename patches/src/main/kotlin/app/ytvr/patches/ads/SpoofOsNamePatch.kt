package app.ytvr.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.ytvr.patches.shared.Constants.COMPATIBILITY_YOUTUBE_VR
import app.ytvr.patches.shared.VERSION_NAME_OVERRIDE_KEY
import app.ytvr.patches.shared.holdsStringLiteral

/** The system feature whose presence is what the app calls "this is Android Automotive". */
private const val AUTOMOTIVE_FEATURE = "android.hardware.type.automotive"

/**
 * Matches the "is this an Android Automotive device" feature check.
 *
 * The app's innertube client info derives its `osName` from three feature
 * checks: "Android Wear", this one ("Android Automotive"), then "ChromeOS",
 * falling back to "Android". The check itself is a plain
 * `hasSystemFeature("android.hardware.type.automotive")` lookup.
 *
 * The class and method names are obfuscated, so the anchor is the pair of
 * literals instead: `Z(Context)` whose own body holds the automotive feature
 * string, in the class that also holds the version-name preference key.
 *
 * Measured over the stock 1.88.10 dex: `Z(Context)` + the automotive literal
 * matches two methods (`avv.Y` and the intended `ocn.c`); adding the preference
 * key as a class fact leaves exactly one. The literal cannot anchor on its own
 * -- it occurs in nine classes of the app and its bundled libraries.
 */
private val automotiveDeviceFingerprint = Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    custom = { method, classDef ->
        classDef.holdsStringLiteral(VERSION_NAME_OVERRIDE_KEY) &&
            method.holdsStringLiteral(AUTOMOTIVE_FEATURE)
    },
)

/**
 * Reports the client as an Android Automotive device.
 *
 * This mirrors the mechanism the phone patch set uses to block video ads: its
 * `Hide ads` patch overrides the `osName` of the player, get_watch and reel
 * requests with "Android Automotive" (`SpoofOSNamePatch.getOSName(true)`),
 * while browse/search/guide keep the real osName.
 *
 * In this app the osName is not built per request; it is computed once in the
 * client info constructor from `ocn`'s feature checks and then reused by every
 * request that carries the client info (including the SABR media fetch), so
 * forcing the feature check covers all of them.
 *
 * Note: unlike the phone patch this applies to every request, and it also feeds
 * the client form factor computed from the same checks. Narrow it to the player
 * path if the broader spoof causes trouble.
 *
 * RESULT (verified on device 2026-10-09): does NOT remove the first-video
 * pre-roll. The spoof does propagate — the QOE ping switches from
 * `cff=LARGE_FORM_FACTOR` to `cff=AUTOMOTIVE_FORM_FACTOR`, so Google received the
 * Automotive device class — and the ad played anyway. This is the phone patch
 * set's own ad-blocking mechanism, so its ineffectiveness here (where the ad is
 * stitched into the SABR stream) is a decisive negative: do not re-try it. See
 * See the patch sources for what was measured.
 */
@Suppress("unused")
val spoofOsNamePatch = bytecodePatch(
    name = "Spoof osName (Android Automotive)",
    description = "Reports the app as an Android Automotive device, the way the " +
        "phone patch set blocks video ads.",
    default = false
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_VR)

    execute {
        automotiveDeviceFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """
        )
    }
}
