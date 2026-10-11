package app.ytvr.extension;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Fetches SponsorBlock segments for a video id and caches the answer per video.
 *
 * <p>Contract, matching what the phone patch measures on the same endpoint (Morphe's
 * SBRoutes/SBRequester): {@code GET {base}/api/skipSegments} with {@code videoID}, a {@code
 * categories} JSON array of every category whose behaviour is not "ignore", and {@code
 * actionTypes=["skip"]} — the VR port filters the action type explicitly, because a returned {@code
 * mute} or {@code full} segment is not a range to seek past. 404 is success with zero segments (the
 * normal empty answer); every other status, a timeout or an IO error gives up quietly and puts the
 * video on a short no-retry list. One fetch per video id change, one background thread, hard
 * timeouts, a User-Agent that identifies the client, and a small per-video LRU — where the phone
 * patch has no cache at all, a bounded one is this port's addition (VR users replay and re-seek
 * constantly).
 *
 * <p>Never throws into the app, and never runs on the player thread: the controller calls {@link
 * #fetch} from its tick, this class only ever hands the result back through {@link Callback}.
 */
public final class SponsorBlockClient {

    private static final String TAG = "SponsorBlock";
    private static final String USER_AGENT =
            "morphe-ytvr-patches SponsorBlock/1.0 (YouTube VR on Quest; +https://sponsor.ajay.app)";
    private static final int CONNECT_TIMEOUT_MS = 7000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int CACHE_CAPACITY = 4;
    private static final long FAILURE_BACKOFF_MS = 60_000L;

    /** Categories in the order the phone patch's enum defines them. */
    private static final String[] CATEGORIES = {
        "sponsor", "selfpromo", "interaction", "poi_highlight", "intro",
        "outro", "preview", "hook", "filler", "music_offtopic",
    };

    public interface Callback {
        /** Called on the fetch thread with the parsed segments (empty list is normal). */
        void onSegments(String videoId, List<SponsorSegment> segments);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    /** Per-video cache; oldest entry evicted beyond {@link #CACHE_CAPACITY}. */
    private static final Map<String, List<SponsorSegment>> CACHE =
            new LinkedHashMap<String, List<SponsorSegment>>(8, 0.75f, true) {
                protected boolean removeEldestEntry(
                        Map.Entry<String, List<SponsorSegment>> eldest) {
                    return size() > CACHE_CAPACITY;
                }
            };

    /** videoId -> epoch ms of the last failed fetch; no retry inside the backoff window. */
    private static final Map<String, Long> FAILED_AT = new LinkedHashMap<String, Long>();

    /** Callers that arrived while a fetch for the same video was already running. */
    private static final Map<String, List<Callback>> QUEUED =
            new LinkedHashMap<String, List<Callback>>();

    private static volatile String inFlightVideoId;

    private SponsorBlockClient() {}

    /**
     * Asks for the segments of [videoId] unless a valid answer is already cached or a recent
     * attempt failed. [callback] always runs exactly once per call that results in a fetch (or
     * cache hit), on the fetch thread.
     */
    public static void fetch(final String videoId, final Callback callback) {
        try {
            List<SponsorSegment> cached;
            // FAILED_AT is guarded by its own lock everywhere (it used to be read
            // under the CACHE lock and written under this one, which is a data race
            // on a LinkedHashMap).
            synchronized (FAILED_AT) {
                Long failedAt = FAILED_AT.get(videoId);
                if (failedAt != null
                        && System.currentTimeMillis() - failedAt.longValue() < FAILURE_BACKOFF_MS) {
                    return;
                }
            }
            synchronized (CACHE) {
                cached = CACHE.get(videoId);
            }
            if (cached != null) {
                final List<SponsorSegment> hit = copies(cached);
                EXECUTOR.execute(
                        new Runnable() {
                            @Override
                            public void run() {
                                callback.onSegments(videoId, hit);
                            }
                        });
                return;
            }
            if (videoId.equals(inFlightVideoId)) {
                // A fetch for this video is already running: queue this caller instead
                // of dropping it. Dropping it left the controller with its "requested"
                // flag set and no segments, so that video skipped nothing for the rest
                // of the playback.
                synchronized (QUEUED) {
                    List<Callback> waiting = QUEUED.get(videoId);
                    if (waiting == null) {
                        waiting = new ArrayList<Callback>(2);
                        QUEUED.put(videoId, waiting);
                    }
                    waiting.add(callback);
                }
                return;
            }
            inFlightVideoId = videoId;
            EXECUTOR.execute(
                    new Runnable() {
                        @Override
                        public void run() {
                            inFlightVideoId = null;
                            List<SponsorSegment> segments = request(videoId);
                            if (segments == null) {
                                // Failed: the backoff covers a later retry and no caller is told,
                                // which is what the phone build does too.
                                synchronized (QUEUED) {
                                    QUEUED.remove(videoId);
                                }
                                return;
                            }
                            synchronized (CACHE) {
                                CACHE.put(videoId, segments);
                            }
                            // The cache keeps the parsed list; every caller gets fresh objects so
                            // per-playback skip state can never travel through the cache.
                            callback.onSegments(videoId, copies(segments));
                            List<Callback> waiting;
                            synchronized (QUEUED) {
                                waiting = QUEUED.remove(videoId);
                            }
                            if (waiting != null) {
                                for (Callback queued : waiting) {
                                    queued.onSegments(videoId, copies(segments));
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            log("fetch scheduling failed: " + t);
        }
    }

    /** Fresh segment instances: the cache must never lend out mutable skip state. */
    private static List<SponsorSegment> copies(List<SponsorSegment> source) {
        List<SponsorSegment> copy = new ArrayList<SponsorSegment>(source.size());
        for (SponsorSegment segment : source) {
            copy.add(new SponsorSegment(segment));
        }
        return copy;
    }

    /** The request itself; null means "failed, do not retry for a while". */
    private static List<SponsorSegment> request(String videoId) {
        HttpURLConnection connection = null;
        try {
            StringBuilder categories = new StringBuilder("[");
            String separator = "";
            for (String category : CATEGORIES) {
                if ("ignore".equals(SponsorBlockSettings.behaviour(category))) {
                    continue;
                }
                categories.append(separator).append('"').append(category).append('"');
                separator = ",";
            }
            categories.append(']');
            if (categories.length() == 2) {
                // every category is "ignore": nothing to ask the server for
                return new ArrayList<SponsorSegment>(0);
            }

            String url =
                    SponsorBlockSettings.apiBase()
                            + "/api/skipSegments?videoID="
                            + URLEncoder.encode(videoId, "UTF-8")
                            + "&categories="
                            + URLEncoder.encode(categories.toString(), "UTF-8")
                            + "&actionTypes="
                            + URLEncoder.encode("[\"skip\"]", "UTF-8");
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);

            int status = connection.getResponseCode();
            if (status == 404) {
                log("no segments for " + videoId + " (404 is the empty answer)");
                return new ArrayList<SponsorSegment>(0);
            }
            if (status != 200) {
                log("fetch failed for " + videoId + ": http " + status);
                markFailed(videoId);
                return null;
            }

            InputStream in = connection.getInputStream();
            StringBuilder body = new StringBuilder();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) {
                body.append(new String(chunk, 0, read, "UTF-8"));
                if (body.length() > 512 * 1024) {
                    break;
                }
            }
            in.close();
            List<SponsorSegment> segments = parse(videoId, body.toString());
            log("fetched " + segments.size() + " segment(s) for " + videoId);
            return segments;
        } catch (Throwable t) {
            log("fetch failed for " + videoId + ": " + t);
            markFailed(videoId);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Parses the response. The {@code videoID=} form returns a **flat array of segment objects**
     * ({@code {category, segment:[start,end], actionType, ...}}) — measured on the live API; the
     * per-video wrapper ({@code {videoID, segments:[...]}}) belongs to the hash-prefix form, and
     * this parser accepts both so a switch of endpoints cannot silently break it. Only actionType
     * "skip" becomes a segment; ignored categories are dropped too (the server filters both — this
     * is the belt to the server's braces).
     */
    private static List<SponsorSegment> parse(String videoId, String body) throws Exception {
        List<SponsorSegment> segments = new ArrayList<SponsorSegment>();
        JSONArray array = new JSONArray(body);
        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.getJSONObject(i);
            JSONArray wrapped = entry.optJSONArray("segments");
            if (wrapped != null) {
                if (!videoId.equals(entry.optString("videoID"))) {
                    continue;
                }
                for (int s = 0; s < wrapped.length(); s++) {
                    addSegment(segments, wrapped.getJSONObject(s));
                }
            } else {
                addSegment(segments, entry);
            }
        }
        return segments;
    }

    private static void addSegment(List<SponsorSegment> segments, JSONObject entry) {
        try {
            JSONArray range = entry.optJSONArray("segment");
            String category = entry.optString("category");
            String actionType = entry.optString("actionType");
            if (range == null || range.length() != 2 || !"skip".equals(actionType)) {
                return;
            }
            if ("ignore".equals(SponsorBlockSettings.behaviour(category))) {
                return;
            }
            SponsorSegment segment =
                    new SponsorSegment(
                            entry.optString("UUID"),
                            category,
                            range.getDouble(0),
                            range.getDouble(1));
            if (segment.endMs > segment.startMs) {
                segments.add(segment);
            }
        } catch (Throwable t) {
            log("segment parse failed: " + t);
        }
    }

    private static void markFailed(String videoId) {
        synchronized (FAILED_AT) {
            FAILED_AT.put(videoId, Long.valueOf(System.currentTimeMillis()));
            if (FAILED_AT.size() > 32) {
                FAILED_AT.remove(FAILED_AT.keySet().iterator().next());
            }
        }
    }

    /** Through the one gate: a build whose "Verbose logging" row is untouched prints nothing. */
    private static void log(String message) {
        VerboseLog.log(TAG, message);
    }
}
