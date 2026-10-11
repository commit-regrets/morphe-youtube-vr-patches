package app.ytvr.extension;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The single gate every log line in this extension goes through.
 *
 * The port had grown one private {@code log(String)} helper per class, each writing straight to
 * {@code System.out} (the logcat tag the extension has always used). That is what a developer
 * wants -- the settings dump, the per-segment {@code effective:} line, the spoof diagnostics --
 * and exactly what a user of a released build must not see. So those lines now hang off one
 * switch, which is a settings row ("Verbose logging", in the "Patch settings" hub) rather than a
 * build option, so turning them back on needs no rebuild:
 *
 * <ul>
 *   <li>this is the **only** place in {@code extensions/src} that writes to {@code System.out};
 *       every class's own {@code log(String)} is a one-line delegation to {@link #log};</li>
 *   <li>the gate is a plain volatile field, so a closed one costs a field read and returns before
 *       the message is even formatted -- logging off changes nothing else about the app;</li>
 *   <li>the field is refreshed by a preference-change listener, so flipping the row takes effect
 *       on the next log line with no restart. See {@link #LISTENER} for why it is held in a static
 *       field.</li>
 * </ul>
 *
 * Off by default, in this class and in the row's own {@code android:defaultValue}: a build whose
 * row has never been touched logs nothing at all. A gate that cannot be read (no context yet, a
 * preference file that will not open) stays closed for the same reason -- silence is the safe
 * failure here, and a log line is never worth an exception thrown into the app.
 */
public final class VerboseLog implements SharedPreferences.OnSharedPreferenceChangeListener {

    /**
     * The "Verbose logging" row's key. The patch writes the same literal into the hub's XML
     * (`SettingsXml.kt`, where the row is declared); the two have to agree or the row toggles
     * nothing.
     */
    public static final String KEY_VERBOSE_LOGGING = "ytvr_mod_verbose_logging";

    /**
     * Held strongly on purpose: {@code SharedPreferencesImpl} keeps its listeners in a
     * {@code WeakHashMap}, so a listener nothing else references is collected and the row would
     * silently stop working after the first garbage collection.
     */
    private static final VerboseLog LISTENER = new VerboseLog();

    /** The gate. Read on every log line, so it is a field and never a preference lookup. */
    private static volatile boolean enabled;

    /** True once the listener is registered, so a second {@link #init} cannot register it twice. */
    private static volatile boolean watching;

    private VerboseLog() {
    }

    /**
     * Reads the row's stored value and starts watching it. Called at Application.onCreate -- the
     * Extension patch injects the call, and {@link SponsorBlockSettings#initContext} makes it too,
     * because the two are injected into the same method and neither may assume it runs first. A
     * gate initialised after the settings dump would swallow the one line that says what the
     * screen is set to, and this is idempotent, so the second call costs nothing.
     */
    public static void init(Context appContext) {
        try {
            SharedPreferences mod = SponsorBlockSettings.modPreferences(appContext);
            if (mod == null) {
                return;
            }
            enabled = mod.getBoolean(KEY_VERBOSE_LOGGING, false);
            if (!watching) {
                watching = true;
                mod.registerOnSharedPreferenceChangeListener(LISTENER);
            }
        } catch (Throwable t) {
            enabled = false;
        }
    }

    /** Whether the row is on, i.e. whether anything at all may be printed. */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * The one log call: prints {@code <tag>: <message>} to {@code System.out} (logcat's
     * {@code System.out} tag, which is where the extension's lines have always been) and does
     * nothing whatsoever when the gate is closed.
     */
    public static void log(String tag, String message) {
        if (!enabled) {
            return;
        }
        System.out.println(tag + ": " + message);
    }

    /**
     * The live half of the toggle: androidx persists the row's own Boolean, and this picks the new
     * value up wherever the write came from. No restart, no re-read on the log path.
     */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        if (KEY_VERBOSE_LOGGING.equals(key)) {
            enabled = preferences.getBoolean(KEY_VERBOSE_LOGGING, false);
        }
    }
}
