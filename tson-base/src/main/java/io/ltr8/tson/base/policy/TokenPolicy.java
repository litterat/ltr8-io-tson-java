package io.ltr8.tson.base.policy;

import io.ltr8.unicode.RestrictionLevel;

import java.lang.Character.UnicodeScript;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * [TSON-DATA] §8.2's <b>token policy</b>: a UTS #39 §5.2 {@link RestrictionLevel} and the script combinations
 * admitted beside it, applied to the whole of each token's text, with its refusal messages.
 *
 * <p><b>No unit, because a value has no segments.</b> {@code _} and {@code -} are word separators by
 * convention in a name and ordinary characters in a value, so segmenting a value would admit UTS #39's own
 * {@code Toys-Я-Us}, the spoof a strict token policy exists to refuse. The unit is {@link
 * IdentifierPolicy#perSegment()}'s, and a token policy cannot state one.
 *
 * <p>Instances are immutable; {@link #permitting} returns a modified copy.
 */
public final class TokenPolicy {

    private final ScriptRule rule;

    private TokenPolicy(ScriptRule rule) {
        this.rule = rule;
    }

    public static TokenPolicy of(RestrictionLevel level) {
        return new TokenPolicy(new ScriptRule(level));
    }

    public static TokenPolicy asciiOnly() {
        return of(RestrictionLevel.ASCII_ONLY);
    }

    public static TokenPolicy singleScript() {
        return of(RestrictionLevel.SINGLE_SCRIPT);
    }

    public static TokenPolicy highlyRestrictive() {
        return of(RestrictionLevel.HIGHLY_RESTRICTIVE);
    }

    public static TokenPolicy moderatelyRestrictive() {
        return of(RestrictionLevel.MODERATELY_RESTRICTIVE);
    }

    /** §5.2 level 5: no script restriction. */
    public static TokenPolicy scriptsUnchecked() {
        return of(RestrictionLevel.MINIMALLY_RESTRICTIVE);
    }

    /**
     * §5.2 level 6: no script restriction. Over a token it judges as {@link #scriptsUnchecked()} does, the
     * identifier profile it drops being one no token is judged under. See {@link RestrictionLevel#UNRESTRICTED}.
     */
    public static TokenPolicy unrestricted() {
        return of(RestrictionLevel.UNRESTRICTED);
    }

    /**
     * This policy with one further script combination admitted, the same device §5.2 uses for Latn+Jpan
     * and its siblings. The narrowest relaxation available: a deployment that knows it is Russian says
     * {@code permitting(LATIN, CYRILLIC)} rather than dropping a level and losing the rule everywhere else.
     */
    public TokenPolicy permitting(UnicodeScript... scripts) {
        return new TokenPolicy(rule.permitting(scripts));
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
        return rule.level();
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
        return rule.permittedScripts();
    }

    /** Whether the check does anything at all -- an {@code UNRESTRICTED} whole-text policy scans nothing. */
    public boolean checksScripts() {
        return rule.checksScripts();
    }

    /** The reason {@code text} fails this policy, or empty when it satisfies it. */
    public Optional<String> violation(String text) {
        if (!rule.checksScripts() || text.isEmpty()) {
            return Optional.empty();
        }
        return rule.checkUnit(text, 0, text.length(), false);
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
        return o instanceof TokenPolicy other && rule.equals(other.rule);
    }

    @Override
    public int hashCode() {
        return rule.hashCode();
    }

    @Override
    public String toString() {
        return level() + (permittedScripts().isEmpty() ? "" : " permitting " + permittedScripts());
    }
}
