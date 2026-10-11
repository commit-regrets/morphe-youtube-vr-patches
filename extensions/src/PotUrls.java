package app.ytvr.extension;

import android.net.Uri;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Makes the spoofed adaptive media URL fetchable, and reports what the app was doing to it.
 *
 * <p>Two independent things put the app's media request into a shape googlevideo refuses with HTTP
 * 400 for a plain adaptive stream (the QOE ping then reports it as `staleconfig`, which is the
 * app's own word -- `zk.au()` classifies a bare 400/403/404/410/416 as `staleconfig` when the app
 * believes its stream config is stale; `axp` hardcodes `er.2004` for every HTTP error, so neither
 * name comes from the server):
 *
 * <p>1. `pqy`'s constructor sets `this.c = <underlying source> instanceof ayd` (cronet), and
 * `pqy.b(axf)` uses that flag to rewrite every `/videoplayback` fetch into a **UMP envelope
 * request** (`?ump=1&range=<off>-<end>`, HTTP range header dropped). The UMP envelope is the SABR
 * protocol; there is no SABR config behind a VISIONOS URL. The extension patch clears the flag, so
 * the URL is used exactly as minted.
 *
 * <p>2. `axu.b(axf)` reads the app's **own, un-spoofed**
 * `mediaCommonConfig.mediaUstreamerRequestConfig` and, when its `c` field is set, converts the
 * request to a **POST carrying a serialized ClientAbrState protobuf body** ("AbrStateDataSpec").
 * Upstream neutralises this with `removeVideoPlaybackPostBody`. Here the conversion is undone in
 * the DataSpec builder (`axe`), whose fields are mutable, right before it builds the immutable
 * `axf`.
 *
 * <p>Hooked at instruction 0 of `axe.a()`, the DataSpec build, this sees the *final* URL, method
 * and body for every media request and normalises POST+body back to a plain GET. The fetch matrix
 * below is the on-device evidence: it calls the same URL as GET and as POST with and without the
 * UMP parameters and reports each status.
 */
public final class PotUrls {

    private static final String TAG = "YTVR-URL";
    private static final String MEDIA_PATH = "/videoplayback";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final AtomicBoolean MATRIX_RUN = new AtomicBoolean();
    private static final Map<String, Integer> ROUTES = new HashMap<String, Integer>();
    private static final Map<String, Integer> AD_PATHS = new HashMap<String, Integer>();
    private static final Map<String, Integer> REQUESTS = new HashMap<String, Integer>();

    /** Host/path fragments that identify an ad creative or ad tracking request. */
    private static final String[] AD_URL_MARKERS = {
        "youtube.com/pagead/",
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagservices.com",
        "2mdn.net",
    };

    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();
    private static final Map<String, Field> FIELDS = new HashMap<String, Field>();

    private PotUrls() {}

    /**
     * Hooked at instruction 0 of `axe.a()`; never throws into the app.
     *
     * <p>This is the shipping path: it only normalises the request. The logging and the probe
     * fetches live in {@link #onBuildVerbose}, which the patch installs only when its diagnostics
     * option is on.
     */
    public static void onBuild(Object builder) {
        normalise(builder);
    }

