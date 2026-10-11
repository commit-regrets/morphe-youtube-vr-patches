package app.ytvr.extension;

import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The skip decision, ported from the phone patch's SegmentPlaybackController and
 * reduced to what survives without a UI.
 *
 * Drive model: the VR app has no periodic media-time callback, so this class runs
 * its own ~500 ms tick on the main looper and reads the playhead through the
 * host's bridge — the same accessors the app's own UI poller hits every 100 ms.
 * At each tick the controller picks the first not-yet-skipped segment whose range
 * contains the playhead and seeks to its end. Auto-skip fires on contact, exactly
 * like the phone build (it has no look-ahead rule either; its 1200 ms tolerance
 * exists for its scheduled-skip path, which a 500 ms tick makes unnecessary).
 *
 * Guards, all carried over from the phone controller:
 * - the ad gate: no skip while the app's own ad UI is counting down (the phone
 *   refuses to skip while its ad progress text is visible);
 * - the clamp: the seek target is clamped to duration - 250 ms, and a skip whose
 *   playhead and target both sit in the last 250 ms is aborted (the phone build's
 *   "refused seek" case, which is what makes a video change a non-event);
 * - the re-request guard: our seek returns void, so a segment is not re-requested
 *   while a seek is in flight — confirmed by the onSeeked hook, or expired after
 *   3 s;
 * - skip-once: a per-segment persistent flag survives a backwards seek; plain
 *   skip intentionally re-skips.
 *
 * Every decision logs with the SponsorBlock: prefix — on a head-mounted device
 * the logcat line is the only oracle for "no segment here" vs "the hook never ran".
 */
public final class SponsorSkipController {

    private static final String TAG = "SponsorBlock";

    /** The phone controller's end-of-video guard. */
    private static final long END_GUARD_MS = 250L;
    /** Tick period; the phone build works from a 1000 ms callback. */
    private static final long TICK_MS = 500L;
    /** A seek is re-requestable once it is neither confirmed nor in flight. */
    private static final long SEEK_CONFIRM_TIMEOUT_MS = 3000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Whether the ~500 ms tick loop is running (it starts once and never stops). */
    private static volatile boolean running;
    private static final SponsorBlockApi api = YtvrSponsorBlockHost.INSTANCE;

    private static void tickSafely() {
        try {
            tick();
        } catch (Throwable t) {
            log("tick failed: " + t);
        }
    }

    /** The self-rescheduling loop: exactly one posting exists at any time. */
    private static final Runnable TICK = new Runnable() {
        public void run() {
            tickSafely();
            if (running) {
                MAIN.postDelayed(TICK, TICK_MS);
            }
        }
    };

    /** One extra check outside the loop (used when a fetch lands mid-interval). */
    private static void checkNow() {
        MAIN.post(new Runnable() {
            public void run() {
                tickSafely();
            }
        });
    }

    /** Video state. */
    private static final Object SEGMENTS_LOCK = new Object();
    private static String pendingVideoId;
    private static String currentVideoId;
    private static List<SponsorSegment> segments;
    private static boolean fetchRequested;

    /** Tick bookkeeping. */
    private static long lastPosition = -1L;
    private static long durationMs = -1L;
    private static long adUiUntil;
    private static boolean adSuppressionLogged;
    /** True between onPlaying and onPaused/onStopped: the only state the tick polls in. */
    private static volatile boolean playing;
    private static boolean positionUnavailableLogged;

    /** In-flight skip: the target we are waiting for onSeeked to confirm. */
    private static long awaitedSeekTarget = -1L;
    /** Where the playhead was when that seek was requested, for the stats' saved-time number. */
    private static long awaitedSeekFrom = -1L;
    private static long awaitedSeekAt;
    /**
     * The segment whose seek is in flight. skip-once is marked on this only once
     * onSeeked confirms the landing — the phone build marks after its seek returns
     * true, and ours returns void, so confirmation is the only honest signal.
     */
    private static SponsorSegment pendingSkipSegment;

    /**
     * The segment a *seek* landed inside: it plays out, because dragging the seek bar
     * into a segment is the viewer asking for that part. Cleared the moment the
     * playhead leaves the segment in either direction, so dragging back before it
     * re-arms the skip — which is what the web extension does and what a viewer
     * expects from a seek bar.
     */
    private static SponsorSegment suppressedSegment;

    private SponsorSkipController() {
    }

