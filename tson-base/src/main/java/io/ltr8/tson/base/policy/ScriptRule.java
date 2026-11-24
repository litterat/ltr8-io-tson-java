package io.ltr8.tson.base.policy;

import io.ltr8.unicode.RestrictionLevel;

import java.lang.Character.UnicodeScript;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A UTS #39 §5.2 {@link RestrictionLevel} and the script combinations admitted beside it -- [TSON-DATA] §8.2's
 * restricted-script rule over one unit, with its refusal messages. {@link TokenPolicy} applies it to whole text and
 * {@link IdentifierPolicy} to a name or each of its segments; each states the rule as its own components.
 *
 * <p><b>Why the level is the rule here at all</b>, having been the second choice for confusable
 * <em>names</em>: {@code ConfusableNames} is a relation and needs a set to hold over, and this is the rule
 * for the cases that have no set — one identifier judged alone, and every value in a document. It is the
 * same reasoning that makes restriction levels right for a browser judging a domain name, which likewise
 * cannot enumerate what it might be confused with.
 *
 * <p>Equal when it would judge every unit alike: the level and the admitted combinations in the order {@link
 * #permitting} added them.
 */
record ScriptRule(RestrictionLevel level, List<Set<UnicodeScript>> permittedScripts) {

    ScriptRule {
        Objects.requireNonNull(level, "level");
        permittedScripts = List.copyOf(permittedScripts);
    }

    ScriptRule(RestrictionLevel level) {
        this(level, List.of());
    }

    ScriptRule permitting(UnicodeScript... scripts) {
        List<Set<UnicodeScript>> extended = new ArrayList<>(permittedScripts);
        extended.add(Set.of(scripts));
        return new ScriptRule(level, extended);
    }

    /** Whether the check does anything at all -- an {@code UNRESTRICTED} level scans nothing. */
    boolean checksScripts() {
        return level.restrictsScripts();
    }

    /** Whether the identifier profile ({@code Identifier_Status}) still applies -- everything but level 6. */
    boolean appliesIdentifierProfile() {
        return level.keepsIdentifierProfile();
    }

    /**
     * This level over {@code text[from, to)}, the whole text or one segment of a name; {@code segment} says which,
     * for the message. Allocation-free when the unit passes, as {@link RestrictionLevel#admits} is.
     */
    Optional<String> checkUnit(String text, int from, int to, boolean segment) {
        if (level.admits(text, from, to, permittedScripts)) {
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
}
