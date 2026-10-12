package app.ytvr.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.ytvr.patches.shared.Constants.COMPATIBILITY_YOUTUBE_VR
import app.ytvr.patches.shared.bodyReferencesType
import app.ytvr.patches.shared.holdsStringLiteral

/** The generated proto the app hands to the native SABR player. protoc names are not obfuscated. */
private const val SSDAI_INFO_PROTO =
    "Lcom/google/android/apps/youtube/proto/streaming/ServerStitchedDaiInfoOuterClass\$ServerStitchedDaiInfo;"

/** The `daistate` URL parameter the provider sets; the class that holds it is the DAI provider. */
private const val DAI_STATE_PARAMETER = "daistate"

/**
 * Matches the provider of the server-stitched DAI (SSAI) ad information that
 * the app hands to the native SABR/onesie player.
 *
 * `b(long)` builds the `ServerStitchedDaiInfo` proto (the ad configs the
 * streaming backend stitches into the content stream), `a(onu, long, long)`
 * builds the per-break playback URI carrying `daistate`/`acpns`, and
 * `c(long)` reports whether a position is inside an ad break.
 *
 * Returning "nothing" here is exactly the app's own "no DAI configured" state:
 * the interface ships a no-op implementation (`pzk`) that returns
 * null/null/false for these three methods, and the factory files select the
 * real one. Forcing the no-op behaviour therefore removes the ad configuration
 * without disabling outright DAI support elsewhere.
 *
 * The anchor is the generated proto type in the signature plus two facts that
 * separate the real provider from the interface (`pzl`, which declares the same
 * method) and from the no-op (`pzk`, which implements it): the class holds the
 * `daistate` protocol literal, and this method's body actually *uses* the proto
 * type rather than only declaring it. Measured over the stock 1.88.10 dex: the
 * signature alone matches three methods -- the interface declaration and the
 * no-op implementation among them -- the class fact alone matches one, and the
 * body reference alone matches one.
 */
private val stitchedDaiInfoFingerprint = Fingerprint(
    parameters = listOf("J"),
    returnType = SSDAI_INFO_PROTO,
    custom = { method, classDef ->
        classDef.holdsStringLiteral(DAI_STATE_PARAMETER) &&
            method.bodyReferencesType(SSDAI_INFO_PROTO)
    },
)

/**
 * The per-break playback URI builder and the "is this position inside an ad
 * break" test of the same provider. Both stay anchored on the class name, on
 * purpose.
 *
 * Their own shapes are not discriminating: measured over the stock 1.88.10 dex,
 * a `Uri`-returning `(reference, J, J)` method matches three (this one, the
 * no-op, and the interface), and a `Z(J)` method matches 134 across the app and
 * its libraries. The only fact that would pin them is the class's own
 * `daistate`/`acpns` protocol literals -- the same fact the info hook above
 * uses -- which would hang all three hooks off one literal for a patch that is
 * off by default. Left as they are, a future rename fails this patch loudly
 * (unresolved fingerprint) instead of hooking a wrong method silently.
 */
private val stitchedDaiUriFingerprint = Fingerprint(
    definingClass = "Lryi;",
    name = "a",
    parameters = listOf("Lonu;", "J", "J"),
    returnType = "Landroid/net/Uri;",
)

private val stitchedDaiBreakFingerprint = Fingerprint(
    definingClass = "Lryi;",
    name = "c",
    parameters = listOf("J"),
    returnType = "Z",
)

/**
 * Matches the callback the native SABR player uses to deliver its ad cue point
 * list to the app. Dropping the list means the app never learns where the
 * server placed an ad break, so it never fulfils an ad break or reports one.
 *
 * The class name is fixed (it is not obfuscated, it is part of the media
 * interfaces package), only the method shape is matched.
 */
private val cuePointCallbackFingerprint = Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/media/interfaces/CuePointDataProviderWrapper\$NativeCallback;",
    name = "onCuepointList",
    parameters = listOf("[B"),
    returnType = "V",
)

/**
 * Removes the client side plumbing of server-stitched (SSAI/DAI) video ads:
 * the ad configuration and the cue point list the app exchanges with the
 * native player.
 *
 * IMPORTANT: this does NOT remove the ad itself. On-device testing (2026-10-09)
 * showed the pre-roll still plays with these hooks active — the ad is stitched
 * into the SABR stream by the server. What this removes is the app's handling
 * of the ad break (ad break reporting, `daistate`/`acpns`, cue point
 * fulfilment). Keep it only for that effect; do not expect it to hide ads.
 */
@Suppress("unused")
val hideStitchedAdsPatch = bytecodePatch(
    name = "Hide stitched ads (SSAI)",
    description = "Removes the client side ad configuration and cue points for " +
        "server-stitched (SSAI) streams. It does not remove the ad itself; keeping " +
        "ads out is the Extension patch's streaming spoof. Off by default.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_VR)

    execute {
        // Never report an ad break, never hand out an ad config and never
        // build a stitched ad URI: the app's own "no DAI configured" state.
        // Each hook reports itself first (PotUrls.onAdPath) so a capture shows
        // whether the SSAI/DAI path is exercised at all.
        stitchedDaiInfoFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "ssdaiInfo"
                invoke-static {v0}, Lapp/ytvr/extension/PotUrls;->onAdPath(Ljava/lang/String;)V
                const/4 v0, 0x0
                return-object v0
            """,
        )

        stitchedDaiUriFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "ssdaiUri"
                invoke-static {v0}, Lapp/ytvr/extension/PotUrls;->onAdPath(Ljava/lang/String;)V
                const/4 v0, 0x0
                return-object v0
            """,
        )

        stitchedDaiBreakFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "ssdaiBreak"
                invoke-static {v0}, Lapp/ytvr/extension/PotUrls;->onAdPath(Ljava/lang/String;)V
                const/4 v0, 0x0
                return v0
            """,
        )

        // Drop the ad cue point list delivered by the native player.
        cuePointCallbackFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "cuepointList"
                invoke-static {v0}, Lapp/ytvr/extension/PotUrls;->onAdPath(Ljava/lang/String;)V
                return-void
            """,
        )
    }
}
