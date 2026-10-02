package io.ltr8.tson.base.policy;

import java.lang.Character.UnicodeScript;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * UTS #39 §5.2's restriction levels over whole text, as a policy a caller holds -- [TSON-DATA] §8.2's
 * restricted-script rule. It is the <b>token policy</b> as it stands, and the script half of an {@link
 * IdentifierPolicy}, which adds what only a name has: a unit that may be each segment, the restricted-character
 * rule, and skeleton distinctness over a scope.
 *
 * <p><b>Why the level is the rule here at all</b>, having been the second choice for confusable
 * <em>names</em>: {@code ConfusableNames} is a relation and needs a set to hold over, and this is the rule
 * for the cases that have no set — one identifier judged alone, and every value in a document. It is the
 * same reasoning that makes restriction levels right for a browser judging a domain name, which likewise
 * cannot enumerate what it might be confused with.
 *
 * <p><b>No unit, because a value has no segments.</b> {@code _} and {@code -} are word separators by
 * convention in a name and ordinary characters in a value, so segmenting a value would admit UTS #39's own
 * {@code Toys-Я-Us}, the spoof a strict token policy exists to refuse. The unit is {@link
 * IdentifierPolicy#perSegment()}'s, and a token policy cannot state one.
 *
 * <p>Instances are immutable; {@link #permitting} returns a modified copy.
 */
public final class ScriptPolicy {

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
    private final List<Set<UnicodeScript>> permitted;

    private ScriptPolicy(Level level, List<Set<UnicodeScript>> permitted) {
        this.level = level;
        this.permitted = List.copyOf(permitted);
    }

    public static ScriptPolicy of(Level level) {
        return new ScriptPolicy(level, List.of());
    }

    public static ScriptPolicy asciiOnly() {
        return of(Level.ASCII_ONLY);
    }

    public static ScriptPolicy singleScript() {
        return of(Level.SINGLE_SCRIPT);
    }

    public static ScriptPolicy highlyRestrictive() {
        return of(Level.HIGHLY_RESTRICTIVE);
    }

    public static ScriptPolicy moderatelyRestrictive() {
        return of(Level.MODERATELY_RESTRICTIVE);
    }

    /** §5.2 level 5: no script restriction, the identifier profile kept. */
    public static ScriptPolicy scriptsUnchecked() {
        return of(Level.MINIMALLY_RESTRICTIVE);
    }

    /** §5.2 level 6: no script restriction and no identifier profile. See {@link Level#UNRESTRICTED}. */
    public static ScriptPolicy unrestricted() {
        return of(Level.UNRESTRICTED);
    }

    /**
     * This policy with one further script combination admitted, the same device §5.2 uses for Latn+Jpan
     * and its siblings. The narrowest relaxation available: a deployment that knows it is Russian says
     * {@code permitting(LATIN, CYRILLIC)} rather than dropping a level and losing the rule everywhere else.
     */
    public ScriptPolicy permitting(UnicodeScript... scripts) {
        List<Set<UnicodeScript>> extended = new java.util.ArrayList<>(permitted);
        extended.add(Set.of(scripts));
        return new ScriptPolicy(level, extended);
    }

    /**
     * The UTS #39 §5.2 restriction level this policy applies.
     *
     * <p>With {@link #permittedScripts()}, the whole of what this policy is -- which is what lets a deployment
     * <em>state</em> its configuration rather than only apply it. A document one processor accepts and another
     * refuses differs by exactly these and an {@link IdentifierPolicy}'s own components, and a reader of the refusal
     * has no other way to learn which one moved: the policy that judged is not in the document, not in the
     * schema, and not in the diagnostic. See {@link ProcessorPolicy}, which is the policies plus
     * {@link ProcessorPolicy#dataVersion()} as one value a run or a response states once.
     */
    public Level level() {
        return level;
    }

    /**
     * The script combinations this policy admits over and above its {@link #level()}, in the order {@link
     * #permitting} added them -- empty for a policy that has taken no such relaxation.
     *
     * <p>The narrowest relaxation available is also the one most likely to explain a disagreement between
     * two deployments, since a level is usually left at the default and a {@code permitting(LATIN,
     * CYRILLIC)} is by definition a local decision.
     */
    public List<Set<UnicodeScript>> permittedScripts() {
        return permitted;
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
        if (!checksScripts() || text.isEmpty()) {
            return Optional.empty();
        }
        return checkUnit(text, 0, text.length(), false);
    }

    /**
     * This level over {@code text[from, to)} -- the unit an {@link IdentifierPolicy} chose, which is the whole
     * name or one segment of it. {@code segment} says which, for the message.
     *
     * <p><b>Allocation-free when the unit passes</b>, which on the token surface is every token of an ordinary
     * document and on the name surface every name. A conforming unit is answered by a scan over the range that
     * materialises nothing -- not even the substring; only a genuinely mixed-script one reaches {@link #mixed},
     * and that one is either an error or a deliberate combination, so a set and a message there cost nothing
     * that matters.
     */
    Optional<String> checkUnit(String text, int from, int to, boolean segment) {
        if (level == Level.ASCII_ONLY) {
            for (int i = from; i < to; i++) {
                if (text.charAt(i) >= 0x80) {
                    return Optional.of("'" + text.substring(from, to) + "' is not ASCII, and this processor "
                            + "requires ASCII-only " + (segment ? "segments" : "names") + " (UTS #39 §5.2)");
                }
            }
            return Optional.empty();
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
                return mixed(text.substring(from, to));
            }
        }
        return Optional.empty();
    }

    /** The unit is written in more than one script: decide whether this level admits the combination. */
    private Optional<String> mixed(String unit) {
        Set<UnicodeScript> scripts = scriptsOf(unit);
        if (covered(scripts)) {
            return Optional.empty();
        }
        return Optional.of("'" + unit + "' mixes the scripts " + scripts + ", which UTS #39 §5.2's "
                + level + " does not admit -- a homograph reads as another name exactly by mixing scripts "
                + "inside one word");
    }

    private boolean covered(Set<UnicodeScript> scripts) {
        for (int i = 0; i < permitted.size(); i++) {
            if (permitted.get(i).containsAll(scripts)) {
                return true;
            }
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

    /** The scripts {@code text} is written in, ignoring Common and Inherited per §5.1. */
    private static Set<UnicodeScript> scriptsOf(String text) {
        EnumSet<UnicodeScript> seen = EnumSet.noneOf(UnicodeScript.class);
        for (int i = 0; i < text.length(); ) {
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

    /**
     * Two policies are equal when they would judge every text alike -- the level and the admitted
     * combinations in the order {@link #permitting} added them.
     *
     * <p>A policy is a value, and the types that report one are records ({@code ProcessorPolicy})
     * whose own equality is component-wise: without this, two processors configured identically compare
     * unequal, which is the opposite of what a caller comparing two deployments' configurations is asking.
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof ScriptPolicy other && level == other.level && permitted.equals(other.permitted);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(level, permitted);
    }

    @Override
    public String toString() {
        return level + (permitted.isEmpty() ? "" : " permitting " + permitted);
    }
}
