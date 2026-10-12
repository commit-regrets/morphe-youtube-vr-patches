package app.ytvr.patches.shared

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.ytvr.patches.shared.Constants.COMPATIBILITY_YOUTUBE_VR
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.util.function.Supplier

/**
 * Matches the app's Application.onCreate, the earliest reliable entry point.
 *
 * Shared with patches that also inject an app-start call (SponsorBlock bakes its
 * option values into `const-string`s here), which is why this is internal.
 */
internal val applicationOnCreateFingerprint = Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/vr/application/YouTubeVrTikTokRoot_Application;",
    name = "onCreate",
    returnType = "V",
    parameters = emptyList(),
)

/**
 * Public, generated innertube proto for the streaming data. protoc generates these names and the
 * app does not obfuscate them (verified in the dex), which makes the type a durable anchor.
 */
private const val STREAMING_DATA_PROTO =
    "Lcom/google/protos/youtube/api/innertube/StreamingDataOuterClass\$StreamingData;"

/** True when [this] stores a reference of [type] into a field of its own [classDef]. */
private fun Method.writesOwnFieldOfType(classDef: ClassDef, type: String): Boolean = implementation?.instructions?.any { instruction ->
    instruction.opcode == Opcode.IPUT_OBJECT &&
        (instruction as? ReferenceInstruction)?.reference.let { reference ->
            reference is FieldReference &&
                reference.definingClass == classDef.type &&
                reference.type == type
        }
} ?: false

/**
 * Matches the VideoStreamingData builder that takes the PlayerResponse proto: its one-parameter
 * constructor stores that response's streaming data into a field of its own class, which is what
 * the player, the SABR decision and the media fetch controller all read.
 *
 * Measured over the 1.88.10 dex (146,836 methods): exactly one constructor in the app stores the
 * generated `StreamingData` proto into a field of its own class, and it is this one. The variants
 * that are *not* unique are recorded so nobody re-tries them: `getDefaultInstance()` on that proto
 * matches 7 methods, `DEFAULT_VALUE_FOR_STRING` appears in 1479 files.
 *
 * The `parameterTypes.size == 1` check is load-bearing. With it removed the predicate also matches
 * the sibling two-parameter constructor, and the patcher then attaches the hook to that one **while
 * still reporting `Applied: Extension`, exit 0, no failures** -- measured. A green run is not proof
 * that the intended method was patched, which is why docs/FINGERPRINTS.md requires checking the
 * patched dex.
 *
 * Internal so SponsorBlock can hook the same constructor for its video-id capture without
 * re-deriving the anchor (and without adding a class to the patched set).
 */
internal val videoStreamingDataCtorFingerprint = Fingerprint(
    name = "<init>",
    returnType = "V",
    custom = { method, classDef ->
        method.parameterTypes.size == 1 &&
            method.writesOwnFieldOfType(classDef, STREAMING_DATA_PROTO)
    },
)

/**
 * Matches the app's own SABR/onesie *decision* -- `PlatypusEnablingManager.c(opw, oqd)`, the
 * private method that returns the `DecisionWithReason` both public entry points (`a`, `b`) are
 * derived from.
 *
 * Anchored without any obfuscated name: the class is a named library class, the return type is
 * its own nested enum (a name R8 keeps because the class name is kept), and it is the only
 * method in the class with two parameters returning that enum.
 *
 * Why the patch touches this and not `a`/`b`: the decision already contains the branch this
 * project needs -- no SABR URI in the streaming data means `DISABLED_BY_SABR_STREAMING_URI` --
 * but two earlier branches return `ENABLED` from the player config first, without ever looking
 * at the data. Neutralising exactly those two (`sabrNotForced`) makes the decision follow the
 * data, which is what keeps a *failed* spoof fetch from taking playback down with it. The
 * previous revision forced `a`/`b` to return false instead, i.e. it turned the onesie player off
 * unconditionally, and that is fatal for the app's own SABR-era stream.
 */
private val platypusDecisionFingerprint = Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/media/player/exo2/platypus/PlatypusEnablingManager;",
    returnType = "Lcom/google/android/libraries/youtube/media/player/exo2/platypus/PlatypusEnablingManager\$DecisionWithReason;",
    custom = { method, _ -> method.parameterTypes.size == 2 },
)

/**
 * The register holding parameter [index] of an instance method. Parameters occupy the *last*
 * registers of a dex frame, so with `registerCount` registers and `parameterTypes.size`
 * parameters, parameter 0 sits at `registerCount - parameterTypes.size`.
 */
private fun Method.parameterRegister(index: Int): Int = implementation!!.registerCount - parameterTypes.size + index

/**
 * A no-argument boolean call made directly on one of the method's own parameters.
 *
 * [index] is the instruction index, [name] the obfuscated method name (for diagnostics only --
 * nothing keys off it) and [parameter] the parameter it was made on.
 */
private data class ParameterBooleanCall(val index: Int, val name: String, val parameter: Int)

