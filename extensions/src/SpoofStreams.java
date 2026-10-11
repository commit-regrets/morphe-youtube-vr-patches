package app.ytvr.extension;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streaming-spoof injection for YouTube VR (see docs/INTERNALS.md).
 *
 * <p>Hooked into oqb.<init>(zjp) -- the builder that turns the PlayerResponse proto (zjp, field h =
 * StreamingData, field g = VideoDetails with c = video id) into the app's VideoStreamingData. That
 * constructor reads zjp.h in its first instructions, so replacing zjp.h here makes the whole
 * downstream (VideoStreamingData, the SABR enable decision, the media fetch controller) see the
 * spoofed stream instead of the one the ANDROID_VR client is served.
 *
 * <p>The spoofed response (as the VISIONOS client) contains ordinary adaptive formats and no
 * serverAbrStreamingUrl, so no pre-roll is stitched into it.
 *
 * <p>Deliberately reflection-only: no app classes are referenced at compile time, so the extension
 * needs no stub classpath. All work happens on the calling thread.
 */
public final class SpoofStreams {

    private static final String API = "https://youtubei.googleapis.com/youtubei/v1/";
    private static final String TAG = "YTVR-SPOOF";
    private static final String DEFAULT_VIDEO_ID = "aqz-KE-bpKQ";
    private static final String PLAYER_ROUTE = "player?fields=streamingData&alt=proto";
    private static final String VISITOR_ROUTE =
            "visitor_id?prettyPrint=false&fields=responseContext.visitorData";

    private static final String PLAYER_RESPONSE_CLASS = "defpackage.zjp";
    private static final String STREAMING_DATA_CLASS =
            "com.google.protos.youtube.api.innertube.StreamingDataOuterClass$StreamingData";

    /**
     * An 8K HDR sample (the default video is only 4K60): the identity matrix probes both, and the
     * ANDROID_VR control row reports each video's true ceiling on the native client.
     */
    private static final String PROBE_VIDEO_ID = "1La4QzGeaaQ";

    /**
     * Candidate clients as {label, client JSON fragment}. The native ANDROID_VR response's formats
     * carry NO absolute URLs (SABR-era: metadata only, bytes flow through the SABR session --
     * 2026-10-10 log: media requests to a bare "?cpn=..&rn=N" with no host), so 8K-in-VP9 must come
     * from another client whose response carries playable URLs. The VISIONOS client's only 8K is
     * AV1 (itag 702), which the picker drops. TV clients serve VP9 4320p (itag 272) with absolute
     * URLs. Version strings are the real, current ones yt-dlp uses; an unknown version 404s
     * (VISIONOS lesson), a stale one reports a playability error -- the status column tells them
     * apart.
     */
    private static final String[][] PROBE_IDENTITIES = {
        {
            "vos control (17,1 / 1.03)",
            "\"clientName\":\"VISIONOS\",\"clientVersion\":\"1.03\","
                    + "\"deviceMake\":\"Apple\",\"deviceModel\":\"RealityDevice17,1\","
                    + "\"osName\":\"visionOS\",\"osVersion\":\"26.6.1\",\"hl\":\"en\",\"gl\":\"US\""
        },
        {
            "TVHTML5",
            "\"clientName\":\"TVHTML5\",\"clientVersion\":\"7.20250122.12.00\","
                    + "\"hl\":\"en\",\"gl\":\"US\""
        },
        {
            "TVHTML5_SIMPLY",
            "\"clientName\":\"TVHTML5_SIMPLY_EMBEDDED_PLAYER\","
                    + "\"clientVersion\":\"2.0\",\"hl\":\"en\",\"gl\":\"US\""
        },
        {
            "WEB",
            "\"clientName\":\"WEB\",\"clientVersion\":\"2.20250312.04.00\","
                    + "\"hl\":\"en\",\"gl\":\"US\""
        },
        {
            "MWEB",
            "\"clientName\":\"MWEB\",\"clientVersion\":\"2.20250311.03.00\","
                    + "\"hl\":\"en\",\"gl\":\"US\""
        },
        {
            "ANDROID",
            "\"clientName\":\"ANDROID\",\"clientVersion\":\"19.09.37\","
                    + "\"androidSdkVersion\":\"30\",\"osName\":\"Android\",\"osVersion\":\"11\","
                    + "\"hl\":\"en\",\"gl\":\"US\""
        },
    };

    /** video id -> spoofed StreamingData instance, so each video is fetched once. */
    private static final Map<String, Object> CACHE = new HashMap<String, Object>();

    private static volatile String visitorId;
    private static volatile boolean reported;

    /**
     * Whether the playback currently in hand runs on **spoofed** stream data.
     *
     * <p>Read by the hooks that must only ever apply to a spoofed stream and must never touch the
     * app's own:
     *
     * <p>- `umpRewriteAllowed` (the media data source's UMP rewrite flag), - {@link
     * PotUrls#onBuild} (the POST/AbrStateDataSpec normalisation).
     *
     * <p>Both exist to make a *plain adaptive URL* fetch like a plain adaptive URL. The app's own
     * SABR-era stream needs exactly the opposite -- it is a UMP/onesie stream and is fetched
     * through the UMP envelope with the AbrState body -- so applying either to it breaks native
     * playback.
     *
     * <p>Set only by {@link #augmentPlayerResponse} (true on a successful swap, false when the
     * fetch cannot be used). The SABR/onesie *decision* deliberately does NOT read this flag: it is
     * derived from the injected data itself (see {@link #sabrNotForced}), so it cannot depend on
     * hook ordering.
     */
    private static volatile boolean injected;

    /** Makes the "why is there no stream?" diagnosis below run at most once per process. */
    private static final AtomicBoolean WALL_REPORTED = new AtomicBoolean();

    /**
     * Retry cooldown after a spoofed fetch that produced nothing usable.
     *
     * <p>While the server refuses this client (HTTP 200, empty body, LOGIN_REQUIRED) there is no
     * point asking again for every video: it costs a request per player response against a service
     * that is already declining to serve us, and the answer is known. Playback is unaffected -- the
     * fallback path is native either way -- so the only thing the cooldown gives up is how quickly
     * a recovered server is noticed. It doubles per consecutive failure up to [MAX_BACKOFF_MS], so
     * a wall that lifts is picked up within half an hour, and a session that starts working resets
     * it immediately.
     */
    private static final long INITIAL_BACKOFF_MS = 5 * 60 * 1000L;

    private static final long MAX_BACKOFF_MS = 30 * 60 * 1000L;
    private static volatile long backoffMs = INITIAL_BACKOFF_MS;
    private static volatile long nextAttemptAt;

