package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.compiler.ast.DataValue;

import java.util.Set;

/**
 * Dispatch-by-{@code !typeName} over a fixed candidate-name set -- this package's own copy of
 * {@code reader.NamedDispatchReader}. Reads the value's own type-ref, requires it names one of
 * {@code candidateNames}, delegates to that candidate's own reader, resolved lazily at dispatch
 * time rather than eagerly for every candidate up front (a closed list of variants is alternatives,
 * not a fixed group every one of which is needed on every read).
 *
 * <p>{@code positionName}/{@code candidateNoun} exist purely so the error messages stay accurate to
 * whichever kind of dispatch this is (currently only {@link ChoiceReader}'s closed, declared-variant
 * list) without the dispatch logic itself needing to know or care.
 */
final class NamedDispatchReader implements TsonValueReader<Object> {

    private final String positionName;
    private final String missingTypeRefMessage;
    private final String candidateNoun;
    private final Set<String> candidateNames;
    private final TsonValueReaderResolver resolver;

    NamedDispatchReader(String positionName, String missingTypeRefMessage, String candidateNoun,
                         Set<String> candidateNames, TsonValueReaderResolver resolver) {
        this.positionName = positionName;
        this.missingTypeRefMessage = missingTypeRefMessage;
        this.candidateNoun = candidateNoun;
        this.candidateNames = candidateNames;
        this.resolver = resolver;
    }

    @Override
    public Object read(DataValue value, TsonReadContext ctx) {
        if (value == null || value.typeRef().isEmpty()) {
            ctx.report(Diagnostic.Code.UNKNOWN_TYPE_REF,
                    "'" + positionName + "' " + missingTypeRefMessage + ": " + candidateNames,
                    "one of " + candidateNames, "no type annotation");
            return null;
        }
        String typeRef = value.typeRef().get();
        if (!candidateNames.contains(typeRef)) {
            ctx.report(Diagnostic.Code.UNKNOWN_TYPE_REF, "'" + typeRef + "' is not a " + candidateNoun + " of '"
                            + positionName + "' -- expected one of " + candidateNames,
                    "one of " + candidateNames, typeRef);
            return null;
        }
        return resolver.resolve(typeRef).read(value, ctx);
    }
}