/**
 * The `invoke-virtual {<parameter i>}, <that parameter's own type>;->x()Z` calls in this method:
 * a no-argument boolean call made directly on a parameter.
 *
 * Used to find the "return ENABLED without ever consulting the stream" branch in the SABR
 * decision without naming the obfuscated method (`ap` in 1.88.10). Both the receiver register and
 * the referenced defining class have to match the parameter, which is what makes it specific: the
 * same method also calls `Optional.isEmpty()` and `Uri.getAuthority()` on locals, and `oqd.n()`
 * is not a boolean.
 *
 * Measured on 1.88.10, `c(opw, oqd)` yields exactly three, in source order:
 *
 *   1. `opw.ap()`  -- parameter 0 -- the player config's forced-SABR flag (line 37)
 *   2. `oqd.C()`   -- parameter 1 -- a premium-stream test, `oqa(0)`: every format has to be
 *                                    `X()` and carry a `/pudl` URL (line 40)
 *   3. `opw.ak()`  -- parameter 0 -- the *disable* branch (line 43)
 *
 * Only the first is neutralised. `C()` cannot fire for an adaptive stream (its formats are
 * `/videoplayback`, not `/pudl`), and it is the app's own gate for premium content, so it stays;
 * removing branch 2 alone is also what keeps the injected case correct -- the SABR-URI check
 * below both of them still runs and still says `DISABLED_BY_SABR_STREAMING_URI` when the injected
 * data has no onesie URL. Anything but a single-register `invoke-virtual` is rejected rather than
 * guessed at, so a future version fails loudly instead of rewriting the wrong call.
 */
private fun Method.booleanCallsOnParameters(): List<ParameterBooleanCall> {
    val instructions = implementation?.instructions?.toList() ?: return emptyList()
    val found = mutableListOf<ParameterBooleanCall>()
    for (index in instructions.indices) {
        val invoke = instructions[index] as? FiveRegisterInstruction ?: continue
        if (invoke.opcode != Opcode.INVOKE_VIRTUAL || invoke.registerCount != 1) continue
        val reference = (invoke as? ReferenceInstruction)?.reference as? MethodReference ?: continue
        if (reference.parameterTypes.isNotEmpty() || reference.returnType != "Z") continue
        val parameter = parameterTypes.indices.firstOrNull { candidate ->
            parameterRegister(candidate) == invoke.registerC &&
                reference.definingClass == parameterTypes[candidate].toString()
        } ?: continue
        found += ParameterBooleanCall(index, reference.name, parameter)
    }
    return found
}

/**
 * The immutable Media3 DataSpec constructor's prototype. Framework and primitive types only, so
 * R8 cannot rename anything here. Media3 has grown this signature before; a change means updating
 * this list, and the failure is loud (`Failed to match the fingerprint`).
 */
private val DATA_SPEC_CONSTRUCTOR_PARAMETERS = listOf(
    "Landroid/net/Uri;", "J", "Ljava/lang/String;", "I", "[B",
    "Ljava/util/Map;", "J", "J", "Ljava/lang/String;", "I", "Ljava/lang/Object;",
)

/**
 * Matches the DataSpec **build**: the no-argument method that reads the mutable request builder's
 * own fields and hands them to the immutable DataSpec constructor. It is the last point at which a
 * media request's method and body are still mutable -- the built DataSpec has only final fields --
 * and the POST/AbrState conversion (`axu.b`) builds through it, which is why normalising the method
 * and body here is what fixes the HTTP 400.
 *
 * The previous version matched `Laxe;` / `a` / `Laxf;` by name, all three of which R8 reassigns on
 * every release. This matches on:
 *   - no parameters;
 *   - the class carrying a `Landroid/net/Uri;`, a `[B` and an `I` field (the URL, body and HTTP
 *     method this patch rewrites);
 *   - the method reading those fields of its *own* class;
 *   - the method invoking a constructor whose prototype is exactly the DataSpec constructor above.
 *
 * Eleven arguments do not fit `invoke-direct`'s 4-bit register list, so the dex calls it as
 * `invoke-direct/range`; both opcodes are accepted. Measured on 1.88.10 (25,093 classes): four call
 * sites construct that eleven-argument DataSpec, exactly one of them is in a no-parameter method,
 * and in exactly that one is every argument a read of the defining class's own field. A looser
 * anchor ("no-arg method reading a Uri, a byte[] and an int field") matches five methods in three
 * classes, which is why the constructor prototype carries the discrimination.
 *
 * Constructors are excluded explicitly: `parameters = emptyList()` does *not* mean "not a
 * constructor", and `<init>` is dex-reserved, so testing the name is rename-proof.
 */
