package io.ltr8.unicode;

import java.lang.Character.UnicodeScript;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * UTS #39 §5.2's restriction levels, loosest last, and the check each makes over text: which script combinations
 * one run of text may contain.
 *
 * <p><b>A level judges text alone</b>, with no set to compare against, which is what makes it the rule where
 * there is none -- one identifier judged on its own, a value, a domain name a browser shows. Where the names of a
 * closed set are known, {@link ConfusableNames} is the sharper rule: it refuses only a real collision, where a
 * level refuses every mixed-script name it does not admit.
 *
 * <p>A caller may admit combinations beyond a level's own ({@link #admits}'s {@code alsoAdmitted}), the device
 * §5.2 itself uses for Latn+Jpan and its siblings: a system that knows it is Russian admits Latin+Cyrillic rather
 * than dropping a level and losing the rule everywhere else.
 */
public enum RestrictionLevel {

    /** Every character in the ASCII range. */
    ASCII_ONLY,

    /** ASCII-only, or covered by one script (Common and Inherited ignored). */
    SINGLE_SCRIPT,

    /** Single-script, or one of Latin+Jpan, Latin+Hanb, Latin+Kore. */
    HIGHLY_RESTRICTIVE,

    /** Highly Restrictive, or Latin and any one other script except Cyrillic and Greek. */
    MODERATELY_RESTRICTIVE,

    /** No script restriction. The identifier profile still applies -- see {@link #UNRESTRICTED}. */
    MINIMALLY_RESTRICTIVE,

    /**
     * No script restriction, and characters need not be in the identifier profile either -- §5.2 is explicit that
     * this level alone drops it, which means {@link IdentifierStatus} and with it the obsolete and technical
     * characters and the joiner exclusion. A caller that means "stop checking scripts" wants
     * {@link #MINIMALLY_RESTRICTIVE}; §5.2 calls this one a diagnostic tool.
     *
     * <p>On text that has no identifier profile -- an ordinary value, which is not a name -- §5.2 makes this and
     * {@code MINIMALLY_RESTRICTIVE} identical.
     */
    UNRESTRICTED;

    /** Latin + Han + Hiragana + Katakana (Latn + Jpan). */
    private static final Set<UnicodeScript> JPAN = EnumSet.of(UnicodeScript.LATIN, UnicodeScript.HAN,
            UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA);

    /** Latin + Han + Bopomofo (Latn + Hanb). */
    private static final Set<UnicodeScript> HANB = EnumSet.of(UnicodeScript.LATIN, UnicodeScript.HAN,
            UnicodeScript.BOPOMOFO);

    /** Latin + Han + Hangul (Latn + Kore). */
    private static final Set<UnicodeScript> KORE = EnumSet.of(UnicodeScript.LATIN, UnicodeScript.HAN,
            UnicodeScript.HANGUL);

    /** §5.2 names these two as the exceptions Moderately Restrictive does <em>not</em> pair with Latin. */
    private static final Set<UnicodeScript> CONFUSABLE_WITH_LATIN =
            EnumSet.of(UnicodeScript.CYRILLIC, UnicodeScript.GREEK);

    /** Whether this level restricts scripts at all -- levels 5 and 6 do not. */
    public boolean restrictsScripts() {
        return this != MINIMALLY_RESTRICTIVE && this != UNRESTRICTED;
    }

    /** Whether the identifier profile ({@code Identifier_Status}) still applies -- every level but 6. */
    public boolean keepsIdentifierProfile() {
        return this != UNRESTRICTED;
    }

    /**
     * Whether {@code text[from, to)} satisfies this level, with each combination in {@code alsoAdmitted} admitted
     * beside the level's own. A level that restricts no scripts admits everything; {@code ASCII_ONLY} admits ASCII
     * alone, whatever else is listed.
     *
     * <p><b>Allocation-free when the text is in one script</b>, which is nearly all text: a scan over the range that
     * materialises nothing, not even the substring. Only genuinely mixed-script text builds the set of its scripts.
     */
    public boolean admits(String text, int from, int to, List<Set<UnicodeScript>> alsoAdmitted) {
        if (!restrictsScripts()) {
            return true;
        }
        if (this == ASCII_ONLY) {
            for (int i = from; i < to; i++) {
                if (text.charAt(i) >= 0x80) {
                    return false;
                }
            }
            return true;
        }
        UnicodeScript only = null;
        for (int i = from; i < to; ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            UnicodeScript script = UnicodeScript.of(codePoint);
            if (script == UnicodeScript.COMMON || script == UnicodeScript.INHERITED
                    || script == UnicodeScript.UNKNOWN) {
                continue;
            }
            if (only == null) {
                only = script;
            } else if (only != script) {
                return covers(scriptsOf(text, from, to), alsoAdmitted);
            }
        }
        return true;
    }

    /** The scripts {@code text[from, to)} is written in, ignoring Common and Inherited per §5.1. */
    public static Set<UnicodeScript> scriptsOf(String text, int from, int to) {
        EnumSet<UnicodeScript> seen = EnumSet.noneOf(UnicodeScript.class);
        for (int i = from; i < to; ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            UnicodeScript script = UnicodeScript.of(codePoint);
            if (script != UnicodeScript.COMMON && script != UnicodeScript.INHERITED
                    && script != UnicodeScript.UNKNOWN) {
                seen.add(script);
            }
        }
        return seen;
    }

    /** Whether this level, or one of {@code alsoAdmitted}, admits text written in exactly {@code scripts}. */
    private boolean covers(Set<UnicodeScript> scripts, List<Set<UnicodeScript>> alsoAdmitted) {
        for (int i = 0; i < alsoAdmitted.size(); i++) {
            if (alsoAdmitted.get(i).containsAll(scripts)) {
                return true;
            }
        }
        if (this == SINGLE_SCRIPT) {
            return false;
        }
        if (JPAN.containsAll(scripts) || HANB.containsAll(scripts) || KORE.containsAll(scripts)) {
            return true;
        }
        if (this != MODERATELY_RESTRICTIVE) {
            return false;
        }
        // Latin and any one other script, except the two §5.2 names as confusable with Latin.
        if (!scripts.contains(UnicodeScript.LATIN)) {
            return false;
        }
        UnicodeScript other = null;
        for (UnicodeScript script : scripts) {
            if (script == UnicodeScript.LATIN) {
                continue;
            }
            if (other != null) {
                return false;
            }
            other = script;
        }
        return other != null && !CONFUSABLE_WITH_LATIN.contains(other);
    }
}