    /** Diagnostic twin of {@link #onBuild}: reports the request, then normalises it. */
    public static void onBuildVerbose(Object builder) {
        try {
            Object uriValue = builder == null ? null : field(builder, "a");
            Object method = builder == null ? null : field(builder, "d");
            Object body = builder == null ? null : field(builder, "e");
            if (uriValue instanceof Uri && ((Uri) uriValue).toString().contains(MEDIA_PATH)) {
                Uri uri = (Uri) uriValue;
                int bodyBytes = body instanceof byte[] ? ((byte[]) body).length : -1;
                // Every media DataSpec, numbered, with the identifying query *values*. The
                // param-name set alone is not enough: an ad stream fetched from the same server
                // has the same parameter names, so a shape-based log hides it. `c` says which
                // client minted the URL (our spoofed inject is `c=VISIONOS`; anything else is not
                // from the spoof) and `id` says which video is being fetched.
                int seq = SEQUENCE.incrementAndGet();
                if (seq <= 200) {
                    log(
                            "media #"
                                    + seq
                                    + " "
                                    + uri.getHost()
                                    + " id="
                                    + uri.getQueryParameter("id")
                                    + " itag="
                                    + uri.getQueryParameter("itag")
                                    + " c="
                                    + uri.getQueryParameter("c")
                                    + " source="
                                    + uri.getQueryParameter("source")
                                    + " xtags="
                                    + uri.getQueryParameter("xtags")
                                    + " sabr="
                                    + flag(uri, "sabr")
                                    + " ump="
                                    + flag(uri, "ump")
                                    + " daistate="
                                    + flag(uri, "daistate")
                                    + " acpns="
                                    + flag(uri, "acpns")
                                    + " method="
                                    + methodName(method)
                                    + " body="
                                    + bodyBytes);
                    if (SpoofStreams.injected() && (!isGet(method) || body != null)) {
                        log(
                                "normalising to GET with no body (pqy's UMP rewrite and axu's"
                                        + " AbrStateDataSpec POST are both defeated; the same URL returns 206"
                                        + " as a plain GET)");
                    }
                }
                if (seq == 1 && MATRIX_RUN.compareAndSet(false, true)) {
                    fetchMatrix(uri.toString());
                }
            }
        } catch (Throwable t) {
            if (FAILURE_LOGGED.compareAndSet(false, true)) {
                log("media log failed: " + t);
            }
        }
        normalise(builder);
    }

    /**
     * Turns a `/videoplayback` DataSpec back into a plain GET with no body. The app's own `axu`
     * rewrites it into a POST carrying a serialized ClientAbrState protobuf, which googlevideo
     * refuses with HTTP 400 for a plain adaptive URL -- the bug that made every spoofed video spin
     * forever (`rc.400` / `staleconfig`).
     *
     * <p>Only while a spoofed stream is in play. The AbrState POST is the *app's own* shape for its
     * own stream -- a SABR/onesie stream is supposed to be fetched that way -- so normalising it
     * there would break native playback. When the spoof is unavailable the app has to keep the
     * request shape it was designed around.
     */
    private static void normalise(Object builder) {
        try {
            if (builder == null || !SpoofStreams.injected()) {
                return;
            }
            Builder fields = builder(builder);
            if (fields == null) {
                // Already logged loudly by builder(). Deliberately not a silent no-op: if the app's
                // builder changes shape, media requests stop being normalised, and that has to be
                // visible rather than looking like a quiet success.
                return;
            }
            Object uriValue = fields.url.get(builder);
            if (!(uriValue instanceof Uri) || !((Uri) uriValue).toString().contains(MEDIA_PATH)) {
                return;
            }
            fields.method.set(builder, Integer.valueOf(1));
            fields.body.set(builder, null);
        } catch (Throwable t) {
            if (FAILURE_LOGGED.compareAndSet(false, true)) {
                log("normalise failed: " + t);
            }
        }
    }

    /**
     * The three builder fields this patch rewrites. `url` and `body` are unique by type in the
     * builder; `method` is an `int` and the class has more than one, so it is taken as the `int`
     * field declared immediately before the body field (declaration order follows the DataSpec
     * constructor's parameter order, whose fourth argument is the method), falling back to the only
     * `int` field if there is just one.
     */
    private static final class Builder {
        final Field url;
        final Field method;
        final Field body;

        Builder(Field url, Field method, Field body) {
            this.url = url;
            this.method = method;
            this.body = body;
        }
    }