    /**
     * Whether to make the injected 8K tier survive the app's own checks (the `enable8k` patch
     * option, **on** -- it is the 8K option, and the default here matches it).
     *
     * <p>What it does, and why each half is there, all from measurements on this device:
     *
     * <p>1. **AV1 level**: the 8K tier's codec string is `av01.0.17M.10` = AV1 level 6.1, while
     * this device's decoder advertises 5.1 (the app prints `c2.qti.av1.decoder` as `l.20000`,
     * Android's `AV1Level51`). The claim is rewritten to 5.1. On its own this changed nothing,
     * which is what makes it a *partial* fix rather than the answer. 2. **HDR**: this is the part
     * that matters. YouTube now offers 8K only as HDR AV1 -- itag 702, which the app's own table
     * calls `DASH_FMP4_AV1_8K_HDR`, with a non-HDR sibling 571 (`DASH_FMP4_AV1_8K`) that the
     * *stream* does not contain -- and this headset's panels are not HDR, so the app has every
     * reason to drop it. The app tests HDR two ways (`onu.U()` from `colorInfo`, `onu.V()` from an
     * itag set) and both are neutralised. See {@link #makeHdrTierSdr}.
     *
     * <p>Supporting evidence that HDR is the gate, not a guess: with an AV1 HDR 2160p entry (701)
     * *and* a VP9 SDR 2160p60 entry (315) both present, the player fetched 315; `axh` contains
     * HDR-aware format filters (case 14 uses `!onu.V(...)`); and the app's own player request
     * declares its `MediaCapabilities` to the server (the `see` decorator, allowlisted to the
     * `player` route), which is how the stock app's ladder can legitimately differ from ours.
     *
     * <p>Not yet verified end to end: whether neutralising HDR is *sufficient*. If the exclusion
     * happens instead in the renderer's display-capability check, the next step is the app-side one
     * (`aat.n`/`gep.aH` ask `display.isHdr()`), which tells the renderer HDR is supported on a
     * display that is not -- a bigger lie with a bigger failure mode, so it is not in this build.
     */
    private static volatile boolean experiment8k = true;

    /**
     * AV1 level 5.1 in codec-string numbering (Android's `AV1Level51`), what this device claims.
     */
    private static final int AV1_LEVEL_5_1 = 13;

    /**
     * The HDR AV1 tiers and their standard-dynamic-range siblings, from the app's **own** format
     * table (`ons`: `DASH_FMP4_AV1_8K(571)` vs `DASH_FMP4_AV1_8K_HDR(702)`, and so on down the
     * family). Same codec string, same width and height in that table -- the pair differs only in
     * the HDR flag -- so relabelling an entry from one to the other keeps every other property the
     * app derives from it.
     *
     * <p>The **whole family**, not just the 8K pair, and that is a measured correction: a revision
     * that relabelled only 702 (and the DRM pair 827/572) left the quality menu with no 8K option
     * at all, where the family-wide version offers it. The likely reason is coherence -- for a
     * video whose AV1 tiers arrive HDR-only, relabelling one tier leaves the app an AV1 family with
     * a single 4320p rung and no siblings, and it drops the family; relabelling the family gives it
     * the complete standard-range ladder the app expects. That is an inference from two device
     * observations, not a code reading.
     *
     * <p>The cost, stated plainly: a relabelled entry is still HDR *content* (that is what the
     * server sent), now presented as standard-range. The presence guard in {@link #makeHdrTierSdr}
     * keeps that to videos whose standard-range siblings are genuinely absent -- when the server
     * offers both, nothing is touched.
     */
    private static final int[][] HDR_TO_SDR_ITAG = {
        {702, 571}, {701, 401}, {700, 400}, {699, 399}, {698, 398},
        {697, 397}, {696, 396}, {695, 395}, {694, 394}, {827, 572},
    };

    /**
     * Logs the highest few video itags of a streaming data, read from the format protos' itag
     * field. Diagnostic, and the only line that can answer "does the app's own ladder contain an 8K
     * tier the injected one lacks" -- the injected ladder's itags come out of its URLs, but the
     * app's own formats are SABR-era and carry no URL at all.
     *
     * <p>The itag field is read by its obfuscated name (`yvh.e` in 1.88.10), the same way this
     * class already reads `zjp.h`/`zjp.n`. It fails soft: no field, no line.
     */
    private static void logLadder(String label, Object streamingData) {
        if (streamingData == null) {
            return;
        }
        try {
            java.util.List<Integer> itags = new java.util.ArrayList<Integer>();
            for (Object format : allFormats(streamingData)) {
                Object mime = field(format, "g");
                if (!(mime instanceof String) || !((String) mime).startsWith("video")) {
                    continue;
                }
                Object itag = field(format, "e");
                if (itag instanceof Integer) {
                    itags.add((Integer) itag);
                }
            }
            log(label + ": " + itags.size() + " video formats, itags=" + itags);
        } catch (Throwable t) {
            log(label + ": could not read the itag field (" + t + ")");
        }
    }

