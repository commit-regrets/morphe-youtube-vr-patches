package app.ytvr.patches.misc

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.ytvr.patches.shared.Constants.COMPATIBILITY_YOUTUBE_VR
import org.w3c.dom.Element
import java.io.FileWriter

/**
 * Sets the name the headset shows for this app (library / app info).
 *
 * The official bundle's "Custom branding" patch cannot do it: it is a resource patch for the
 * phone YouTube/Music apps, so enabling it for YouTube VR applies **zero** patches and the
 * clone stays listed as plain "YouTube" -- indistinguishable from the store app (and from the
 * phone YouTube app, which is also on the device). Verified by applying it and reading the
 * built resources: `youtube_vr_app_name` was still `YouTube`.
 *
 * Implemented the way the official branding patches do it: the name goes into a
 * `values-v24` overlay, which overrides the base `youtube_vr_app_name` at runtime without
 * duplicating the string inside the base `values/strings.xml` (a duplicate would be invalid).
 *
 * Note this must be applied *before* the official "Clone app" patch, like every patch in this
 * bundle: Morphe filters patches by package name, and after the clone the package is no longer
 * `com.google.android.apps.youtube.vr.oculus`.
 */
@Suppress("unused")
val appNamePatch = resourcePatch(
    name = "App name",
    description = "Changes the app name shown in the headset's library and in app info. " +
        "The stock name is just \"YouTube\", which is easy to confuse with the store app.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_VR)

    val appName by stringOption(
        key = "appName",
        default = "YouTube VR (patched)",
        title = "App name",
        description = "Shown in the headset library and in app info. A distinct name makes the " +
            "patched app easy to tell apart from the store one.",
        required = true,
    )

    execute {
        val valuesV24 = get("res/values-v24")
        valuesV24.mkdirs()
        val strings = valuesV24.resolve("strings.xml")
        if (!strings.exists()) {
            FileWriter(strings).use {
                it.write("<?xml version=\"1.0\" encoding=\"utf-8\"?><resources></resources>")
            }
        }

        document("res/values-v24/strings.xml").use { doc ->
            val element: Element = doc.createElement("string")
            element.setAttribute("name", "youtube_vr_app_name")
            element.textContent = appName
            doc.getElementsByTagName("resources").item(0).appendChild(element)
        }
    }
}