private val dataSpecBuildFingerprint = Fingerprint(
    parameters = emptyList(),
    custom = dataSpecBuild@{ method, classDef ->
        if (method.name == "<init>") {
            return@dataSpecBuild false
        }
        val classFieldTypes = classDef.fields.map { it.type }.toSet()
        if (!classFieldTypes.containsAll(listOf("Landroid/net/Uri;", "[B", "I"))) {
            return@dataSpecBuild false
        }

        var readsUrl = false
        var readsBody = false
        var readsMethod = false
        var buildsDataSpec = false

        for (instruction in method.implementation?.instructions ?: return@dataSpecBuild false) {
            val reference = (instruction as? ReferenceInstruction)?.reference
            if (reference is FieldReference) {
                // only fields of the class being matched: the builder reading itself
                if (reference.definingClass != method.definingClass) continue
                when (reference.type) {
                    "Landroid/net/Uri;" -> readsUrl = true
                    "[B" -> readsBody = true
                    "I" -> readsMethod = true
                }
            } else if (reference is MethodReference &&
                (
                    instruction.opcode == Opcode.INVOKE_DIRECT ||
                        instruction.opcode == Opcode.INVOKE_DIRECT_RANGE
                    )
            ) {
                if (reference.name == "<init>" &&
                    reference.parameterTypes.map { it.toString() } == DATA_SPEC_CONSTRUCTOR_PARAMETERS
                ) {
                    buildsDataSpec = true
                }
            }
        }

        readsUrl && readsBody && readsMethod && buildsDataSpec
    },
)

/** The parameter the UMP rewrite appends to `/videoplayback`: `…&ump=1&range=<off>-<end>`. */
private const val UMP_LITERAL = "ump"

/** The `this.<final boolean> = <arg> instanceof <type>` shape, which is the UMP rewrite flag. */
private fun Method.hasInstanceOfFeedingFinalBooleanField(classDef: ClassDef): Boolean = instanceOfFedBooleanWriteIndices(classDef).isNotEmpty()

/**
 * Indices of the writes that store an `instance-of` result into a `Z` field of this method's own
 * class. With [classDef] the field must also be final, which is the shape the media-data-source
 * fingerprint anchors on; the patch then reuses the very site the fingerprint matched on instead of
 * naming the obfuscated `Lpqy;->c:Z`, so the fingerprint's promise and the rewrite cannot drift
 * apart.
 */
private fun Method.instanceOfFedBooleanWriteIndices(classDef: ClassDef? = null): List<Int> {
    val instructions = implementation?.instructions?.toList() ?: return emptyList()
    val writes = mutableListOf<Int>()
    for (index in instructions.indices) {
        val instruction = instructions[index]
        if (instruction.opcode != Opcode.INSTANCE_OF) continue
        val destination = (instruction as? OneRegisterInstruction)?.registerA ?: continue
        for (write in (index + 1)..minOf(index + 8, instructions.lastIndex)) {
            if (instructions[write].opcode != Opcode.IPUT_BOOLEAN) continue
            if ((instructions[write] as? TwoRegisterInstruction)?.registerA != destination) continue
            val field = (instructions[write] as? ReferenceInstruction)?.reference as? FieldReference
                ?: continue
            if (field.definingClass != definingClass || field.type != "Z") continue
            if (classDef != null &&
                classDef.fields.none { it.name == field.name && (it.accessFlags and 0x10) != 0 }
            ) {
                continue
            }
            writes += write
        }
    }
    return writes
}

/**
 * Matches the media data source constructor whose `this.c = <arg> instanceof <type>` decides
 * whether every `/videoplayback` fetch is rewritten into a **UMP envelope request**
 * (`e("ump", "1")` + `e("range", "<off>-<end>")`, HTTP range header dropped). The UMP envelope is
 * the SABR protocol, so a plain adaptive URL -- what the VISIONOS spoof returns -- is refused by
 * googlevideo with HTTP 400 / `staleconfig`. Clearing the flag makes the source fetch the URL
 * exactly as minted, which is what the extension's own probe fetch gets 206 for.
 *
 * Anchored on three independent facts, none of which is an obfuscated name: five parameters, the
 * `"ump"` literal being held by the class, and the boolean-field instruction shape. Measured over
 * the 1.88.10 dex: the literal occurs in exactly one class of the app, and the three together
 * match exactly one method. Recorded so nobody re-tries them: the instruction shape alone matches
 * 8 constructors, `"/videoplayback"` occurs in 48 methods across at least 5 classes.
 *
 * A weaker fallback also measures unique if the shape ever proves brittle after an update:
 * `name == "<init>" && parameterTypes.size == 5 && classDef.holdsStringLiteral("ump")` -> 1.
 */
private val mediaDataSourceCtorFingerprint = Fingerprint(
    name = "<init>",
    returnType = "V",
    custom = { method, classDef ->
        method.parameterTypes.size == 5 &&
            classDef.holdsStringLiteral(UMP_LITERAL) &&
            method.hasInstanceOfFeedingFinalBooleanField(classDef)
    },
)

/**
 * Matches the builder of every innertube request URL:
 * `uri.buildUpon().appendEncodedPath("youtubei/v1").appendEncodedPath(<route>)`, with the route
 * name in a field.
 *
 * Anchored on the **literal** rather than on obfuscated names (`oth.l()` in 1.88.10). "youtubei/v1"
 * is a protocol path, so it survives app updates, and it occurs in exactly one class of the app --
 * verified against the decompiled 1.88.10 sources. Upstream blocks the `get_watch` and
 * `initplayback` routes here so the app is forced onto `/player`, the only response this spoof
 * rewrites; until the logs say which routes are used, the hook only reports them.
 */
