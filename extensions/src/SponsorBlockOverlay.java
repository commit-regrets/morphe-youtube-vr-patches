package app.ytvr.extension;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import java.util.List;

/**
 * Draws SponsorBlock segments on the VR seek bar, the way the phone patch draws
 * them on the phone seek bar.
 *
 * The VR player controls are ordinary Android views inside a {@code VrCardView}
 * ({@code R.layout.watch_layer_panel}), driven by the controller class whose
 * constructor binds {@code R.id.seekbar} / {@code seekbar_container} /
 * {@code seekbar_position} / {@code seekbar_buffered} / {@code seekbar_thumb} —
 * that constructor references all five resource ids, which is both the anchor the
 * patch's fingerprint uses and why the ids are baked into the app at start
 * ({@link #configure(int)}) rather than guessed here.
 *
 * The app sizes the played bar itself, in the controller's timeline update
 * ({@code (position, windowStart, duration, buffered)}):
 *
 * <pre>
 *   spanWidth = parent.width - track.leftMargin - track.rightMargin
 *   positionBar.width = (position - windowStart) / (duration - windowStart) * spanWidth
 * </pre>
 *
 * so this class mirrors exactly that geometry and draws the segments in the
 * played bar's coordinate space. Everything is defensive: a missing view, an odd
 * timeline or a dead app class must never disturb playback.
 */
public final class SponsorBlockOverlay {

    private static final String TAG = "SponsorBlock";

    /**
     * SponsorBlock's own segment colours, per category. The table lives in
     * {@link CategoryColours} so the settings screen's colour legend and these markers can
     * never disagree about what green means.
     */
    private static int colour(String category) {
        return CategoryColours.colour(category);
    }

    private static volatile int trackId = -1;
    private static volatile int positionId = -1;
    private static volatile int containerId = -1;
    private static volatile int bufferedId = -1;
    private static volatile int thumbId = -1;
    private static volatile int backgroundId = -1;

    private static volatile View track;
    /** The played-bar view: the marker layer copies *its* geometry. */
    private static volatile View positionBar;
    private static volatile View container;
    private static volatile View bufferedBar;
    private static volatile View thumb;
    /** `R.id.seekbar_bg` — the line a viewer sees; the markers copy its exact bounds. */
    private static volatile View lineView;
    private static volatile MarkerOverlay overlay;

    /** The timeline the app last reported, in ms; the marker geometry comes from it. */
    private static volatile long windowStartMs;
    private static volatile long durationMs;

    private static boolean armingLogged;
    private static boolean missingLogged;
    private static boolean dumpLogged;
    private static boolean scaleLogged;
    /** The thickness the line's selected drawable actually draws, for the log. */
    private static volatile int lineThickness = -1;
    private static volatile int timelineLogs;
    private static volatile int geometryLogs;
    private static volatile int sizeLogs;
    private static volatile int drawLogs;
    private static volatile int drawnLogs;

    private SponsorBlockOverlay() {
    }

    /**
     * The line view's id (`R.id.seekbar_bg`), in its own call so the injected block
     * needs no extra register in {@code Application.onCreate}.
     */
    public static void setBackgroundId(int seekbarBackgroundId) {
        backgroundId = seekbarBackgroundId;
    }

    /**
     * Called once at app start with the resource ids the patch mined from the
     * player-controls constructor, so no id has to be guessed at runtime.
     */
    public static void configure(int seekbarTrackId, int seekbarPositionId, int seekbarContainerId,
                                 int seekbarBufferedId, int seekbarThumbId) {
        try {
            trackId = seekbarTrackId;
            positionId = seekbarPositionId;
            containerId = seekbarContainerId;
            bufferedId = seekbarBufferedId;
            thumbId = seekbarThumbId;
            if (!armingLogged) {
                armingLogged = true;
                log("seekbar overlay armed (track=0x" + Integer.toHexString(seekbarTrackId)
                    + " position=0x" + Integer.toHexString(seekbarPositionId) + ")");
            }
        } catch (Throwable t) {
            // never throw into the app
        }
    }

