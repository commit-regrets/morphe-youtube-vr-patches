package app.ytvr.extension;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;

/**
 * SponsorBlock configuration: the patch's baked-in defaults, overridden by whatever the user
 * changed in the in-app "Patch settings" panel.
 *
 * <p>The patch options are still the source of the defaults (API base, one behaviour per category),
 * written into the injected {@code const-string} pair at Application.onCreate and parsed once.
 * {@link #initContext(Context)} then layers the user's own choices on top from SharedPreferences,
 * so a change in the panel takes effect on the next video without a rebuild. Anything unparsable
 * falls back to the documented defaults with a log line, never an exception into the app.
 *
 * <p>Behaviours are the phone patch's five, with one mapping: {@code skip}, {@code skip-once},
 * {@code seekbar-only} and {@code ignore} are implemented as themselves, and {@code manual-skip}
 * ("Show a Skip button") is stored as {@code seekbar-only} because this client has no skip button
 * to show. That is not a loss of intent -- both mean "do not seek for me, just show me where it is"
 * -- it is the closest this player can get to the phone behaviour, and it is what the four of
 * upstream's ten category defaults that use {@code manual-skip} become here.
 *
 * <p>The distinction that matters is therefore {@link #autoSkips}: only {@code skip} and {@code
 * skip-once} move the playhead. Any gate that asks "is this not ignore?" would wrongly auto-skip a
 * {@code seekbar-only} segment, so the gates ask this instead.
 */
public final class SponsorBlockSettings {

    private static final String TAG = "SponsorBlock";

    /** The API base the phone patch ships with. */
    private static final String DEFAULT_API = "https://sponsor.ajay.app";

    /** Keys of the rows this patch adds to the app's own settings screen. */
    private static final String KEY_MOD_ENABLED = "ytvr_mod_sb_enabled";

    private static final String KEY_MOD_MARKERS = "ytvr_mod_sb_markers";

    /**
     * The behaviour pickers' key prefix, and the prefix the per-category switches used before them.
     * They are different on purpose: a `ListPreference` reads its key with `getString`, and
     * `SharedPreferencesImpl.getString` throws when a Boolean is stored there -- so reusing the
     * switch's key made opening the screen crash with `Boolean cannot be cast to String`. The old
     * values are migrated into the new keys at start-up (see {@link #migrateLegacySwitches}).
     */
    private static final String KEY_CATEGORY = "ytvr_mod_sb_behaviour_";

    private static final String KEY_LEGACY_CATEGORY = "ytvr_mod_cat_";
    private static final String KEY_SKIP_ONCE = "ytvr_mod_sb_skip_once";

    /** The minimum-segment-duration row's key (a SeekBarPreference, so an int). */
    private static final String KEY_MIN_DURATION = "ytvr_mod_sb_min_duration";

    /**
     * The switch keys as (key, title), only for the start-up log: it says which categories the
     * screen has switched on, which is otherwise invisible without a screenshot.
     */
    private static final String[][] CATEGORY_KEYS = {
        {"sponsor"}, {"selfpromo"}, {"interaction"}, {"poi_highlight"}, {"intro"},
        {"outro"}, {"preview"}, {"hook"}, {"filler"}, {"music_offtopic"},
    };

    private static volatile String apiBase = DEFAULT_API;
    private static final Map<String, String> BEHAVIOURS = new HashMap<String, String>();
    private static volatile boolean configured;
    private static volatile boolean enabled = true;

    /** The seek-bar markers, which are this port's own addition, not the phone patch's. */
    private static volatile boolean markersEnabled = true;

    private static volatile Context context;

    /**
     * True once {@link #initContext} has finished its migrations: the dump reports their result.
     */
    private static volatile boolean storeReady;

    /** True once the start-up dump has actually been printed, so a later gate cannot repeat it. */
    private static volatile boolean dumpLogged;

    private SponsorBlockSettings() {}