private val innertubeUriBuilderFingerprint = Fingerprint(
    returnType = "Landroid/net/Uri\$Builder;",
    parameters = emptyList(),
    strings = listOf("youtubei/v1"),
)

/**
 * Matches cronet's own `UrlRequest` factory. Every HTTP request the app makes goes through it,
 * which is what makes it a complete request log -- the `/videoplayback` DataSpec hook only covers
 * media fetched through the legacy player's DataSpec builder, and the onesie/ad modules fetch
 * elsewhere.
 */
private val cronetUrlRequestBuilderFingerprint = Fingerprint(
    definingClass = "Lorg/chromium/net/impl/CronetUrlRequestContext;",
    name = "newUrlRequestBuilder",
    returnType = "Lorg/chromium/net/UrlRequest\$Builder;",
    parameters = listOf(
        "Ljava/lang/String;",
        "Lorg/chromium/net/UrlRequest\$Callback;",
        "Ljava/util/concurrent/Executor;",
    ),
)

/**
 * The skip-ad countdown: the VR floating skip button and the 2D panel one. Both display the
 * remaining ad time, so a call is direct evidence that an ad is on screen -- the QOE
 * `ad_playback` marker only lands in a later ping and is absent in short sessions, which is why
 * it looked like "no ad" in every capture so far.
 *
 * Anchored on framework references and shape only, because R8 reassigns class and method names on
 * every release. The countdown body is `void(int)` writing a CharSequence through
 * `TextView.setText` and mirroring it into `TextView.setContentDescription`; measured over the
 * 1.88.10 dex (146,836 methods) exactly two methods satisfy that, and they are these two ad UIs.
 * Neither anchor alone is enough -- `setContentDescription` alone matches 3, `setText(CharSequence)`
 * alone 4 -- and access flags do not discriminate (the near misses `SeekBarPreference.Z(I)V` and
 * `fim.c(I)V` are PUBLIC|FINAL too).
 *
 * Because that shape matches both ad UIs at once, each fingerprint pins one of them with a further
 * framework fact about its *class*, so a fingerprint still resolves to exactly one method:
 * the panel one is the class that inflates a layout, the VR one animates a view.
 */
/** Shared with SponsorBlock, which gates its skips on the same ad-UI signal. */
internal fun isSkipAdCountdown(method: Method): Boolean {
    val instructions = method.implementation?.instructions ?: return false
    var writesText = false
    var mirrorsAccessibilityText = false
    for (instruction in instructions) {
        val reference = (instruction as? ReferenceInstruction)?.reference
        if (reference !is MethodReference) continue
        val parameterTypes = reference.parameterTypes
        val singleCharSequence =
            parameterTypes.size == 1 && parameterTypes[0].toString() == "Ljava/lang/CharSequence;"
        when {
            // setText(CharSequence) or setText(CharSequence, TextView$BufferType)
            reference.name == "setText" &&
                (
                    singleCharSequence ||
                        (
                            parameterTypes.size == 2 &&
                                parameterTypes[0].toString() == "Ljava/lang/CharSequence;" &&
                                parameterTypes[1].toString() == "Landroid/widget/TextView\$BufferType;"
                            )
                    ) ->
                writesText = true

            reference.name == "setContentDescription" && singleCharSequence ->
                mirrorsAccessibilityText = true
        }
    }
    return writesText && mirrorsAccessibilityText
}

/** Shared with SponsorBlock, which hooks both countdowns for its own ad gate. */
internal val skipAdCountdownFingerprints = listOf(
    // The VR floating skip button's class animates its view.
    Fingerprint(
        returnType = "V",
        parameters = listOf("I"),
        custom = { method, classDef ->
            isSkipAdCountdown(method) &&
                classDef.callsMethodOn("Landroid/view/ViewPropertyAnimator;", "translationY")
        },
    ),
    // The 2D panel skip button's class inflates a layout.
    Fingerprint(
        returnType = "V",
        parameters = listOf("I"),
        custom = { method, classDef ->
            isSkipAdCountdown(method) && classDef.callsMethodOn("Landroid/view/View;", "inflate")
        },
    ),
)

/**
 * Injects the YouTube VR patch extension into the app.
 *
 * The extension sources live in `extensions/src`; the `packageExtensionDex` Gradle
 * task compiles them, dexes them with d8 and ships the result inside the patch
 * bundle as `ytvr-extension.dex`. `extendWith` streams that dex into the patched
 * APK, so bytecode injected by other patches can call `app.ytvr.extension.*`.
 *
 * Phase 3: substitutes the spoofed streaming data (adaptive formats, no SABR URL)
 * for the one the ANDROID_VR client is served, and takes the SABR player out of the
 * picture so the legacy player consumes it. Together these are what remove the
 * pre-roll. Reports through System.out (logcat tag "System.out").
 */
