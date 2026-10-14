package app.ytvr.extension;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Method;

/**
 * The in-headset "Skip &lt;category&gt;" button: this port's implementation of the phone patch's
 * {@code SkipSponsorButton}, for the {@code manual-skip} behaviour ("Show a Skip button").
 *
 * <p><b>Why this is the app's own skip-Ad card.</b> The VR app builds its ad-skip button as a
 * standalone {@code VrCardView} registered with the activity's VR view root ({@code
 * gah.q(R.layout.vr_skip_ad_button)} → {@code addVrContentView}), shown and hidden with {@code
 * gbd.o(boolean)}, and positioned by the environment next to the player controls. That is the only
 * surface in this app that is drawn during playback <em>and</em> hit-tested by the controller ray —
 * a view added to the player-controls card is drawn only while the controls are on screen, and a
 * view added to the window's decor is never composited at all (both measured, see {@code
 * docs/SPONSORBLOCK-NOTES.md}). So the button is built exactly the way the app builds its own: the
 * same layout, the same registration, the same show/hide call.
 *
 * <p><b>What the patch gives this class.</b> Two bridge methods it generates on the activity
 * ({@link #attachActivity} is handed the activity by the hook on the app's skip-Ad-button
 * constructor):
 *
 * <ul>
 *   <li>{@code YtvrSbMakeSkipCard(activity) -> View} — inflates the app's skip-button layout,
 *       registers it as a VR content view, sets its panel slot, and hands back the card view;
 *   <li>{@code YtvrSbSkipCardVisible(View, boolean)} — the app's own show/hide call.
 * </ul>
 *
 * Both are <em>our</em> names on the app's class, so R8's renames cannot break the calls; the app's
 * own member names are never used here. Everything is defensive: a missing bridge, a changed layout
 * or a dead activity logs and leaves playback alone.
 *
 * <p>All work happens on the main thread: the controller's tick already runs there, and the hook
 * that hands the activity over runs inside the app's own constructor.
 */
public final class SkipButtonCard {

    private static final String TAG = "SponsorBlock";

    private static final String MAKE_CARD = "YtvrSbMakeSkipCard";
    private static final String SET_VISIBLE = "YtvrSbSkipCardVisible";

    /**
     * The activity whose VR view root the card is registered with, or null before the first video.
     */
    private static volatile Object activity;

    private static volatile Method makeCard;
    private static volatile Method visibilityBridge;

    /**
     * The card view itself (the app's `VrCardView`), the clickable container inside it, its label.
     */
    private static volatile View card;

    private static volatile View button;
    private static volatile TextView label;

    /** What is offered right now; remembered so a card built later still shows it. */
    private static volatile SponsorSegment offered;

    private static volatile boolean createdLogged;
    private static volatile boolean placedLogged;
    private static volatile boolean failureLogged;
    private static volatile boolean shown;
    private static volatile boolean threadReported;

    private SkipButtonCard() {}

