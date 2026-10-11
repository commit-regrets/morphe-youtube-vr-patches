package app.ytvr.patches.playability

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.ytvr.patches.shared.Constants.COMPATIBILITY_YOUTUBE_VR

@Suppress("unused")
val removeViewerDiscretionDialogPatch = bytecodePatch(
    name = "Remove viewer discretion dialog",
    description = "Removes the dialog that appears when opening a video that has been age-restricted " +
            "by accepting it automatically. This does not bypass the age restriction.",
    default = true
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_VR)

    execute {
        // The confirm dialog is gated on the playability policy's confirmation
        // states: the dialog is only shown for responses that still report an
        // unconfirmed adult or controversial content status, and player
        // requests carry the same states as "allowAdultContent" and
        // "allowControversialContent" flags. Reporting both confirmations as
        // given accepts the dialog automatically: the server returns playable
        // streams for age-restricted videos and the confirm-status responses
        // that would show the dialog no longer occur.

        // Always report the adult content confirmation as given.
        AdultConfirmationFingerprint.method.addInstructions(
            0,
            """
                sget-object v0, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                return-object v0
            """
        )

        // Always report the controversial content confirmation as given.
        ControversialConfirmationFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """
        )
    }
}
