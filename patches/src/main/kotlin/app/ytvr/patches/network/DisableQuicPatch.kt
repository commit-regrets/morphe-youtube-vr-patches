package app.ytvr.patches.network

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.ytvr.patches.shared.Constants.COMPATIBILITY_YOUTUBE_VR
import app.ytvr.patches.shared.applicationOnCreateFingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/**
 * Matches the builder of the main Cronet engine used for all YouTube requests.
 *
 * The class and method names are obfuscated and expected to change between
 * app versions, so only the signature and distinguishing hints are matched.
 *
 * This anchor is *already* name-free, and it was measured rather than assumed
 * (stock 1.88.10 dex, 5 dex files / 27,722 classes):
 *
 *  - It matches exactly **one** method: `Lnpg;->a(Object, Object)Object`, the lambda
 *    `ltq`'s `CronetEngineModule` hands to the engine factory, which is also the
 *    method holding the `youtubei.googleapis.com` QUIC-hint list.
 *  - The conjunction the notes proposed instead (`enableQuic` + `enableHttp2` +
 *    `setLibraryLoader` in one method) is **not** unique: it matches two methods,
 *    because `phn`'s engine builder (`Lpju;->a`) chains the same three calls. That
 *    second engine is deliberately not patched, so the conjunction would be a
 *    regression, not a hardening. (It also has to be spelled with
 *    `ExperimentalCronetEngine$Builder` as the owner of `setLibraryLoader`, not
 *    `CronetEngine$Builder`: the chain's static type is the subclass, so the
 *    "obvious" descriptor matches zero methods.)
 *  - `phn`'s site cannot be reached by accident from here anyway: it passes `false`
 *    to `enableQuic`, and the `literal(1)` filter requires a `const 1` immediately
 *    before the call, which is exactly the `enableQuic(true)` this patch flips.
 *
 * The three filters are not decoration -- the patch replaces the matched constant with a call that
 * asks the extension, and re-inserts the constant after the matched `move-result-object`, so they
 * must stay in this order: `literal(1)` first, then the call, then its result.
 */
private val cronetEngineBuilderFingerprint = Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
    strings = listOf("youtubei.googleapis.com"),
    filters = listOf(
        // The constant passed to enableQuic.
        literal(1),
        methodCall(
            smali = "Lorg/chromium/net/ExperimentalCronetEngine\$Builder;->enableQuic(Z)Lorg/chromium/net/ExperimentalCronetEngine\$Builder;",
            location = MatchAfterImmediately(),
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
    ),
)

/** The extension class that owns the switch, and the seed this patch writes into it. */
private const val EXTENSION_CLASS = "Lapp/ytvr/extension/QuicTransport;"

/**
 * Adds the in-headset **QUIC (HTTP/3)** switch.
 *
 * The patch is default-on because the switch has to be installed to exist, and its option keeps the
 * seed at "QUIC allowed" -- the configuration every device measurement in this repository was taken
 * with. It is named for what the app does with QUIC rather than for the direction of the switch:
 * upstream's equivalent is *Disable QUIC protocol* with a negative-sense option, and this bundle
 * reads positively everywhere (patch, option, hub row) so that on means on.
 *
 * **A change applies after the app restarts**: Cronet builds its engine once per process, and the
 * injected call is read there.
 */
@Suppress("unused")
val quicProtocolPatch = bytecodePatch(
    name = "QUIC (HTTP/3)",
    description = "Adds the in-headset switch for QUIC, the transport under HTTP/3: TLS over UDP, " +
        "used for this app's API and media requests. Leave it on unless playback stalls on a " +
        "network that blocks or throttles UDP 443 (corporate Wi-Fi, many VPNs), where turning it " +
        "off forces TCP/TLS. A change applies after the app restarts.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_VR)

    val enableQuic by booleanOption(
        key = "enableQuic",
        default = true,
        title = "QUIC (HTTP/3)",
        description = "Seeds the in-headset switch on first launch; the Patch settings row " +
            "governs from the first change there. Off forces TCP/TLS for every request.",
    )

    execute {
        // The seed rides Application.onCreate like the other options, and the extension writes it
        // through to the setting on its first read, so the hub row shows this build's choice.
        val seed = if (enableQuic == false) "0x0" else "0x1"
        applicationOnCreateFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, $seed
                sput-boolean v0, $EXTENSION_CLASS->buildDefault:Z
            """,
        )

        cronetEngineBuilderFingerprint.let { fingerprint ->
            val literalMatch = fingerprint.instructionMatches[0]
            val moveResultMatch = fingerprint.instructionMatches[2]
            val register = fingerprint.method
                .getInstruction<OneRegisterInstruction>(literalMatch.index).registerA

            // The matched constant is the flag handed to enableQuic, and the register holding it
            // also feeds the enableHttp2 call that follows. Ask the extension instead of passing a
            // constant, so the hub row decides -- and restore `true` afterwards, because that is
            // what the later call expects.
            fingerprint.method.replaceInstruction(
                literalMatch.index,
                "invoke-static {}, $EXTENSION_CLASS->quicEnabled()Z",
            )
            fingerprint.method.addInstructions(literalMatch.index + 1, "move-result v$register")
            // The two edits above turned one instruction into two, so the fingerprint's own
            // `moveResultMatch.index` (measured before either edit) is one short: hence +2.
            fingerprint.method.addInstructions(moveResultMatch.index + 2, "const/4 v$register, 0x1")
        }
    }
}
