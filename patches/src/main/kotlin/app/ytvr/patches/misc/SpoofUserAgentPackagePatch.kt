package app.ytvr.patches.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import app.ytvr.patches.shared.Constants.COMPATIBILITY_YOUTUBE_VR
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/**
 * Matches the default User-Agent builder used by the bundled Cronet engine
 * (called from `org/chromium/net/impl/CronetLibraryLoader` and the engine
 * builders). It prefixes the User-Agent with the installed package name and
 * version code, then appends the platform and Cronet details.
 *
 * A renamed (cloned) install therefore advertises a non-stock package name in
 * the User-Agent of every request, which identifies the app as modified on the
 * server side. The class and method names are obfuscated and expected to change
 * between app versions, so the anchor is the platform string it appends plus the
 * package-name lookup it prefixes it with.
 *
 * Measured over the stock 1.88.10 dex: the platform literal occurs in exactly one
 * method of the app whether or not the `getPackageName()` call is required, so
 * the lookup filter is kept only because the patch rewrites *that* call site --
 * it is the first of the two `getPackageName()` calls in the method, and it is
 * the one whose result is appended to the builder.
 */
private val defaultUserAgentFingerprint = Fingerprint(
    returnType = "Ljava/lang/String;",
    strings = listOf(" (Linux; U; Android "),
    filters = listOf(
        // Reads the installed package name for the User-Agent prefix.
        methodCall(smali = "Landroid/content/Context;->getPackageName()Ljava/lang/String;"),
    ),
)

/**
 * Reports the stock package name in the Cronet User-Agent instead of the
 * installed app's package name, so a cloned or renamed install does not
 * advertise itself to the server in request headers.
 *
 * The version code that follows the package name is left alone: cloning does
 * not change it, so it already matches the unmodified app.
 */
@Suppress("unused")
val spoofUserAgentPackagePatch = bytecodePatch(
    name = "Spoof package name in User-Agent",
    description = "Adds an option to report a different app package name in the Cronet User-Agent.",
    default = true
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_VR)

    val spoofedPackageName by stringOption(
        key = "spoofedPackageName",
        default = "com.google.android.apps.youtube.vr.oculus",
        title = "Spoofed package name",
    )

    execute {
        val packageName = (spoofedPackageName ?: "com.google.android.apps.youtube.vr.oculus")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        val matchIndex = defaultUserAgentFingerprint.instructionMatches.first().index
        val register = defaultUserAgentFingerprint.method
            .getInstruction<OneRegisterInstruction>(matchIndex + 1).registerA

        // Replace the package name lookup with the configured name; the
        // move-result that consumed it becomes a no-op so the register keeps
        // the constant for the StringBuilder append that follows.
        defaultUserAgentFingerprint.method.replaceInstruction(
            matchIndex,
            "const-string v$register, \"$packageName\""
        )
        defaultUserAgentFingerprint.method.replaceInstruction(matchIndex + 1, "nop")
    }
}
