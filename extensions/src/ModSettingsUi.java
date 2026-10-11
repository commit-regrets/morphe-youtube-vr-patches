package app.ytvr.extension;

/**
 * Bridges the system back affordance to the "Patch settings" hub's navigation.
 *
 * Horizon OS draws its own back button on a focused 2D panel, and the settings card's own arrow
 * is hidden (the patch sets it gone in the holder layouts) so the screen shows exactly one back
 * control. That system control dispatches a back event to the activity; the SponsorBlock patch
 * hooks the activity's onBackPressed to call {@link #goBack()} first, which pops the settings
 * navigation (SponsorBlock screen -> hub -> list -> close) exactly like the hidden arrow did.
 *
 * The settings holder registers itself here through a bridge method the patch adds to it
 * ({@code YtvrSbGoBack} -- our name on the app's class, so R8 renames cannot break the call).
 */
public final class ModSettingsUi {
    private static final String TAG = "SponsorBlock";

    private static volatile Object sSettings;

    private ModSettingsUi() {
    }

    /** Called by the settings holder when its view is created. */
    public static void onSettingsShown(Object settings) {
        sSettings = settings;
        log("patch settings back registered");
    }

    /**
     * Called from the preference-fragment base's view-destroyed callback for every settings
     * fragment; only the registered holder's own destroy clears the registration (the list and
     * screen fragments inside the holder destroy as part of normal navigation).
     */
    public static void onSettingsHidden(Object fragment) {
        if (fragment == sSettings) {
            sSettings = null;
            log("patch settings back deregistered");
        }
    }

    /**
     * Pops the settings navigation when the hub surface is showing. Returns true when the back
     * was consumed (the caller must not run its own handling).
     */
    public static boolean goBack() {
        log("patch settings back pressed, registered=" + (sSettings != null));
        Object settings = sSettings;
        if (settings == null) {
            return false;
        }
        try {
            settings.getClass().getMethod("YtvrSbGoBack").invoke(settings);
            return true;
        } catch (Exception exception) {
            log("patch settings back failed: " + exception);
            return false;
        }
    }

    /** Through the one gate: a build whose "Verbose logging" row is untouched prints nothing. */
    private static void log(String message) {
        VerboseLog.log(TAG, message);
    }
}
