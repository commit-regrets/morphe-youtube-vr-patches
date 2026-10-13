package app.ytvr.extension;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The category rows' user interface: their name is painted in the category's seek-bar colour, and
 * tapping one opens this port's behaviour picker.
 *
 * <p>Why the picker is built here rather than by a `ListPreference`'s own dialog: this app replaces
 * androidx's preference adapter with its own, and that adapter dispatches a row click itself. A
 * `ListPreference` row renders (with its summary) but tapping it does nothing, because the app's
 * dispatcher does not know the row type -- verified on the device. Row clicks *are* delivered to a
 * view-level listener (that is how the hub row navigates), so this replaces that listener on the
 * rows it recognises and shows a platform `AlertDialog` of its own. Platform APIs only: this class
 * is compiled against `android.jar`, with no androidx on the classpath, and androidx's members are
 * R8-renamed in this APK anyway.
 *
 * <p>The choices are written straight to the app's own preference file, the same file the patch's
 * rows and {@link SponsorBlockSettings} read, so a pick takes effect on the next segment with no
 * restart.
 */
public final class CategoryRows {

    private static final String TAG = "SponsorBlock";

    /** The arrays the patch writes, so the labels here and in the XML cannot drift apart. */
    private static final String[] BEHAVIOUR_ENTRY_NAMES = {
        "ytvr_sb_behaviour_entries", "ytvr_sb_behaviour_values",
    };

    private static final String[] HIGHLIGHT_ENTRY_NAMES = {
        "ytvr_sb_highlight_entries", "ytvr_sb_highlight_values",
    };

    /** Used only if the resources cannot be read: the same lists the patch bakes in. */
    private static final String[] FALLBACK_LABELS = {
        "Skip automatically", "Skip automatically once", "Show in seekbar only", "Disable",
    };

    private static final String[] FALLBACK_VALUES = {"skip", "skip-once", "seekbar-only", "ignore"};
    private static final String[] FALLBACK_HIGHLIGHT_LABELS = {
        "Skip automatically", "Show in seekbar only", "Disable",
    };
    private static final String[] FALLBACK_HIGHLIGHT_VALUES = {"skip", "seekbar-only", "ignore"};

    /**
     * Where a row remembers the colour its own theme gave it, so a recycled row can be restored.
     */
    private static final int ORIGINAL_COLOUR_TAG = 0x7f0f0001;

    /** Marks the behaviour list inside a row, so a rebind can clear it and a tap can toggle it. */
    private static final String LIST_TAG = "ytvr_sb_behaviour_list";

    /**
     * The two rows that are ours but are not categories, matched by the title the patch writes them
     * with (`SettingsXml.STATS_TITLE` / `SettingsXml.MIN_DURATION_TITLE`).
     */
    private static final String STATS_TITLE = "Stats";

    private static final String MIN_DURATION_TITLE = "Minimum segment duration";

    /**
     * The log gate's row, matched by the title the patch gives it
     * (`SettingsXml.VERBOSE_LOGGING_TITLE`). It is the one row whose value this class writes itself
     * -- see {@link #decorateLogGateRow}.
     */
    private static final String VERBOSE_TITLE = "Verbose logging";

    /**
     * The video-quality row, matched by the title the patch writes it with
     * (`SettingsXml.VIDEO_QUALITY_TITLE`). It is the second row whose picker this class owns, and
     * the reason the row is a plain `Preference` rather than a `ListPreference` -- see {@link
     * #decorateQualityRow}.
     */
    private static final String VIDEO_QUALITY_TITLE = "Default video quality";

    /**
     * The quality list's accent, so an open list reads as a different control from a category's.
     */
    private static final int QUALITY_ACCENT = 0xFF3EA6FF;

    private static final Set<String> REPORTED = Collections.synchronizedSet(new HashSet<String>());
    private static volatile Field ITEM_VIEW;
    private static volatile boolean HOOK_REPORTED;
    private static volatile boolean MISS_REPORTED;

    private CategoryRows() {}

