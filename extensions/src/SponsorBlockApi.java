package app.ytvr.extension;

/**
 * The host surface the skip controller works through.
 *
 * This is the port of the phone patch's adapter boundary: the controller never
 * touches an obfuscated app member, it asks this interface for the playhead, the
 * duration and the seek. The obfuscation map touches exactly one file
 * ({@link YtvrSponsorBlockHost}, which reflects into the captured player wrapper).
 */
public interface SponsorBlockApi {

    /** Hands over the player wrapper instance (called at loadVideo). */
    void attach(Object player);

    /** Current media time in ms, or a negative value if unknown. */
    long position();

    /** Video duration in ms, or a non-positive value if unknown. */
    long duration();

    /**
     * Seeks to [positionMs]. The return value says whether the bridge exists at
     * all — the underlying player call returns void, so "the seek worked" is
     * observed by the controller through the onSeeked callback, not here.
     */
    boolean seekTo(long positionMs);
}