    /**
     * Makes the injected HDR tiers look like their standard-dynamic-range siblings, so the app's
     * HDR gating stops excluding them -- the 8K tier in particular, since YouTube now offers 8K
     * *only* as HDR AV1 (itag 702) and this headset's panels are not HDR.
     *
     * <p>Both of the app's HDR tests are covered, because which one is live depends on an
     * experiment flag (`onu.i`, set from rollout 45374643) that is not observable from here:
     *
     * <p>- `onu.U()` reads `colorInfo.transferCharacteristics` and calls the format HDR when it is
     * PQ (10) or HLG (2) -- so the color info is cleared; - `onu.V()` asks whether the format's
     * **itag** is in the app's HDR set (`opt.m`) -- so the itag is relabelled to the sibling above.
     *
     * <p>Evidence this is the right question rather than a guess: with both an AV1 HDR 2160p entry
     * (701) and a VP9 SDR 2160p60 entry (315) in the injected ladder, the player fetched 315; and
     * `axh` (the app's format-filter dispatcher) contains HDR-aware filters, e.g. case 14 `return
     * onu.g(k, l) <= 480 && !onu.V(yvhVar)`.
     *
     * <p>The URL is untouched, so the bytes fetched are still the real 8K AV1 stream; only the
     * app's classification of it changes. The itag it reports in telemetry becomes the sibling's,
     * which is why this logs every rewrite instead of doing it quietly.
     */
    private static void makeHdrTierSdr(Object streamingData) {
        try {
            // Which itags the ladder already offers: if the SDR sibling is present there is
            // nothing to do, and relabelling anyway would put two entries under one itag.
            java.util.Set<Integer> present = new java.util.HashSet<Integer>();
            for (Object format : allFormats(streamingData)) {
                Object itag = field(format, "e");
                if (itag instanceof Integer) {
                    present.add((Integer) itag);
                }
            }

            int relabelled = 0;
            int clearedColor = 0;
            for (Object format : allFormats(streamingData)) {
                boolean video = false;
                try {
                    Object mimeField = field(format, "g");
                    video = mimeField instanceof String && ((String) mimeField).startsWith("video");
                } catch (Throwable ignored) {
                    // treated as not video
                }
                if (!video) {
                    continue;
                }
                // The color info path: a null ColorInfo makes onu.U() fall through to "not HDR".
                // Cleared on every video format, which is what the verified build did; onu.U() is
                // a per-format predicate, so this is belt-and-braces against a ladder-level HDR
                // decision rather than the mechanism itself.
                if (field(format, "A") != null && tryAssign(format, "A", null)) {
                    clearedColor++;
                }
                Object itagValue = field(format, "e");
                if (!(itagValue instanceof Integer)) {
                    continue;
                }
                int itag = (Integer) itagValue;
                int sibling = 0;
                for (int[] pair : HDR_TO_SDR_ITAG) {
                    if (pair[0] == itag) {
                        sibling = pair[1];
                        break;
                    }
                }
                if (sibling == 0 || present.contains(Integer.valueOf(sibling))) {
                    continue;
                }
                if (tryAssign(format, "e", Integer.valueOf(sibling))) {
                    relabelled++;
                    log(
                            "8K: itag "
                                    + itag
                                    + " relabelled to its SDR sibling "
                                    + sibling
                                    + " (same codec, same size; the URL keeps itag="
                                    + itag
                                    + ")");
                }
            }
            if (relabelled > 0) {
                log(
                        "8K: HDR gate aside -- "
                                + relabelled
                                + " format(s) relabelled to their SDR"
                                + " siblings ("
                                + clearedColor
                                + " color info(s) cleared). Measured"
                                + " 2026-10-11: YouTube serves 8K only as HDR AV1, the app's own ladder tops"
                                + " out at the same itag 702, this display is not HDR, and it is the legacy"
                                + " player path that then drops the tier.");
            }
        } catch (Throwable t) {
            log("8K: HDR relabel failed: " + t);
        }
    }

    private SpoofStreams() {}

    /**
     * The `enable8k=false` patch option, injected as a call at Application.onCreate. The field and
     * the option default to the same thing (on), so this only ever expresses the non-default, and a
     * build that missed the call still behaves as documented.
     */
    public static void disable8kExperiment() {
        experiment8k = false;
        log(
                "8K: HDR/level relaxation disabled by the patch option (the injected ladder is used"
                        + " as the server sent it, which caps this device at 4K)");
    }

    /** True while a spoofed stream is what the player is being given. */
    public static boolean injected() {
        return injected;
    }

    /**
     * Always false: the answer both "force the onesie/SABR player" branches of the app's own
     * decision take.
     *
     * <p>`PlatypusEnablingManager.c(opw, oqd)` decides between the onesie (SABR) player and the
     * legacy player, and it *already* handles the case this project creates -- a streaming data
     * with no SABR URI -- with a branch of its own:
     *
     * <p>```java if (opwVar.ap()) return ENABLED; // forced on by the player config if (oqdVar.C()
     * && this.a.R().k) return ENABLED; // forced on by a second config if (!opwVar.ak()) return
     * DISABLED_BY_PLAYER_CONFIG; Optional u = oqdVar.n(); // the SABR URI if (u.isEmpty() ||
     * authority(u).isEmpty()) return DISABLED_BY_SABR_STREAMING_URI; return (!oqdVar.p ||
     * this.a.R().j) ? ENABLED : DISABLED_DUE_TO_OFFLINE; ```
     *
     * <p>The first two branches short-circuit it: the app's config says "use onesie", so it never
     * looks at whether there is a onesie URL to use. Neutralising exactly those two lets the *data*
     * decide, which is the invariant that matters:
     *
     * <p>- injected streaming data (the VISIONOS fetch, SABR URI blanked) -> no URI ->
     * `DISABLED_BY_SABR_STREAMING_URI` -> the legacy player fetches the adaptive URLs. This is the
     * original Phase 3 design and it keeps the app's own `pcmp=su` telemetry intact. - the app's
     * own (native) streaming data -> URI present -> `ENABLED` -> the onesie player, exactly as
     * stock.
     *
     * <p>The previous revision instead forced `a(...)`/`b(...)` to return false outright. That is
     * what made a failed spoof fetch fatal: SABR was off unconditionally, so the app was left
     * holding its own SABR-era response, whose formats carry no URLs, with the only player that can
     * use it switched off -- `ERR_INVALID_URL`, `shost.null`, retry forever, no playback.
     */
    public static boolean sabrNotForced() {
        return false;
    }

    /**
     * The media data source's UMP rewrite flag: `this.c = <wrapped source> instanceof ayd`, which
     * makes every `/videoplayback` fetch go out as `?ump=1&range=<off>-<end>` instead of a plain
     * range GET.
     *
     * <p>A UMP envelope request needs a SABR session behind it. The injected adaptive URLs have
     * none, so for them the flag must be false (the app's own UMP request against a plain adaptive
     * URL is what produced `rc.400`/`staleconfig`). The app's own stream is the opposite case: its
     * UMP framing is how the SABR stream is fetched, so the flag must keep whatever value the app
     * computed.
     */
    public static int umpRewriteAllowed(int appValue) {
        return injected ? 0 : appValue;
    }