    // ------------------------------------------------------------------
    // Hooked entry points. Every one catches Throwable: never throw into the app.
    // ------------------------------------------------------------------

    /**
     * Hooked at instruction 0 of the player-response constructor (the same site
     * the Extension patch uses): records which video is being prepared.
     *
     * Every response is recorded — content AND ad. The "skip responses with the
     * app's ad-marker list" idea from the first build is gone: on device the
     * *content* response itself carries a non-empty marker (m=1 observed while
     * playing Pc1JzImPw_M, 2026-10-10), so that gate silently discarded the only
     * response that matters. Fetching segments for an ad's id is harmless — the
     * server answers "no segments", and the content response overwrites the
     * pending id before loadVideo in the normal order.
     */
    public static void onPlayerResponse(Object playerResponse) {
        try {
            if (playerResponse == null || !SponsorBlockSettings.isConfigured()) {
                return;
            }
            String videoId = readVideoId(playerResponse);
            if (videoId == null) {
                if (!noIdReported) {
                    noIdReported = true;
                    log("player response carried no video id (id-shaped String scan found"
                        + " nothing) — segments for this video cannot be fetched");
                }
                return;
            }
            if (!videoId.equals(pendingVideoId)) {
                pendingVideoId = videoId;
                log("player response for " + videoId);
            }
        } catch (Throwable t) {
            log("onPlayerResponse failed: " + t);
        }
    }

    private static boolean noIdReported;

    /**
     * Hooked at instruction 0 of the player wrapper's loadVideo: the player hands
     * itself over, the video changes, so state resets and the fetch starts.
     */
    public static void onLoadVideo(Object player) {
        try {
            if (!SponsorBlockSettings.isConfigured()) {
                return;
            }
            api.attach(player);
            adUiUntil = 0L;
            awaitedSeekTarget = -1L;
            synchronized (SEGMENTS_LOCK) {
                if (pendingVideoId == null) {
                    // Always say so: this line is the difference between "no player
                    // response was seen" and "the hook never ran".
                    log("loadVideo with no known video id (was: "
                        + (currentVideoId == null ? "none" : currentVideoId)
                        + ") — no fetch, no skipping for this video");
                    return;
                }
                if (pendingVideoId.equals(currentVideoId)) {
                    // A re-prepare of the same video — the segments stay, but this is
                    // a NEW PLAYBACK, so the per-playback skip state must not. Without
                    // this reset, a sponsor segment marked skip-once stayed consumed
                    // for the rest of the app run: measured on device, the first watch
                    // skipped at 41.177 s while a second watch of the same video in the
                    // same process skipped nothing at all.
                    resetSkipStateForNewPlayback();
                    return;
                }
                segments = null;
                fetchRequested = false;
                String previous = currentVideoId;
                currentVideoId = pendingVideoId;
                pendingSkipSegment = null;
                lastPosition = -1L;
                durationMs = -1L;
                log("video " + currentVideoId + " (was: "
                    + (previous == null ? "none" : previous) + ")");
            }
            startTick();
        } catch (Throwable t) {
            log("onLoadVideo failed: " + t);
        }
    }

    /**
     * Hooked at instruction 0 of the player events' onSeeked: confirms our own
     * skip seek, or records a user seek (which invalidates any awaited target).
     */
    public static void onSeeked(long positionMs) {
        try {
            boolean ours = awaitedSeekTarget >= 0L
                && Math.abs(positionMs - awaitedSeekTarget) <= 5000L
                && System.currentTimeMillis() - awaitedSeekAt <= SEEK_CONFIRM_TIMEOUT_MS;
            if (awaitedSeekTarget >= 0L) {
                if (ours) {
                    log("seek confirmed at " + positionMs + " ms (target " + awaitedSeekTarget + ")");
                    SponsorSegment segment = pendingSkipSegment;
                    if (segment != null) {
                        // The honest saving is target - where the playhead was, not the segment's
                        // whole length: a skip that lands near its end saves almost nothing.
                        long saved = awaitedSeekTarget - awaitedSeekFrom;
                        if (saved > 0L) {
                            SponsorBlockStats.recordSkip(saved, segment.category);
                        }
                    }
                    if (segment != null
                        && "skip-once".equals(SponsorBlockSettings.behaviour(segment.category))) {
                        // Only now is it honest to consume skip-once: a refused or clamped
                        // seek leaves the segment skippable, which is what the phone build's
                        // boolean seek result buys it.
                        segment.didAutoSkipped = true;
                    }
                } else {
                    log("seek to " + awaitedSeekTarget + " not confirmed (landed at " + positionMs
                        + " ms) — segment stays skippable");
                }
                awaitedSeekTarget = -1L;
                pendingSkipSegment = null;
            }
            if (!ours) {
                noteSeekInto(positionMs);
            }
        } catch (Throwable t) {
            // never throw into the app
        }
    }

