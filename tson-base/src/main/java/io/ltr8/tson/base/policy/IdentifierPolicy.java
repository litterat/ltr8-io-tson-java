package io.ltr8.tson.base.policy;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.unicode.IdentifierProfile;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * [TSON-DATA] §8.2's <b>identifier policy</b>: all three name-hygiene mechanisms, as a deployment configures
 * them for the positions that hold a name. Skeleton distinctness over a scope ({@link
 * #appliesSkeletonDistinctness()}), {@code Identifier_Status} (the restricted-character rule, which the level
 * carries), and a restriction level ({@link #scripts()}) applied to a unit that is the whole name or each
 * segment of it ({@link #isPerSegment()}).
 *
 * <p><b>A name is judged under its family's profile as well as this policy</b> ({@link #judge}). The profile
 * says what the name's own characters are: one it adds -- §7.7's {@code -}, a profile's {@code $} -- meets no
 * restricted-character rule, and one it adds that is not {@code XID_Continue} divides the name into segments
 * ({@link IdentifierProfile#separates}). The policy is the deployment's and the profile the schema's, and
 * neither stands in for the other: §8.2 forbids a schema to carry a policy, and a profile only ever decides
 * what is a name.
 *
 * <p><b>Two axes for the level, not one ladder.</b> The level says which script combinations a unit may
 * contain; the unit says whether it applies to the whole name or to each segment. They are independent,
 * because per-segment {@link UnicodePolicy.Level#HIGHLY_RESTRICTIVE} and {@link
 * UnicodePolicy.Level#MODERATELY_RESTRICTIVE} are incomparable: the first admits {@code id_пользователя}
 * (Latin and Cyrillic, never inside one word) and refuses Latin+Devanagari; the second does the opposite.
 *
 * <p><b>Skeleton distinctness has a switch of its own</b>, since §8.2 requires each of the three to be
 * relaxable and no level reaches it: UTS #39 ties {@code Identifier_Status} to Unrestricted, and nothing ties
 * a relation over a set to a level that judges one name. The per-name rules are judged here; the relation is
 * judged where a scope is enumerated, and asks {@link #appliesSkeletonDistinctness()} first.
 *
 * <p>Instances are immutable; {@link #perSegment()} and {@link #withSkeletonDistinctness} return modified
 * copies.
 */
public final class IdentifierPolicy {

    /**
     * One per-name rule a name failed: the rule as its {@link Diagnostic.Code} -- a character to change and a
     * script to relax want different fixes, and a code is what a consumer routes on -- and why, opening with
     * the name or the unit refused.
     */
    public record Violation(Diagnostic.Code code, String reason) {
    }

    private static final IdentifierPolicy NONE = new IdentifierPolicy(UnicodePolicy.unrestricted(), false, false);

    private final UnicodePolicy scripts;
    private final boolean perSegment;
    private final boolean skeletonDistinctness;

    private IdentifierPolicy(UnicodePolicy scripts, boolean perSegment, boolean skeletonDistinctness) {
        this.scripts = Objects.requireNonNull(scripts, "scripts");
        this.perSegment = perSegment;
        this.skeletonDistinctness = skeletonDistinctness;
    }

    /** {@code scripts} over each whole name, with skeleton distinctness -- §8.2's defaults bar the level. */
    public static IdentifierPolicy of(UnicodePolicy scripts) {
        return new IdentifierPolicy(scripts, false, true);
    }

    /** §8.2's recommended policy: Highly Restrictive over the whole name, all three mechanisms on. */
    public static IdentifierPolicy defaults() {
        return of(UnicodePolicy.highlyRestrictive());
    }

    /**
     * A policy that judges nothing: Unrestricted, and no skeleton distinctness. For a source whose names did
     * not come from document text, and for a read that holds no position at which a member is a name.
     */
    public static IdentifierPolicy none() {
        return NONE;
    }

    /**
     * This policy's level applied to each segment of a name rather than to the whole of it -- the relaxation
     * §8.2 says to reach for first. It admits {@code id_пользователя} and {@code alpha_α} while still refusing
     * {@code аdmin}, {@code pаssword} and {@code id_аdmin}, because a homograph has to sit <em>inside</em> a
     * word to read as that word. A name's segments are divided by its profile's own separators ({@link
     * IdentifierProfile#separates}).
     */
    public IdentifierPolicy perSegment() {
        return new IdentifierPolicy(scripts, true, skeletonDistinctness);
    }

    /** This policy with skeleton distinctness -- mechanism 1, the look-alike rule over a scope -- on or off. */
    public IdentifierPolicy withSkeletonDistinctness(boolean on) {
        return new IdentifierPolicy(scripts, perSegment, on);
    }

    /** The restriction level and any combinations admitted over it. */
    public UnicodePolicy scripts() {
        return scripts;
    }

    /** Whether the level applies to each segment of a name rather than to the whole of it. */
    public boolean isPerSegment() {
        return perSegment;
    }

    /** Whether no two names in one scope may share a UTS #39 skeleton. */
    public boolean appliesSkeletonDistinctness() {
        return skeletonDistinctness;
    }

    /**
     * Whether the restricted-character rule applies: everything but Unrestricted, which §8.2 says "drops the
     * profile too", taking {@code Identifier_Status} with it.
     */
    public boolean appliesIdentifierProfile() {
        return scripts.appliesIdentifierProfile();
    }

    /**
     * §8.2's two per-name rules over {@code name} under {@code profile}: every rule it fails, the
     * restricted-character rule first, or an empty list.
     *
     * <p><b>Allocation-free when the name passes</b>, which is every name of an ordinary document: both rules
     * scan without materialising anything, and the empty list is a constant. A caller asks {@code isEmpty()}
     * before it iterates, and so pays for an iterator only on a refusal.
     */
    public List<Violation> judge(String name, IdentifierProfile profile) {
        Optional<String> restricted = appliesIdentifierProfile() ? profile.restrictedCharacter(name)
                : Optional.empty();
        Optional<String> script = restrictedScript(name, profile);
        if (restricted.isEmpty()) {
            return script.isEmpty() ? List.of()
                    : List.of(new Violation(Diagnostic.Code.RESTRICTED_SCRIPT, script.get()));
        }
        Violation character = new Violation(Diagnostic.Code.RESTRICTED_CHARACTER, restricted.get());
        return script.isEmpty() ? List.of(character)
                : List.of(character, new Violation(Diagnostic.Code.RESTRICTED_SCRIPT, script.get()));
    }

    /** The restriction level over {@code name}'s unit, or each unit: the first that fails, or empty. */
    private Optional<String> restrictedScript(String name, IdentifierProfile profile) {
        if (!scripts.checksScripts() || name.isEmpty()) {
            return Optional.empty();
        }
        if (!perSegment) {
            return scripts.checkUnit(name, 0, name.length(), false);
        }
        int start = 0;
        for (int i = 0; i <= name.length(); ) {
            int cp = i < name.length() ? name.codePointAt(i) : -1;
            if (cp != -1 && !profile.separates(cp)) {
                i += Character.charCount(cp);
                continue;
            }
            if (i > start) {
                Optional<String> failure = scripts.checkUnit(name, start, i, true);
                if (failure.isPresent()) {
                    return failure;
                }
            }
            i += cp == -1 ? 1 : Character.charCount(cp);
            start = i;
        }
        return Optional.empty();
    }

    /** Two policies are equal when they would judge every name and every scope alike. */
    @Override
    public boolean equals(Object o) {
        return o instanceof IdentifierPolicy other && scripts.equals(other.scripts) && perSegment == other.perSegment
                && skeletonDistinctness == other.skeletonDistinctness;
    }

    @Override
    public int hashCode() {
        return Objects.hash(scripts, perSegment, skeletonDistinctness);
    }

    @Override
    public String toString() {
        return scripts.level() + (perSegment ? " per segment" : "")
                + (scripts.permittedScripts().isEmpty() ? "" : " permitting " + scripts.permittedScripts())
                + (skeletonDistinctness ? "" : " without skeleton distinctness");
    }
}
