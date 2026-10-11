package app.ytvr.extension;

/**
 * One SponsorBlock segment, in the units the skip decision works in (ms).
 *
 * Runtime flags mirror the phone controller: {@code didAutoSkipped} is the
 * persistent per-segment flag honoured only by the skip-once behaviour, and
 * {@code lastSkipRequestAt} is the re-request guard that stops a second seek
 * from firing while the first one is still in flight (our seek returns void, so
 * "in flight" lasts until onSeeked confirms or the guard expires).
 */
public final class SponsorSegment {

    public final String uuid;
    public final String category;
    public final long startMs;
    public final long endMs;

    public boolean didAutoSkipped;
    public volatile long lastSkipRequestAt;

    public SponsorSegment(String uuid, String category, double startSeconds, double endSeconds) {
        this.uuid = uuid;
        this.category = category;
        this.startMs = (long) (startSeconds * 1000.0d);
        this.endMs = (long) (endSeconds * 1000.0d);
    }

    /**
     * A fresh instance carrying no runtime state. The fetch cache hands these out
     * rather than its own objects: the skip decision mutates {@link #didAutoSkipped},
     * so a cached list must never carry the previous playback's flags into a new
     * one — that is what made a second watch of the same video skip nothing.
     */
    public SponsorSegment(SponsorSegment source) {
        this(source.uuid, source.category, source.startMs / 1000.0d, source.endMs / 1000.0d);
    }

    /**
     * Forgets the per-playback skip state, for when a new playback of the same
     * video starts. The phone build resets through {@code clearData()} when the
     * player is created; skip-once means "once per playback", not "once per app
     * run", which is exactly the difference a second watch exposed on device.
     */
    public void resetForNewPlayback() {
        this.didAutoSkipped = false;
        this.lastSkipRequestAt = 0L;
    }

    public String toString() {
        return category + " " + startMs / 1000.0d + "s->" + endMs / 1000.0d + "s";
    }
}