    /**
     * A seek we did not make (the viewer dragging the bar, or the app positioning
     * itself) landed at [positionMs]. If that is strictly inside a segment, that
     * segment plays out instead of being skipped; the tick clears this as soon as the
     * playhead leaves the segment, so seeking back before it skips again on approach.
     *
     * Measured expectation (from the web extension's behaviour): with a 2:00-2:50
     * segment, seeking to 1:55 skips when playback reaches 2:00, while seeking to 2:05
     * plays the rest of the segment.
     */
    private static void noteSeekInto(long positionMs) {
        List<SponsorSegment> current = segments;
        if (current == null) {
            return;
        }
        for (SponsorSegment segment : current) {
            if (positionMs > segment.startMs && positionMs < segment.endMs) {
                if (suppressedSegment != segment) {
                    suppressedSegment = segment;
                    log("segment plays (the playhead was seeked into it): " + segment);
                }
                return;
            }
        }
    }

    /**
     * Hooked at instruction 0 of the player events' onPlaying / onPaused /
     * onStopped. The tick polls the playhead only while this is true — polling
     * the native player during prepare is what the first build got wrong: the app
     * itself starts its 100 ms poller only on a playback-state transition, and a
     * prepare-time poll loop coincided with a prepare→retry loop on device
     * (loadVideo every ~500 ms = "keeps on loading").
     */
    public static void onPlaying() {
        try {
            if (!playing) {
                log("playing");
            }
            playing = true;
            positionUnavailableLogged = false;
        } catch (Throwable t) {
            // never throw into the app
        }
    }

    public static void onPaused() {
        try {
            playing = false;
        } catch (Throwable t) {
            // never throw into the app
        }
    }

    public static void onStopped() {
        try {
            playing = false;
        } catch (Throwable t) {
            // never throw into the app
        }
    }

    /**
     * Hooked in front of the ad-skip countdown ticks (the same two methods the
     * Extension patch reports through): an ad is on screen. The gate expires a
     * few seconds after the last tick so a paused countdown cannot block forever.
     */
    public static void onAdCountdown(int remainingMs) {
        try {
            adUiUntil = System.currentTimeMillis() + 4000L;
        } catch (Throwable t) {
            // never throw into the app
        }
    }

    // ------------------------------------------------------------------
    // The tick.
    // ------------------------------------------------------------------


    // ------------------------------------------------------------------
    // The tick.
    // ------------------------------------------------------------------

    private static void startTick() {
        if (running) {
            return;
        }
        running = true;
        MAIN.post(TICK);
    }

    private static void tick() {
        if (!SponsorBlockSettings.isConfigured() || !SponsorBlockSettings.enabled()
            || !SponsorBlockSettings.anySkipping()) {
            return;
        }
        if (System.currentTimeMillis() < adUiUntil) {
            if (!adSuppressionLogged) {
                adSuppressionLogged = true;
                log("skip suppressed: ad on screen");
            }
            return;
        }
        adSuppressionLogged = false;

        if (!playing) {
            return;
        }

        long position = api.position();
        if (position < 0L) {
            if (!positionUnavailableLogged) {
                positionUnavailableLogged = true;
                log("position unavailable (bridge returned -1) while playing");
            }
            return;
        }
        long duration = api.duration();
        if (duration > 0L) {
            durationMs = duration;
        }
        boolean playheadAdvanced = position != lastPosition;
        lastPosition = position;

        // Seek-bar bookkeeping, before the decisions: a segment we were seeked into plays
        // while the playhead stays inside it, and skip-once is per *pass*, so a playhead
        // that is behind a segment again gets that segment's skip back. Both are what the
        // web extension does; without them a second pass or a scrub-back did nothing.
        List<SponsorSegment> current = segments;
        if (current != null) {
            SponsorSegment suppressed = suppressedSegment;
            if (suppressed != null
                && (position < suppressed.startMs || position >= suppressed.endMs)) {
                suppressedSegment = null;
                log("segment skippable again (the playhead left it): " + suppressed);
            }
            for (SponsorSegment segment : current) {
                if (segment.didAutoSkipped && position < segment.startMs) {
                    segment.didAutoSkipped = false;
                }
            }
        }

        synchronized (SEGMENTS_LOCK) {
            if (currentVideoId == null) {
                return;
            }
            if (segments == null) {
                maybeFetch();
                return;
            }
        }
        if (!playheadAdvanced || current == null || current.isEmpty()) {
            return;
        }
        for (SponsorSegment segment : current) {
            if (shouldSkip(segment, position)) {
                requestSkip(segment, position);
                return;
            }
        }
    }