    /**
     * Called once at Application.onCreate from a patch-injected invoke-static, with the option
     * values baked into the dex as string constants.
     *
     * @param apiBase e.g. https://sponsor.ajay.app (a self-hosted instance works)
     * @param packedCategories {@code category=behaviour} pairs joined by {@code ;}, e.g. {@code
     *     sponsor=skip-once;selfpromo=skip;...}
     */
    public static void configure(String apiBase, String packedCategories) {
        try {
            String base = apiBase == null ? "" : apiBase.trim();
            if (!base.startsWith("https://")) {
                log("ignoring api base '" + base + "': not https, using " + DEFAULT_API);
                base = DEFAULT_API;
            }
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            SponsorBlockSettings.apiBase = base;

            synchronized (BEHAVIOURS) {
                BEHAVIOURS.clear();
                if (packedCategories != null) {
                    for (String pair : packedCategories.split(";")) {
                        int eq = pair.indexOf('=');
                        if (eq <= 0) {
                            continue;
                        }
                        String category = pair.substring(0, eq).trim();
                        String behaviour = pair.substring(eq + 1).trim();
                        if (isBehaviour(behaviour)) {
                            BEHAVIOURS.put(category, normaliseBehaviour(behaviour));
                        } else {
                            log(
                                    "ignoring category '"
                                            + category
                                            + "': unknown behaviour '"
                                            + behaviour
                                            + "'");
                        }
                    }
                }
            }
            configured = true;
            log("configured: api=" + base + " categories=" + packedCategories);
        } catch (Throwable t) {
            log("configure failed: " + t);
        }
    }

    /**
     * The behaviour for a category: one of "skip", "skip-once", "seekbar-only" or "ignore".
     *
     * <p>Three sources, in this order, because the screen has changed shape twice:
     *
     * <ol>
     *   <li>the per-category **picker** (a `ListPreference`, so androidx persisted a String);
     *   <li>the older per-category **switch**, read together with the global "skip each segment
     *       only once" switch -- on became skip (or skip-once when that switch was on), off became
     *       ignore, so choices made with that screen are not lost;
     *   <li>the behaviour baked into the build by the patch's options.
     * </ol>
     */
    public static String behaviour(String category) {
        return resolveBehaviour(
                modString(KEY_CATEGORY + category),
                modBoolean(KEY_LEGACY_CATEGORY + category),
                modBoolean(KEY_SKIP_ONCE),
                BEHAVIOURS.get(category));
    }

    /**
     * The precedence itself, as a pure function: the picker's String, then the old switch pair,
     * then the baked default. Separated from the store so it can be checked without a device (the
     * fake-Context route does not work -- `android.jar`'s `ContextWrapper` constructor is a stub).
     */
    static String resolveBehaviour(
            String picked, Boolean legacySwitch, Boolean legacyOnce, String baked) {
        if (picked != null && isBehaviour(picked)) {
            return normaliseBehaviour(picked);
        }
        if (legacySwitch != null) {
            if (!legacySwitch) {
                return "ignore";
            }
            return legacyOnce != null && legacyOnce ? "skip-once" : "skip";
        }
        return baked == null ? "ignore" : baked;
    }

    /**
     * Records what the picker chose. The picker lives in {@link CategoryRows} and writes through
     * here so the key and its normalisation have exactly one owner, and so the write lands in the
     * same file (and under the same name) every reader uses.
     */
    public static void setBehaviour(String category, String behaviour) {
        try {
            SharedPreferences mod = modPreferences();
            if (mod == null) {
                log("behaviour not saved: no app context");
                return;
            }
            String value = normaliseBehaviour(behaviour);
            mod.edit().putString(KEY_CATEGORY + category, value).apply();
            log("behaviour set: " + category + "=" + value);
        } catch (Throwable t) {
            log("behaviour write failed: " + t);
        }
    }

    /**
     * Records the "Verbose logging" row's choice, in the same file and under the same key the gate
     * reads.
     *
     * <p>The row is written *here* rather than by its own `SwitchPreference`, because that is the
     * one path the gate is guaranteed to see: measured on the device, a plain androidx switch on
     * these screens flips to ON while `ytvr_mod_verbose_logging` never appears in this file at all
     * -- the extension printed `patch settings (...): the screen's switches are not there yet` on a
     * build whose row read ON, across an update install that preserved app data. {@link
     * CategoryRows} owns the row and calls this on a tap, which is also what the behaviour pickers
     * already do.
     */
    static void setVerboseLogging(boolean on) {
        try {
            SharedPreferences mod = modPreferences();
            if (mod == null) {
                log("verbose logging not saved: no app context");
                return;
            }
            mod.edit().putBoolean(VerboseLog.KEY_VERBOSE_LOGGING, on).apply();
            // No log here: the tap's echo is VerboseLog.logGateToggle, printed ungated by the row
            // before it calls this. A gated log at this point doubles the ON echo (the write's own
            // preference listener has already opened the gate) and is swallowed on OFF.
        } catch (Throwable t) {
            log("verbose logging write failed: " + t);
        }
    }

