package app.ytvr.extension;

import java.lang.reflect.Method;

/**
 * The app-facing half of the SponsorBlock adapter: reflects into the captured
 * VR player wrapper ({@code pwe}) and calls the three bridge methods the patch
 * generated on it.
 *
 * This is the port of the phone patch's adapter boundary: the controller never
 * touches an obfuscated app member, it asks {@link SponsorBlockApi} for the
 * playhead, the duration and the seek. The obfuscation map touches exactly one
 * file — this one — and even it names nothing obfuscated: the bridges
 * ({@code YtvrSbGetPositionMs}, {@code YtvrSbGetDurationMs}, {@code YtvrSbSeekTo})
 * are added to the wrapper at patch time, their bodies built from the bytecode of
 * the app's own time-info snapshot method and seek method, so a version bump that
 * renames the underlying accessors still produces working bridges as long as the
 * patch's fingerprints match. The bridge names are ours, added after R8, so they
 * cannot be renamed out from under the reflection.
 *
 * The bridge bodies call the same player-backend accessors the app's own UI
 * poller calls at 100 ms on the main thread; the controller's tick runs on the
 * main looper too, so the access is exactly as permitted as the app's own.
 */
public final class YtvrSponsorBlockHost implements SponsorBlockApi {

    private static final String TAG = "SponsorBlock";

    public static final YtvrSponsorBlockHost INSTANCE = new YtvrSponsorBlockHost();

    private static final String POSITION_BRIDGE = "YtvrSbGetPositionMs";
    private static final String DURATION_BRIDGE = "YtvrSbGetDurationMs";
    private static final String SEEK_BRIDGE = "YtvrSbSeekTo";

    private static volatile Object player;
    private static volatile Method positionMethod;
    private static volatile Method durationMethod;
    private static volatile Method seekMethod;
    private static volatile boolean bridgesReportedMissing;

    private YtvrSponsorBlockHost() {
    }

    /**
     * Called by the controller when the player wrapper is handed over by the
     * loadVideo hook. Resolves the bridge methods once per player class.
     */
    public void attach(Object newPlayer) {
        player = newPlayer;
        if (newPlayer != null && positionMethod == null) {
            resolve(newPlayer.getClass());
        }
    }

    private static synchronized void resolve(Class<?> type) {
        try {
            Method position = type.getMethod(POSITION_BRIDGE);
            Method duration = type.getMethod(DURATION_BRIDGE);
            Method seek = type.getMethod(SEEK_BRIDGE, long.class);
            position.setAccessible(true);
            duration.setAccessible(true);
            seek.setAccessible(true);
            positionMethod = position;
            durationMethod = duration;
            seekMethod = seek;
            log("bridges resolved on " + type.getName());
        } catch (Throwable t) {
            positionMethod = null;
            durationMethod = null;
            seekMethod = null;
            if (!bridgesReportedMissing) {
                bridgesReportedMissing = true;
                log("bridge methods missing on " + type.getName() + ": " + t
                    + " — the installed app was not patched by this SponsorBlock build;"
                    + " skipping is disabled");
            }
        }
    }

    public long position() {
        Object current = player;
        Method method = positionMethod;
        if (current == null || method == null) {
            return -1L;
        }
        try {
            return (Long) method.invoke(current);
        } catch (Throwable t) {
            return -1L;
        }
    }

    public long duration() {
        Object current = player;
        Method method = durationMethod;
        if (current == null || method == null) {
            return -1L;
        }
        try {
            return (Long) method.invoke(current);
        } catch (Throwable t) {
            return -1L;
        }
    }

    public boolean seekTo(long positionMs) {
        Object current = player;
        Method method = seekMethod;
        if (current == null || method == null) {
            return false;
        }
        try {
            method.invoke(current, Long.valueOf(positionMs));
            return true;
        } catch (Throwable t) {
            log("seek " + positionMs + " failed: " + t);
            return false;
        }
    }

    /** Through the one gate: a build whose "Verbose logging" row is untouched prints nothing. */
    private static void log(String message) {
        VerboseLog.log(TAG, message);
    }
}
