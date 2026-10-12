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
 * The three filters are not decoration -- the patch rewrites the matched constant
 * and re-inserts it after the matched `move-result-object`, so they must stay in
 * this order: `literal(1)` first, then the call, then its result.
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

@Suppress("unused")
val disableQuicProtocolPatch = bytecodePatch(
    name = "Disable QUIC protocol",
    description = "Adds an option (off by default) to disable QUIC (Quick UDP Internet " +
        "Connections) network protocol; selecting the patch alone changes nothing until " +
        "the option is enabled.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_VR)

    val disableQuic by booleanOption(
        key = "disableQuic",
        default = false,
        title = "Disable QUIC protocol",
    )

    execute {
        if (disableQuic == true) {
            cronetEngineBuilderFingerprint.let { fingerprint ->
                val literalMatch = fingerprint.instructionMatches[0]
                val moveResultMatch = fingerprint.instructionMatches[2]
                val register = fingerprint.method
                    .getInstruction<OneRegisterInstruction>(literalMatch.index).registerA

                // The register holding the constant also feeds the enableHttp2 call that
                // follows, so replace the constant passed to enableQuic with false
                // and restore it to true once the call has returned.
                fingerprint.method.replaceInstruction(literalMatch.index, "const/4 v$register, 0x0")
                fingerprint.method.addInstructions(moveResultMatch.index + 1, "const/4 v$register, 0x1")
            }
        }
    }
}