    /**
     * The current segment list, for the seek-bar marker layer. Read without the lock
     * on purpose: the list is replaced wholesale and never mutated in place, so a
     * stale read costs one repaint, never a torn list.
     */
    public static List<SponsorSegment> segmentsSnapshot() {
        return segments;
    }

    /** Asks the client for the current video's segments exactly once. */
    private static void maybeFetch() {        if (fetchRequested) {
            return;
        }
        fetchRequested = true;
        final String videoId = currentVideoId;
        log("queued " + videoId);
        SponsorBlockClient.fetch(videoId, new SponsorBlockClient.Callback() {
            @Override
            public void onSegments(String fetchedVideoId, List<SponsorSegment> fetched) {
                try {
                    synchronized (SEGMENTS_LOCK) {
                        if (!fetchedVideoId.equals(currentVideoId)) {
                            log("discarding segments for " + fetchedVideoId
                                + " (now on " + currentVideoId + ")");
                            return;
                        }
                        List<SponsorSegment> sorted =
                            new ArrayList<SponsorSegment>(fetched);
                        // The Patch settings row's minimum: a segment shorter than this is dropped
                        // before it can skip or be drawn. Highlight is exempt, as upstream exempts
                        // it -- a highlight is a point of interest, not a stretch to skip past.
                        long minMs = SponsorBlockSettings.minSegmentDurationMs();
                        int tooShort = 0;
                        if (minMs > 0L) {
                            for (int index = sorted.size() - 1; index >= 0; index--) {
                                SponsorSegment segment = sorted.get(index);
                                if (!"poi_highlight".equals(segment.category)
                                    && (segment.endMs - segment.startMs) < minMs) {
                                    sorted.remove(index);
                                    tooShort++;
                                }
                            }
                        }
                        Collections.sort(sorted, new Comparator<SponsorSegment>() {
                            public int compare(SponsorSegment a, SponsorSegment b) {
                                return Long.compare(a.startMs, b.startMs);
                            }
                        });
                        segments = sorted;
                        if (tooShort > 0) {
                            log("dropped " + tooShort + " segment(s) under the " + (minMs / 1000L)
                                + "s minimum");
                        }
                    }
                    int skippable = 0;
                    int marked = 0;
                    for (SponsorSegment segment : fetched) {
                        String behaviour = SponsorBlockSettings.behaviour(segment.category);
                        if (SponsorBlockSettings.autoSkips(behaviour)) {
                            skippable++;
                        }
                        if (SponsorBlockSettings.marks(behaviour)) {
                            marked++;
                        }
                    }
                    // Both numbers, because they differ now: a `seekbar-only` category
                    // contributes a marker and no skip, which is exactly what the log has
                    // to show when behaviour is being checked from a capture.
                    log("segments ready: " + fetched.size() + " (" + skippable + " auto-skip, "
                        + marked + " marked)");
                    // Show them on the seek bar right away, rather than waiting for
                    // the next timeline tick to repaint the marker layer.
                    SponsorBlockOverlay.onSegmentsChanged();
                    // The fetch took seconds; the playhead may already be inside a
                    // segment. Run one check now — on the main thread, like every
                    // other position read (the backend accessor asserts on it).
                    checkNow();
                } catch (Throwable t) {
                    log("segment delivery failed: " + t);
                }
            }
        });
    }

