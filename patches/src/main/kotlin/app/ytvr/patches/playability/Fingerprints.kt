package app.ytvr.patches.playability

import app.morphe.patcher.Fingerprint
import app.ytvr.patches.shared.holdsStringLiteral

/**
 * The playability policy's own logger name. It is a framework-style logger literal
 * (`com/google/android/libraries/youtube/player/playability/PlayabilityPolicy`) that the class
 * writes when the confirmation state changes, so it survives an update that renames the class.
 */
private const val PLAYABILITY_LOGGER =
    "com/google/android/libraries/youtube/player/playability/PlayabilityPolicy"

/**
 * Matches the method of the playability policy that returns the adult content
 * confirmation state. It feeds the "allowAdultContent" flag of player requests
 * and the age-gated playability routing. Returning true here reports the
 * confirmation as given, so the server returns playable streams for
 * age-restricted videos and the confirm dialog is accepted automatically.
 *
 * The class and method names are obfuscated, so the anchor is the shape plus a
 * class fact: a no-argument method returning the boxed `Boolean`, in the class
 * that holds the policy's logger literal. Measured over the stock 1.88.10 dex:
 * exactly one method matches, and the logger literal occurs in exactly one class
 * of the app.
 */
object AdultConfirmationFingerprint : Fingerprint(
    returnType = "Ljava/lang/Boolean;",
    parameters = emptyList(),
    custom = { method, classDef ->
        method.implementation != null && classDef.holdsStringLiteral(PLAYABILITY_LOGGER)
    },
)

/**
 * Matches the method of the playability policy that returns the controversial
 * content confirmation state, feeding the "allowControversialContent" flag of
 * player requests. Returning true here reports the confirmation as given.
 *
 * The same class fact as the adult anchor, with the `Z` shape instead of the
 * boxed `Boolean`. `implementation != null` is load-bearing: the class also
 * declares an abstract no-argument `Z` method, so the shape plus the class fact
 * alone measures two matches and the abstract one has no body to inject into.
 */
object ControversialConfirmationFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    custom = { method, classDef ->
        method.implementation != null && classDef.holdsStringLiteral(PLAYABILITY_LOGGER)
    },
)