@Suppress("unused")
val extensionPatch = bytecodePatch(
    name = "Extension",
    description = "Injects the extension the other patches run through: its streaming spoof " +
        "is what removes the pre-roll (ad-free playback) and provides the 8K format " +
        "support. Turning this patch off restores ads.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_VR)

    // ON by default: this is what makes the installed app ad-free and is the
    // configuration the whole project verified. It replaces the app's streaming
    // data with formats fetched as the VISIONOS client (so the server does not
    // stitch a pre-roll into them), clears the client-side ad slots in the app's
    // own player response, and normalises the media request so it plays.
    // Turning it off gives an unmodified (ad-bearing) player, i.e. only useful
    // for A/B comparisons -- see docs/INTERNALS.md.
    val enableStreamSpoof by booleanOption(
        key = "enableStreamSpoof",
        default = true,
        title = "Ad-free streaming spoof (recommended)",
    )

    // Verbose diagnostics for developing the patches: logs every innertube
    // route, every HTTP request, every media request and every ad-path entry
    // point, and runs a few extra probe fetches. Off by default so a normal
    // build stays quiet and fast.
    //
    // It selects *which hooks are installed* and is also the build's own word for "I am
    // developing": with it on, the extension's log gate starts open (`VerboseLog.setDefaultEnabled`,
    // injected below), so the diagnostics are readable from the app's first line without tapping
    // the "Verbose logging" row first. A build that never asks for the diagnostics -- CI, a
    // release, an end user applying the bundle with no options -- logs nothing at all, and that is
    // where the released artefact comes from: this option is chosen when a bundle is *applied*,
    // not when it is built, so the published bundle stays silent for whoever applies it next.
    //
    // The row still wins over the option: its stored value survives `adb install -r`, so a viewer
    // who turned logging off stays quiet even in a diagnostics build.
    val enableDiagnostics by booleanOption(
        key = "enableDiagnostics",
        default = false,
        title = "Verbose diagnostics (development only)",
    )

    // For local test builds: log from the first line and *ignore* the "Verbose logging" row's
    // stored value, which is otherwise the winner (it is sticky across `adb install -r`, so a
    // device that once had the row switched off stays silent even in a diagnostics build -- the
    // gate's default only loses to a stored value, by design). Kept separate from
    // enableDiagnostics on purpose: this installs no extra hooks and makes no probe request, so the
    // everyday "build it and watch the log" loop costs nothing but the lines themselves.
    val forceVerboseLogging by booleanOption(
        key = "forceVerboseLogging",
        default = false,
        title = "Force verbose logging (development builds)",
    )

    // One-shot identity matrix: fetches two videos under several candidate identities plus an
    // ANDROID_VR control and logs the max format height of each, to find which context field
    // unlocks 8K. It costs ~14 *unauthenticated* requests per app start, which is the kind of
    // traffic that gets an IP's unauthenticated innertube requests refused (see
    // SABR-SPOOF-PLAN.md, 2026-10-11), so it now requires enableDiagnostics as well -- its
    // output is unreadable without the route log anyway -- and stays off in every default build.
    val enableIdentityMatrix by booleanOption(
        key = "enableIdentityMatrix",
        default = false,
        title = "Probe identity matrix (~14 requests; needs Verbose diagnostics)",
    )

    // Native-formats mode: RETIRED (2026-10-11). It kept the app's own ANDROID_VR streaming
    // data -- whose format list names the 8K VP9 tiers the VISIONOS list lacks -- and blanked
    // its SABR URI so the legacy player would fetch plain adaptive URLs. Measured 2026-10-10:
    // the app's own formats carry NO absolute URL (SABR-era metadata), so the legacy player
    // fetched a bare "?cpn=..&rn=N" forever. Kept as an option because the guard inside
    // SpoofStreams.nativeFormatsNoSabr is the measurement: enabling it now logs the finding
    // and changes nothing, so it cannot break playback.
    val enableNativeFormats by booleanOption(
        key = "enableNativeFormats",
        default = false,
        title = "Native formats without SABR (retired experiment)",
    )

    // The 8K option: make the injected 8K tier survive the app's own checks. Two rewrites, both
    // on the spoofed data only, never on the app's own stream.
    //
    // The tier is really there -- itag 702, AV1 4320p60, absolute URL -- and the app still fetched
    // itag 315 (VP9 2160p) with its menu at 4K, so something in the app rejects it. Two candidates,
    // both addressed here:
    //
    //  1. its codec string is AV1 level 6.1 while this device's decoder advertises 5.1 (the app's
    //     own QOE ping prints `c2.qti.av1.decoder` as `l.20000`, Android's AV1Level51). Alone,
    //     rewriting the claim to 5.1 changed nothing -- that is the measured half of this option.
    //  2. **HDR is the likelier gate.** YouTube now serves 8K only as HDR AV1: the app's own table
    //     calls 702 `DASH_FMP4_AV1_8K_HDR` and pairs it with a non-HDR 571 (`DASH_FMP4_AV1_8K`)
    //     that the stream does not contain, and this headset's panels are not HDR. Supporting
    //     evidence: given AV1 HDR 2160p (701) and VP9 SDR 2160p60 (315) together, the player chose
    //     315; the app has HDR-aware format filters (`axh` case 14 uses `!onu.V(...)`); and the
    //     app's own player request declares its `MediaCapabilities` to the server (the `see`
    //     decorator, allowlisted to the `player` route), which is why its ladder may differ.
    //
    // So the injected HDR entries are relabelled to their SDR siblings and their color info is
    // cleared. The URL is untouched, so the bytes are still the real 8K stream; only the app's
    // classification changes. ON by default -- it is the 8K option -- and the honest caveat is
    // that sufficiency is unproven: if the exclusion is instead in the renderer's display
    // capability check (`aat.n`/`gep.aH` ask `display.isHdr()`), that needs an app-side patch and
    // a bigger lie. Turn this off to get the server's ladder exactly as sent (4K ceiling).
    val enable8k by booleanOption(
        key = "enable8k",
        default = true,
        title = "8K: accept the AV1 8K HDR tier as SDR (experiment)",
    )

    // The dex is a bundle resource; the anonymous class makes the bundle's own
    // class loader resolve it, whichever class loader loaded the patches.
    extendWith(
        Supplier {
            object {}.javaClass.getResourceAsStream("/ytvr-extension.dex")
        },
    )

    execute {
        // The log gate, at the earliest point there is. `init` reads the "Verbose logging" row's
        // stored value (the SponsorBlock patch adds the row to the hub), and with the diagnostics
        // option on the gate's *default* is "on" as well, so a development build logs from its
        // first line without anyone tapping the row. Without this call the gate would only be
        // initialised when the SponsorBlock patch is applied, and a build of the Extension patch
        // alone could not turn its own diagnostics on.
        //
        // It is injected **last** of this patch's Application.onCreate edits, and that is not
        // cosmetic: every one of these blocks is inserted at index 0, so the last insertion ends up
        // first in the method and the gate opens before the other start-up lines (the task label,
        // the 8K note). The SponsorBlock patch's block goes in after this patch has run (measured
        // in the installed build: Extension is applied first, so its instructions end up behind
        // that block) and therefore executes *before* the gate here -- which is why
        // `setDefaultEnabled` re-reads the preference file instead of only remembering the flag,
        // and why the settings store re-emits its dump when the gate opens. Nothing depends on
        // that order: `VerboseLog` is idempotent and re-reads, and the dump is printed once,
        // whichever of the two calls opens the gate.
        //
        // `forceVerboseLogging` goes *in* the block before `init`, because it is the one thing that
        // must beat the row's stored value rather than sit under it: a local test build on a device
        // whose "Verbose logging" row was switched off once would otherwise stay silent. `init`
        // consults that flag too, so the order inside the block is about the listing it prints, not
        // about whether the gate ends up open.
        fun injectLogGate() {
            val gate = mutableListOf<String>()
            if (enableDiagnostics == true) {
                gate += "const/4 v0, 0x1"
                gate += "invoke-static {v0}, Lapp/ytvr/extension/VerboseLog;->setDefaultEnabled(Z)V"
            }
            if (forceVerboseLogging == true) {
                // Before `init`, deliberately: `init` consults the same flag, so the gate is open
                // either way, and going first also makes its development-build listing of the
                // preference files and the files holding the `ytvr_mod` keys run.
                gate += "const/4 v0, 0x1"
                gate += "invoke-static {v0}, Lapp/ytvr/extension/VerboseLog;->forceEnabled(Z)V"
            }
            gate += "invoke-static {p0}, Lapp/ytvr/extension/VerboseLog;->init(Landroid/content/Context;)V"
            applicationOnCreateFingerprint.method.addInstructions(0, gate.joinToString("\n"))
        }

        // Give the app a task label. Horizon OS's library names a *running* app from the store's
        // record for its package; a re-signed, renamed build has none, and the fallback is the
        // task label, which this app leaves null -- so the library shows its "App name
        // unavailable" placeholder (and a Quit action) on the app's card. Installed here, outside
        // the spoof gate: it is about the launcher, not about ads.
        applicationOnCreateFingerprint.method.addInstructions(
            0,
            "invoke-static {p0}, Lapp/ytvr/extension/TaskLabel;->install(Ljava/lang/Object;)V",
        )

        // The player-response hook. Two modes, and only two:
        // - spoof: swap in the VISIONOS streaming data (and, only when the swap happened, blank
        //   the app's ad slots) -- and fall back to leaving everything alone when the spoofed
        //   fetch cannot produce a usable stream (see SpoofStreams.augmentPlayerResponse).
        // - native formats: retired, guarded inside nativeFormatsNoSabr because the app's own
        //   formats carry no URLs (SABR-era), so blanking the SABR URI leaves nothing to fetch.
        //
        // With the spoof off, nothing is hooked here at all. It used to clear the ad slots on
        // its own, which is KNOWN BROKEN for playback (2026-10-10): with the native SABR path
        // live, a blanked ad-slot list makes the playback director reset (bestVideoFormat=0,
        // STOPPAGE_DIRECTOR_RESET_INTERNALLY) -- the pre-roll is too integrated into the native
        // SABR setup to remove from under it. "Spoof off" therefore means stock, which is the
        // only meaning an off switch can usefully have, and SpoofStreams.clearPlayerAdSlots is
        // kept unwired as the A/B reference for that finding.
        if (enableNativeFormats == true) {
            videoStreamingDataCtorFingerprint.method.addInstructions(
                0,
                "invoke-static {p1}, Lapp/ytvr/extension/SpoofStreams;->nativeFormatsNoSabr(Ljava/lang/Object;)V",
            )
        } else if (enableStreamSpoof == true) {
            videoStreamingDataCtorFingerprint.method.addInstructions(
                0,
                "invoke-static {p1}, Lapp/ytvr/extension/SpoofStreams;->augmentPlayerResponse(Ljava/lang/Object;)V",
            )
        }

        if (enableIdentityMatrix == true && enableDiagnostics == true) {
            applicationOnCreateFingerprint.method.addInstructions(
                0,
                "invoke-static {}, Lapp/ytvr/extension/SpoofStreams;->identityMatrixProbe()V",
            )
        }

        // The 8K relaxation only ever rewrites the *injected* ladder, so its off switch is only
        // worth injecting alongside the spoof that feeds it.
        if (enableStreamSpoof == true && enable8k != true) {
            applicationOnCreateFingerprint.method.addInstructions(
                0,
                "invoke-static {}, Lapp/ytvr/extension/SpoofStreams;->disable8kExperiment()V",
            )
        }

        // Everything below is the spoof's playback plumbing (media-request normalisation, the
        // two rewrites that make the legacy player usable, the ad hooks). It is only correct
        // while a spoofed stream is in play -- SpoofStreams decides that per fetch and the
        // runtime hooks consult it -- so with the spoof off there is nothing to install.
        if (enableStreamSpoof != true && enableNativeFormats != true) {
            // Nothing else will be injected at index 0, so the gate can go in now: it stays last
            // of this patch's Application.onCreate edits, which is what puts it first in the
            // method (see injectLogGate).
            injectLogGate()
            return@execute
        }

        // Diagnostics only: fetch a fixed video once at app start to prove the spoofed
        // request contract still holds on this app version.
        if (enableDiagnostics == true && enableStreamSpoof == true) {
            applicationOnCreateFingerprint.method.addInstructions(
                0,
                "invoke-static {}, Lapp/ytvr/extension/SpoofStreams;->probe()V",
            )
        }

        // The last Application.onCreate edit of this patch -- see injectLogGate for why the order
        // of these insertions is load-bearing. Everything injected above runs after the gate opens.
        injectLogGate()

        // Phase 3d: keep the media data source from rewriting the URL into a UMP envelope
        // request. `<this>.c = <underlying source> instanceof ayd` (cronet) selects the
        // `?ump=1&range=...` form for every /videoplayback fetch; for a plain adaptive URL
        // that is a SABR-protocol request with no config behind it, so googlevideo answers
        // HTTP 400 (`rc.400`, `staleconfig`) and the player retries forever. The write is
        // `instance-of vX, p1, Layd` followed by `iput-boolean vX, p0, <this>.c:Z`.
        //
        // The site is located by that shape rather than by the obfuscated `Lpqy;->c:Z`: the
        // fingerprint already proved this constructor is the one whose class holds "ump" and
        // whose `Z` field is written from an `instance-of`, and requiring exactly one site keeps
        // this as safe as the name lookup was -- a second candidate aborts the patch instead of
        // clearing an arbitrary boolean.
        //
        // The flag is *gated*, not cleared: `SpoofStreams.umpRewriteAllowed` zeroes it only
        // while a spoofed stream is in play and otherwise hands back the value the app computed.
        // The app's own stream is a UMP/SABR stream and is fetched precisely this way, so an
        // unconditional clear -- which is what this used to be -- breaks native playback the
        // moment the spoof is unavailable. Two instructions are appended after the `instance-of`
        // instead of one replacing it, so the app's own value is still computed and available.
        val ctor = mediaDataSourceCtorFingerprint.method
        val instructions = ctor.implementation!!.instructions
        val writes = ctor.instanceOfFedBooleanWriteIndices()
        check(writes.size == 1) {
            "expected exactly one instance-of-fed boolean write in the media data source " +
                "constructor, found ${writes.size}: cannot gate the UMP rewrite"
        }
        val write = writes.single()
        val iput = ctor.getInstruction<TwoRegisterInstruction>(write)
        val producer = (write - 1 downTo maxOf(0, write - 8)).first { index ->
            val instruction = instructions[index]
            instruction.opcode == Opcode.INSTANCE_OF &&
                (instruction as? OneRegisterInstruction)?.registerA == iput.registerA
        }
        // `v${iput.registerA}` is named directly in the invoke list, which is only legal for a
        // 4-bit register: the same register the `const/4` that this replaces had to name, so it
        // is already known to fit.
        ctor.addInstructions(
            producer + 1,
            """
                invoke-static {v${iput.registerA}}, Lapp/ytvr/extension/SpoofStreams;->umpRewriteAllowed(I)I
                move-result v${iput.registerA}
            """,
        )

        // Phase 3e: undo the AbrStateDataSpec POST conversion at the DataSpec build -- this is
        // the playback fix, so it is always installed. `PotUrls.onBuild` only normalises;
        // `PotUrls.onBuildVerbose` additionally logs each media request and runs the fetch
        // matrix, so the shipping build does not pay for the diagnostics.
        //
        // `p0` is copied into `v0` first, never passed directly: `invoke-*` register lists are
        // 4-bit (v0-v15), and `axe.a()` has far more registers than that, so naming `p0` in the
        // list does not assemble. The assembler reports that as *zero* methods in the synthetic
        // class, which surfaces as the very unhelpful `PatchException: Collection is empty`.
        dataSpecBuildFingerprint.method.addInstructions(
            0,
            if (enableDiagnostics == true) {
                """
                    move-object/from16 v0, p0
                    invoke-static {v0}, Lapp/ytvr/extension/PotUrls;->onBuildVerbose(Ljava/lang/Object;)V
                """
            } else {
                """
                    move-object/from16 v0, p0
                    invoke-static {v0}, Lapp/ytvr/extension/PotUrls;->onBuild(Ljava/lang/Object;)V
                """
            },
        )

        if (enableDiagnostics == true) {
            // Which innertube routes the app requests (is it using get_watch / an ad-break route
            // instead of the /player response the spoof rewrites?).
            innertubeUriBuilderFingerprint.method.addInstructions(
                0,
                """
                    move-object/from16 v0, p0
                    invoke-static {v0}, Lapp/ytvr/extension/PotUrls;->onInnertubeRoute(Ljava/lang/Object;)V
                """,
            )
        }

        // Ad-request filter: rewrite ad creatives/tracking to a harmless URL. Behaviour, not
        // diagnostics, so it is always installed; the request log alongside it is not.
        //
        // The hook names the parameter register directly instead of copying it into `v0` first:
        // in a method with few registers `p0` *is* `v0`, so a copy into `v0` overwrites `this` and
        // the verifier rejects the class -- `VerifyError: void gtu.b(int): instance field access on
        // object that has non-reference type Integer`. The copy idiom is only for methods with
        // more than 15 registers, where a `p` register cannot be named at all.
        cronetUrlRequestBuilderFingerprint.method.addInstructions(
            0,
            (
                if (enableDiagnostics == true) {
                    "invoke-static {p1}, Lapp/ytvr/extension/PotUrls;->onUrlRequest(Ljava/lang/String;)V\n"
                } else {
                    ""
                }
                ) +
                """
                    invoke-static {p1}, Lapp/ytvr/extension/PotUrls;->filterUrl(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object p1
                """,
        )

        // The ad UI countdown: an ad on screen makes it tick, which is the one ad signal that is
        // immediate (the QOE `ad_playback` marker only lands in a later ping). Cheap, and silent
        // when there is no ad.
        skipAdCountdownFingerprints.forEach { fingerprint ->
            fingerprint.method.addInstructions(
                0,
                "invoke-static {p1}, Lapp/ytvr/extension/PotUrls;->onAdCountdown(I)V",
            )
        }

        // Phase 3b, revised 2026-10-11: let the stream data decide which player is used, and
        // only neutralise the two branches that force the onesie/SABR player on from the player
        // config without consulting that data. The decision then reads:
        //
        //   injected data (SABR URI blanked) -> DISABLED_BY_SABR_STREAMING_URI -> legacy player
        //   the app's own data (URI present) -> ENABLED                         -> onesie player
        //
        // which is the invariant that matters: never pick the onesie player without a onesie URL.
        // The previous revision returned false from `a`/`b` instead, i.e. it disabled SABR
        // unconditionally -- which is sound only while the spoof keeps arriving, and fatal once
        // it does not (the app's own formats carry no URLs; nothing plays at all).
        //
        // The two calls are found by "a no-arg boolean invoke made directly on one of this
        // method's two parameters", so no obfuscated method name is named (`ap` and `C` in
        // 1.88.10) and a changed shape fails loudly instead of rewriting the wrong call. Both
        // are `invoke-virtual {vX}, ...` returning Z, so replacing the invoke with a
        // `invoke-static ... ->Z` keeps the `move-result` that follows it valid.
        // Only the *first* such call is neutralised: the player config's forced-SABR flag, which
        // is the first thing the decision looks at. The `oqd.C()` premium-stream test and the
        // `opw.ak()` disable branch are deliberately left alone (see the helper above).
        val decision = platypusDecisionFingerprint.method
        val parameterCalls = decision.booleanCallsOnParameters()
        val forcedOn = parameterCalls.firstOrNull()
        check(forcedOn != null && forcedOn.parameter == 0) {
            "cannot find the player config's forced-SABR call in the SABR decision " +
                "(${decision.definingClass}->${decision.name}): expected a no-argument boolean " +
                "call on its first parameter, found $parameterCalls. Without neutralising it, " +
                "the decision never reaches its own SABR-URI check and a failed spoof fetch " +
                "takes playback down with it."
        }
        decision.replaceInstruction(
            forcedOn.index,
            "invoke-static {}, Lapp/ytvr/extension/SpoofStreams;->sabrNotForced()Z",
        )
    }
}
