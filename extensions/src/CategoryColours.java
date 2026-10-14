package app.ytvr.extension;

/**
 * The category palette, shared by the seek-bar markers and the settings rows.
 *
 * <p>The colour table lives here alone so a marker and the row that names it can never disagree;
 * {@link CategoryRows} paints the rows and this class only answers "what colour is this category".
 */
public final class CategoryColours {

    /**
     * The category rows' display titles as the patch writes them, mapped to their category key.
     * **The titles are duplicated in the patch's `SettingsXml.CATEGORIES`** -- they are the only
     * thing a row hook can match on, since it never sees the row's key. Renaming a row there
     * without renaming it here loses that row's colour and its behaviour picker, so the row hook
     * logs every title it recognises.
     */
    private static final String[][] TITLE_TO_CATEGORY = {
        {"Sponsor", "sponsor"},
        {"Unpaid / self-promotion", "selfpromo"},
        {"Interaction reminder", "interaction"},
        {"Intro / intermission", "intro"},
        {"Outro / endcards", "outro"},
        {"Highlight", "poi_highlight"},
        {"Preview / recap", "preview"},
        {"Hook / greetings", "hook"},
        {"Filler / tangent", "filler"},
        {"Non-music section", "music_offtopic"},
    };

    private CategoryColours() {}

    /**
     * SponsorBlock's own segment colours, per category -- the seek bar's palette, and the settings
     * rows'. All ten of upstream's defaults are here; `hook` was missing, which is why hook
     * segments were drawn in the fallback grey on the seek bar (found by the colour legend logging
     * `Hook / greetings #ff808080`). The one difference from upstream is the alpha: these are
     * opaque (`FF`) where upstream uses `CC`, matching what this overlay has always drawn.
     */
    public static int colour(String category) {
        if (category == null) {
            return 0xFF808080;
        }
        switch (category) {
            case "sponsor":
                return 0xFF00D400;
            case "selfpromo":
                return 0xFFFFFF00;
            case "interaction":
                return 0xFFCC00FF;
            case "intro":
                return 0xFF00FFFF;
            case "outro":
                return 0xFF0202ED;
            case "preview":
                return 0xFF008FD6;
            case "music_offtopic":
                return 0xFFFF9900;
            case "filler":
                return 0xFF7300FF;
            case "poi_highlight":
                return 0xFFFF1684;
            case "hook":
                return 0xFF395699;
            default:
                return 0xFF808080;
        }
    }

    /**
     * The short name the in-player Skip button offers the category by. The phone patch composes
     * "Skip &lt;category&gt;" from resources the VR app does not have, so the wording is this
     * port's own; it is short because the button's card is sized by its text and the longest row
     * title ("Unpaid / self-promotion") would make it wide enough to cover the player controls.
     *
     * <p>A highlight gets "Skip to", not "Skip": the jump is *to* the point most viewers are
     * looking for, which is what upstream's own wording says.
     */
    static String skipLabel(String category) {
        if (category == null) {
            return "Skip segment";
        }
        switch (category) {
            case "sponsor":
                return "Skip Sponsor";
            case "selfpromo":
                return "Skip Self-promotion";
            case "interaction":
                return "Skip Interaction";
            case "intro":
                return "Skip Intro";
            case "outro":
                return "Skip Outro";
            case "preview":
                return "Skip Preview";
            case "music_offtopic":
                return "Skip Non-music";
            case "filler":
                return "Skip Filler";
            case "poi_highlight":
                return "Skip to Highlight";
            case "hook":
                return "Skip Hook";
            default:
                return "Skip segment";
        }
    }

    /** The category key whose settings row carries this title, or null. */
    static String categoryFor(String title) {
        if (title == null) {
            return null;
        }
        for (String[] row : TITLE_TO_CATEGORY) {
            if (row[0].equals(title)) {
                return row[1];
            }
        }
        return null;
    }
}
