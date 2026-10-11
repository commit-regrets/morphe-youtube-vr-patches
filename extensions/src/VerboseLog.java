package app.ytvr.extension;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /** This class's own lines (the file list) carry the store's tag, like the rest of the store. */
    private static final String TAG = "SponsorBlock";

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
            if (fallback) {
                // Development builds only, and only while the gate is open: the *names* of the
                // app's preference files. Measured need -- a switch on these screens can show a new
                // state while the file this class reads never receives the key, so knowing which
                // files exist is the difference between guessing where it went and looking.
                logPreferenceFiles(appContext);
                logYtvrKeyLocations(appContext);
            }
        } catch (Throwable t) {
            enabled = false;
        }
    }

    /** Lists the app's preference files (names only) into the log, in a development build. */
    private static void logPreferenceFiles(Context appContext) {
        try {
            String[] names = new File(appContext.getDataDir(), "shared_prefs").list();
            log(TAG, "preference files: "
                + (names == null || names.length == 0 ? "(none)" : String.join(", ", names)));
        } catch (Throwable t) {
            log(TAG, "preference files unreadable: " + t);
        }
    }

    /**
     * In a development build: which preference file holds the keys this mod reads and writes.
     * Measured need -- the screens set their storage name to "youtube" and this extension reads
     * that same file now, so this line is the cross-check that the mod's keys really are where
     * both writers put them. Key *names* only; values stay unknown to the log.
     */
    private static void logYtvrKeyLocations(Context appContext) {
        try {
            String[] names = new File(appContext.getDataDir(), "shared_prefs").list();
            if (names == null) {
                return;
            }
            for (String name : names) {
                if (!name.endsWith(".xml")) {
                    continue;
                }
                Map<String, ?> all = appContext
                    .getSharedPreferences(name.substring(0, name.length() - 4), 0).getAll();
                List<String> ours = new ArrayList<>();
                for (String key : all.keySet()) {
                    if (key.startsWith("ytvr_mod")) {
                        ours.add(key);
                    }
                }
                if (!ours.isEmpty()) {
                    log(TAG, "ytvr_mod keys in " + name + ": " + String.join(", ", ours));
                }
            }
        } catch (Throwable t) {
            log(TAG, "ytvr key scan unreadable: " + t);
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
     * The viewer's own choice, applied by the row that owns the switch ({@link CategoryRows}): the
     * value is persisted by {@link SponsorBlockSettings#setVerboseLogging} and the gate follows
     * here, without waiting for the preference listener. Opening it also flushes the settings dump
     * if that was built while the gate was still shut.
     */
    public static void setEnabled(boolean value) {
        enabled = value;
        if (value) {
            SponsorBlockSettings.onLogGateOpened();
        }
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
     * The echo of the row's own tap, printed through the gate **deliberately bypassed**: it is the
     * confirmation of a tap that by definition changes the gate, so gating it would swallow half
     * of what it reports. Measured 2026-10-11: a tap ON logs while the gate is still shut, and a
     * tap OFF loses the race against this class's own preference listener, which closes the gate
     * from the disk-write thread before the caller reaches its log call -- the value landed both
     * times, the line never printed. This is the only ungated print in the extension.
     */
    public static void logGateToggle(boolean value) {
        System.out.println(TAG + ": verbose logging set: " + value);
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