    /**
     * Hooked at instruction 0 of `androidx.preference.Preference`'s row binder -- the one-parameter
     * method that populates a row's view, which the patch finds by the fact that it calls
     * `TextView.setText(CharSequence)` rather than by any name. It is handed the binder's view
     * holder, so the row view is recovered by looking for the holder's `View` field whose tree
     * carries the title -- no field name needed either.
     *
     * <p>The work is posted rather than done inline because the binder is what sets the title, the
     * summary and the app's own click listener: all three must already be in place before this
     * paints over the first two and replaces the third.
     *
     * <p>Never throws into the app: a settings row that fails to colour must not cost the screen.
     */
    public static void bindRow(Object holder) {
        try {
            if (holder == null) {
                return;
            }
            if (!HOOK_REPORTED) {
                HOOK_REPORTED = true;
                log("category row hook fired");
            }
            final View row = rowViewOf(holder);
            if (row == null) {
                if (!MISS_REPORTED) {
                    MISS_REPORTED = true;
                    log(
                            "no row view on the preference holder ("
                                    + holder.getClass().getName()
                                    + "): the category rows will not be coloured");
                }
                return;
            }
            row.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            decorate(row);
                        }
                    });
        } catch (Throwable t) {
            log("row hook failed: " + t);
        }
    }

    /** Colour the name, then let the row pick its behaviour. */
    private static void decorate(View row) {
        try {
            TextView title = (TextView) row.findViewById(android.R.id.title);
            if (title == null) {
                return;
            }
            // A recycled row must not keep another category's open list.
            ViewParent titleParent = title.getParent();
            if (titleParent instanceof ViewGroup) {
                clearList((ViewGroup) titleParent);
            }
            CharSequence text = title.getText();
            String label = text == null ? "" : text.toString();
            String category = CategoryColours.categoryFor(label);
            Object tag = row.getTag(ORIGINAL_COLOUR_TAG);
            Integer remembered = tag instanceof Integer ? (Integer) tag : null;

            if (category == null) {
                // Not a category row. Three of ours are still not categories: the stats and the
                // minimum-duration rows are display-only -- their summary is written here, because
                // a
                // static XML summary could never show a running total or a live value -- and the
                // verbose-logging row is the one row whose *write* is ours as well.
                if (STATS_TITLE.equals(label)) {
                    showSummary(row, SponsorBlockStats.summary());
                } else if (MIN_DURATION_TITLE.equals(label)) {
                    long minSeconds = SponsorBlockSettings.minSegmentDurationMs() / 1000L;
                    showSummary(
                            row,
                            minSeconds <= 0L
                                    ? "Off: every segment is used"
                                    : "Segments under " + minSeconds + "s are ignored");
                } else if (VERBOSE_TITLE.equals(label)) {
                    decorateLogGateRow(row);
                } else if (VIDEO_QUALITY_TITLE.equals(label)) {
                    decorateQualityRow(row);
                }
                // **Leave every other row completely alone** -- this hook runs for every row of
                // every
                // settings screen, and clearing the click listener here removed the app's own
                // handler, which is how the "Patch settings" row stopped opening (verified on the
                // device). The colour is only put back if we were the ones who changed it.
                if (remembered != null) {
                    title.setTextColor(remembered.intValue());
                    row.setTag(ORIGINAL_COLOUR_TAG, null);
                }
                return;
            }
            if (remembered == null) {
                remembered = Integer.valueOf(title.getCurrentTextColor());
                row.setTag(ORIGINAL_COLOUR_TAG, remembered);
            }
            title.setTextColor(CategoryColours.colour(category));
            // The row's subtitle is written from the *effective* behaviour, not from the
            // ListPreference's own value. androidx only substitutes the summary for a value it has
            // already persisted, and its notion of "current" knows nothing about the baked defaults
            // or the legacy-switch migration -- measured: `music_offtopic` has no stored value and
            // showed "Disable" while the extension was running `seekbar-only`. What the row says
            // must
            // match what the player does.
            showSummary(
                    row,
                    behaviourLabel(
                            row.getContext(), category, SponsorBlockSettings.behaviour(category)));
            if (REPORTED.add(label)) {
                log(
                        "category row ready: '"
                                + label
                                + "' #"
                                + Integer.toHexString(CategoryColours.colour(category)));
            }
            attachPicker(row);
        } catch (Throwable t) {
            log("row decoration failed: " + t);
        }
    }

    /**
     * The "Verbose logging" row: the log gate's switch, and the one row this class *writes*.
     *
     * <p>The value is written through {@link SponsorBlockSettings#setVerboseLogging} instead of by
     * the widget's own persistence, because that path is the one the gate is guaranteed to read.
     * Measured on the device: this row's `SwitchPreference` flips to ON and stays ON across a
     * screen rebuild, while `ytvr_mod_verbose_logging` never appears in the preference file the
     * extension reads -- `SponsorBlock: patch settings (...): the screen's switches are not there
     * yet` printed on a build whose row read ON, across an update install that preserved app data.
     * Owning both halves (the write and the displayed state) is the same bargain the behaviour
     * pickers already make, and it also makes the switch honest in a development build, where the
     * gate starts open with nothing stored: the row shows what the gate *is*, not what someone once
     * chose.
     */
    private static void decorateLogGateRow(final View row) {
        try {
            final CompoundButton toggle = compoundButtonIn(row);
            // One listener instance, so a programmatic setChecked can detach and re-attach the very
            // same one: without that, the row's own tap (which sets the widget too) would re-enter
            // this listener and log the same change twice.
            final CompoundButton.OnCheckedChangeListener listener =
                    new CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(CompoundButton button, boolean checked) {
                            setLogGate(checked);
                        }
                    };
            showLogGate(toggle, listener);
            row.setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View clicked) {
                            setLogGate(!VerboseLog.enabled());
                            showLogGate(toggle, listener);
                        }
                    });
        } catch (Throwable t) {
            log("verbose logging row failed: " + t);
        }
    }

    /**
     * Draws the switch from the gate's effective state, without that being read as a tap: the
     * listener comes off for the duration of the change. Also the path that shows a development
     * build's open gate on a row with nothing stored.
     */
    private static void showLogGate(
            CompoundButton toggle, CompoundButton.OnCheckedChangeListener listener) {
        if (toggle == null) {
            return;
        }
        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(VerboseLog.enabled());
        toggle.setOnCheckedChangeListener(listener);
    }

    /** Persists the choice in the file the gate reads, and opens or closes the gate immediately. */
    private static void setLogGate(boolean on) {
        // One tap reaches this through both of the row's listeners (the row's onClick and the
        // switch's checked-change), so deduplicate on the gate's current state: a request for the
        // state already in force is the second delivery of the same tap. Measured 2026-10-11: an
        // ON tap echoed twice same-millisecond once the echo stopped being gated -- the old gated
        // echo had been printing "once per tap" only because the gate swallowed the duplicate.
        if (VerboseLog.enabled() == on) {
            return;
        }
        // The echo comes first and ungated: the write below hands the gate its new state, and a
        // gated echo is exactly the one line a viewer needs to see a tap worked (see
        // VerboseLog.logGateToggle).
        VerboseLog.logGateToggle(on);
        SponsorBlockSettings.setVerboseLogging(on);
        VerboseLog.setEnabled(on);
    }

    /**
     * The "Default video quality" row: the current resolution goes under the row's name and the
     * picker is this class's own in-row list.
     *
     * <p>**Why this row is not a `ListPreference`.** Measured on the device, both halves fail the
     * same way, and the failure is indistinguishable from "the row does nothing": this app's
     * preference adapter dispatches a row click itself and does nothing for a row type it does not
     * know, and androidx's `ListPreference` picker is an `AlertDialog`, which is never composited
     * into the VR panel (see {@link #showList}). That was the report this replaced -- tapping the
     * row opened nothing (2026-10-13). Owning the row means owning its write too, exactly like the
     * behaviour pickers and the log gate, so the value is written by {@link
     * VideoQuality#setSelection}.
     */
    private static void decorateQualityRow(final View row) {
        try {
            String current = VideoQuality.currentSelection();
            showSummary(row, VideoQuality.labelFor(current));
            if (REPORTED.add(VIDEO_QUALITY_TITLE)) {
                log(
                        "video quality row ready: '"
                                + current
                                + "' ("
                                + VideoQuality.labelFor(current)
                                + ")");
            }
            // Rows are recycled, so the listener reads nothing from this bind: it opens the row it
            // is handed and lets the list decide what that row currently means.
            row.setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View clicked) {
                            showQualityList(clicked);
                        }
                    });
        } catch (Throwable t) {
            log("video quality row failed: " + t);
        }
    }

    /**
     * The quality picker: the same in-row list the behaviour rows use, in two columns.
     *
     * <p>The columns are not decoration. This list is nine options against the behaviour list's
     * four, so one column would run past the bottom of the panel -- and scrolling to reach the last
     * options rebinds the row, which closes the list. Two columns of short labels halve that
     * height.
     *
     * <p>The options themselves live in {@link VideoQuality}, which is also what the player reads,
     * so the list and the value in force cannot drift apart.
     */
    private static void showQualityList(View row) {
        try {
            TextView title = (TextView) row.findViewById(android.R.id.title);
            if (title == null) {
                log("video quality list: the row has no title view to hang the list on");
                return;
            }
            ViewParent parent = title.getParent();
            final ViewGroup host =
                    parent instanceof ViewGroup
                            ? (ViewGroup) parent
                            : (row instanceof ViewGroup ? (ViewGroup) row : null);
            if (host == null) {
                log("video quality list: nowhere inside the row to put the list");
                return;
            }
            if (host.findViewWithTag(LIST_TAG) != null) {
                clearList(host);
                return;
            }

            Context context = row.getContext();
            final String[] labels = VideoQuality.ENTRY_LABELS;
            final String[] values = VideoQuality.ENTRY_VALUES;
            final String current = VideoQuality.currentSelection();

            final TextView summary = (TextView) row.findViewById(android.R.id.summary);
            if (summary != null && summary.getVisibility() == View.VISIBLE) {
                summary.setVisibility(View.INVISIBLE);
            }

            LinearLayout list = new LinearLayout(context);
            list.setTag(LIST_TAG);
            list.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xE61C1C1C);
            background.setCornerRadius(dp(context, 10));
            background.setStroke(dp(context, 1), (QUALITY_ACCENT & 0x00FFFFFF) | 0x66000000);
            list.setBackground(background);
            list.setPadding(dp(context, 12), dp(context, 2), dp(context, 12), dp(context, 2));

            LinearLayout line = null;
            for (int index = 0; index < labels.length && index < values.length; index++) {
                if (index % 2 == 0) {
                    line = new LinearLayout(context);
                    line.setOrientation(LinearLayout.HORIZONTAL);
                    list.addView(
                            line,
                            new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT));
                }
                final String value = values[index];
                final String optionLabel = labels[index];
                boolean selected = value.equals(current);
                TextView option = new TextView(context);
                option.setText((selected ? "\u2713 " : "   ") + optionLabel);
                option.setTextColor(selected ? QUALITY_ACCENT : 0xFFEEEEEE);
                option.setTextSize(16f);
                option.setPadding(0, dp(context, 8), 0, dp(context, 8));
                option.setClickable(true);
                option.setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View clicked) {
                                VideoQuality.setSelection(value);
                                clearList(host);
                                showSummary(row, optionLabel);
                                log("video quality list: " + value + " ('" + optionLabel + "')");
                            }
                        });
                line.addView(
                        option,
                        new LinearLayout.LayoutParams(
                                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }

            host.addView(list, listParams(context, host, title, summary));
            // The list's own rect, not the row's: at this point the row is still as tall as it was
            // before the list was attached, so scrolling by that would leave the last options below
            // the panel's edge. Posted, so the list has been laid out by the time it is measured.
            row.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            View shown = host.findViewWithTag(LIST_TAG);
                            if (shown != null) {
                                shown.requestRectangleOnScreen(
                                        new Rect(0, 0, shown.getWidth(), shown.getHeight()), false);
                            }
                        }
                    });
            log(
                    "video quality list shown: '"
                            + current
                            + "', "
                            + labels.length
                            + " option(s) in the row");
        } catch (Throwable t) {
            log("video quality list failed: " + t);
        }
    }

    /**
     * The switch inside a row, found by type: androidx's ids are R8-renamed, platform types are
     * not.
     */
    private static CompoundButton compoundButtonIn(View view) {
        if (view instanceof CompoundButton) {
            return (CompoundButton) view;
        }
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            CompoundButton found = compoundButtonIn(group.getChildAt(index));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Replaces the row's click listener with the behaviour picker.
     *
     * <p>Replacing rather than adding is deliberate: the app's own listener dispatches by key and
     * does nothing for these rows, so there is nothing to preserve, and a listener set here wins
     * because it runs after the bind. Rows are recycled, so the listener reads the row's *current*
     * title instead of closing over the category it was installed for -- a stale category would
     * otherwise change the wrong row's behaviour.
     */
    private static void attachPicker(final View row) {
        row.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View clicked) {
                        TextView title = (TextView) clicked.findViewById(android.R.id.title);
                        String label =
                                title == null || title.getText() == null
                                        ? null
                                        : title.getText().toString();
                        String category = CategoryColours.categoryFor(label);
                        if (category != null) {
                            showList(clicked, category);
                        }
                    }
                });
    }

    /**
     * The picker: the category's behaviours as a list, built **inside the row's own view**.
     *
     * <p>Three placements were tried and measured on the device, which is why this one looks odd: a
     * platform `AlertDialog` is created and shown with no exception and never appears, and views
     * added to the window's decor view are built and attached with no exception and never appear
     * either (`behaviour list shown: ... 4 option(s)` in the log, nothing on the panel). The panel
     * is an Impress-rendered texture of the app's own content, so only views inside that subtree
     * are drawn -- hence the list goes under the row's summary, in the column that holds its title.
     *
     * <p>Tapping the row again closes it, and a rebind clears it (see {@link #decorate}) so a
     * recycled row cannot carry another category's list.
     */
    private static void showList(View row, String category) {
        try {
            TextView title = (TextView) row.findViewById(android.R.id.title);
            if (title == null) {
                log("behaviour list: the row has no title view to hang the list on");
                return;
            }
            // The column that holds the title and summary: the list belongs under them.
            ViewParent parent = title.getParent();
            final ViewGroup host =
                    parent instanceof ViewGroup
                            ? (ViewGroup) parent
                            : (row instanceof ViewGroup ? (ViewGroup) row : null);
            if (host == null) {
                log("behaviour list: nowhere inside the row to put the list");
                return;
            }
            if (host.findViewWithTag(LIST_TAG) != null) {
                clearList(host);
                return;
            }

            Context context = row.getContext();
            boolean highlight = "poi_highlight".equals(category);
            final String[] labels =
                    stringArray(
                            context,
                            highlight ? HIGHLIGHT_ENTRY_NAMES[0] : BEHAVIOUR_ENTRY_NAMES[0],
                            highlight ? FALLBACK_HIGHLIGHT_LABELS : FALLBACK_LABELS);
            final String[] values =
                    stringArray(
                            context,
                            highlight ? HIGHLIGHT_ENTRY_NAMES[1] : BEHAVIOUR_ENTRY_NAMES[1],
                            highlight ? FALLBACK_HIGHLIGHT_VALUES : FALLBACK_VALUES);
            final String current = SponsorBlockSettings.behaviour(category);
            int colour = CategoryColours.colour(category);

            final TextView summary = (TextView) row.findViewById(android.R.id.summary);
            // The list marks the current behaviour itself, so the row's summary would only repeat
            // it -- and inside the row the two would sit in the same column.
            if (summary != null && summary.getVisibility() == View.VISIBLE) {
                summary.setVisibility(View.INVISIBLE);
            }

            LinearLayout list = new LinearLayout(context);
            list.setTag(LIST_TAG);
            list.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable background = new GradientDrawable();
            // Near-opaque and dark like the app's own cards: a translucent list over a playing
            // video
            // reads as a washed-out smudge on the panel (seen in a capture).
            background.setColor(0xE61C1C1C);
            background.setCornerRadius(dp(context, 10));
            background.setStroke(dp(context, 1), (colour & 0x00FFFFFF) | 0x66000000);
            list.setBackground(background);
            list.setPadding(dp(context, 12), dp(context, 2), dp(context, 12), dp(context, 2));

            for (int index = 0; index < labels.length && index < values.length; index++) {
                final String value = values[index];
                final String optionLabel = labels[index];
                boolean selected = value.equals(current);
                TextView option = new TextView(context);
                option.setText((selected ? "\u2713 " : "   ") + optionLabel);
                option.setTextColor(selected ? colour : 0xFFEEEEEE);
                option.setTextSize(17f);
                option.setPadding(0, dp(context, 9), 0, dp(context, 9));
                option.setClickable(true);
                option.setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View clicked) {
                                SponsorBlockSettings.setBehaviour(category, value);
                                clearList(host);
                                showSummary(row, optionLabel);
                                log(
                                        "behaviour list: "
                                                + category
                                                + " -> "
                                                + value
                                                + " ('"
                                                + optionLabel
                                                + "')");
                            }
                        });
                list.addView(
                        option,
                        new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT));
            }

            host.addView(list, listParams(context, host, title, summary));
            // A row near the bottom of the panel would otherwise push the last options off the
            // visible area, and scrolling to them would rebind the row and close the list.
            row.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            row.requestRectangleOnScreen(
                                    new Rect(0, 0, row.getWidth(), row.getHeight()), false);
                        }
                    });
            log(
                    "behaviour list shown: "
                            + category
                            + " ("
                            + title.getText()
                            + ") now "
                            + current
                            + ", "
                            + labels.length
                            + " option(s) in the row");
        } catch (Throwable t) {
            log("behaviour list failed: " + t);
        }
    }

    /** The entry label for a behaviour, from the patch's own arrays so the wording cannot drift. */
    private static String behaviourLabel(Context context, String category, String behaviour) {
        boolean highlight = "poi_highlight".equals(category);
        String[] labels =
                stringArray(
                        context,
                        highlight ? HIGHLIGHT_ENTRY_NAMES[0] : BEHAVIOUR_ENTRY_NAMES[0],
                        highlight ? FALLBACK_HIGHLIGHT_LABELS : FALLBACK_LABELS);
        String[] values =
                stringArray(
                        context,
                        highlight ? HIGHLIGHT_ENTRY_NAMES[1] : BEHAVIOUR_ENTRY_NAMES[1],
                        highlight ? FALLBACK_HIGHLIGHT_VALUES : FALLBACK_VALUES);
        int index = indexOf(values, behaviour);
        if (index >= 0 && index < labels.length) {
            return labels[index];
        }
        // A behaviour the row's own list does not offer (a `skip-once` highlight, or one set
        // through
        // the patch options): say the raw value rather than showing a label that would be a lie.
        return behaviour;
    }

    /**
     * Layout params that put the list *under* the row's text, in whatever container the row uses.
     *
     * <p>This is not cosmetic: the row's text column is a `RelativeLayout` (`preference.xml`:
     * title, then summary with `layout_below="@android:id/title"`), so `LinearLayout.LayoutParams`
     * were ignored and the list landed in the corner on top of the summary -- verified against a
     * captured panel, where "Skip automatically" appeared twice, overlapping.
     */
    private static ViewGroup.LayoutParams listParams(
            Context context, ViewGroup host, TextView title, TextView summary) {
        int top = dp(context, 8);
        if (host instanceof RelativeLayout) {
            RelativeLayout.LayoutParams params =
                    new RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.MATCH_PARENT,
                            RelativeLayout.LayoutParams.WRAP_CONTENT);
            boolean summaryShown = summary != null && summary.getVisibility() == View.VISIBLE;
            params.addRule(
                    RelativeLayout.BELOW, summaryShown ? android.R.id.summary : android.R.id.title);
            params.topMargin = top;
            return params;
        }
        if (host instanceof LinearLayout) {
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = top;
            return params;
        }
        return new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Removes the list from a row, on a choice, on a second tap, or on a rebind. */
    private static void clearList(ViewGroup host) {
        try {
            View list = host.findViewWithTag(LIST_TAG);
            if (list != null) {
                host.removeView(list);
            }
            // The summary was hidden while the list was open (the list marks the current choice
            // itself); it has to come back, or the row would show no behaviour at all afterwards.
            TextView summary = (TextView) host.findViewById(android.R.id.summary);
            if (summary != null && summary.getVisibility() != View.VISIBLE) {
                CharSequence text = summary.getText();
                if (text != null && text.length() > 0) {
                    summary.setVisibility(View.VISIBLE);
                }
            }
        } catch (Throwable t) {
            log("behaviour list clear failed: " + t);
        }
    }

    private static int dp(Context context, int value) {
        return (int)
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        value,
                        context.getResources().getDisplayMetrics());
    }

    /** Puts the chosen behaviour under the row's name, the way the phone patch's rows read. */
    private static void showSummary(View row, String label) {
        try {
            TextView summary = (TextView) row.findViewById(android.R.id.summary);
            if (summary == null) {
                return;
            }
            summary.setText(label);
            // The binder hides the summary when its text is empty (`setVisibility(8)` measured in
            // `Preference.a(cgb)`), and setting text does not bring it back: without this the two
            // display-only rows -- Stats and Minimum segment duration -- rendered with no subtitle.
            if (summary.getVisibility() != View.VISIBLE) {
                summary.setVisibility(View.VISIBLE);
            }
        } catch (Throwable t) {
            log("row summary failed: " + t);
        }
    }

    /**
     * The patch's own arrays, so the dialog and the XML cannot disagree; the list is the fallback.
     */
    private static String[] stringArray(Context context, String name, String[] fallback) {
        try {
            int id = context.getResources().getIdentifier(name, "array", context.getPackageName());
            if (id != 0) {
                String[] values = context.getResources().getStringArray(id);
                if (values != null && values.length > 0) {
                    return values;
                }
            }
            log("no " + name + " array; using the built-in list");
        } catch (Throwable t) {
            log("array " + name + " unreadable: " + t);
        }
        return fallback;
    }

    private static int indexOf(String[] values, String value) {
        for (int index = 0; index < values.length; index++) {
            if (values[index].equals(value)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The holder's row view: the first `View` field whose tree carries the title TextView. Testing
     * for the title is what picks the item view over the inner views a holder may also hold, and it
     * uses only `android.R.id.title`, a platform id.
     */
    private static View rowViewOf(Object holder) {
        Class<?> holderClass = holder.getClass();
        Field cached = ITEM_VIEW;
        if (cached != null && cached.getDeclaringClass().isAssignableFrom(holderClass)) {
            try {
                Object value = cached.get(holder);
                if (value instanceof View) {
                    return (View) value;
                }
            } catch (Throwable ignored) {
                ITEM_VIEW = null;
            }
        }
        for (Class<?> type = holderClass;
                type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !View.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(holder);
                    if (value instanceof View
                            && ((View) value).findViewById(android.R.id.title) != null) {
                        ITEM_VIEW = field;
                        return (View) value;
                    }
                } catch (Throwable ignored) {
                    // A field we cannot read is simply not the item view.
                }
            }
        }
        return null;
    }

    /** Through the one gate: a build whose "Verbose logging" row is untouched prints nothing. */
    private static void log(String message) {
        VerboseLog.log(TAG, message);
    }
}
