package app.ytvr.patches.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.ytvr.patches.shared.callsMethodOn
import app.ytvr.patches.shared.holdsStringLiteral

/**
 * Matches the method that turns the ad placements of a player response
 * into a list of ad breaks to schedule. Returning an empty list here
 * prevents preroll, midroll, postroll and pause ads from ever being scheduled.
 *
 * The class and method names are obfuscated and expected to change between app
 * versions, so the anchor is the shape instead: a `List`-returning method that
 * schedules its work with a `PriorityQueue`. `java.util.PriorityQueue` is a
 * framework type, so R8 cannot rename it.
 *
 * Measured over the stock 1.88.10 dex (5 dex files, 27,722 classes): ten methods
 * app-wide call `PriorityQueue(int, Comparator)`, and exactly one of those
 * returns `List` -- this one. The previous version of this fingerprint read the
 * placements through `oqi.F()`, an obfuscated name; `List` + `oqi.F()` alone
 * still matches three methods (`mqx.r`, `muk.a`, `rge.N`), so the framework
 * constructor is what carries the discrimination.
 */
object AdBreakRendererFingerprint : Fingerprint(
    returnType = "Ljava/util/List;",
    filters = listOf(
        methodCall(smali = "Ljava/util/PriorityQueue;-><init>(ILjava/util/Comparator;)V"),
    ),
)

/**
 * Matches the method that performs the network request for an ad break
 * ("player/ad_break"). Returning null here prevents ad break responses
 * from ever being fetched.
 *
 * The anchor is the distinctive log template alone. Measured: it occurs in
 * exactly one method of the app, and the `Lonm;` return type this fingerprint
 * used to name is obfuscated. The custom filter keeps the one property the
 * injected `return-object` depends on -- that the method returns a reference --
 * without naming the obfuscated type, so a future version that moved the string
 * into a primitive-returning helper fails to match instead of assembling
 * invalid bytecode.
 */
object AdBreakRequestFingerprint : Fingerprint(
    strings = listOf("Exception when trying to request AdBreakResponseModel: %s"),
    custom = { method, _ ->
        // A reference return type: `L…;` or an array (`[`), both of which `return-object` accepts.
        method.returnType.first().let { it == 'L' || it == '[' }
    },
)

/**
 * Matches the helper that reports whether a player response carries a
 * PlayerBytes ad slot: a pre-rendered ad layout embedded directly in the
 * response, used for the pre-roll played on the first playback after fresh
 * app data. Returning false here prevents that slot from ever being
 * bootstrapped, scheduled or fulfilled.
 *
 * Three independent facts, none of them an app name:
 *   - `Z` with exactly one reference parameter (the player response);
 *   - the class holds the `AdCpn is null or empty for CISA scheduling` literal,
 *     which is the diagnostic the ad-slot scheduler it belongs to logs;
 *   - the body asks an `Optional` whether it `isPresent()` -- the slot lookup.
 *
 * Measured over 1.88.10: the shape alone (`Z`, one reference parameter, an
 * `isPresent()` call) matches twelve methods, and the class fact alone still
 * leaves the class's two sibling `Z(...)` detectors; all three together match
 * exactly one, this one.
 */
object PlayerBytesSlotFingerprint : Fingerprint(
    returnType = "Z",
    custom = { method, classDef ->
        method.parameterTypes.size == 1 &&
            method.parameterTypes[0].toString().startsWith("L") &&
            classDef.holdsStringLiteral("AdCpn is null or empty for CISA scheduling") &&
            method.callsMethodOn("Lj$/util/Optional;", "isPresent")
    },
)

/**
 * Matches the observer that bootstraps the pre-rendered PlayerBytes ad layout
 * embedded in a player response: it extracts the layout proto, builds a
 * playable ad layout and caches it for the pre-roll played on the first
 * playback after fresh app data. Returning early here prevents the layout
 * from ever being built or cached, regardless of which code path consults it.
 *
 * The anchor mirrors the upstream Android "Hide video ads" patch, which
 * neutralizes the equivalent loader by its distinctive log string. Measured:
 * the string occurs in exactly one method of the app, and it is not the
 * near-identical message without the `PlayerAds count: ` suffix that sits in
 * the same class.
 */
object PlayerBytesAdLayoutFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    strings = listOf(
        "Bootstrapped layout construction resulted in non PlayerBytesLayout. PlayerAds count: ",
    ),
)
