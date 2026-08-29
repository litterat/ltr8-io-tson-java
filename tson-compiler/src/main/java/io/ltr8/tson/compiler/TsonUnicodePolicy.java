package io.ltr8.tson.compiler;

import java.lang.Character.UnicodeScript;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * UTS #39 §5.2's restriction levels, as a policy a caller holds ({@code SPEC-FEEDBACK.md} #3 Step 4).
 *
 * <p><b>Two axes, not one ladder.</b> A <em>level</em> says which script combinations a unit may contain; a
 * <em>unit</em> says whether that level applies to the whole text or to each {@code _}/{@code -} delimited
 * segment. They are genuinely independent, because per-segment {@link Level#HIGHLY_RESTRICTIVE} and
 * {@link Level#MODERATELY_RESTRICTIVE} are incomparable: the first admits {@code id_пользователя} (Latin and
 * Cyrillic, never inside one word) and refuses Latin+Devanagari; the second does the opposite. A single
 * ordered knob cannot express both.
 *
 * <p><b>Why the level is the mechanism here at all</b>, having been the second choice for confusable
 * <em>names</em>: {@code ConfusableNames} is a relation and needs a set to hold over, and this is the rule
 * for the cases that have no set — one identifier judged alone, and every value in a document. It is the
 * same reasoning that makes restriction levels right for a browser judging a domain name, which likewise
 * cannot enumerate what it might be confused with.
 *
 * <p>Lives in the root package with the rest of the consumer-facing surface rather than beside {@code Xid}
 * and {@code Confusables}, which are internal machinery in the unexported {@code lexer} package. A caller
 * names this type at their own call site, which is the same thing that earns it the {@code Tson} prefix.
 *
 * <p>Instances are immutable; {@link #perSegment()} and {@link #permitting} return modified copies, so a
 * call site reads as one position rather than three settings.
 */
public final class TsonUnicodePolicy {

    /** UTS #39 §5.2's levels, loosest last. */
    public enum Level {

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
         * No script restriction, and characters need not be in the identifier profile either -- §5.2 is
         * explicit that this level alone drops it, which here means {@code IdentifierStatus} and with it the
         * obsolete and technical characters and the joiner exclusion. A deployment that means "stop checking
         * scripts" wants {@link #MINIMALLY_RESTRICTIVE}; §5.2 calls this one a diagnostic tool.
         *
         * <p>On a surface that has no identifier profile -- an ordinary token, which is not a name -- §5.2
         * makes this and {@code MINIMALLY_RESTRICTIVE} identical.
         */
        UNRESTRICTED
    }

    /** Latin + Han + Hiragana + Katakana (Latn + Jpan). */
    private static final Set<UnicodeScript> JPAN = EnumSet.of(UnicodeScript.LATIN, UnicodeScript.HAN,
            UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA);

    /** Latin + Han + Bopomofo (Latn + Hanb). */
    private static final Set<UnicodeScript> HANB = EnumSet.of(UnicodeScript.LATIN, UnicodeScript.HAN,
            UnicodeScript.BOPOMOFO);

    /** Latin + Han + Hangul (Latn + Kore). */
    private static final Set<UnicodeScript> KORE = EnumSet.of(UnicodeScript.LATIN, UnicodeScript.HAN,
            UnicodeScript.HANGUL);

    /** §5.2 names these two as the exceptions Moderately Restrictive does *not* pair with Latin. */
    private static final Set<UnicodeScript> CONFUSABLE_WITH_LATIN =
            EnumSet.of(UnicodeScript.CYRILLIC, UnicodeScript.GREEK);

    private final Level level;
    private final boolean perSegment;
    private final List<Set<UnicodeScript>> permitted;

    private TsonUnicodePolicy(Level level, boolean perSegment, List<Set<UnicodeScript>> permitted) {
        this.level = level;
        this.perSegment = perSegment;
        this.permitted = List.copyOf(permitted);
    }

    public static TsonUnicodePolicy of(Level level) {
        return new TsonUnicodePolicy(level, false, List.of());
    }

    public static TsonUnicodePolicy asciiOnly() {
        return of(Level.ASCII_ONLY);
    }

    public static TsonUnicodePolicy singleScript() {
        return of(Level.SINGLE_SCRIPT);
    }

    public static TsonUnicodePolicy highlyRestrictive() {
        return of(Level.HIGHLY_RESTRICTIVE);
    }

    public static TsonUnicodePolicy moderatelyRestrictive() {
        return of(Level.MODERATELY_RESTRICTIVE);
    }

    /** §5.2 level 5: no script restriction, the identifier profile kept. */
    public static TsonUnicodePolicy scriptsUnchecked() {
        return of(Level.MINIMALLY_RESTRICTIVE);
    }

    /** §5.2 level 6: no script restriction and no identifier profile. See {@link Level#UNRESTRICTED}. */
    public static TsonUnicodePolicy unrestricted() {
        return of(Level.UNRESTRICTED);
    }

    /**
     * This policy applied to each {@code _}/{@code -} delimited segment rather than to the whole text.
     *
     * <p>Programming identifiers are compounds, and their separators are exactly the boundaries at which a
     * script change is ordinary rather than suspicious: it admits {@code id_пользователя} and {@code alpha_α}
     * while still refusing {@code аdmin}, {@code pаssword} and {@code id_аdmin}, because a homograph has to
     * sit <em>inside</em> a word to read as that word. It belongs on identifiers only -- in a value
     * {@code _} and {@code -} are ordinary characters, and UTS #39's own {@code Toys-Я-Us} is what
     * segmenting a value would wrongly admit.
     */
    public TsonUnicodePolicy perSegment() {
        return new TsonUnicodePolicy(level, true, permitted);
    }

    /**
     * This policy with one further script combination admitted, the same mechanism §5.2 uses for Latn+Jpan
     * and its siblings. The narrowest relaxation available: a deployment that knows it is Russian says
     * {@code permitting(LATIN, CYRILLIC)} rather than dropping a level and losing the rule everywhere else.
     */
    public TsonUnicodePolicy permitting(UnicodeScript... scripts) {
        List<Set<UnicodeScript>> extended = new java.util.ArrayList<>(permitted);
        extended.add(Set.of(scripts));
        return new TsonUnicodePolicy(level, perSegment, extended);
    }

    /** Whether the check does anything at all -- an {@code UNRESTRICTED} whole-text policy scans nothing. */
    public boolean checksScripts() {
        return level != Level.MINIMALLY_RESTRICTIVE && level != Level.UNRESTRICTED;
    }

    /** Whether the identifier profile ({@code IdentifierStatus}) still applies -- everything but level 6. */
    public boolean appliesIdentifierProfile() {
        return level != Level.UNRESTRICTED;
    }

    /** The reason {@code text} fails this policy, or empty when it satisfies it. */
    public Optional<String> violation(String text) {
        if (!checksScripts()) {
            return Optional.empty();
        }
        for (String unit : perSegment ? text.split("[_-]") : new String[] {text}) {
            if (unit.isEmpty()) {
                continue;
            }
            Optional<String> failure = checkUnit(unit);
            if (failure.isPresent()) {
                return failure;
            }
        }
        return Optional.empty();
    }

    private Optional<String> checkUnit(String unit) {
        if (level == Level.ASCII_ONLY) {
            return unit.chars().allMatch(c -> c < 0x80)
                    ? Optional.empty()
                    : Optional.of("'" + unit + "' is not ASCII, and this processor requires ASCII-only "
                            + (perSegment ? "segments" : "names") + " (UTS #39 §5.2)");
        }
        Set<UnicodeScript> scripts = scriptsOf(unit);
        if (scripts.size() <= 1 || covered(scripts)) {
            return Optional.empty();
        }
        return Optional.of("'" + unit + "' mixes the scripts " + scripts + ", which UTS #39 §5.2's "
                + level + " does not admit -- a homograph reads as another name exactly by mixing scripts "
                + "inside one word");
    }

    private boolean covered(Set<UnicodeScript> scripts) {
        if (permitted.stream().anyMatch(set -> set.containsAll(scripts))) {
            return true;
        }
        if (level == Level.SINGLE_SCRIPT) {
            return false;
        }
        if (JPAN.containsAll(scripts) || HANB.containsAll(scripts) || KORE.containsAll(scripts)) {
            return true;
        }
        if (level != Level.MODERATELY_RESTRICTIVE) {
            return false;
        }
        // Latin and any one other script, except the two §5.2 names as confusable with Latin.
        Set<UnicodeScript> others = new LinkedHashSet<>(scripts);
        return others.remove(UnicodeScript.LATIN) && others.size() == 1
                && CONFUSABLE_WITH_LATIN.stream().noneMatch(others::contains);
    }

    /** The scripts {@code text} is written in, ignoring Common and Inherited per §5.1. */
    private static Set<UnicodeScript> scriptsOf(String text) {
        EnumSet<UnicodeScript> seen = EnumSet.noneOf(UnicodeScript.class);
        text.codePoints().forEach(cp -> {
            UnicodeScript script = UnicodeScript.of(cp);
            if (script != UnicodeScript.COMMON && script != UnicodeScript.INHERITED
                    && script != UnicodeScript.UNKNOWN) {
                seen.add(script);
            }
        });
        return seen;
    }

    @Override
    public String toString() {
        return level + (perSegment ? " per segment" : "") + (permitted.isEmpty() ? "" : " permitting " + permitted);
    }
}
