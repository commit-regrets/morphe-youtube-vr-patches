package app.ytvr.patches.shared

import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * Facts about the app's own bytecode that more than one fingerprint anchors on.
 *
 * Every predicate here is phrased in terms of **references and structure**, never in terms of the
 * app's own class or member names: R8 reassigns those on every release, which is exactly what makes
 * a name-anchored fingerprint dangerous -- it is not a loud failure, it is the *wrong method*
 * patched while the patcher still reports `Applied:` and exits 0. See docs/FINGERPRINTS.md.
 *
 * The string-literal predicates were promoted out of `ExtensionPatch.kt` when the Hide-ads and
 * playability fingerprints needed them too; keeping one copy is what stops the two from drifting.
 */

/** True when [this] method uses [value] as a string literal anywhere in its body. */
fun Method.holdsStringLiteral(value: String): Boolean =
    implementation?.instructions?.any { instruction ->
        (instruction as? ReferenceInstruction)?.reference.let { reference ->
            reference is StringReference && reference.string == value
        }
    } ?: false

/**
 * True when any method of [this] class uses [value] as a string literal.
 *
 * This is the class-level form: it anchors on a fact the *class* holds when the method being
 * patched has no distinguishing literal of its own.
 */
fun ClassDef.holdsStringLiteral(value: String): Boolean = methods.any { it.holdsStringLiteral(value) }

/**
 * True when [this] method calls [name] on [definingClass].
 *
 * [definingClass] is meant to be a framework or generated type (`Lj$/util/Optional;`,
 * `Landroid/widget/TextView;`), which R8 cannot rename, and the prototype is deliberately not
 * checked so an overload or an added parameter does not break the anchor.
 */
fun Method.callsMethodOn(definingClass: String, name: String): Boolean =
    implementation?.instructions?.any { instruction ->
        (instruction as? ReferenceInstruction)?.reference.let { reference ->
            reference is MethodReference &&
                reference.definingClass == definingClass &&
                reference.name == name
        }
    } ?: false

/** True when any method of [this] class calls [name] on [definingClass]. */
fun ClassDef.callsMethodOn(definingClass: String, name: String): Boolean =
    methods.any { it.callsMethodOn(definingClass, name) }

/**
 * True when the body of [this] method references the type [type] itself: a field access on it, a
 * call on it, `new-instance`, `check-cast` or `instance-of`.
 *
 * Only the **body** counts, which is the point: a method that merely declares [type] as its return
 * type or parameter does not qualify, so this separates the provider that *builds* a generated
 * proto from the interface (and the no-op implementation) that only mentions it in a signature.
 */
fun Method.bodyReferencesType(type: String): Boolean =
    implementation?.instructions?.any { instruction ->
        when (val reference = (instruction as? ReferenceInstruction)?.reference) {
            is FieldReference -> reference.definingClass == type
            is MethodReference -> reference.definingClass == type
            is TypeReference -> reference.type == type
            else -> false
        }
    } ?: false
