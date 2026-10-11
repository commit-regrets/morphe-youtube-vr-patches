package app.ytvr.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.ytvr.patches.shared.Constants.COMPATIBILITY_YOUTUBE_VR
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * The static field [this] method loads and hands straight back to its caller: the app's own empty
 * result, which the injected early return has to reproduce.
 *
 * This exists so the injected `return` does not have to *name* an obfuscated class. The ad-break
 * renderer returns its empty-list singleton from two places; hardcoding `Lvrm;->a:Lvmo;` kept
 * working as a fingerprint (that is structural now) while the injected code still referenced a name
 * R8 reassigns -- which would have thrown `NoSuchFieldError` on the device the first time a release
 * renamed it, with nothing failing at patch time. Reading the reference out of the method makes the
 * injected instruction exactly what the app itself returns, and fails here instead of there.
 *
 * The shape is "`sget-object vX, F` immediately followed by `return-object vX`", and it must select
 * exactly one field; anything else aborts the patch.
 */
private fun Method.loadedAndReturnedStaticField(): FieldReference {
    val instructions = implementation?.instructions?.toList()
        ?: error("$definingClass->$name has no body to read a singleton from")
    val fields = instructions.mapIndexedNotNull { index, instruction ->
        if (instruction.opcode != Opcode.SGET_OBJECT) return@mapIndexedNotNull null
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
            ?: return@mapIndexedNotNull null
        val register = (instruction as? OneRegisterInstruction)?.registerA
            ?: return@mapIndexedNotNull null
        val returned = instructions.drop(index + 1).take(2).any { following ->
            following.opcode == Opcode.RETURN_OBJECT &&
                (following as? OneRegisterInstruction)?.registerA == register
        }
        field.takeIf { returned }
    }.distinctBy { it.toString() }
    check(fields.size == 1) {
        "expected exactly one static singleton that $definingClass->$name loads and returns; " +
            "found ${fields.size}: ${fields.map { it.toString() }}"
    }
    return fields.single()
}

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Hides video ads.",
    default = true
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_VR)

    execute {
        // Prevent preroll, midroll, postroll and pause ads from being scheduled
        // by always returning an empty ad break list. The returned object is the
        // immutable empty-list singleton the renderer itself returns when a
        // player response has no ad placements -- read out of the method, so the
        // injection names no obfuscated class.
        //
        // Each hook also reports itself (PotUrls.onAdPath), so a capture shows which
        // ad paths the app actually drives while an ad is on screen. If none of them
        // fire, the ad cannot be coming from the client-side ad machinery at all.
        val renderer = AdBreakRendererFingerprint.method
        val emptyList = renderer.loadedAndReturnedStaticField()
        renderer.addInstructions(
            0,
            """
                const-string v0, "adBreakRenderer"
                invoke-static {v0}, Lapp/ytvr/extension/PotUrls;->onAdPath(Ljava/lang/String;)V
                sget-object v0, $emptyList
                return-object v0
            """
        )

        // Prevent ad break responses from being requested over the network.
        AdBreakRequestFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "adBreakRequest"
                invoke-static {v0}, Lapp/ytvr/extension/PotUrls;->onAdPath(Ljava/lang/String;)V
                const/4 v0, 0x0
                return-object v0
            """
        )

        // Prevent the pre-rendered PlayerBytes ad slot embedded in the player
        // response (the pre-roll shown on the first playback) from being
        // treated as present.
        PlayerBytesSlotFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "playerBytesSlot"
                invoke-static {v0}, Lapp/ytvr/extension/PotUrls;->onAdPath(Ljava/lang/String;)V
                const/4 v0, 0x0
                return v0
            """
        )

        // Prevent the bootstrap of the pre-rendered PlayerBytes ad layout from
        // ever running, so no ad layout is built or cached for the pre-roll.
        PlayerBytesAdLayoutFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "playerBytesLayout"
                invoke-static {v0}, Lapp/ytvr/extension/PotUrls;->onAdPath(Ljava/lang/String;)V
                return-void
            """
        )
    }
}