    private static final java.util.Map<String, Builder> BUILDERS =
            new java.util.HashMap<String, Builder>();
    private static final java.util.Set<String> UNRESOLVED_BUILDERS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * Resolves the builder's fields by **type**, not by name.
     *
     * <p>The durability trap this exists for: the fingerprint that finds the builder survives an
     * app update, but a name-based field lookup returns null on a miss, so the patch would keep
     * applying and silently stop normalising anything -- exactly when the update it was meant to
     * survive happened. Names are gone by design here; if the shape changes, this logs and says so.
     */
    private static Builder builder(Object target) {
        Class<?> type = target.getClass();
        String key = type.getName();
        synchronized (BUILDERS) {
            Builder cached = BUILDERS.get(key);
            if (cached != null) {
                return cached;
            }
        }
        if (UNRESOLVED_BUILDERS.contains(key)) {
            return null;
        }

        Field[] declared = type.getDeclaredFields();
        Field url = null;
        Field body = null;
        for (Field candidate : declared) {
            if (candidate.getType() == Uri.class && url == null) {
                url = candidate;
            } else if (candidate.getType() == byte[].class && body == null) {
                body = candidate;
            }
        }

        Field method = null;
        if (body != null) {
            for (int i = 1; i < declared.length; i++) {
                if (declared[i] == body && declared[i - 1].getType() == int.class) {
                    method = declared[i - 1];
                    break;
                }
            }
        }
        if (method == null) {
            Field onlyInt = null;
            int intCount = 0;
            for (Field candidate : declared) {
                if (candidate.getType() == int.class) {
                    onlyInt = candidate;
                    intCount++;
                }
            }
            if (intCount == 1) {
                method = onlyInt;
            }
        }

        Builder resolved =
                (url != null && method != null && body != null)
                        ? new Builder(url, method, body)
                        : null;
        if (resolved == null) {
            UNRESOLVED_BUILDERS.add(key);
            log(
                    "could not resolve the DataSpec builder's fields by type on "
                            + key
                            + " (url="
                            + (url != null)
                            + " method="
                            + (method != null)
                            + " body="
                            + (body != null)
                            + "): media requests are NOT being normalised");
        } else {
            synchronized (BUILDERS) {
                BUILDERS.put(key, resolved);
            }
            log(
                    "DataSpec builder fields resolved by type on "
                            + key
                            + ": url="
                            + url.getName()
                            + " method="
                            + method.getName()
                            + " body="
                            + body.getName());
        }
        return resolved;
    }

