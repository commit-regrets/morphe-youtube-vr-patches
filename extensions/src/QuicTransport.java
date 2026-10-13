package app.ytvr.extension;

import android.content.SharedPreferences;

/**
 * The "QUIC (HTTP/3)" switch: whether the app's Cronet engine is allowed to use QUIC, chosen in the
 * headset instead of at patch time.
 *
 * <p>QUIC is the transport under HTTP/3: TLS 1.3 over <em>UDP</em>, with multiplexed streams that
 * do not head-of-line-block and handshakes that survive a network switch. Cronet enables it, and
 * YouTube advertises hints for {@code youtubei.googleapis.com} -- the literal the patch's
 * fingerprint keys off -- for both its API and its media requests. Off is the interesting
 * direction: networks that drop or throttle UDP 443 (corporate Wi-Fi, many VPNs, some ISPs) make
 * HTTP/3 stall or fall back badly, and TCP/TLS is what fixes that.
 *
 * <p><b>The switch is positive-sense here, negative in the patch option.</b> The row reads "QUIC
 * (HTTP/3)" with a plain on/off, because every other hub row is a feature switch; the Manager
 * option keeps upstream's name ({@code disableQuic}) for parity with the official bundle, and the
 * patch inverts it once, at the seed.
 *
 * <p><b>Only a restart can apply a change.</b> Cronet builds its engine once per process, so the
 * injected call is read where that happens: the value in force is the one logged there, not
 * whatever the row was set to afterwards. The injected default keeps QUIC <em>enabled</em>, which
 * is the configuration this port's device verification ran with.
 */
public final class QuicTransport {

    private static final String TAG = "QuicTransport";

    /** The hub row's key in youtube.xml, shared with SettingsXml.kt. */
    private static final String KEY = "ytvr_mod_quic_enabled";

    /**
     * Seed from the build option ({@code disableQuic}, inverted once by the patch). Written by the
     * patch's injected {@code sput-boolean}.
     */
    public static volatile boolean buildDefault = true;

    private static volatile Boolean lastLogged;

    private QuicTransport() {}

    /**
     * Injection point: the boolean the Cronet engine builder passes to {@code enableQuic(...)}.
     * Called once per process, where the engine is built.
     */
    public static boolean quicEnabled() {
        boolean enabled = currentSelection();
        Boolean logged = lastLogged;
        if (logged == null || logged.booleanValue() != enabled) {
            lastLogged = Boolean.valueOf(enabled);
            log(
                    "engine built with QUIC "
                            + (enabled ? "enabled (HTTP/3 over UDP)" : "disabled (TCP/TLS only)"));
        }
        return enabled;
    }

    /**
     * The setting in force: the viewer's row if set, else the build's seed, written through once.
     */
    public static boolean currentSelection() {
        try {
            SharedPreferences mod = SponsorBlockSettings.modPreferences();
            if (mod == null) {
                return buildDefault;
            }
            if (!mod.contains(KEY)) {
                mod.edit().putBoolean(KEY, buildDefault).apply();
                log("no stored switch yet, seeded QUIC " + (buildDefault ? "enabled" : "disabled"));
                return buildDefault;
            }
            return mod.getBoolean(KEY, buildDefault);
        } catch (Throwable t) {
            log("reading the switch failed, using the build default: " + t);
            return buildDefault;
        }
    }

    /** Records the viewer's choice; the summary says it applies after a restart. */
    public static void setQuicEnabled(boolean enabled) {
        try {
            SharedPreferences mod = SponsorBlockSettings.modPreferences();
            if (mod != null) {
                mod.edit().putBoolean(KEY, enabled).apply();
            }
            log("switch set: QUIC " + (enabled ? "enabled" : "disabled") + " (next launch)");
        } catch (Throwable t) {
            log("writing the switch failed: " + t);
        }
    }

    private static void log(String message) {
        VerboseLog.log(TAG, message);
    }
}
