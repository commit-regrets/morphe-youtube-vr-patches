package app.ytvr.extension;

import android.content.SharedPreferences;
import java.lang.reflect.Method;

/**
 * Default video quality: the resolution every video starts at, instead of the app's own per-video
 * choice.
 *
 * <p>The patch injects two calls: {@code configure(int)} at Application.onCreate with the build
 * option's seed, and {@link #fixInitialResolution(Object)} in front of the store of {@code
 * initialPlaybackVideoQualityFixedResolution} in the player's PlaybackStartParameters constructor
 * (measured: {@code saj.<init>}, the only construction site, through the {@code sai} builder). The
 * setting on the Patch settings hub ({@code ytvr_mod_video_quality} in youtube.xml) wins over the
 * seed; the seed exists so a build configured before the headset was ever used already carries its
 * option's value.
 *
 * <p>A resolution the video does not have makes the app fall back to that video's highest available
 * format -- the behaviour the phone patch documents -- so the hub's "Highest available" entry
 * (4320) means 8K when the stream spoof provides itag 702 and otherwise the best there is. "Auto"
 * is a passthrough, not a resolution.
 *
 * <p>j$.util.Optional is desugared into the app but absent from this extension's compile classpath,
 * so the incoming and replacement Optionals cross as opaque Objects and {@code Optional.of} is
 * reached reflectively, resolved once.
 */
public final class VideoQuality {

    private static final String TAG = "VideoQuality";

    /** The hub row's key in youtube.xml, shared with SettingsXml.kt. */
    private static final String KEY = "ytvr_mod_video_quality";

    /** "Auto (the app's own per-video choice)": passthrough, also what the patch seeds for it. */
    private static final int AUTO = -2;

    /**
     * Seed from the build option. Written by the patch's injected {@code sput} at
     * Application.onCreate, so it is public on purpose: the injected code lives in an app class.
     */
    public static volatile int buildDefaultHeight = 4320;

    /** {@code j$.util.Optional::of}, resolved once. */
    private static volatile Method optionalOf;

    private VideoQuality() {}

    /** Injection point (Application.onCreate): the build option's seed. */
    public static void configure(int seedHeight) {
        buildDefaultHeight = seedHeight;
        log("build default: " + seedHeight);
    }

    /**
     * Injection point (PlaybackStartParameters constructor). Returns the replacement for the
     * initial fixed resolution, or the incoming Optional unchanged for auto and on any failure.
     */
    public static Object fixInitialResolution(Object incoming) {
        try {
            int height = storedOrSeed();
            if (height == AUTO) {
                log("auto: keeping the app's own per-video choice");
                return incoming;
            }
            log("starting videos at " + height + "p");
            return optionalOf(height);
        } catch (Throwable t) {
            log("kept the app's own choice after a failure: " + t);
            return incoming;
        }
    }

    /**
     * The hub setting wins over the seed, and the first read writes the seed through, so the hub
     * row displays it instead of persisting its own XML default over it.
     */
    private static int storedOrSeed() {
        try {
            SharedPreferences mod = SponsorBlockSettings.modPreferences();
            String stored = mod == null ? null : mod.getString(KEY, null);
            if (stored == null) {
                int seed = buildDefaultHeight;
                if (mod != null) {
                    mod.edit().putString(KEY, String.valueOf(seed)).apply();
                }
                log("no stored setting yet, seeded " + seed);
                return seed;
            }
            if ("auto".equals(stored)) {
                return AUTO;
            }
            return Integer.parseInt(stored.trim());
        } catch (Throwable t) {
            log("reading the setting failed, using the seed: " + t);
            return buildDefaultHeight;
        }
    }

    private static Object optionalOf(int height) throws Exception {
        Method of = optionalOf;
        if (of == null) {
            of = Class.forName("j$.util.Optional").getMethod("of", Object.class);
            optionalOf = of;
        }
        return of.invoke(null, height);
    }

    private static void log(String message) {
        VerboseLog.log(TAG, message);
    }
}
