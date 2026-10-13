package app.ytvr.extension;

import android.content.SharedPreferences;
import java.lang.reflect.Method;

/**
 * Default video quality: the resolution every video starts at, instead of the app's own per-video
 * choice.
 *
 * <p>The patch injects two calls: {@code configure(String)} at Application.onCreate with the build
 * option's seed, and {@link #fixInitialResolution(Object)} in front of the store of {@code
 * initialPlaybackVideoQualityFixedResolution} in the player's PlaybackStartParameters constructor
 * (measured: {@code saj.<init>}, the only construction site, through the {@code sai} builder). The
 * setting on the Patch settings hub ({@code ytvr_mod_video_quality} in youtube.xml) wins over the
 * seed; the seed exists so a build configured before the headset was ever used already carries its
 * option's value.
 *
 * <p><b>The contract with the app, both halves measured in the 1.88.10 dex.</b> The field is a
 * {@code j$.util.Optional}, so the injected call has to hand back one of those: the patch emits a
 * {@code check-cast} after it, because ART's verifier rejects a store of a plain {@code
 * java.lang.Object} into the field ({@code VerifyError: register v6 has type Reference:
 * java.lang.Object but expected Precise Reference: j$.util.Optional}, which crashed the app the
 * moment a video started). And the value inside is an {@code Integer}: the builder stores {@code
 * Optional.of(Integer.valueOf(i))} ({@code sai.c(int)}) and the only reader takes it back out as
 * {@code ((Integer) optional.get()).intValue()} ({@code saj.b(saj)}).
 *
 * <p>A resolution the video does not have makes the app fall back to that video's highest available
 * format -- the behaviour the phone patch documents -- so the hub's "Highest available" entry (and
 * "8K", which is the same ceiling) means 8K when the stream spoof provides itag 702 and otherwise
 * the best there is. The two entries are separate values rather than one so the row can show the
 * viewer's own choice back to them; nothing above 4320 exists to tell them apart today. "Auto" is a
 * passthrough, not a resolution.
 *
 * <p>j$.util.Optional is desugared into the app but absent from this extension's compile classpath,
 * so the incoming and replacement Optionals cross as opaque Objects and {@code Optional.of} is
 * reached reflectively, resolved once.
 */
public final class VideoQuality {

    private static final String TAG = "VideoQuality";

    /** The hub row's key in youtube.xml, shared with SettingsXml.kt. */
    private static final String KEY = "ytvr_mod_video_quality";

    /** "Highest available": the video's own best format. */
    public static final String HIGHEST = "highest";

    /** "Auto (per video)": passthrough, and also what the patch seeds for it. */
    public static final String AUTO = "auto";

    /**
     * What {@link #HIGHEST} and {@code "4320"} both resolve to: the tallest format YouTube serves.
     */
    private static final int HIGHEST_HEIGHT = 4320;

    /** The picker's options, in the order the in-row list shows them (see {@link #labelFor}). */
    public static final String[] ENTRY_LABELS = {
        "Highest available",
        "8K (4320p)",
        "4K (2160p)",
        "1440p",
        "1080p (Full HD)",
        "720p HD",
        "480p",
        "360p",
        "Auto (per video)",
    };

    /** One value per label, in the same order. These are what youtube.xml stores. */
    public static final String[] ENTRY_VALUES = {
        HIGHEST, "4320", "2160", "1440", "1080", "720", "480", "360", AUTO,
    };

    /**
     * Seed from the build option. Written by the patch's injected {@code sput-object} at
     * Application.onCreate, so it is public on purpose: the injected code lives in an app class.
     */
    public static volatile String buildDefault = HIGHEST;

    /** {@code j$.util.Optional::of}, resolved once. */
    private static volatile Method optionalOf;

    private VideoQuality() {}

    /** Injection point (Application.onCreate): the build option's seed. */
    public static void configure(String seed) {
        buildDefault = seed;
        log("build default: '" + seed + "'");
    }

    /**
     * The setting in force, as one of {@link #ENTRY_VALUES}. The first read writes the build
     * option's seed through, so the hub row shows the build's choice instead of its own fallback.
     */
    public static String currentSelection() {
        try {
            SharedPreferences mod = SponsorBlockSettings.modPreferences();
            String stored = mod == null ? null : mod.getString(KEY, null);
            if (stored == null) {
                String seed = seed();
                if (mod != null) {
                    mod.edit().putString(KEY, seed).apply();
                }
                log("no stored setting yet, seeded '" + seed + "'");
                return seed;
            }
            return stored;
        } catch (Throwable t) {
            log("reading the setting failed, using the build default: " + t);
            return seed();
        }
    }

    /** Records the viewer's pick in the file the row and the player both read. */
    public static void setSelection(String value) {
        try {
            SharedPreferences mod = SponsorBlockSettings.modPreferences();
            if (mod != null) {
                mod.edit().putString(KEY, value).apply();
            }
            log("setting: " + value + " ('" + labelFor(value) + "')");
        } catch (Throwable t) {
            log("writing the setting failed: " + t);
        }
    }

    /** The label for a stored value, so the row and the picker cannot disagree. */
    public static String labelFor(String value) {
        for (int index = 0; index < ENTRY_VALUES.length; index++) {
            if (ENTRY_VALUES[index].equals(value)) {
                return ENTRY_LABELS[index];
            }
        }
        // A value no entry offers (an older build's setting): say the raw value rather than show a
        // label that would be a lie.
        return value == null ? "" : value;
    }

    /**
     * Injection point (PlaybackStartParameters constructor). Returns the replacement for the
     * initial fixed resolution, or the incoming Optional unchanged for auto and on any failure.
     */
    public static Object fixInitialResolution(Object incoming) {
        try {
            String selection = currentSelection();
            if (AUTO.equals(selection)) {
                log("auto: keeping the app's own per-video choice");
                return incoming;
            }
            int height = heightFor(selection);
            log("starting videos at " + height + "p (setting '" + selection + "')");
            return optionalOf(height);
        } catch (Throwable t) {
            log("kept the app's own choice after a failure: " + t);
            return incoming;
        }
    }

    private static String seed() {
        return buildDefault == null ? HIGHEST : buildDefault;
    }

    /**
     * The height to request for a stored value. {@code highest} and every numeric entry are that
     * height; anything unparsable falls back to the highest, never to a lower one -- a setting that
     * cannot be read must not quietly downgrade the picture.
     */
    private static int heightFor(String value) {
        if (HIGHEST.equals(value)) {
            return HIGHEST_HEIGHT;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable t) {
            log("unusable setting '" + value + "', using " + HIGHEST_HEIGHT + "p: " + t);
            return HIGHEST_HEIGHT;
        }
    }

    private static Object optionalOf(int height) throws Exception {
        Method of = optionalOf;
        if (of == null) {
            of = Class.forName("j$.util.Optional").getMethod("of", Object.class);
            optionalOf = of;
        }
        // Autoboxed to Integer on purpose: that is what the app's own builder stores and what its
        // only reader casts the value back to (see the class comment).
        return of.invoke(null, height);
    }

    private static void log(String message) {
        VerboseLog.log(TAG, message);
    }
}