    /** Diagnostic entry point (Application.onCreate): probes a fixed video id. */
    public static void probe() {
        new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    String id = DEFAULT_VIDEO_ID;
                                    byte[] response = fetchSpoofedPlayerResponse(id);
                                    log(
                                            "probe videoId="
                                                    + id
                                                    + " bytes="
                                                    + response.length
                                                    + " streamingDataBytes="
                                                    + (extractFirstMessage(response) == null
                                                            ? 0
                                                            : extractFirstMessage(response)
                                                                    .length));
                                } catch (Throwable t) {
                                    log("probe failed: " + t);
                                }
                            }
                        },
                        "ytvr-probe")
                .start();
    }

    /**
     * Hooked into oqb.<init>(zjp): replace the response's StreamingData with the spoofed one for
     * the video being prepared. Never throws into the app.
     *
     * <p>Fails **open**: when the spoofed fetch cannot produce playable stream data, the app's own
     * response is left completely untouched (its `h`, its ad slots, and -- via {@link #injected()}
     * -- the shape of its media requests), so the app plays its own stream exactly as if this patch
     * were not installed. The pre-roll comes back with it; that is the right trade against having
     * no playback at all, and it is what the previous revision got wrong (it disabled SABR
     * unconditionally, which is fatal once the spoof stops arriving).
     */
    public static void augmentPlayerResponse(Object playerResponse) {
        try {
            if (playerResponse == null) {
                return;
            }
            String videoId = readVideoId(playerResponse);
            logListFields(playerResponse, videoId);
            // The ladder the app's own request was served, before anything of ours replaces it.
            // If the stock app plays 8K and the spoofed one does not, this is what says whether
            // the difference is the *stream* (a different itag family) or the *player path*.
            logLadder("native ladder", field(playerResponse, "h"));
            if (videoId == null || videoId.isEmpty()) {
                if (!reported) {
                    reported = true;
                    log("augment: no video id (fields g=" + field(playerResponse, "g") + ")");
                }
                return;
            }

            Object streamingData = CACHE.get(videoId);
            if (streamingData == null) {
                if (System.currentTimeMillis() < nextAttemptAt) {
                    fallBackToNative(videoId, "the spoof is in its retry cooldown", true);
                    return;
                }
                byte[] response = fetchSpoofedPlayerResponse(videoId);
                byte[] message = extractFirstMessage(response);
                if (message == null || message.length == 0) {
                    fallBackToNative(
                            videoId,
                            "the spoofed /player response carried no"
                                    + " streamingData ("
                                    + response.length
                                    + " bytes)");
                    return;
                }
                Object parsed = parseStreamingData(message);
                if (!hasPlayableUrl(parsed)) {
                    fallBackToNative(
                            videoId,
                            "the spoofed streamingData has no absolute media"
                                    + " URL, so the legacy player would have nothing to fetch");
                    return;
                }
                if (experiment8k) {
                    relaxAv1Levels(parsed);
                    makeHdrTierSdr(parsed);
                }
                logLadder("injected ladder", parsed);
                logInjectedUrlShape(parsed);
                log(
                        "augment: spoofed streamingData for "
                                + videoId
                                + " ("
                                + message.length
                                + " bytes)");
                CACHE.put(videoId, parsed);
                streamingData = parsed;
                // Working: a wall that lifts is picked up on the first video after it does.
                nextAttemptAt = 0L;
                backoffMs = INITIAL_BACKOFF_MS;
            }

            assign(playerResponse, "h", streamingData);
            // Only now, with the swap actually done, may anything of the app's own setup be
            // touched. `injected` also drives the media request shape (plain GET, no UMP
            // envelope); blanking the ad-slot list is only safe alongside an injected stream,
            // because with the native SABR setup live a blanked list resets the playback
            // director (bestVideoFormat=0, STOPPAGE_DIRECTOR_RESET_INTERNALLY -- measured).
            injected = true;
            clearAdSlots(playerResponse);
        } catch (Throwable t) {
            log("augment failed: " + t);
            // No swap can have happened, so fail open rather than leave a native stream on the
            // legacy player.
            injected = false;
        }
    }

    /**
     * Leaves the app's own player response alone and puts the media request shape back to the app's
     * own, so the stock SABR/onesie path plays. Logged loudly, because "the app works but the
     * pre-roll is back" is otherwise indistinguishable from the patch not being installed.
     *
     * <p>[quiet] is for the retry cooldown, where the reason is already known and repeating the
     * paragraph for every video would bury the rest of the log.
     */
    private static void fallBackToNative(String videoId, String reason) {
        fallBackToNative(videoId, reason, false);
    }

    private static void fallBackToNative(String videoId, String reason, boolean quiet) {
        boolean wasInjected = injected;
        injected = false;
        if (quiet) {
            return;
        }
        long wait = backoffMs;
        nextAttemptAt = System.currentTimeMillis() + wait;
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        log(
                "augment: "
                        + reason
                        + " for "
                        + videoId
                        + " -- FALLING BACK to the app's own"
                        + " stream: nothing in its response is touched and media requests keep their native"
                        + " shape, so playback is the unpatched app's (the pre-roll included). The spoof is"
                        + " unavailable, not the video. Next spoof attempt at most "
                        + (wait / 60000L)
                        + " minute(s) away."
                        + (wasInjected
                                ? " (a previous response in this session had been spoofed)"
                                : ""));
        reportWallOnce(videoId);
    }

    /**
     * One extra request per process, and only after a spoofed fetch has already failed, to name the
     * reason in the log.
     *
     * <p>The player request asks for `streamingData` alone, so the server's explanation -- a
     * `playabilityStatus` such as `LOGIN_REQUIRED` / "Sign in to confirm you're not a bot" -- is
     * filtered out of it. What arrives instead is HTTP 200 with an empty body, which on its own
     * says nothing about why. This asks for JSON and logs status and reason once, so the condition
     * is visible in a logcat capture instead of being inferred.
     */
    private static void reportWallOnce(String videoId) {
        if (!WALL_REPORTED.compareAndSet(false, true)) {
            return;
        }
        try {
            byte[] json =
                    post(
                            "player?prettyPrint=false",
                            "{\"context\":{\"client\":{"
                                    + clientFields(visitorId())
                                    + "}},"
                                    + "\"contentCheckOk\":true,\"racyCheckOk\":true,"
                                    + "\"videoId\":\""
                                    + videoId
                                    + "\"}");
            org.json.JSONObject root =
                    new org.json.JSONObject(new String(json, StandardCharsets.UTF_8));
            org.json.JSONObject playability = root.optJSONObject("playabilityStatus");
            log(
                    "diagnosis: the VISIONOS /player request says status="
                            + (playability == null
                                    ? "(none)"
                                    : playability.optString("status", "(none)"))
                            + " reason=\""
                            + (playability == null ? "" : playability.optString("reason", ""))
                            + "\""
                            + " streamingData="
                            + (root.optJSONObject("streamingData") == null ? "ABSENT" : "present")
                            + " -- while it is not OK, the app plays its own stream, pre-roll included;"
                            + " the spoof resumes on its own when the server serves this client again");
        } catch (Throwable t) {
            log("diagnosis failed: " + t);
        }
    }

    /**
     * Hooked into oqb.<init>(zjp) in native-formats mode. **Retired: it cannot work on this app
     * version.**
     *
     * <p>It was meant to keep the app's own ANDROID_VR streaming data -- whose format list carries
     * 8K VP9 tiers the VISIONOS list lacks -- and blank its SABR URI so the legacy player would
     * fetch plain adaptive URLs. Measured 2026-10-10: the native response's formats carry **no
     * absolute URL at all** (SABR-era metadata), so the legacy player fetches a bare `?cpn=..&rn=N`
     * forever. The check below is that measurement, kept as a guard: this mode can no longer blank
     * a SABR URI and break playback, it can only report that it is dead.
     */
    public static void nativeFormatsNoSabr(Object playerResponse) {
        if (playerResponse == null) {
            return;
        }
        try {
            Object streamingData = field(playerResponse, "h");
            if (streamingData == null) {
                log("native mode: the app's response carries no streamingData");
                return;
            }
            if (!hasPlayableUrl(streamingData)) {
                log(
                        "native mode: RETIRED -- the app's own formats carry no absolute media URL"
                                + " (SABR-era), so blanking the SABR URI would leave the legacy player with"
                                + " nothing to fetch. Nothing was changed; playback stays native.");
                return;
            }
            clearAdSlots(playerResponse);
            clearSabrUri(streamingData);
            injected = true;
            log("native mode: the app's own streamingData kept, SABR URI blanked");
        } catch (Throwable t) {
            log("native mode failed: " + t);
            injected = false;
        }
    }

    /**
     * Clears the ad-slot list of the app's own player response and nothing else.
     *
     * <p>**Not installed by any patch option any more.** It is the "spoof off, but still ad-free"
     * mode, and it does not work on this app version: the pre-roll is scheduled *through* the
     * native SABR setup, so blanking its ad-slot list from under it resets the playback director
     * (`bestVideoFormat=0`, `STOPPAGE_DIRECTOR_RESET_INTERNALLY` -- measured). It is kept, and not
     * wired up, as the A/B reference for that finding. With the spoof off the patch now leaves the
     * player response completely alone, so "off" really means stock.
     */
    public static void clearPlayerAdSlots(Object playerResponse) {
        if (playerResponse == null) {
            return;
        }
        clearAdSlots(playerResponse);
    }

    /**
     * True when at least one format in this streaming data carries an absolute media URL.
     *
     * <p>This is what keeps a *successful-looking* fetch from breaking playback. Both SABR-era
     * responses -- the app's own ANDROID_VR one, and whatever a future profile returns -- list
     * formats with metadata but no URL; injecting one of those leaves the legacy player with
     * nothing to fetch (measured: an endless bare `?cpn=..&rn=N` retry). Anything injected has to
     * be fetchable.
     */
    private static boolean hasPlayableUrl(Object streamingData) {
        for (String listField : new String[] {"e", "f"}) {
            Object list;
            try {
                list = field(streamingData, listField);
            } catch (Throwable t) {
                continue;
            }
            if (!(list instanceof java.util.List)) {
                continue;
            }
            for (Object format : (java.util.List<?>) list) {
                if (urlOf(format) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The media URL held by one Format proto, or null when it has none. */
    private static String urlOf(Object format) {
        try {
            for (Class<?> c = format.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class
                            || java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object value = f.get(format);
                    if (value instanceof String
                            && ((String) value).contains("googlevideo.com/videoplayback")) {
                        return (String) value;
                    }
                }
            }
        } catch (Throwable t) {
            // no URL found
        }
        return null;
    }

    /**
     * Blanks the ad-slot list of the app's own player response (`zjp.n`).
     *
     * <p>That list is where the client-side pre-roll lives. On device the app was observed fetching
     * a *separate* video as the ad (the media fetched during the skip-ad countdown belongs to a
     * video id that was never requested by the user), and the ad UI ticks down against it, so the
     * ad is scheduled from the app's own response -- the one response the spoof never replaced
     * (only its {@code streamingData} field is swapped). `mgn.g(zjp, [SLOT_TYPE_PLAYER_BYTES])`
     * streams exactly this field, as do `mkz.a` (the layout bootstrap the `Hide ads` patch neuters)
     * and `mqx` (the ad-break holder) -- so blanking it here removes the data all three of them
     * read, instead of fighting each consumer.
     *
     * <p>The field is an immutable protobuf list, so it is replaced with the app's own canonical
     * empty list (`wxe.emptyProtobufList()`), which is an instance of the app's list type.
     */
    private static void clearAdSlots(Object playerResponse) {
        try {
            Object slots = field(playerResponse, "n");
            int size = slots instanceof java.util.List ? ((java.util.List<?>) slots).size() : -1;
            if (size <= 0) {
                log("ad slots: the app's response carries none (size=" + size + ")");
            } else {
                Class<?> owner = null;
                for (Class<?> c = playerResponse.getClass(); c != null; c = c.getSuperclass()) {
                    try {
                        c.getMethod("emptyProtobufList");
                        owner = c;
                        break;
                    } catch (NoSuchMethodException ignored) {
                        // keep walking up: it lives on the generated-message base class
                    }
                }
                if (owner == null) {
                    log(
                            "ad slots: no emptyProtobufList() on "
                                    + playerResponse.getClass().getName()
                                    + " -- cannot blank");
                    return;
                }
                assign(playerResponse, "n", owner.getMethod("emptyProtobufList").invoke(null));
                log(
                        "ad slots: cleared "
                                + size
                                + " entr"
                                + (size == 1 ? "y" : "ies")
                                + " from the app's own player response (the pre-roll lives here)");
            }
        } catch (Throwable t) {
            log("clearAdSlots failed: " + t);
        }
    }

    /**
     * Reports every non-empty repeated field of a player response.
     *
     * <p>Diagnostic for finding the ad fields: the app fetches the pre-roll as a *second* player
     * response (a video id the user never asked for), so the two responses the hook sees in one
     * session -- the content's and the ad's -- can be diffed. Whatever is populated for the content
     * and empty for the ad (client-side ad placements / slots) is the ad carrier; the field names
     * are obfuscated, so the diff is the only reliable way to name them.
     */
    private static void logListFields(Object playerResponse, String videoId) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Class<?> c = playerResponse.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                            || !java.util.List.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object value = f.get(playerResponse);
                    if (value instanceof java.util.List) {
                        int size = ((java.util.List<?>) value).size();
                        if (size > 0) {
                            sb.append(f.getName()).append('=').append(size).append(' ');
                        }
                    }
                }
            }
            log(
                    "lists for "
                            + videoId
                            + ": "
                            + (sb.length() == 0 ? "(none)" : sb.toString().trim()));
            // `m` is populated only on the response for the ad video the app fetches as the
            // pre-roll (observed: content response n=2/m empty, ad response m=1) -- i.e. it is a
            // reliable "this response is an ad" marker.
            Object adMarker = field(playerResponse, "m");
            int adMarkers =
                    adMarker instanceof java.util.List ? ((java.util.List<?>) adMarker).size() : -1;
            if (adMarkers > 0) {
                log(
                        "ad response detected for "
                                + videoId
                                + " (m="
                                + adMarkers
                                + "): this player response is the pre-roll itself");
            }
        } catch (Throwable t) {
            log("logListFields failed: " + t);
        }
    }

    /** Reads zjp.g.c -- the video id inside the VideoDetails proto. */
    private static String readVideoId(Object playerResponse) throws Exception {
        Object videoDetails = field(playerResponse, "g");
        if (videoDetails == null) {
            return null;
        }
        Object id = field(videoDetails, "c");
        return id instanceof String ? (String) id : null;
    }

    /**
     * Reports the shape of the injected media URLs (host, path, parameter names) and the set of
     * itags present, so the quality ceiling the spoof actually delivers is visible in the log next
     * to what the app requested. Itags are the cheap way to see that: the VISIONOS list is 4K-max
     * in VP9 (itag 315/337) with 8K only as AV1 (702), which is the whole 4K ceiling.
     */
    private static void logInjectedUrlShape(Object streamingData) {
        try {
            StringBuilder itags = new StringBuilder();
            String firstUrl = null;
            for (String listField : new String[] {"e", "f"}) {
                Object list = field(streamingData, listField);
                if (!(list instanceof java.util.List)) {
                    continue;
                }
                for (Object format : (java.util.List<?>) list) {
                    String url = urlOf(format);
                    if (url == null) {
                        continue;
                    }
                    if (firstUrl == null) {
                        firstUrl = url;
                    }
                    itags.append(param(url, "itag")).append(',');
                }
            }
            if (firstUrl == null) {
                log("injected: no media URL found in the injected streamingData");
                return;
            }
            int q = firstUrl.indexOf('?');
            log(
                    "injected: "
                            + firstUrl.substring(0, q < 0 ? firstUrl.length() : q)
                            + " id="
                            + param(firstUrl, "id")
                            + " itag="
                            + param(firstUrl, "itag")
                            + " c="
                            + param(firstUrl, "c")
                            + " paramsPresent="
                            + paramsOf(firstUrl));
            log("injected: itags=[" + itags.toString() + "]");
        } catch (Throwable t) {
            log("injected shape failed: " + t);
        }
    }

    /** Value of one query parameter, for identifying an injected media URL in the log. */
    private static String param(String url, String name) {
        int at = url.indexOf('?' + name + '=');
        if (at < 0) {
            at = url.indexOf('&' + name + '=');
        }
        if (at < 0) {
            return "-";
        }
        at += name.length() + 2;
        int end = url.indexOf('&', at);
        return url.substring(at, end < 0 ? url.length() : end);
    }

    private static String paramsOf(String url) {
        StringBuilder sb = new StringBuilder();
        int q = url.indexOf('?');
        if (q < 0) {
            return "";
        }
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                sb.append(pair, 0, eq).append(',');
            }
        }
        return sb.toString();
    }

    /**
     * One visitor id for the whole session, fetched once. Every spoofed request that carries
     * visitor data must carry the *same* one: the media URLs the app then fetches belong to that
     * visitor's session, so a request under a different one would describe a different session.
     */
    public static synchronized String visitorId() {
        if (visitorId == null) {
            visitorId = fetchVisitorId();
        }
        return visitorId;
    }

    /**
     * POSTs /player as the VISIONOS client and returns the raw response bytes.
     *
     * <p>Deliberately bare: no integrity token. The token chain this class used to carry was
     * measured on 2026-10-11 **not** to clear the bot gate for this client -- a bare request, a
     * video-bound token and a visitor-bound token were all answered identically (HTTP 200, empty
     * body, LOGIN_REQUIRED) -- so there is nothing here to switch back on.
     */
    private static byte[] fetchSpoofedPlayerResponse(String videoId) throws Exception {
        return postPlayer(videoId, visitorId());
    }

    /**
     * Rewrites the AV1 level claim of the injected formats to the level this device's decoder
     * advertises, which is what the app's codec check consults. See {@link #experiment8k}: on its
     * own it was measured **not** to unlock 8K, so it is the smaller half of that option. Logged,
     * because a silent rewrite of a codec string is exactly the sort of thing that should never
     * happen invisibly.
     */
    private static void relaxAv1Levels(Object streamingData) {
        try {
            int rewritten = 0;
            for (Object format : allFormats(streamingData)) {
                String mime = av1MimeType(format);
                if (mime == null) {
                    continue;
                }
                int level = av1Level(mime);
                if (level <= AV1_LEVEL_5_1) {
                    continue;
                }
                String relaxed = withAv1Level(mime, AV1_LEVEL_5_1);
                if (relaxed != null && setCodecString(format, mime, relaxed)) {
                    rewritten++;
                }
            }
            if (rewritten > 0) {
                log(
                        "8K: relaxed the AV1 level claim on "
                                + rewritten
                                + " format(s) to "
                                + AV1_LEVEL_5_1
                                + " (level 5.1, what this device's decoder advertises)."
                                + " Measured 2026-10-11: this does NOT make the app offer the 8K tier, so"
                                + " the 4K ceiling is not a codec-level check.");
            }
        } catch (Throwable t) {
            log("8K: AV1 level relaxation failed: " + t);
        }
    }

    /** Best-effort field write: false when the field is not there, never throws. */
    private static boolean tryAssign(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            if (f == null) {
                return false;
            }
            f.set(target, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Every format in both of the streaming data's format lists. */
    private static java.util.List<Object> allFormats(Object streamingData) throws Exception {
        java.util.List<Object> formats = new java.util.ArrayList<Object>();
        for (String listField : new String[] {"e", "f"}) {
            Object list = field(streamingData, listField);
            if (list instanceof java.util.List) {
                formats.addAll((java.util.List<?>) list);
            }
        }
        return formats;
    }

    /** The AV1 mime/codec string held by one Format, or null when it is not AV1. */
    private static String av1MimeType(Object format) {
        try {
            for (Class<?> c = format.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class
                            || java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object value = f.get(format);
                    if (value instanceof String) {
                        String text = (String) value;
                        int at = text.indexOf("av01.");
                        if (at >= 0 && text.indexOf('/') >= 0) {
                            return text;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // no AV1 mime
        }
        return null;
    }

    /**
     * The level from an AV1 codec string: `av01.<profile>.<level><tier>.<bit depth>...`, e.g.
     * `av01.0.17M.10.0.110.0` -> 17. Returns 0 when the shape is not recognised.
     */
    private static int av1Level(String mime) {
        try {
            int at = mime.indexOf("av01.");
            String[] parts = mime.substring(at + 5).split("\\.");
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < parts[1].length(); i++) {
                char ch = parts[1].charAt(i);
                if (ch >= '0' && ch <= '9') {
                    digits.append(ch);
                }
            }
            return digits.length() == 0 ? 0 : Integer.parseInt(digits.toString());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** The same codec string with its level replaced, or null when it cannot be parsed. */
    private static String withAv1Level(String mime, int level) {
        try {
            int at = mime.indexOf("av01.");
            String[] parts = mime.substring(at + 5).split("\\.");
            String tier = parts[1].replaceAll("[0-9]", "");
            String rest = mime.substring(at + 5 + parts[0].length() + 1 + parts[1].length());
            return mime.substring(0, at) + "av01." + parts[0] + "." + level + tier + rest;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Replaces the codec string on the Format field that held [old]. */
    private static boolean setCodecString(Object format, String old, String replacement) {
        try {
            for (Class<?> c = format.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class
                            || java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    f.setAccessible(true);
                    if (old.equals(f.get(format))) {
                        f.set(format, replacement);
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            log("8K: could not rewrite a codec string: " + t);
        }
        return false;
    }

    /**
     * The /player POST: the VISIONOS client context and the video id, and nothing else -- see
     * {@link #fetchSpoofedPlayerResponse} for why no integrity token is attached.
     */
    private static byte[] postPlayer(String videoId, String visitor) throws Exception {
        StringBuilder body = new StringBuilder(1024);
        body.append("{\"context\":{\"client\":{").append(clientFields(visitor)).append("}},");
        body.append("\"contentCheckOk\":true,\"racyCheckOk\":true,")
                .append("\"videoId\":\"")
                .append(videoId)
                .append("\"}");
        return post(PLAYER_ROUTE, body.toString());
    }

    private static String fetchVisitorId() {
        try {
            byte[] body =
                    post(VISITOR_ROUTE, "{\"context\":{\"client\":{" + clientFields(null) + "}}}");
            return extract(new String(body, StandardCharsets.UTF_8), "\"visitorData\":\"");
        } catch (Throwable t) {
            log("visitorId fetch failed: " + t);
            return null;
        }
    }

    private static String clientFields(String visitorData) {
        return clientFields(visitorData, "RealityDevice17,1", "1.03");
    }

    private static String clientFields(
            String visitorData, String deviceModel, String clientVersion) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("\"deviceMake\":\"Apple\",\"deviceModel\":\"").append(deviceModel).append("\",");
        sb.append("\"clientName\":\"VISIONOS\",\"clientVersion\":\"")
                .append(clientVersion)
                .append("\",");
        if (visitorData != null && !visitorData.isEmpty()) {
            sb.append("\"visitorData\":\"").append(visitorData).append("\",");
        }
        sb.append("\"osName\":\"visionOS\",\"osVersion\":\"26.6.1\",\"hl\":\"en\",\"gl\":\"US\"");
        return sb.toString();
    }

    /**
     * One-shot identity matrix (development only): fetches two videos (a known 4K60 and a known 8K)
     * under several candidate VISIONOS identities plus an ANDROID_VR control, and logs each
     * response's playability status and max format height. The control row reports each video's
     * true ceiling on the app's native client; the winning VISIONOS row is the one matching it on
     * the 8K video. Runs from Application.onCreate on its own thread.
     */
    public static void identityMatrixProbe() {
        new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    String visitor = visitorId();
                                    log(
                                            "matrix: visitor="
                                                    + (visitor == null
                                                            ? "null"
                                                            : visitor.length() + " chars"));
                                    // Control: what the app's own client is offered unauthenticated
                                    // (it is
                                    // bot-walled; the app's real requests are authenticated).
                                    probeRow(
                                            "vr control (ANDROID_VR 1.88.10)",
                                            "\"clientName\":\"ANDROID_VR\",\"clientVersion\":\"1.88.10\","
                                                    + "\"deviceMake\":\"Oculus\",\"deviceModel\":\"Quest 3\","
                                                    + "\"osName\":\"Android\",\"osVersion\":\"12\",\"hl\":\"en\",\"gl\":\"US\"",
                                            null);
                                    for (String[] row : PROBE_IDENTITIES) {
                                        // The visitor id belongs to the VISIONOS session; other
                                        // clients must
                                        // not present it.
                                        boolean isVos = row[0].startsWith("vos ");
                                        probeRow(
                                                row[0],
                                                isVos ? clientFields(visitor) : row[1],
                                                isVos ? visitor : null);
                                    }
                                    log("matrix: done");
                                } catch (Throwable t) {
                                    log("matrix failed: " + t);
                                }
                            }
                        },
                        "ytvr-matrix")
                .start();
    }

    /** Fetches both probe videos as one client identity and logs the result summary. */
    private static void probeRow(String label, String clientFragment, String visitor) {
        for (String videoId : new String[] {DEFAULT_VIDEO_ID, PROBE_VIDEO_ID}) {
            // Three route shapes: the device 404'd `prettyPrint=false` (2026-10-10) while the
            // laptop returned 200 for it, so fall through the variants and log whatever
            // non-200 body comes back -- one run must identify the working shape.
            String[] routes = {
                "player?prettyPrint=false", "player", "player?alt=json",
            };
            for (String route : routes) {
                try {
                    String body =
                            "{\"context\":{\"client\":{"
                                    + clientFragment
                                    + "}},"
                                    + "\"contentCheckOk\":true,\"racyCheckOk\":true,\"videoId\":\""
                                    + videoId
                                    + "\"}";
                    byte[] json = post(route, body);
                    String summary = summarizeJson(json);
                    log(
                            "matrix " + label + " video=" + videoId + " route=" + route + " -> "
                                    + summary);
                    if (!summary.contains("streamingData=none")
                            && !summary.startsWith("unparseable")) {
                        break;
                    }
                } catch (Throwable t) {
                    log(
                            "matrix "
                                    + label
                                    + " video="
                                    + videoId
                                    + " route="
                                    + route
                                    + " failed: "
                                    + t);
                }
            }
        }
    }

    /**
     * status + format count + max height + the top format's itag/qualityLabel of a /player JSON.
     */
    private static String summarizeJson(byte[] bytes) {
        try {
            org.json.JSONObject root =
                    new org.json.JSONObject(new String(bytes, StandardCharsets.UTF_8));
            org.json.JSONObject ps = root.optJSONObject("playabilityStatus");
            String status = ps == null ? "?" : ps.optString("status", "?");
            org.json.JSONObject sd = root.optJSONObject("streamingData");
            org.json.JSONArray formats = sd == null ? null : sd.optJSONArray("adaptiveFormats");
            if (formats == null) {
                // No formats: this is the diagnostic case (a bot wall, an error payload or a
                // key wall), so include the body excerpt in the log.
                String excerpt =
                        new String(bytes, StandardCharsets.UTF_8)
                                .substring(0, Math.min(bytes.length, 400))
                                .replace('\n', ' ');
                return "status=" + status + " streamingData=none body=" + excerpt;
            }
            int max = 0;
            String top = "-";
            StringBuilder high = new StringBuilder();
            int withUrl = 0;
            int count = formats.length();
            for (int i = 0; i < count; i++) {
                org.json.JSONObject f = formats.getJSONObject(i);
                int h = f.optInt("height", 0);
                boolean hasUrl = f.has("url") && !f.optString("url", "").isEmpty();
                if (hasUrl) {
                    withUrl++;
                }
                if (h > max) {
                    max = h;
                    top =
                            f.optInt("itag", 0)
                                    + "/"
                                    + f.optString("qualityLabel", "?")
                                    + (hasUrl ? "/url" : "/NO-URL");
                }
                if (h >= 2160) {
                    String mime = f.optString("mimeType", "?");
                    int slash = mime.indexOf('/') + 1;
                    int semi = mime.indexOf(';', slash);
                    String codec =
                            semi < 0
                                    ? mime.substring(Math.max(slash, 0))
                                    : mime.substring(slash, semi);
                    high.append(f.optInt("itag", 0))
                            .append('/')
                            .append(codec)
                            .append('/')
                            .append(h)
                            .append(hasUrl ? "" : "/nourl")
                            .append(' ');
                }
            }
            return "status="
                    + status
                    + " formats="
                    + count
                    + " urls="
                    + withUrl
                    + " maxH="
                    + max
                    + " top="
                    + top
                    + " high={ "
                    + high.toString().trim()
                    + " }";
        } catch (Throwable t) {
            return "unparseable (" + bytes.length + " bytes): " + t;
        }
    }

    /**
     * The request filters on a single field, so the response's first top-level length-delimited
     * field is the streamingData message; return its payload.
     */
    private static byte[] extractFirstMessage(byte[] bytes) {
        int at = 0;
        while (at < bytes.length) {
            long tag = 0;
            int shift = 0;
            while (at < bytes.length) {
                int b = bytes[at++] & 0xFF;
                tag |= ((long) (b & 0x7F)) << shift;
                shift += 7;
                if ((b & 0x80) == 0) {
                    break;
                }
            }
            int wireType = (int) (tag & 7);
            if (wireType == 2) {
                long length = 0;
                shift = 0;
                while (at < bytes.length) {
                    int b = bytes[at++] & 0xFF;
                    length |= ((long) (b & 0x7F)) << shift;
                    shift += 7;
                    if ((b & 0x80) == 0) {
                        break;
                    }
                }
                if (length <= 0 || at + length > bytes.length) {
                    return null;
                }
                byte[] payload = new byte[(int) length];
                System.arraycopy(bytes, at, payload, 0, (int) length);
                return payload;
            }
            // Skip other wire types (varint / fixed32 / fixed64).
            if (wireType == 0) {
                while (at < bytes.length && (bytes[at++] & 0x80) != 0) {
                    // keep reading the varint
                }
            } else if (wireType == 5) {
                at += 4;
            } else if (wireType == 1) {
                at += 8;
            } else {
                return null;
            }
        }
        return null;
    }

    /** Parses spoofed bytes into the app's own StreamingData proto class. */
    private static Object parseStreamingData(byte[] message) throws Exception {
        Class<?> streamingData = Class.forName(STREAMING_DATA_CLASS);
        Class<?> registry = Class.forName("com.google.protobuf.ExtensionRegistryLite");
        Object generated = registry.getMethod("getGeneratedRegistry").invoke(null);
        Method parseFrom = streamingData.getMethod("parseFrom", ByteBuffer.class, registry);
        Object parsed = parseFrom.invoke(null, ByteBuffer.wrap(message), generated);
        clearSabrUri(parsed);
        return parsed;
    }

    /**
     * Blanks the serverAbrStreamingUrl of the spoofed streaming data.
     *
     * <p>The VISIONOS response carries BOTH plain adaptive formats and a SABR/onesie URL: a proto
     * dump of the live response (2026-10-09) shows the message's only `sabr=1` URL as a 976-byte
     * string field, alongside 30 adaptive `Format` messages. The plan assumed there was no SABR URL
     * at all, so the whole message was injected -- which leaves the app holding a onesie stream to
     * fall back to, and that is the stream class the backend stitches a pre-roll into (it is also
     * why `disableSABR` was needed in the first place). The plan's original Phase 3 design was to
     * clear it: "clear `k` (no SABR URI, which is what makes `PlatypusEnablingManager` report
     * `DISABLED_BY_SABR_STREAMING_URI` and fall back to the legacy player)". This implements that.
     *
     * <p>The field is located by value (`sabr=1`) rather than by name, so a rename cannot silently
     * skip it, and the log says which field was cleared and how big it was.
     */
    private static void clearSabrUri(Object streamingData) {
        try {
            for (Class<?> c = streamingData.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class
                            || java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object value = f.get(streamingData);
                    if (value instanceof String && ((String) value).contains("sabr=1")) {
                        log(
                                "clearing the SABR stream URI (field "
                                        + f.getName()
                                        + ", "
                                        + ((String) value).length()
                                        + " chars): the spoofed client must not"
                                        + " leave an ad-bearing onesie stream reachable");
                        f.set(streamingData, "");
                    }
                }
            }
        } catch (Throwable t) {
            log("clearSabrUri failed: " + t);
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = findField(target.getClass(), name);
        return f == null ? null : f.get(target);
    }

    private static void assign(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        if (f == null) {
            throw new NoSuchFieldException(target.getClass().getName() + "." + name);
        }
        f.set(target, value);
    }

    private static Field findField(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        // Fall back to a public lookup (proto fields are public).
        try {
            Field f = type.getField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private static byte[] post(String route, String body) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) URI.create(API + route).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty(
                "User-Agent", System.getProperty("http.agent", "Dalvik") + " gzip");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setDoOutput(true);

        OutputStream out = connection.getOutputStream();
        out.write(body.getBytes(StandardCharsets.UTF_8));
        out.flush();
        out.close();

        int code = connection.getResponseCode();
        InputStream in =
                code == HttpURLConnection.HTTP_OK
                        ? connection.getInputStream()
                        : connection.getErrorStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (in != null) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            in.close();
        }
        byte[] bytes = buffer.toByteArray();
        int q = route.indexOf('?');
        log((q < 0 ? route : route.substring(0, q)) + " http=" + code + " bytes=" + bytes.length);
        return bytes;
    }

    private static String extract(String json, String prefix) {
        int start = json.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        start += prefix.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    /** Through the one gate: a build whose "Verbose logging" row is untouched prints nothing. */
    private static void log(String message) {
        VerboseLog.log(TAG, message);
    }
}