    /**
     * Hooked at instruction 0 of the app's skip-Ad-button constructor, which is what hands over the
     * activity: that constructor receives it as its first parameter, and it runs once per player
     * surface — the app is building its own skip button, which is exactly when this one can be
     * built the same way.
     */
    public static void attachActivity(Object host) {
        try {
            if (host == null) {
                return;
            }
            if (host == activity && card != null) {
                return;
            }
            // A different player surface gets its own card; the previous one is hidden rather than
            // dropped, because a card registered with a VR view root stays registered until the app
            // removes it.
            View previous = card;
            if (previous != null) {
                setVisible(previous, false);
            }
            activity = host;
            card = null;
            button = null;
            label = null;
            shown = false;
            makeCard = findMethod(host.getClass(), MAKE_CARD);
            visibilityBridge = findMethod(host.getClass(), SET_VISIBLE);
            if (makeCard == null || visibilityBridge == null) {
                reportFailureOnce(
                        "the patch's skip-button bridges are missing ("
                                + MAKE_CARD
                                + "="
                                + (makeCard != null)
                                + " "
                                + SET_VISIBLE
                                + "="
                                + (visibilityBridge != null)
                                + ")");
                return;
            }
            Object created = makeCard.invoke(null, host);
            if (!(created instanceof View)) {
                reportFailureOnce(
                        "the bridge returned "
                                + (created == null ? "null" : created.getClass().getName())
                                + " instead of a card view");
                return;
            }
            View cardView = (View) created;
            View container = null;
            TextView text = null;
            if (cardView instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) cardView;
                text = firstLabelledText(group);
                if (text != null) {
                    container = directChildOf(group, text);
                }
            }
            if (container == null || text == null) {
                reportFailureOnce(
                        "the skip-button card has no labelled button (children="
                                + describeChildren(cardView)
                                + ")");
                return;
            }
            // The app's own card carries its button and its ad countdown as siblings, and the
            // countdown is the visible one by default. Only the button is wanted here.
            if (cardView instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) cardView;
                for (int index = 0; index < group.getChildCount(); index++) {
                    View child = group.getChildAt(index);
                    child.setVisibility(child == container ? View.VISIBLE : View.GONE);
                }
            }
            container.setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View clicked) {
                            SponsorSkipController.onSkipButtonClicked();
                        }
                    });
            card = cardView;
            button = container;
            label = text;
            shown = false;
            setVisible(cardView, false);
            // The anchor the app's skip-Ad button uses puts the card under the player controls;
            // this port wants it at the left end of that panel, which is where the phone's own skip
            // button sits. The shift is a view translation, which is exactly the lever the app
            // itself uses on this card (`gtu` moves it by `player_controls_panel_translation_y`).
            // It needs the card's measured width, so it is also (re)applied on every layout.
            cardView.addOnLayoutChangeListener(
                    new View.OnLayoutChangeListener() {
                        @Override
                        public void onLayoutChange(
                                View changed,
                                int left,
                                int top,
                                int right,
                                int bottom,
                                int oldLeft,
                                int oldTop,
                                int oldRight,
                                int oldBottom) {
                            place(changed);
                        }
                    });
            place(cardView);
            if (!createdLogged) {
                createdLogged = true;
                log(
                        "skip button card ready: "
                                + cardView.getClass().getName()
                                + " children="
                                + describeChildren(cardView)
                                + " label='"
                                + text.getText()
                                + "'");
            }
            // A segment may already be waiting (the card is built when the player surface is, which
            // can be after the segments for the first video arrived).
            apply(offered);
        } catch (Throwable t) {
            reportFailureOnce("skip button card failed: " + t);
        }
    }

    /**
     * Puts [segment]'s offer on screen, or takes the offer away when it is null. Called by the
     * controller on every state change, never per tick.
     */
    public static void setSegment(final SponsorSegment segment) {
        try {
            offered = segment;
            if (card == null) {
                // No player surface yet: the offer is remembered and applied when it appears.
                return;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                if (!threadReported) {
                    threadReported = true;
                    log("skip button update off the main thread: posting");
                }
                View root = card;
                root.post(
                        new Runnable() {
                            @Override
                            public void run() {
                                apply(segment);
                            }
                        });
                return;
            }
            apply(segment);
        } catch (Throwable t) {
            log("skip button update failed: " + t);
        }
    }

    /** The view work itself, always on the main thread. */
    private static void apply(SponsorSegment segment) {
        try {
            if (card == null) {
                return;
            }
            boolean show = segment != null;
            String text = show ? CategoryColours.skipLabel(segment.category) : null;
            if (show && label != null && text != null && !text.contentEquals(label.getText())) {
                label.setText(text);
            }
            if (button != null) {
                button.setVisibility(show ? View.VISIBLE : View.GONE);
            }
            if (shown != show) {
                shown = show;
                setVisible(card, show);
                log(
                        show
                                ? "skip button shown: '" + text + "' for " + segment
                                : "skip button hidden");
            }
        } catch (Throwable t) {
            log("skip button apply failed: " + t);
        }
    }

    /** The app's own show/hide call on a card, through the generated bridge. */
    private static void setVisible(View cardView, boolean visible) {
        Method method = visibilityBridge;
        if (method == null || cardView == null) {
            return;
        }
        try {
            method.invoke(null, cardView, Boolean.valueOf(visible));
        } catch (Throwable t) {
            log("skip button show/hide failed: " + t);
        }
    }

    /**
     * How far left of the app's own skip-Ad anchor the card's **left edge** sits, measured in
     * player-controls panel widths. The app anchors its ad-skip card under the player controls,
     * near their centre; the phone patch's own skip button sits at the bottom **left** of the
     * video. Settled by looking at it in the headset, not by arithmetic: 0.5 of a panel was "can we
     * move more to left?", 1.0 was "a bit too far", so the answer is in between.
     */
    private static final float LEFT_PANEL_WIDTHS = 0.75f;

    /**
     * Shifts the card left of the player-controls panel, using the app's own panel width as the
     * reference so the placement cannot be a magic number. Both numbers are logged, because this is
     * the one part of the button that a log cannot settle — a look in the headset is what says
     * whether the placement is right.
     */
    private static void place(View cardView) {
        try {
            int panel = dimension(cardView.getContext(), "player_controls_panel_width");
            int width = cardView.getWidth();
            if (panel <= 0 || width <= 0) {
                return;
            }
            float offset = width / 2f - LEFT_PANEL_WIDTHS * panel;
            if (cardView.getTranslationX() != offset) {
                cardView.setTranslationX(offset);
            }
            if (!placedLogged) {
                placedLogged = true;
                log(
                        "skip button placed: card="
                                + width
                                + "x"
                                + cardView.getHeight()
                                + "px panel="
                                + panel
                                + "px offsetX="
                                + offset
                                + " leftEdge="
                                + (offset - width / 2f)
                                + "px density="
                                + cardView.getResources().getDisplayMetrics().density);
            }
        } catch (Throwable t) {
            log("skip button placement failed: " + t);
        }
    }

    /** An app dimension by name, or 0 when the APK no longer has it. */
    private static int dimension(Context context, String name) {
        if (context == null) {
            return 0;
        }
        int id = context.getResources().getIdentifier(name, "dimen", context.getPackageName());
        return id == 0 ? 0 : context.getResources().getDimensionPixelSize(id);
    }

    /**
     * A method by name anywhere in [type]'s hierarchy. `Class.getMethod` would need the bridge's
     * *parameter* types, which are obfuscated app types this extension cannot name; the name is
     * ours and unique, so the walk is by name alone.
     */
    private static Method findMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name)) {
                    return method;
                }
            }
        }
        return null;
    }

    /** The first `TextView` in [group]'s tree that carries text -- the app's own button label. */
    private static TextView firstLabelledText(ViewGroup group) {
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child instanceof TextView) {
                CharSequence text = ((TextView) child).getText();
                if (text != null && text.length() > 0) {
                    return (TextView) child;
                }
            }
            if (child instanceof ViewGroup) {
                TextView found = firstLabelledText((ViewGroup) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** [descendant]'s ancestor that is a direct child of [group], or null. */
    private static View directChildOf(ViewGroup group, View descendant) {
        View current = descendant;
        while (current != null && current.getParent() instanceof View) {
            if (current.getParent() == group) {
                return current;
            }
            current = (View) current.getParent();
        }
        return null;
    }

    private static String describeChildren(View view) {
        if (!(view instanceof ViewGroup)) {
            return view == null ? "none" : view.getClass().getSimpleName();
        }
        ViewGroup group = (ViewGroup) view;
        StringBuilder text = new StringBuilder("[");
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (index > 0) {
                text.append(", ");
            }
            text.append(child.getClass().getSimpleName())
                    .append(" id=0x")
                    .append(Integer.toHexString(child.getId()))
                    .append(" visible=")
                    .append(child.getVisibility() == View.VISIBLE);
        }
        return text.append(']').toString();
    }

    /** Logs the first failure only: a broken card must cost one line, not one line per tick. */
    private static void reportFailureOnce(String message) {
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        card = null;
        button = null;
        label = null;
        log("skip button unavailable: " + message);
    }

    /** Through the one gate: a build whose "Verbose logging" row is untouched prints nothing. */
    private static void log(String message) {
        VerboseLog.log(TAG, message);
    }
}
