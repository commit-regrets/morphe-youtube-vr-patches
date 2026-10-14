package app.ytvr.extension;

import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The skip decision, ported from the phone patch's SegmentPlaybackController and reduced to what
 * survives without a UI.
 *
 * <p>Drive model: the VR app has no periodic media-time callback, so this class runs its own ~500
 * ms tick on the main looper and reads the playhead through the host's bridge — the same accessors
 * the app's own UI poller hits every 100 ms. At each tick the controller picks the first
 * not-yet-skipped segment whose range contains the playhead and seeks to its end. Auto-skip fires
 * on contact, exactly like the phone build (it has no look-ahead rule either; its 1200 ms tolerance
 * exists for its scheduled-skip path, which a 500 ms tick makes unnecessary).
 *
 * <p>Guards, all carried over from the phone controller: - the ad gate: no skip while the app's own
 * ad UI is counting down (the phone refuses to skip while its ad progress text is visible); - the
 * clamp: the seek target is clamped to duration - 250 ms, and a skip whose playhead and target both
 * sit in the last 250 ms is aborted (the phone build's "refused seek" case, which is what makes a
 * video change a non-event); - the re-request guard: our seek returns void, so a segment is not
 * re-requested while a seek is in flight — confirmed by the onSeeked hook, or expired after 3 s; -
 * skip-once: a per-segment persistent flag survives a backwards seek; plain skip intentionally
 * re-skips.
 *
 * <p>Every decision logs with the SponsorBlock: prefix — on a head-mounted device the logcat line
 * is the only oracle for "no segment here" vs "the hook never ran".
 */
public final class SponsorSkipController {

    private static final String TAG = "SponsorBlock";

    /** The phone controller's end-of-video guard. */
    private static final long END_GUARD_MS = 250L;

    /** Tick period; the phone build works from a 1000 ms callback. */
    private static final long TICK_MS = 500L;

    /** A seek is re-requestable once it is neither confirmed nor in flight. */
    private static final long SEEK_CONFIRM_TIMEOUT_MS = 3000L;

    /**
     * The phone controller's "a segment that ends this soon is not offered" window: a skip button
     * that appears for the last fraction of a second of a segment is worse than none.
     */
    private static final long ENDS_SOON_MS = 800L;

    /**
     * How long the Skip button stays up for one segment. The phone patch's own default, read out of
     * its settings: `SB_AUTO_HIDE_SKIP_BUTTON` is on by default and its duration is
     * `FOUR_SECONDS.adjustedDuration` — four seconds less the 200 ms that enum subtracts, i.e.
     * **3800 ms**. After the window the segment is hidden until the playhead leaves it, which is
     * what stops the button sitting over a 22-second segment for all 22 seconds (reported from the
     * headset, and the phone patch measured to answer it: 2026-10-14).
     */
    private static final long SKIP_OFFER_MS = 3800L;

    /**
     * How long a highlight is offered by its own button. The phone patch caps it with the same
     * auto-hide duration (`min(time to the highlight, autoHideSkipButtonDurationMs)`); the "is it
     * still ahead" test in {@link #publishSkipOffer} does the rest.
     */
    private static final long HIGHLIGHT_OFFER_MS = SKIP_OFFER_MS;

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
    private static final Runnable TICK =
            new Runnable() {
                public void run() {
                    tickSafely();
                    if (running) {
                        MAIN.postDelayed(TICK, TICK_MS);
                    }
                }
            };