    /**
     * The baked defaults moved from this port's first set (five categories auto-skipping) to
     * upstream's map, where only sponsor skips by itself.
     *
     * <p>A row the viewer has already chosen keeps its value -- but androidx *persisted* the old
     * default into the pref file the first time the screen was opened, so every untouched row is
     * indistinguishable from a deliberate choice. Without this step the new defaults would never be
     * seen by anyone who had opened the screen. A stored value that still equals the old default is
     * therefore moved to the new one, and every move is logged so it can be undone in the picker.
     */
    private static final String[][] DEFAULT_MOVES = {
        {"sponsor", "skip", "skip-once"},
        {"selfpromo", "skip", "seekbar-only"},
        {"interaction", "skip", "seekbar-only"},
        {"intro", "skip", "seekbar-only"},
        {"outro", "skip", "seekbar-only"},
        {"music_offtopic", "ignore", "seekbar-only"},
        {"poi_highlight", "ignore", "seekbar-only"},
    };

    private static void migrateOldDefaults() {
        try {
            SharedPreferences mod = modPreferences();
            if (mod == null) {
                return;
            }
            SharedPreferences.Editor editor = null;
            StringBuilder moved = new StringBuilder();
            for (String[] move : DEFAULT_MOVES) {
                String key = KEY_CATEGORY + move[0];
                String stored = modString(key);
                if (stored == null || !stored.equals(move[1])) {
                    continue;
                }
                if (editor == null) {
                    editor = mod.edit();
                }
                editor.putString(key, move[2]);
                moved.append(' ')
                        .append(move[0])
                        .append(' ')
                        .append(move[1])
                        .append(" -> ")
                        .append(move[2]);
            }
            if (editor != null) {
                editor.apply();
                log("moved the rows still on the old defaults:" + moved);
            }
        } catch (Throwable t) {
            log("default migration failed: " + t);
        }
    }

    /**
     * Segments shorter than this are ignored (0 = keep everything). Highlight is exempt, as
     * upstream exempts it. The row is a `SeekBarPreference`, so androidx persisted an int.
     */
    public static long minSegmentDurationMs() {
        Integer seconds = modInt(KEY_MIN_DURATION);
        if (seconds == null || seconds.intValue() <= 0) {
            return 0L;
        }
        return seconds.longValue() * 1000L;
    }

    /** The app context, for the classes that keep their own keys in the same preference file. */
    public static Context appContext() {
        return context;
    }