    /**
     * Hooked at the player-controls constructor: [root] contains the seek bar, so
     * this is where the marker layer is installed over it.
     */
    public static void attach(View root) {
        try {
            if (root == null || trackId < 0 || positionId < 0) {
                return;
            }
            View position = root.findViewById(positionId);
            View trackView = root.findViewById(trackId);
            if (position == null || trackView == null) {
                if (!missingLogged) {
                    missingLogged = true;
                    log("seekbar overlay: controls view has no seekbar (position="
                        + (position != null) + " track=" + (trackView != null) + ")");
                }
                return;
            }
            ViewParent parent = position.getParent();
            if (!(parent instanceof ViewGroup)) {
                if (!missingLogged) {
                    missingLogged = true;
                    log("seekbar overlay: the played bar has no view-group parent");
                }
                return;
            }
            if (overlay != null && track == trackView) {
                return;
            }
            ViewGroup group = (ViewGroup) parent;
            MarkerOverlay view = new MarkerOverlay(group.getContext());
            group.addView(view);
            track = trackView;
            positionBar = position;
            container = root.findViewById(containerId);
            bufferedBar = root.findViewById(bufferedId);
            thumb = root.findViewById(thumbId);
            if (backgroundId > 0) {
                lineView = root.findViewById(backgroundId);
            }
            overlay = view;
            // Size from the layout as well as from the timeline: while the controls are
            // hidden the app's timeline update still runs, but the views have no size
            // yet, and the first real layout is the moment the markers become drawable.
            trackView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View changed, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    MarkerOverlay current = overlay;
                    if (current != null) {
                        current.sync();
                    }
                }
            });
            view.sync();
            log("seekbar overlay attached over " + trackView.getClass().getName() + " in "
                + group.getClass().getName());
        } catch (Throwable t) {
            log("seekbar overlay attach failed: " + t);
        }
    }

    /**
     * Hooked at the controller's timeline update, whose four longs are
     * {@code (positionMs, windowStartMs, durationMs, bufferedMs)} — read out of the
     * method body, where the duration drives the total-time text and the
     * position/duration pair drives the played-bar width.
     */
    public static void onTimeline(long positionMs, long windowStart, long duration, long bufferedMs) {
        try {
            windowStartMs = windowStart;
            durationMs = duration;
            if (timelineLogs < 3) {
                timelineLogs++;
                log("timeline " + positionMs + "ms / window " + windowStart + "ms / duration "
                    + duration + "ms / buffered " + bufferedMs + "ms"
                    + (overlay == null ? " (no overlay attached yet)" : ""));
            }
            MarkerOverlay view = overlay;
            if (view != null) {
                view.sync();
            }
        } catch (Throwable t) {
            // never throw into the app
        }
    }

    /** The controller calls this when a fetch lands, so markers appear immediately. */
    public static void onSegmentsChanged() {
        try {
            MarkerOverlay view = overlay;
            if (view != null) {
                view.postInvalidate();
            }
        } catch (Throwable t) {
            // never throw into the app
        }
    }

    /**
     * Repaint the markers. Nothing in the extension calls this any more (the switches live in the
     * app's own settings screen, and the overlay repaints on every timeline tick, so a change shows up
     * within a frame or two), but it stays as the one-line way to force a repaint from a hook.
     */
    public static void onMarkersToggled() {
        try {
            MarkerOverlay view = overlay;
            if (view != null) {
                view.postInvalidate();
            }
        } catch (Throwable t) {
            // never throw into the app
        }
    }

    /**
     * Half of the 3dp transparent stroke in {@code seekbar_gutter_bg.xml}'s default item:
     * an Android shape stroke is centred on the shape's edge, so half of it eats into the
     * fill and that is the inset between the line's box and the line a viewer sees.
     */
    private static int lineInset(Context context) {
        if (context == null) {
            return 2;
        }
        float density = context.getResources().getDisplayMetrics().density;
        return Math.max(1, Math.round(1.5f * density));
    }

    /** Through the one gate: a build whose "Verbose logging" row is untouched prints nothing. */
    private static void log(String message) {
        VerboseLog.log(TAG, message);
    }

    /**
     * One-shot dump of every view around the seek bar, so the marker geometry can be
     * derived from measurements instead of assumptions (this is what settled where the
     * line actually starts and how wide it really is).
     */
    private static void dumpGeometry(ViewGroup group, int left, int spanWidth, int top, int height) {
        log("seekbar dump: marker left=" + left + " span=" + spanWidth + " top=" + top
            + " height=" + height);
        describe("container", container);
        describe("track", track);
        describe("position", positionBar);
        describe("buffered", bufferedBar);
        describe("thumb", thumb);
        View played = positionBar;
        if (played != null && played.getParent() instanceof ViewGroup) {
            ViewGroup barGroup = (ViewGroup) played.getParent();
            log("seekbar dump: played bar's parent " + barGroup.getClass().getSimpleName()
                + " " + barGroup.getWidth() + "x" + barGroup.getHeight()
                + " children=" + barGroup.getChildCount());
            for (int i = 0; i < barGroup.getChildCount() && i < 12; i++) {
                describe("  child[" + i + "]", barGroup.getChildAt(i));
            }
        }
        log("seekbar dump: overlay parent " + group.getClass().getSimpleName() + " "
            + group.getWidth() + "x" + group.getHeight() + " padding=" + group.getPaddingLeft()
            + "/" + group.getPaddingRight());
    }

    private static void describe(String label, View view) {
        if (view == null) {
            log("seekbar dump: " + label + " = absent");
            return;
        }
        StringBuilder margins = new StringBuilder();
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams frame = (FrameLayout.LayoutParams) params;
            margins.append(" margins=").append(frame.leftMargin).append('/').append(frame.rightMargin);
            margins.append(" gravity=").append(frame.gravity);
        }
        log("seekbar dump: " + label + " " + view.getClass().getSimpleName()
            + " id=0x" + Integer.toHexString(view.getId())
            + " " + view.getWidth() + "x" + view.getHeight()
            + " left=" + view.getLeft() + " top=" + view.getTop()
            + " padding=" + view.getPaddingLeft() + "/" + view.getPaddingRight()
            + margins
            + " background=" + (view.getBackground() == null ? "none"
                : view.getBackground().getClass().getSimpleName())
            + " visibility=" + view.getVisibility());
    }

    /**
     * The marker layer itself: a transparent view laid over the played bar's
     * coordinate space that draws one bar per skippable segment.
     */
    private static final class MarkerOverlay extends View {

        private final Paint paint = new Paint();
        private int lastLeft = Integer.MIN_VALUE;
        private int lastTop = Integer.MIN_VALUE;
        private int lastWidth = -1;
        private int lastHeight = -1;

        MarkerOverlay(Context context) {
            super(context);
            paint.setStyle(Paint.Style.FILL);
            setWillNotDraw(false);
        }

        /**
         * Matches the app's own geometry: the played bar is
         * {@code (containerWidth - track.leftMargin - track.rightMargin)} wide and spans
         * from the container's left edge. The track's margins *inset the played bar*;
         * they are not where it starts — using them as our left offset shifted the
         * markers right and made them overhang the line (measured on device).
         */
        void sync() {
            View trackView = track;
            if (trackView == null) {
                return;
            }
            ViewParent parent = getParent();
            if (!(parent instanceof ViewGroup)) {
                return;
            }
            ViewGroup group = (ViewGroup) parent;
            int trackLeft = 0;
            int trackRight = 0;
            ViewGroup.LayoutParams trackParams = trackView.getLayoutParams();
            if (trackParams instanceof FrameLayout.LayoutParams) {
                trackLeft = ((FrameLayout.LayoutParams) trackParams).leftMargin;
                trackRight = ((FrameLayout.LayoutParams) trackParams).rightMargin;
            }
            int spanWidth = group.getWidth() - trackLeft - trackRight;
            if (spanWidth <= 0) {
                spanWidth = trackView.getWidth();
            }
            // The played bar's own left edge is where the span begins (it grows to the
            // right as playback advances, so this does not drift).
            View played = positionBar;
            int leftMargin = played != null ? played.getLeft() : trackLeft;
            // Vertical geometry comes from the played bar, not from the track: the track
            // is the whole seek-bar box, while the visible line is the played bar inside
            // it. Copying the played bar's top and height is what puts the markers *on*
            // the line (measured on device: the track's own top/height drew them below it).
            int top = trackView.getTop();
            int height = trackView.getHeight();
            if (played != null && played.getHeight() > 0) {
                top = played.getTop();
                height = played.getHeight();
            }
            // Copy the line view itself (`R.id.seekbar_bg`): the 387x9 box a viewer sees.
            // The inset that makes it look thin at rest is applied when drawing, so the
            // state change needs no relayout of our layer.
            View line = lineView;
            if (line != null && line.getWidth() > 0 && line.getHeight() > 0) {
                leftMargin = line.getLeft();
                spanWidth = line.getWidth();
                top = line.getTop();
                height = line.getHeight();
            }
            if (!dumpLogged && group.getWidth() > 0) {
                dumpLogged = true;
                dumpGeometry(group, leftMargin, spanWidth, top, height);
            }
            if (leftMargin == lastLeft && top == lastTop && spanWidth == lastWidth
                && height == lastHeight) {
                invalidate();
                return;
            }
            lastLeft = leftMargin;
            lastTop = top;
            lastWidth = spanWidth;
            lastHeight = height;
            ViewGroup.LayoutParams params = getLayoutParams();
            if (params == null) {
                params = new FrameLayout.LayoutParams(spanWidth, height);
                setLayoutParams(params);
            } else {
                params.width = spanWidth;
                params.height = height;
            }
            if (params instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) params).leftMargin = leftMargin;
                ((FrameLayout.LayoutParams) params).topMargin = top;
            }
            // Follow the line's own transform. The seek bar is drawn thin while the
            // controller ray is away and scales up ("chunky") as the ray approaches, and
            // that is a scale on the line view, not a layout change — copying the layout
            // height alone left the markers looking permanently hovered (reported from
            // the headset). Same box, so the line's pivot applies unchanged.
            View lineForTransform = lineView;
            if (lineForTransform != null) {
                if (getScaleX() != lineForTransform.getScaleX()) {
                    setScaleX(lineForTransform.getScaleX());
                }
                if (getScaleY() != lineForTransform.getScaleY()) {
                    setScaleY(lineForTransform.getScaleY());
                }
                if (getAlpha() != lineForTransform.getAlpha()) {
                    setAlpha(lineForTransform.getAlpha());
                }
                setPivotX(lineForTransform.getPivotX());
                setPivotY(lineForTransform.getPivotY());
                if (!scaleLogged) {
                    scaleLogged = true;
                    log("seekbar line: drawable thickness=" + lineThickness + " transform: scale=" + lineForTransform.getScaleX() + "x"
                        + lineForTransform.getScaleY() + " pivot=" + lineForTransform.getPivotX()
                        + "," + lineForTransform.getPivotY() + " alpha=" + lineForTransform.getAlpha()
                        + " size=" + lineForTransform.getWidth() + "x" + lineForTransform.getHeight());
                }
            }
            if (isInLayout()) {
                // sync() also runs from the track's layout listener, and Android logs
                // "requestLayout() improperly called ... during layout" if we ask for a
                // pass from inside one. Defer instead.
                post(new Runnable() {
                    @Override
                    public void run() {
                        requestLayout();
                    }
                });
            } else {
                requestLayout();
            }
            invalidate();
            if (geometryLogs < 6) {
                geometryLogs++;
                log("seekbar geometry #" + geometryLogs + ": span=" + spanWidth + "px left="
                    + leftMargin + " top=" + top + " height=" + height + " (parent "
                    + group.getClass().getSimpleName() + " " + group.getWidth() + "px, track "
                    + trackView.getWidth() + "px)");
            }
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            if (sizeLogs < 4) {
                sizeLogs++;
                log("seekbar overlay sized " + width + "x" + height + "px (was " + oldWidth + "x"
                    + oldHeight + ")");
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            try {
                List<SponsorSegment> segments = SponsorSkipController.segmentsSnapshot();
                long duration = durationMs;
                long windowStart = windowStartMs;
                long span = duration - windowStart;
                int width = getWidth();
                int height = getHeight();
                if (!SponsorBlockSettings.markersEnabled() || !SponsorBlockSettings.enabled()) {
                    return;
                }
                if (segments == null || segments.isEmpty() || span <= 0L || width <= 0) {
                    if (drawLogs < 2) {
                        drawLogs++;
                        log("seekbar draw skipped: width=" + width + " height=" + height
                            + " segments=" + (segments == null ? "none" : segments.size())
                            + " span=" + span + "ms");
                    }
                    return;
                }
                int drawn = 0;
                // The line's own drawable is an inset shape: `seekbar_gutter_bg.xml` fills
                // it with a 3dp transparent stroke (radius 1.5dp) by default, so the line a
                // viewer sees is this box inset by half that stroke — which is also why the
                // markers looked slightly wide before. In the selected state the drawable
                // has no stroke and fills the box, which is the "chunky" look when the
                // controller ray is near; following the view's selection keeps the markers
                // matching the line in both states.
                View line = lineView;
                boolean selected = line != null && line.isSelected();
                int inset = selected ? 0 : lineInset(getContext());
                int top = inset;
                int bottom = height - inset;
                if (bottom <= top) {
                    top = 0;
                    bottom = height;
                }
                float radius = Math.max(1f, (bottom - top) / 2f);
                for (SponsorSegment segment : segments) {
                    if ("ignore".equals(SponsorBlockSettings.behaviour(segment.category))) {
                        continue;
                    }
                    int x1 = (int) Math.max(0L, Math.min((long) width,
                        (long) (((double) (segment.startMs - windowStart) / span) * width)));
                    int x2 = (int) Math.max(0L, Math.min((long) width,
                        (long) (((double) (segment.endMs - windowStart) / span) * width)));
                    if (x2 <= x1) {
                        x2 = Math.min(width, x1 + 2);
                    }
                    x1 += inset;
                    x2 -= inset;
                    if (x2 <= x1) {
                        continue;
                    }
                    paint.setColor(colour(segment.category));
                    canvas.drawRoundRect(x1, top, x2, bottom, radius, radius, paint);
                    drawn++;
                }
                if (drawnLogs < 4) {
                    drawnLogs++;
                    log("seekbar markers drawn: " + drawn + " segment(s) over " + width + "px"
                        + " inset=" + inset + (selected ? " (line selected)" : "")
                        + " thickness=" + (bottom - top)
                        + " (window " + windowStart + "ms.." + duration + "ms)");
                }
            } catch (Throwable t) {
                // never throw into the app's draw pass
            }
        }
    }
}