    /** One extra check outside the loop (used when a fetch lands mid-interval). */
    private static void checkNow() {
        MAIN.post(
                new Runnable() {
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
     * The segment whose seek is in flight. skip-once is marked on this only once onSeeked confirms
     * the landing — the phone build marks after its seek returns true, and ours returns void, so
     * confirmation is the only honest signal.
     */
    private static SponsorSegment pendingSkipSegment;

    /**
     * The segment a *seek* landed inside: it plays out, because dragging the seek bar into a
     * segment is the viewer asking for that part. Cleared the moment the playhead leaves the
     * segment in either direction, so dragging back before it re-arms the skip — which is what the
     * web extension does and what a viewer expects from a seek bar.
     */
    private static SponsorSegment suppressedSegment;

    /**
     * The `manual-skip` segment whose Skip button is on screen, or null. Exactly one at a time, and
     * only while its behaviour says "offer a button instead of skipping" — the phone controller's
     * `segmentCurrentlyPlaying`.
     */
    private static SponsorSegment offeredSegment;

    /** When the current offer went up; the {@link #SKIP_OFFER_MS} window is measured from here. */
    private static long offeredAt;

    /**
     * The segments the button has been taken away from for this pass: the viewer pressed it, or its
     * window expired. The phone patch's `hiddenSkipSegmentsForCurrentVideoTime`, cleared the same
     * way — as soon as the playhead is no longer inside the segment, so seeking back before it
     * offers the button again, while a playhead still inside it is not nagged.
     */
    private static final Set<SponsorSegment> hiddenSegments = new HashSet<SponsorSegment>();

    /** Until when a highlight that is still ahead is offered by the button. */
    private static volatile long highlightOfferUntil;

    private SponsorSkipController() {}

    // ------------------------------------------------------------------
    // Hooked entry points. Every one catches Throwable: never throw into the app.
    // ------------------------------------------------------------------

    /**
     * Hooked at instruction 0 of the player-response constructor (the same site the Extension patch
     * uses): records which video is being prepared.
     *
     * <p>Every response is recorded — content AND ad. The "skip responses with the app's ad-marker
     * list" idea from the first build is gone: on device the *content* response itself carries a
     * non-empty marker (m=1 observed while playing Pc1JzImPw_M, 2026-10-10), so that gate silently
     * discarded the only response that matters. Fetching segments for an ad's id is harmless — the
     * server answers "no segments", and the content response overwrites the pending id before
     * loadVideo in the normal order.
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
                    log(
                            "player response carried no video id (id-shaped String scan found"
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
     * Hooked at instruction 0 of the player wrapper's loadVideo: the player hands itself over, the
     * video changes, so state resets and the fetch starts.
     */
    public static void onLoadVideo(Object player) {
        try {
            if (!SponsorBlockSettings.isConfigured()) {
                return;
            }
            api.attach(player);
            adUiUntil = 0L;
            awaitedSeekTarget = -1L;
            // A new video: whatever the button was offering belongs to the previous one.
            hiddenSegments.clear();
            highlightOfferUntil = 0L;
            offerSkipSegment(null, "a new video is being prepared");
            synchronized (SEGMENTS_LOCK) {
                if (pendingVideoId == null) {
                    // Always say so: this line is the difference between "no player
                    // response was seen" and "the hook never ran".
                    log(
                            "loadVideo with no known video id (was: "
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
                log(
                        "video "
                                + currentVideoId
                                + " (was: "
                                + (previous == null ? "none" : previous)
                                + ")");
            }
            startTick();
        } catch (Throwable t) {
            log("onLoadVideo failed: " + t);
        }
    }

    /**
     * Hooked at instruction 0 of the player events' onSeeked: confirms our own skip seek, or
     * records a user seek (which invalidates any awaited target).
     */
    public static void onSeeked(long positionMs) {
        try {
            boolean ours =
                    awaitedSeekTarget >= 0L
                            && Math.abs(positionMs - awaitedSeekTarget) <= 5000L
                            && System.currentTimeMillis() - awaitedSeekAt
                                    <= SEEK_CONFIRM_TIMEOUT_MS;
            if (awaitedSeekTarget >= 0L) {
                if (ours) {
                    log(
                            "seek confirmed at "
                                    + positionMs
                                    + " ms (target "
                                    + awaitedSeekTarget
                                    + ")");
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
                            && "skip-once"
                                    .equals(SponsorBlockSettings.behaviour(segment.category))) {
                        // Only now is it honest to consume skip-once: a refused or clamped
                        // seek leaves the segment skippable, which is what the phone build's
                        // boolean seek result buys it.
                        segment.didAutoSkipped = true;
                    }
                } else {
                    log(
                            "seek to "
                                    + awaitedSeekTarget
                                    + " not confirmed (landed at "
                                    + positionMs
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
     * A seek we did not make (the viewer dragging the bar, or the app positioning itself) landed at
     * [positionMs]. If that is strictly inside a segment, that segment plays out instead of being
     * skipped; the tick clears this as soon as the playhead leaves the segment, so seeking back
     * before it skips again on approach.
     *
     * <p>Measured expectation (from the web extension's behaviour): with a 2:00-2:50 segment,
     * seeking to 1:55 skips when playback reaches 2:00, while seeking to 2:05 plays the rest of the
     * segment.
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
     * Hooked at instruction 0 of the player events' onPlaying / onPaused / onStopped. The tick
     * polls the playhead only while this is true — polling the native player during prepare is what
     * the first build got wrong: the app itself starts its 100 ms poller only on a playback-state
     * transition, and a prepare-time poll loop coincided with a prepare→retry loop on device
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
            // Nothing is playing, so nothing can be offered: the card is a floating overlay and
            // would otherwise stay in the headset after the video is closed.
            offerSkipSegment(null, "playback stopped");
        } catch (Throwable t) {
            // never throw into the app
        }
    }

    /**
     * Hooked in front of the ad-skip countdown ticks (the same two methods the Extension patch
     * reports through): an ad is on screen. The gate expires a few seconds after the last tick so a
     * paused countdown cannot block forever.
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
        if (!SponsorBlockSettings.isConfigured()
                || !SponsorBlockSettings.enabled()
                || !SponsorBlockSettings.anyHandled()) {
            offerSkipSegment(null, "the settings do not offer anything");
            return;
        }
        if (System.currentTimeMillis() < adUiUntil) {
            if (!adSuppressionLogged) {
                adSuppressionLogged = true;
                log("skip suppressed: ad on screen");
            }
            offerSkipSegment(null, "ad on screen");
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
            // The button's own bookkeeping, on the phone patch's rule: a segment it was taken away
            // from is forgotten the moment the playhead is no longer inside it, so a viewer who
            // seeks back before a segment gets the button again, while one still inside it is not
            // nagged. A segment whose *clicked* seek never landed is also forgotten, so a refused
            // seek does not lose the button for the rest of the pass.
            if (!hiddenSegments.isEmpty()) {
                long now = System.currentTimeMillis();
                for (Iterator<SponsorSegment> iterator = hiddenSegments.iterator();
                        iterator.hasNext(); ) {
                    SponsorSegment hidden = iterator.next();
                    boolean left = position < hidden.startMs || position >= hidden.endMs;
                    boolean stale =
                            hidden.lastSkipRequestAt != 0L
                                    && now - hidden.lastSkipRequestAt > SEEK_CONFIRM_TIMEOUT_MS;
                    if (left || stale) {
                        iterator.remove();
                    }
                }
            }
            for (SponsorSegment segment : current) {
                if (segment.didAutoSkipped && position < segment.startMs) {
                    segment.didAutoSkipped = false;
                }
            }
        }

        synchronized (SEGMENTS_LOCK) {
            if (currentVideoId == null) {
                offerSkipSegment(null, "no video is playing");
                return;
            }
            if (segments == null) {
                maybeFetch();
                offerSkipSegment(null, "the segments are still being fetched");
                return;
            }
        }
        // One decision per tick, and the automatic skip is made first. Both halves of this method
        // used to run: the offer was published and the seek was requested in the same tick, so a
        // `manual-skip` segment that overlaps an auto-skip one raised the button and was then
        // seeked
        // past -- leaving a button on screen for a segment that was no longer playing until the
        // next
        // tick took it away again. That is up to a full tick (~500 ms) and reads as a glitch, so
        // the
        // tick that skips publishes no offer at all; the next one offers whatever is genuinely
        // playing after the seek lands.
        //
        // The `playheadAdvanced` test is the old guard around this loop, kept here so the skip
        // cadence is unchanged by the reordering: while the playhead is stalled or seeking, the
        // decision is made only for the offer.
        SponsorSegment autoSkip = null;
        if (playheadAdvanced && current != null) {
            for (SponsorSegment segment : current) {
                if (shouldSkip(segment, position) || shouldSkipAhead(segment, position)) {
                    autoSkip = segment;
                    break;
                }
            }
        }
        if (autoSkip != null) {
            requestSkip(autoSkip, position);
            return;
        }
        // The offer runs on every tick, including one whose playhead has not moved: a fetch that
        // lands mid-interval runs one extra check, and the button must appear from it.
        publishSkipOffer(current, position);
    }

    /**
     * Which segment, if any, the in-player Skip button is offering right now — the phone
     * controller's `setSegmentCurrentlyPlaying`, driven from this port's tick instead of from a 1
     * Hz media-time callback.
     *
     * <p>Two shapes, because SponsorBlock stores two kinds of segment:
     *
     * <ul>
     *   <li>a range the playhead is inside, whose behaviour is `manual-skip` — offered while the
     *       playhead is in it, except in the phone patch's 800 ms "ends very soon" window;
     *   <li>a **highlight**, which the API answers as a point (`[t, t]`): the playhead is never
     *       inside it, so it is offered while it is still ahead, for the phone patch's {@link
     *       #HIGHLIGHT_OFFER_MS} window — which is what makes a highlight reachable at all.
     * </ul>
     *
     * <p>The range wins when both apply. The phone patch shows both at once because it has a button
     * for each; this port has one, and the segment the playhead is *inside* is the one the viewer
     * can act on now.
     */
    private static void publishSkipOffer(List<SponsorSegment> current, long position) {
        try {
            if (current == null || current.isEmpty()) {
                offerSkipSegment(null, "there are no segments for this video");
                return;
            }
            long now = System.currentTimeMillis();
            // The offer's own window, the phone patch's auto-hide: once it has run, the segment is
            // hidden for the rest of this pass (until the playhead leaves it).
            SponsorSegment showing = offeredSegment;
            if (showing != null && now - offeredAt > SKIP_OFFER_MS) {
                hiddenSegments.add(showing);
                log("skip button window over (" + SKIP_OFFER_MS + " ms): " + showing);
                offerSkipSegment(null, "the offer's " + SKIP_OFFER_MS + " ms window ran out");
                showing = null;
            }
            // Which overlapping segment, if any, is offered — the phone patch's own choice, read
            // out
            // of its loop: a segment that is *contained* in the current candidate replaces it, so
            // the **innermost** nested segment is offered first. With an `intro` wrapping a
            // `sponsor`, that is "Skip Sponsor" (a 10 s commitment) before "Skip Intro" (30 s) —
            // the phone's order, and the smaller thing to ask a viewer for. Two segments that
            // merely
            // overlap, neither containing the other, keep the earliest-starting one, because
            // nothing
            // replaces the first candidate; that was this port's rule for *every* case before the
            // nesting rule was matched (asked for from the headset, 2026-10-14).
            SponsorSegment best = null;
            for (SponsorSegment segment : current) {
                if (hiddenSegments.contains(segment)
                        || !SponsorBlockSettings.manualSkips(
                                SponsorBlockSettings.behaviour(segment.category))) {
                    continue;
                }
                if (position < segment.startMs || position >= segment.endMs) {
                    continue;
                }
                if (segment.endMs - position <= ENDS_SOON_MS) {
                    continue;
                }
                if (best == null || containsSegment(best, segment)) {
                    best = segment;
                }
            }
            if (best != null) {
                offerSkipSegment(best);
                return;
            }
            SponsorSegment highlight = null;
            for (SponsorSegment segment : current) {
                if (!"poi_highlight".equals(segment.category)
                        || hiddenSegments.contains(segment)
                        || segment.startMs <= position) {
                    continue;
                }
                if (highlight == null || segment.startMs < highlight.startMs) {
                    highlight = segment;
                }
            }
            if (highlight != null
                    && SponsorBlockSettings.manualSkips(
                            SponsorBlockSettings.behaviour(highlight.category))
                    && System.currentTimeMillis() < highlightOfferUntil) {
                offerSkipSegment(highlight);
                return;
            }
            offerSkipSegment(null, "nothing is being offered at " + position + " ms");
        } catch (Throwable t) {
            log("skip offer failed: " + t);
        }
    }

    /**
     * The phone patch's `SponsorSegment.containsSegment`: [inner] sits entirely inside [outer] —
     * the test its own offer loop uses to descend to the innermost nested segment.
     */
    private static boolean containsSegment(SponsorSegment outer, SponsorSegment inner) {
        return outer.startMs <= inner.startMs && inner.endMs <= outer.endMs;
    }

    /**
     * Puts [segment]'s offer on screen, or takes the offer away when it is null. One card ({@link
     * SkipButtonCard}) owns the view; it is handed the segment so it can label the button with the
     * category it is offering.
     */
    private static void offerSkipSegment(SponsorSegment segment) {
        offerSkipSegment(segment, null);
    }

    /**
     * Every clear names its reason. A button that appears and then vanishes is otherwise
     * indistinguishable from one that never appeared, and the first device run of this feature
     * showed exactly that: an offer shown at 23:17:45.350 and cleared at 23:17:47.209, with nothing
     * in the log to say which of the six call sites did it.
     */
    private static void offerSkipSegment(SponsorSegment segment, String reason) {
        if (segment == offeredSegment) {
            return;
        }
        offeredSegment = segment;
        offeredAt = System.currentTimeMillis();
        if (segment == null) {
            log("skip button offer cleared" + (reason == null ? "" : " (" + reason + ")"));
        } else {
            log("skip button offered: " + segment);
        }
        SkipButtonCard.setSegment(segment);
    }

    /**
     * The viewer pressed the Skip button. Nothing is skipped unless a segment is actually being
     * offered, so a stale or duplicated click cannot move the playhead, and the segment is hidden
     * until the playhead leaves it — the phone patch's `onSkipSegmentClicked`.
     */
    public static void onSkipButtonClicked() {
        try {
            SponsorSegment segment = offeredSegment;
            if (segment == null) {
                log("skip button pressed with nothing offered");
                return;
            }
            long position = api.position();
            if (position < 0L) {
                log("skip button pressed but the playhead is unavailable");
                return;
            }
            hiddenSegments.add(segment);
            offerSkipSegment(null, "the viewer pressed the button");
            log("skip button pressed: " + segment);
            requestSkip(segment, position);
        } catch (Throwable t) {
            log("skip button failed: " + t);
        }
    }

    /**
     * The current segment list, for the seek-bar marker layer. Read without the lock on purpose:
     * the list is replaced wholesale and never mutated in place, so a stale read costs one repaint,
     * never a torn list.
     */
    public static List<SponsorSegment> segmentsSnapshot() {
        return segments;
    }

    /** Asks the client for the current video's segments exactly once. */
    private static void maybeFetch() {
        if (fetchRequested) {
            return;
        }
        fetchRequested = true;
        final String videoId = currentVideoId;
        log("queued " + videoId);
        SponsorBlockClient.fetch(
                videoId,
                new SponsorBlockClient.Callback() {
                    @Override
                    public void onSegments(String fetchedVideoId, List<SponsorSegment> fetched) {
                        try {
                            synchronized (SEGMENTS_LOCK) {
                                if (!fetchedVideoId.equals(currentVideoId)) {
                                    log(
                                            "discarding segments for "
                                                    + fetchedVideoId
                                                    + " (now on "
                                                    + currentVideoId
                                                    + ")");
                                    return;
                                }
                                List<SponsorSegment> sorted =
                                        new ArrayList<SponsorSegment>(fetched);
                                // The Patch settings row's minimum: a segment shorter than this is
                                // dropped
                                // before it can skip or be drawn. Highlight is exempt, as upstream
                                // exempts
                                // it -- a highlight is a point of interest, not a stretch to skip
                                // past.
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
                                Collections.sort(
                                        sorted,
                                        new Comparator<SponsorSegment>() {
                                            public int compare(SponsorSegment a, SponsorSegment b) {
                                                return Long.compare(a.startMs, b.startMs);
                                            }
                                        });
                                segments = sorted;
                                if (tooShort > 0) {
                                    log(
                                            "dropped "
                                                    + tooShort
                                                    + " segment(s) under the "
                                                    + (minMs / 1000L)
                                                    + "s minimum");
                                }
                            }
                            int skippable = 0;
                            int offered = 0;
                            int marked = 0;
                            boolean highlightOffered = false;
                            for (SponsorSegment segment : fetched) {
                                String behaviour = SponsorBlockSettings.behaviour(segment.category);
                                if (SponsorBlockSettings.autoSkips(behaviour)) {
                                    skippable++;
                                }
                                if (SponsorBlockSettings.manualSkips(behaviour)) {
                                    offered++;
                                    if ("poi_highlight".equals(segment.category)) {
                                        highlightOffered = true;
                                    }
                                }
                                if (SponsorBlockSettings.marks(behaviour)) {
                                    marked++;
                                }
                            }
                            // The highlight's own button window opens when its segments land, which
                            // is what
                            // the phone patch does (`onSegmentFetchComplete` sets its end time from
                            // the
                            // distance to the highlight, capped by the auto-hide duration).
                            highlightOfferUntil =
                                    highlightOffered
                                            ? System.currentTimeMillis() + HIGHLIGHT_OFFER_MS
                                            : 0L;
                            // All three numbers, because they differ: a `seekbar-only` category
                            // contributes
                            // a marker and nothing else, and a `manual-skip` one contributes a
                            // marker and
                            // the button -- which is exactly what the log has to show when
                            // behaviour is
                            // being checked from a capture.
                            log(
                                    "segments ready: "
                                            + fetched.size()
                                            + " ("
                                            + skippable
                                            + " auto-skip, "
                                            + offered
                                            + " skip-button, "
                                            + marked
                                            + " marked)");
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
     * The phone controller's shouldAutoSkipAndUndoSkipNotActive, plus the playhead-in-range test:
     * behaviour says *seek* (or skip-once not yet done), the playhead is inside the segment, and no
     * seek for this segment is in flight or recently refused.
     *
     * <p>The first test is {@link SponsorBlockSettings#autoSkips} and not "is it not ignore": a
     * `seekbar-only` segment is a live segment this must never seek past, and a `manual-skip` one
     * has been normalised to it.
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
     * The highlight half of the auto-skip decision. A highlight is a point (`[t, t]` from the API),
     * so {@link #shouldSkip}'s "the playhead is inside the segment" test can never be true for one;
     * what "skip automatically" means for a highlight is *jump forward to it*, which is what the
     * browser extension's `poi` action does and what the phone patch does when the segments arrive
     * and the highlight is still ahead.
     *
     * <p>Everything else is the same as {@link #shouldSkip}: only an auto-skip behaviour moves the
     * playhead, `skip-once` is consumed per pass, and the re-request guard still applies. So a
     * plain `skip` highlight jumps again after a rewind, exactly like a plain `skip` range.
     */
    private static boolean shouldSkipAhead(SponsorSegment segment, long position) {
        if (!"poi_highlight".equals(segment.category) || segment.startMs <= position) {
            return false;
        }
        String behaviour = SponsorBlockSettings.behaviour(segment.category);
        if (!SponsorBlockSettings.autoSkips(behaviour)) {
            return false;
        }
        if ("skip-once".equals(behaviour) && segment.didAutoSkipped) {
            return false;
        }
        long sinceRequest = System.currentTimeMillis() - segment.lastSkipRequestAt;
        return sinceRequest < 0L || sinceRequest > SEEK_CONFIRM_TIMEOUT_MS;
    }

    /**
     * The skip itself: clamp to the phone controller's end guard, seek to the segment's end, and
     * wait for onSeeked to confirm before the segment counts as skipped — the phone build refuses a
     * doomed seek up front because its seekTo answers boolean; ours is void, so the confirmation is
     * asynchronous.
     */
    private static void requestSkip(SponsorSegment segment, long position) {
        segment.lastSkipRequestAt = System.currentTimeMillis();
        long target = segment.endMs;
        if (durationMs > 0L) {
            target = Math.min(target, durationMs - END_GUARD_MS);
        }
        if (target <= position
                || (durationMs > 0L
                        && position >= durationMs - END_GUARD_MS
                        && target >= durationMs - END_GUARD_MS)) {
            log(
                    "skip aborted: "
                            + segment
                            + " (position "
                            + position
                            + ", duration "
                            + durationMs
                            + ") — inside the end guard");
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
     * The phone build's {@code clearData()} half that matters without a UI: a new playback of the
     * same video starts with every segment skippable again.
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
        // A new playback offers again: the phone patch's `clearData()` reaches the same set.
        hiddenSegments.clear();
        highlightOfferUntil = 0L;
        offerSkipSegment(null, "a new playback of the same video started");
    }

    // ------------------------------------------------------------------
    // Player-response reading: no obfuscated names, resolve by shape.
    // ------------------------------------------------------------------

    /**
     * The video id inside the player response's video-details proto: the details object is the
     * field holding a String shaped like a YouTube id (11 chars of [A-Za-z0-9_-]), read by shape
     * rather than by obfuscated field name.
     */
    private static String readVideoId(Object playerResponse) {
        for (Class<?> c = playerResponse.getClass(); c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> type = field.getType();
                if (type.isPrimitive()
                        || type == String.class
                        || java.util.List.class.isAssignableFrom(type)) {
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
            boolean ok =
                    (ch >= 'a' && ch <= 'z')
                            || (ch >= 'A' && ch <= 'Z')
                            || (ch >= '0' && ch <= '9')
                            || ch == '-'
                            || ch == '_';
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