    /** An int from the app's own preference file, or null when it (or the file) is absent. */
    private static Integer modInt(String key) {
        try {
            SharedPreferences mod = modPreferences();
            if (mod == null || !mod.contains(key)) {
                return null;
            }
            Object value = mod.getAll().get(key);
            return value instanceof Integer ? (Integer) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** A String from the app's own preference file, or null when it (or the file) is absent. */
    private static String modString(String key) {
        try {
            SharedPreferences mod = modPreferences();
            if (mod == null || !mod.contains(key)) {
                return null;
            }
            Object value = mod.getAll().get(key);
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** A value from the app's own preference file, or null when it (or the file) is absent. */
    private static Boolean modBoolean(String key) {
        try {
            SharedPreferences mod = modPreferences();
            if (mod == null || !mod.contains(key)) {
                return null;
            }
            return mod.getBoolean(key, false);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The file the settings screens persist to. Measured 2026-10-11: the screens' fragment sets its
     * preference storage name to "youtube" before inflating (fyp's `g("youtube")`), so every widget
     * row this patch adds commits there — the same file this extension must read and write. Earlier
     * builds read `<package>_preferences`, a file nothing ever wrote. Package-private so the
     * Default video quality extension reads the same file (VideoQuality.java).
     */
    static SharedPreferences modPreferences() {
        return modPreferences(context);
    }

    /**
     * The same file for a caller that has a context of its own: {@link VerboseLog} reads the log
     * row at Application.onCreate, where the order against {@link #initContext} is the patch's
     * business rather than this class's. The file's name stays in one place, which is what keeps
     * the two readers on the same file.
     */
    static SharedPreferences modPreferences(Context appContext) {
        if (appContext == null) {
            return null;
        }
        return appContext.getSharedPreferences("youtube", Context.MODE_PRIVATE);
    }

    /**
     * Called at app start (patch-injected) with the Application context. The patch's baked defaults
     * were parsed before this, and the settings screen's switches are read from the app's own
     * preference file on demand, so there is nothing to load here beyond the context.
     */
    public static void initContext(Context appContext) {
        try {
            context = appContext == null ? null : appContext.getApplicationContext();
            // Before the first log line below: the gate has to know the row's value, and this call
            // cannot assume the Extension patch's own init ran first (both are injected into the
            // same Application.onCreate). VerboseLog.init is idempotent, so this costs nothing when
            // it did.
            VerboseLog.init(context);
            migrateLegacySwitches();
            migrateOldDefaults();
            // Only now is the store ready for the dump: it reports the post-migration state, so the
            // dump must not be emitted before this point even by a gate that opened first (see
            // onLogGateOpened).
            storeReady = true;
            onLogGateOpened();
        } catch (Throwable t) {
            log("settings store failed: " + t);
        }
    }

    /**
     * Prints the start-up dump unless it has already been printed, and only with the gate open.
     *
     * <p>Called at the end of {@link #initContext} and from {@link VerboseLog} every time its gate
     * opens, because the two injected calls at Application.onCreate come from two different patches
     * and either may run first. In 1.88.10 the order inside the method is the SponsorBlock block
     * (configure, initContext) and *then* the Extension block (the diagnostics default, init), so a
     * development build would otherwise build the dump while the gate was still closed and never
     * print the one line that says what the settings screen is set to.
     */
    static void onLogGateOpened() {
        if (dumpLogged || !storeReady || !VerboseLog.enabled()) {
            return;
        }
        dumpLogged = true;
        logModSettings();
    }

    /**
     * Writes the old per-category switches into the behaviour keys, once, for any category that has
     * no picker value yet.
     *
     * <p>Reading the old switches would not be enough: a `ListPreference` *persists* its own
     * default the first time the screen is bound, so a user who had switched a category off would
     * have that choice silently replaced by the build's default the moment they opened the screen.
     * Migrating first means the picker finds -- and displays -- what they actually chose.
     */
    private static void migrateLegacySwitches() {
        SharedPreferences mod = modPreferences();
        if (mod == null) {
            return;
        }
        SharedPreferences.Editor editor = null;
        for (String[] category : CATEGORY_KEYS) {
            String key = KEY_CATEGORY + category[0];
            if (mod.contains(key)) {
                continue;
            }
            Boolean legacy = modBoolean(KEY_LEGACY_CATEGORY + category[0]);
            if (legacy == null) {
                continue;
            }
            if (editor == null) {
                editor = mod.edit();
            }
            editor.putString(
                    key,
                    resolveBehaviour(
                            null, legacy, modBoolean(KEY_SKIP_ONCE), BEHAVIOURS.get(category[0])));
        }
        if (editor != null) {
            editor.apply();
            log("migrated the old per-category switches into behaviour values");
        }
    }

    /**
     * What the Patch settings screen (the app's own list) currently says. This is the one line that
     * says whether the screen's switches reached the feature, without a screenshot.
     */
    private static void logModSettings() {
        try {
            SharedPreferences mod = modPreferences();
            if (mod == null) {
                log("patch settings: no app context");
                return;
            }
            StringBuilder text = new StringBuilder("patch settings (youtube.xml): ");
            Boolean on = modBoolean(KEY_MOD_ENABLED);
            Boolean markers = modBoolean(KEY_MOD_MARKERS);
            Boolean once = modBoolean(KEY_SKIP_ONCE);
            // The log gate's own row, reported like the rest: this line is the only one that says
            // what the screen is set to, and it is itself gated, so it has to say whether the gate
            // it is behind is open.
            Boolean verbose = modBoolean(VerboseLog.KEY_VERBOSE_LOGGING);
            if (on == null && markers == null && verbose == null) {
                text.append("the screen's switches are not there yet");
            } else {
                text.append("sponsorblock=").append(on == null ? "(default)" : on);
                text.append(" markers=").append(markers == null ? "(default)" : markers);
                text.append(" skipOnce=").append(once == null ? "(default)" : once);
                text.append(" verboseLogging=").append(verbose == null ? "(default)" : verbose);
            }
            int enabled_categories = 0;
            StringBuilder effective = new StringBuilder();
            for (String[] category : CATEGORY_KEYS) {
                Boolean value = modBoolean(KEY_LEGACY_CATEGORY + category[0]);
                if (value != null && value) {
                    enabled_categories++;
                }
                effective
                        .append(' ')
                        .append(category[0])
                        .append('=')
                        .append(behaviour(category[0]));
            }
            text.append(", ").append(enabled_categories).append(" switch(es) on");
            // The effective behaviours, which is what the player will actually do -- the one line
            // that says whether the pickers reached the feature, without a screenshot.
            text.append("; effective:").append(effective);
            log(text.toString());
            // The raw stored picker values, next to the effective ones: the two differ whenever a
            // row has no stored value yet (the baked default shows through), and a row whose
            // displayed subtitle disagrees with this line is a rendering question, not a settings
            // one.
            StringBuilder stored = new StringBuilder();
            for (String[] category : CATEGORY_KEYS) {
                stored.append(' ')
                        .append(category[0])
                        .append('=')
                        .append(
                                modString(KEY_CATEGORY + category[0]) == null
                                        ? "(none)"
                                        : modString(KEY_CATEGORY + category[0]));
            }
            log("stored picker values:" + stored);
            log(
                    "stats: "
                            + SponsorBlockStats.sessionSummary()
                            + "; minimum segment duration: "
                            + (minSegmentDurationMs() / 1000L)
                            + "s");
        } catch (Throwable t) {
            log("patch settings log failed: " + t);
        }
    }

    /** The master switch from the app's own settings screen. */
    public static boolean enabled() {
        Boolean mod = modBoolean(KEY_MOD_ENABLED);
        return mod == null ? enabled : mod;
    }

    /** Whether the seek-bar markers are drawn (this port's own switch on that screen). */
    public static boolean markersEnabled() {
        Boolean mod = modBoolean(KEY_MOD_MARKERS);
        return mod == null ? markersEnabled : mod;
    }

    /** Whether any category would actually move the playhead. */
    public static boolean anySkipping() {
        synchronized (BEHAVIOURS) {
            for (String behaviour : BEHAVIOURS.values()) {
                if (autoSkips(behaviour)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether this behaviour seeks past the segment. The one predicate every skip gate must use:
     * `seekbar-only` is a live segment that is drawn and never skipped, so "not ignore" is the
     * wrong test.
     */
    public static boolean autoSkips(String behaviour) {
        return "skip".equals(behaviour) || "skip-once".equals(behaviour);
    }

    /** Whether this behaviour draws a seek-bar marker. Everything except `ignore` does. */
    public static boolean marks(String behaviour) {
        return behaviour != null && !"ignore".equals(behaviour);
    }

    public static String apiBase() {
        return apiBase;
    }

    /** True once configure() ran; hooks before app start do nothing otherwise. */
    public static boolean isConfigured() {
        return configured;
    }

    private static boolean isBehaviour(String value) {
        return "skip".equals(value)
                || "skip-once".equals(value)
                || "seekbar-only".equals(value)
                || "manual-skip".equals(value)
                || "ignore".equals(value);
    }

    /**
     * The behaviour this client can actually run. Only `manual-skip` changes meaning: it asks for a
     * Skip button, this player has none, so it becomes the behaviour that shows the segment without
     * moving the playhead -- which is what a viewer who would have pressed that button wants
     * anyway.
     */
    private static String normaliseBehaviour(String value) {
        return "manual-skip".equals(value) ? "seekbar-only" : value;
    }

    /** Through the one gate: a build whose "Verbose logging" row is untouched prints nothing. */
    private static void log(String message) {
        VerboseLog.log(TAG, message);
    }
}
