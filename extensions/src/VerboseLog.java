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
 * Two sources decide the gate, and the viewer's own row always outranks the build:
 *
 * <ol>
 *   <li>the row's stored value, which is what a release reads;</li>
 *   <li>{@link #setDefaultEnabled}, called by the patch when its "Verbose diagnostics (development
 *       only)" option is on -- a development build logs from its first line with nobody tapping the
 *       row, while a build that did not ask for the diagnostics (CI, a release, an end user's
 *       apply) is silent with no options at all.</li>
 * </ol>
 *
 * Off by default, in this class and in the row's own {@code android:defaultValue}: a build whose
 * row has never been touched and whose diagnostics were not asked for logs nothing at all. A gate
 * that cannot be read (no context yet, a preference file that will not open) stays closed for the
 * same reason -- silence is the safe failure here, and a log line is never worth an exception
 * thrown into the app.
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

    /**
     * The build's own default, for a build that said "I am developing": {@link #setDefaultEnabled}.
     * A stored row value always wins over it, so a viewer who turned logging off stays quiet even
     * in a diagnostics build.
     */
    private static volatile boolean fallback;

    /**
     * The preference file, kept so {@link #setDefaultEnabled} can re-read it: the two calls the
     * patch injects at Application.onCreate come from two different patches, and neither may assume
     * it runs first.
     */
    private static volatile SharedPreferences preferences;

    /** True once the listener is registered, so a second {@link #init} cannot register it twice. */
    private static volatile boolean watching;

    private VerboseLog() {
    }

    /**
     * Reads the row's stored value -- falling back to the build's own default -- and starts
     * watching it. Called at Application.onCreate by the Extension patch and again by
     * {@link SponsorBlockSettings#initContext}, because the two are injected into the same method
     * and neither may assume it runs first. This is idempotent, so the second call costs nothing.
     */
    public static void init(Context appContext) {
        try {
            SharedPreferences mod = SponsorBlockSettings.modPreferences(appContext);
            if (mod == null) {
                return;
            }
            preferences = mod;
            enabled = mod.getBoolean(KEY_VERBOSE_LOGGING, fallback);
            if (!watching) {
                watching = true;
                mod.registerOnSharedPreferenceChangeListener(LISTENER);
            }
            if (enabled) {
                SponsorBlockSettings.onLogGateOpened();
            }
        } catch (Throwable t) {
            enabled = false;
        }
    }

    /**
     * The build's default, from the patch's "Verbose diagnostics (development only)" option: a
     * development build logs from its first line without anybody tapping the row, and a build that
     * never asked for the diagnostics stays silent.
     *
     * It re-reads rather than only remembering the flag, because this call and {@link #init} are
     * inserted into Application.onCreate by two different patches in whichever order they are
     * applied (1.88.10: the SponsorBlock patch's call lands first inside the method): if init ran
     * with the fallback still false, this call has to be able to open the gate afterwards. A stored
     * row value wins over the default, so a viewer's own choice survives a development build.
     */
    public static void setDefaultEnabled(boolean value) {
        fallback = value;
        SharedPreferences mod = preferences;
        if (mod != null) {
            try {
                enabled = mod.getBoolean(KEY_VERBOSE_LOGGING, value);
            } catch (Throwable t) {
                // Keep the answer the last read produced; a gate that cannot be read stays closed.
            }
        }
        if (enabled) {
            SponsorBlockSettings.onLogGateOpened();
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
     * value up wherever the write came from. No restart, no re-read on the log path. The build's
     * default is the fallback, so clearing the row returns the gate to what the build asked for.
     */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        if (KEY_VERBOSE_LOGGING.equals(key)) {
            enabled = preferences.getBoolean(KEY_VERBOSE_LOGGING, fallback);
            if (enabled) {
                SponsorBlockSettings.onLogGateOpened();
            }
        }
    }
}
