package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.TsonValueReader;
import io.ltr8.tson.parser.ast.DataValue;

import java.util.Set;

/**
 * Shared dispatch-by-{@code !typeName} logic for {@link VariantParser} (an open union -- candidate
 * names discovered from {@code TypeDefinition.subtypes}) and {@link ChoiceParser} (a closed union
 * -- candidate names taken verbatim from {@code ChoiceBody.variants}). Once each has its own fixed
 * candidate-name set, the two are functionally identical: read the value's own type-ref, require it
 * names one of the candidates, delegate to that candidate's own parser -- resolved lazily, at
 * dispatch time, not for every candidate up front (see {@link VariantParser}'s own Javadoc for why:
 * a union's members are alternatives, not a fixed set every one of which is needed on every read).
 *
 * <p>{@code positionName}/{@code candidateNoun} exist purely so the two error messages stay
 * accurate to which kind of union this is ("open type parameters... known subtypes" vs. "a
 * choice... declared variants") without the dispatch logic itself needing to know or care.
 */
final class NamedDispatchParser implements TsonValueReader<Object> {

    private final String positionName;
    private final String missingTypeRefMessage;
    private final String candidateNoun;
    private final Set<String> candidateNames;
    private final CompilationContext ctx;

    NamedDispatchParser(String positionName, String missingTypeRefMessage, String candidateNoun,
                         Set<String> candidateNames, CompilationContext ctx) {
        this.positionName = positionName;
        this.missingTypeRefMessage = missingTypeRefMessage;
        this.candidateNoun = candidateNoun;
        this.candidateNames = candidateNames;
        this.ctx = ctx;
    }

    @Override
    public Object read(DataValue value) {
        String typeRef = value.typeRef().orElseThrow(() -> new IllegalArgumentException(
                "'" + positionName + "' " + missingTypeRefMessage + ": " + candidateNames));
        if (!candidateNames.contains(typeRef)) {
            throw new IllegalArgumentException("'" + typeRef + "' is not a " + candidateNoun + " of '"
                    + positionName + "' -- expected one of " + candidateNames);
        }
        // value passed through unchanged, typeRef included -- see VariantParser's own Javadoc for
        // why no compiled parser in this package ever reads DataValue.typeRef at all, so there's
        // nothing here for a leftover type-ref to be misread as one level down.
        return ctx.resolve(typeRef).read(value);
    }
}