    /**
     * Calls the app's own URL four ways and reports each status, so the 400 is measured rather than
     * argued. Runs once per process, off the main thread, 1 KB per request.
     */
    private static void fetchMatrix(final String url) {
        new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                probe("A GET, Range 0-1023", url, "GET", "bytes=0-1023", null);
                                probe(
                                        "B GET +ump=1&range=0-1023",
                                        url + "&ump=1&range=0-1023",
                                        "GET",
                                        "bytes=0-1023",
                                        null);
                                probe(
                                        "C POST, empty body",
                                        url,
                                        "POST",
                                        "bytes=0-1023",
                                        new byte[0]);
                                probe(
                                        "D POST, 64-byte body",
                                        url,
                                        "POST",
                                        "bytes=0-1023",
                                        new byte[64]);
                            }
                        },
                        "ytvr-urlmatrix")
                .start();
    }

    private static void probe(String label, String url, String method, String range, byte[] body) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod(method);
            if (range != null) {
                connection.setRequestProperty("Range", range);
            }
            connection.setRequestProperty("User-Agent", System.getProperty("http.agent", "Dalvik"));
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-protobuf");
                connection.setFixedLengthStreamingMode(body.length);
                connection.getOutputStream().write(body);
            }
            int code = connection.getResponseCode();
            log(
                    "matrix "
                            + label
                            + ": http="
                            + code
                            + " contentRange="
                            + connection.getHeaderField("Content-Range")
                            + bodySnippet(connection));
        } catch (Throwable t) {
            log("matrix " + label + " failed: " + t);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String bodySnippet(HttpURLConnection connection) {
        try {
            InputStream in = connection.getErrorStream();
            if (in == null) {
                in = connection.getInputStream();
            }
            if (in == null) {
                return "";
            }
            byte[] chunk = new byte[512];
            int read = in.read(chunk);
            in.close();
            if (read <= 0) {
                return "";
            }
            String text =
                    new String(chunk, 0, read, StandardCharsets.ISO_8859_1)
                            .replaceAll("[^\\x20-\\x7E]", " ")
                            .trim();
            if (text.length() > 120) {
                text = text.substring(0, 120);
            }
            return " body=\"" + text + "\"";
        } catch (Throwable t) {
            return "";
        }
    }

    /** "yes"/"-" for a query parameter's presence, logged as a marker rather than a value. */
    private static String flag(Uri uri, String name) {
        return uri.getQueryParameter(name) != null ? "yes" : "-";
    }

    /**
     * Hooked at instruction 0 of `oth.l()`, which builds *every* innertube request URL:
     * `uri.buildUpon().appendEncodedPath("youtubei/v1").appendEncodedPath(oswVar.k)`, where `orh.k`
     * is the route name ("player", "get_watch", "player/ad_break", ...). The spoof only rewrites
     * the `/player` response, so any other player-response source the app consults can still hand
     * it an ad-bearing stream -- this says which routes it actually uses.
     */
    public static void onInnertubeRoute(Object uriBuilderOwner) {
        try {
            Object request = field(uriBuilderOwner, "a");
            Object route = request == null ? null : field(request, "k");
            String name = route instanceof String ? (String) route : "<unknown>";
            int seen;
            synchronized (ROUTES) {
                Integer count = ROUTES.get(name);
                seen = count == null ? 1 : count + 1;
                ROUTES.put(name, Integer.valueOf(seen));
            }
            if (seen <= 3 || seen % 25 == 0) {
                log("innertube route '" + name + "' (call " + seen + ")");
            }
        } catch (Throwable t) {
            if (FAILURE_LOGGED.compareAndSet(false, true)) {
                log("route log failed: " + t);
            }
        }
    }

    /**
     * Hooked in front of every ad-path entry point the patches neutralise (ad-break renderer and
     * request, PlayerBytes slot and layout, SSAI provider and cue points), so the run shows which
     * of them the app actually exercises while an ad is on screen. If none of them fire, the ad
     * cannot be coming from the client-side ad machinery at all.
     */
    public static void onAdPath(String what) {
        try {
            int seen;
            synchronized (AD_PATHS) {
                Integer count = AD_PATHS.get(what);
                seen = count == null ? 1 : count + 1;
                AD_PATHS.put(what, Integer.valueOf(seen));
            }
            if (seen <= 5 || seen % 25 == 0) {
                log("ad path: " + what + " (call " + seen + ")");
            }
        } catch (Throwable t) {
            // never throw into the app
        }
    }

    /**
     * Hooked in front of the skip-ad countdown (`gtu.b(int)` = the VR skip button, `fna.b(int)` =
     * the 2D-panel one). This is the app's own ad UI ticking down the remaining ad time, so it is
     * an unambiguous in-app signal that an ad is on screen -- unlike the QOE `ad_playback` marker,
     * which only appears in a later ping and is absent in short sessions.
     */
    public static void onAdCountdown(int remainingMs) {
        try {
            int seen;
            synchronized (AD_PATHS) {
                Integer count = AD_PATHS.get("skipAdCountdown");
                seen = count == null ? 1 : count + 1;
                AD_PATHS.put("skipAdCountdown", Integer.valueOf(seen));
            }
            if (seen <= 8 || seen % 20 == 0) {
                log(
                        "ad UI: skip-ad countdown "
                                + remainingMs
                                + " ms remaining (tick "
                                + seen
                                + ")");
            }
        } catch (Throwable t) {
            // never throw into the app
        }
    }

    /**
     * Hooked at cronet's own `UrlRequest` factory (`CronetUrlRequestContext.newUrlRequestBuilder`),
     * which sees **every** HTTP request the app makes -- media included. The earlier probes only
     * covered `/videoplayback` DataSpecs built through `axe`, so a media fetch made by another
     * loader (or by the ads module) was invisible. Requests are reported as host+path with a count.
     */
    public static void onUrlRequest(String url) {
        try {
            if (url == null) {
                return;
            }
            int scheme = url.indexOf("://");
            int start = scheme < 0 ? 0 : scheme + 3;
            int slash = url.indexOf('/', start);
            String host = slash < 0 ? url.substring(start) : url.substring(start, slash);
            int query = url.indexOf('?', slash < 0 ? start : slash);
            String path = slash < 0 ? "/" : url.substring(slash, query < 0 ? url.length() : query);
            String key = host + path;
            int seen;
            synchronized (REQUESTS) {
                Integer count = REQUESTS.get(key);
                seen = count == null ? 1 : count + 1;
                REQUESTS.put(key, Integer.valueOf(seen));
            }
            boolean interesting =
                    key.contains("videoplayback")
                            || key.contains("initplayback")
                            || key.contains("get_watch")
                            || key.contains("ad_break")
                            || key.contains("googlevideo")
                            || key.contains("doubleclick")
                            || key.contains("googleads")
                            || key.contains("adservice")
                            || key.contains("manifest");
            if (seen <= 2 || (interesting && seen <= 5)) {
                log(
                        "http: "
                                + key
                                + (interesting ? "  <-- media/ad path" : "")
                                + " (call "
                                + seen
                                + ")"
                                + (interesting ? " params=" + paramNames(url) : ""));
            }
        } catch (Throwable t) {
            // never throw into the app
        }
    }

    /** Query parameter names only -- enough to recognise a media/ad URL without logging values. */
    private static String paramNames(String url) {
        int q = url.indexOf('?');
        if (q < 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                if (sb.length() > 0 && sb.length() < 400) {
                    sb.append(',');
                }
                if (sb.length() < 400) {
                    sb.append(pair, 0, eq);
                }
            }
        }
        return sb.toString();
    }

    /**
     * Rewrites ad requests to a harmless URL, in the one place every HTTP request passes through
     * (see {@link #onUrlRequest}).
     *
     * <p>A device trace of the ad the spoofed build still shows (2026-10-09) contains the whole
     * client-side ad sequence and *no* ad in any stream:
     *
     * <p>``` www.youtube.com/pagead/adview <- the ad creative www.youtube.com/api/stats/ads <- ad
     * playback stats www.youtube.com/pagead/interaction/ <- the Skip press
     * ad.doubleclick.net/ddm/trackimp/... <- impression pixel (dc_ref=.../video/<content id>)
     * ade.googlesyndication.com/ddm/activity_ext/... www.youtube.com/ptracking ```
     *
     * <p>Every `/videoplayback` request in the same run belongs to the injected VISIONOS formats,
     * the spoofed responses carry no `adPlacements`/`playerAds`, and their streams have no ad time
     * (stream duration == content duration), so the pre-roll is delivered through `pagead/adview`
     * -- the app's own client-side ad view -- not through the media stream. Blocking the creative
     * is thus the direct fix, and it is upstream's own technique (`blockGetWatchRequest` /
     * `blockInitPlaybackRequest` rewrite a URL to `google.com/gen_204` in the request builder).
     *
     * <p>`s.youtube.com/api/stats/qoe` is deliberately left alone: it is the diagnostic oracle.
     */
    public static String filterUrl(String url) {
        if (url == null) {
            return null;
        }
        for (String marker : AD_URL_MARKERS) {
            if (url.contains(marker)) {
                log(
                        "blocking ad request ("
                                + marker
                                + "): "
                                + url.substring(0, Math.min(160, url.length())));
                return "https://www.google.com/gen_204";
            }
        }
        return url;
    }

    private static boolean isGet(Object method) {
        return method instanceof Integer && ((Integer) method).intValue() == 1;
    }

    private static String methodName(Object method) {
        if (!(method instanceof Integer)) {
            return String.valueOf(method);
        }
        switch (((Integer) method).intValue()) {
            case 1:
                return "GET";
            case 2:
                return "POST";
            case 3:
                return "HEAD";
            default:
                return String.valueOf(method);
        }
    }

    private static Object field(Object target, String name) {
        Field f = lookup(target.getClass(), name);
        if (f == null) {
            return null;
        }
        try {
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = lookup(target.getClass(), name);
        if (f != null) {
            f.set(target, value);
        }
    }

    private static Field lookup(Class<?> type, String name) {
        String key = type.getName() + "#" + name;
        synchronized (FIELDS) {
            if (FIELDS.containsKey(key)) {
                return FIELDS.get(key);
            }
        }
        Field found = null;
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                found = c.getDeclaredField(name);
                break;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        if (found != null) {
            try {
                found.setAccessible(true);
            } catch (Throwable t) {
                found = null;
            }
        }
        synchronized (FIELDS) {
            FIELDS.put(key, found);
        }
        return found;
    }

    /** Through the one gate: a build whose "Verbose logging" row is untouched prints nothing. */
    private static void log(String message) {
        VerboseLog.log(TAG, message);
    }
}
