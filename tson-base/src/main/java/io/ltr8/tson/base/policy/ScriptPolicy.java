package io.ltr8.tson.base.policy;

import io.ltr8.unicode.RestrictionLevel;

import java.lang.Character.UnicodeScript;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A UTS #39 §5.2 {@link RestrictionLevel} and the script combinations admitted beside it, as a policy a caller
 * holds -- [TSON-DATA] §8.2's restricted-script rule, with its refusal messages. It is the <b>token policy</b> as
 * it stands, and the script half of an {@link IdentifierPolicy}, which adds what only a name has: a unit that may be
 * each segment, the restricted-character rule, and skeleton distinctness over a scope.
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

    private final RestrictionLevel level;
    private final List<Set<UnicodeScript>> permitted;

    private ScriptPolicy(RestrictionLevel level, List<Set<UnicodeScript>> permitted) {
        this.level = level;
        this.permitted = List.copyOf(permitted);
    }

    public static ScriptPolicy of(RestrictionLevel level) {
        return new ScriptPolicy(level, List.of());
    }

    public static ScriptPolicy asciiOnly() {
        return of(RestrictionLevel.ASCII_ONLY);
    }

    public static ScriptPolicy singleScript() {
        return of(RestrictionLevel.SINGLE_SCRIPT);
    }

    public static ScriptPolicy highlyRestrictive() {
        return of(RestrictionLevel.HIGHLY_RESTRICTIVE);
    }

    public static ScriptPolicy moderatelyRestrictive() {
        return of(RestrictionLevel.MODERATELY_RESTRICTIVE);
    }

    /** §5.2 level 5: no script restriction, the identifier profile kept. */
    public static ScriptPolicy scriptsUnchecked() {
        return of(RestrictionLevel.MINIMALLY_RESTRICTIVE);
    }

    /** §5.2 level 6: no script restriction and no identifier profile. See {@link RestrictionLevel#UNRESTRICTED}. */
    public static ScriptPolicy unrestricted() {
        return of(RestrictionLevel.UNRESTRICTED);
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
    public RestrictionLevel level() {
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
        return level.restrictsScripts();
    }

    /** Whether the identifier profile ({@code IdentifierStatus}) still applies -- everything but level 6. */
    public boolean appliesIdentifierProfile() {
        return level.keepsIdentifierProfile();
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
     * name or one segment of it. {@code segment} says which, for the message. Allocation-free when the unit
     * passes, as {@link RestrictionLevel#admits} is.
     */
    Optional<String> checkUnit(String text, int from, int to, boolean segment) {
        if (level.admits(text, from, to, permitted)) {
            return Optional.empty();
        }
        String unit = text.substring(from, to);
        if (level == RestrictionLevel.ASCII_ONLY) {
            return Optional.of("'" + unit + "' is not ASCII, and this processor requires ASCII-only "
                    + (segment ? "segments" : "names") + " (UTS #39 §5.2)");
        }
        return Optional.of("'" + unit + "' mixes the scripts " + RestrictionLevel.scriptsOf(text, from, to)
                + ", which UTS #39 §5.2's " + level + " does not admit -- a homograph reads as another name exactly "
                + "by mixing scripts inside one word");
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
