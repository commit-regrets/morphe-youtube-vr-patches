package app.ytvr.extension;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * How much this port has skipped, kept in the app's own preference file so it survives a restart.
 *
 * Deliberately local only: upstream's stats screen also shows server-side numbers (reputation,
 * submissions, people saved), but those need a private user ID and a network call per skip, and
 * neither belongs in a port whose whole SponsorBlock story is "skip what the crowd labelled".
 *
 * The counters are written on every confirmed skip, which is a rare event (a few per video), so the
 * cost is one `apply()` on the preference file the app already has open.
 */
public final class SponsorBlockStats {

    private static final String TAG = "SponsorBlock";

    /** Segments skipped, and milliseconds of them actually skipped past. */
    public static final String KEY_SEGMENTS = "ytvr_mod_sb_stats_segments";
    public static final String KEY_MILLIS = "ytvr_mod_sb_stats_millis";

    private static volatile Integer sessionSegments;
    private static volatile Long sessionMillis;

    private SponsorBlockStats() {
    }

    /**
     * Counts one skipped segment. [savedMs] is the part of it the viewer did not watch, which is the
     * honest number: a skip that lands near the segment's end saves almost nothing.
     */
    public static void recordSkip(long savedMs, String category) {
        try {
            long saved = Math.max(0L, savedMs);
            Context context = SponsorBlockSettings.appContext();
            if (context == null) {
                return;
            }
            SharedPreferences mod = SponsorBlockSettings.modPreferences(context);
            long segments = mod.getLong(KEY_SEGMENTS, 0L) + 1L;
            long millis = mod.getLong(KEY_MILLIS, 0L) + saved;
            mod.edit().putLong(KEY_SEGMENTS, segments).putLong(KEY_MILLIS, millis).apply();
            log("stats: skipped " + segments + " segment(s), " + (millis / 1000L)
                + "s saved (this one " + (saved / 1000L) + "s, " + category + ")");
        } catch (Throwable t) {
            log("stats failed: " + t);
        }
    }

    /** The row's summary: what has been skipped so far, or that nothing has. */
    public static String summary() {
        try {
            Context context = SponsorBlockSettings.appContext();
            if (context == null) {
                return "no app context yet";
            }
            SharedPreferences mod = SponsorBlockSettings.modPreferences(context);
            long segments = mod.getLong(KEY_SEGMENTS, 0L);
            long millis = mod.getLong(KEY_MILLIS, 0L);
            sessionSegments = Integer.valueOf((int) Math.min(segments, Integer.MAX_VALUE));
            sessionMillis = Long.valueOf(millis);
            if (segments <= 0L) {
                return "Nothing skipped yet";
            }
            return "Skipped " + segments + " segment(s), " + duration(millis) + " saved";
        } catch (Throwable t) {
            return "stats unavailable";
        }
    }

    /** "18 min 20 s", "42 s" -- small enough to sit under a settings row. */
    private static String duration(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        if (hours > 0L) {
            return hours + " h " + (minutes % 60L) + " min";
        }
        if (minutes > 0L) {
            return minutes + " min " + (seconds % 60L) + " s";
        }
        return seconds + " s";
    }

    /** The last values read, for the log. */
    public static String sessionSummary() {
        Integer segments = sessionSegments;
        Long millis = sessionMillis;
        if (segments == null || millis == null) {
            return "not read";
        }
        return segments + " segment(s), " + duration(millis.longValue());
    }

    /** Through the one gate: a build whose "Verbose logging" row is untouched prints nothing. */
    private static void log(String message) {
        VerboseLog.log(TAG, message);
    }
}