    /**
     * The phone controller's shouldAutoSkipAndUndoSkipNotActive, plus the
     * playhead-in-range test: behaviour says *seek* (or skip-once not yet done),
     * the playhead is inside the segment, and no seek for this segment is in
     * flight or recently refused.
     *
     * The first test is {@link SponsorBlockSettings#autoSkips} and not "is it not
     * ignore": a `seekbar-only` segment is a live segment this must never seek past, and
     * a `manual-skip` one has been normalised to it.
     */
    private static boolean shouldSkip(SponsorSegment segment, long position) {
        String behaviour = SponsorBlockSettings.behaviour(segment.category);
        if (!SponsorBlockSettings.autoSkips(behaviour)) {
            return false;
        }
        if (segment == suppressedSegment) {
            // The viewer seeked into this one: it plays out.
            return false;
        }
        if ("skip-once".equals(behaviour) && segment.didAutoSkipped) {
            return false;
        }
        if (position < segment.startMs || position >= segment.endMs) {
            return false;
        }
        long sinceRequest = System.currentTimeMillis() - segment.lastSkipRequestAt;
        return sinceRequest < 0L || sinceRequest > SEEK_CONFIRM_TIMEOUT_MS;
    }

    /**
     * The skip itself: clamp to the phone controller's end guard, seek to the
     * segment's end, and wait for onSeeked to confirm before the segment counts
     * as skipped — the phone build refuses a doomed seek up front because its
     * seekTo answers boolean; ours is void, so the confirmation is asynchronous.
     */
    private static void requestSkip(SponsorSegment segment, long position) {
        segment.lastSkipRequestAt = System.currentTimeMillis();
        long target = segment.endMs;
        if (durationMs > 0L) {
            target = Math.min(target, durationMs - END_GUARD_MS);
        }
        if (target <= position || (durationMs > 0L
            && position >= durationMs - END_GUARD_MS && target >= durationMs - END_GUARD_MS)) {
            log("skip aborted: " + segment + " (position " + position + ", duration "
                + durationMs + ") — inside the end guard");
            return;
        }
        if (!api.seekTo(target)) {
            log("skip failed: no seek bridge for " + segment);
            return;
        }
        // skip-once is NOT marked here: the seek bridge returning true only means the
        // app's seek method was entered, and its result is void. onSeeked marks the
        // segment once the playhead actually lands at the target; until then the
        // segment stays skippable (the request guard below stops a second seek).
        awaitedSeekTarget = target;
        awaitedSeekFrom = position;
        awaitedSeekAt = segment.lastSkipRequestAt;
        pendingSkipSegment = segment;
        log("skipping " + segment + " (position " + position + " ms)");
    }

    /**
     * The phone build's {@code clearData()} half that matters without a UI: a new
     * playback of the same video starts with every segment skippable again.
     */
    private static void resetSkipStateForNewPlayback() {
        List<SponsorSegment> current = segments;
        if (current != null) {
            for (SponsorSegment segment : current) {
                segment.resetForNewPlayback();
            }
        }
        lastPosition = -1L;
        awaitedSeekTarget = -1L;
        pendingSkipSegment = null;
        suppressedSegment = null;
    }

    // ------------------------------------------------------------------
    // Player-response reading: no obfuscated names, resolve by shape.
    // ------------------------------------------------------------------

    /**
     * The video id inside the player response's video-details proto: the details
     * object is the field holding a String shaped like a YouTube id (11 chars of
     * [A-Za-z0-9_-]), read by shape rather than by obfuscated field name.
     */
    private static String readVideoId(Object playerResponse) {
        for (Class<?> c = playerResponse.getClass(); c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> type = field.getType();
                if (type.isPrimitive() || type == String.class || java.util.List.class.isAssignableFrom(type)) {
                    continue;
                }
                Object details;
                try {
                    field.setAccessible(true);
                    details = field.get(playerResponse);
                } catch (Throwable t) {
                    continue;
                }
                if (details == null) {
                    continue;
                }
                String id = idShapedString(details);
                if (id != null) {
                    return id;
                }
            }
        }
        return null;
    }

    /** The id-shaped String field of [holder], or null. */
    private static String idShapedString(Object holder) {
        for (Class<?> c = holder.getClass(); c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType() != String.class
                    || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(holder);
                    if (value instanceof String && isVideoId((String) value)) {
                        return (String) value;
                    }
                } catch (Throwable t) {
                    // keep looking
                }
            }
        }
        return null;
    }

    private static boolean isVideoId(String value) {
        if (value == null || value.length() != 11) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Through the one gate: a build whose "Verbose logging" row is untouched prints nothing. */
    private static void log(String message) {
        VerboseLog.log(TAG, message);
    }
}
