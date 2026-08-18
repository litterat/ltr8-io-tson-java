package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.tree.TsonAnnotation;
import io.ltr8.tson.tree.TsonValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.ValueParser;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dispatch-by-{@code !typeName} over a fixed candidate-name set. Reads the value's own type-ref (via {@link
 * EventSkip#annotationsAndTypeRef}), requires it names one of {@code candidateNames}, and delegates to that
 * candidate's own reader, resolved lazily at dispatch time.
 *
 * <p><b>Untagged recovery.</b> When {@code untaggedRecovery} is non-empty and the value carries no type-ref,
 * the value is instead recovered structurally: its own §4 base-type class selects the variant (§5.4). The map
 * is built (by {@link ChoiceReader}) only where that's sound -- every variant a scalar of a distinct
 * base-type class. When it's empty, an untagged value is the usual error (the tag is required).
 *
 * <p>{@code positionName}/{@code candidateNoun} keep the error messages accurate to whichever dispatch this
 * is (currently only {@link ChoiceReader}'s declared-variant list) without the dispatch logic knowing.
 */
final class NamedDispatchReader implements TsonTypeReader<Object> {

    private final String positionName;
    private final String missingTypeRefMessage;
    private final String candidateNoun;
    private final Set<String> candidateNames;
    private final TsonTypeReaderResolver resolver;
    private final Map<DiscriminationClass, String> untaggedRecovery;
    private final AnnotationTypes annotationTypes;

    NamedDispatchReader(String positionName, String missingTypeRefMessage, String candidateNoun,
                         Set<String> candidateNames, TsonTypeReaderResolver resolver,
                         Map<DiscriminationClass, String> untaggedRecovery, AnnotationTypes annotationTypes) {
        this.positionName = positionName;
        this.missingTypeRefMessage = missingTypeRefMessage;
        this.candidateNoun = candidateNoun;
        this.candidateNames = candidateNames;
        this.resolver = resolver;
        this.untaggedRecovery = untaggedRecovery;
        this.annotationTypes = annotationTypes;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        List<TsonAnnotation> annotations = AnnotationCapture.annotations(ctx, annotationTypes);
        Optional<String> typeRef = EventSkip.typeRef(ctx);
        if (typeRef.isPresent()) {
            String ref = typeRef.get();
            if (!candidateNames.contains(ref)) {
                ctx.report(Diagnostic.Code.UNKNOWN_TYPE_REF, "'" + ref + "' is not a " + candidateNoun + " of '"
                                + positionName + "' -- expected one of " + candidateNames,
                        "one of " + candidateNames, ref);
                EventSkip.coreValue(ctx);
                return null;
            }
            return reattach(resolver.resolve(ref).read(ctx), annotations);
        }
        if (!untaggedRecovery.isEmpty()) {
            return reattach(recoverUntagged(ctx), annotations);
        }
        ctx.report(Diagnostic.Code.UNKNOWN_TYPE_REF,
                "'" + positionName + "' " + missingTypeRefMessage + ": " + candidateNames,
                "one of " + candidateNames, "no type annotation");
        EventSkip.coreValue(ctx);
        return null;
    }

    /** Recovers an untagged value to the variant of its own §4 base-type class (see the class Javadoc). */
    private Object recoverUntagged(TsonReadContext ctx) {
        TsonEvent event = ctx.peek(); // not consumed -- the variant's own reader reads it
        if (event instanceof TokenEvent token) {
            DiscriminationClass valueClass = DiscriminationClass.ofValue(
                    ValueParser.INSTANCE.read(new TokenValue(token.text(), token.form())));
            String variant = untaggedRecovery.get(valueClass);
            if (variant != null) {
                return resolver.resolve(variant).read(ctx);
            }
        }
        ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                "'" + positionName + "' has no variant matching this untagged value -- expected a value of one "
                        + "of " + candidateNames + ", or an explicit type annotation",
                "one of " + candidateNames, String.valueOf(event));
        EventSkip.coreValue(ctx);
        return null;
    }

    /**
     * The annotations written on the dispatched value, re-attached to whatever the chosen reader built.
     * Dispatch has to consume them to reach the type-ref it dispatches on ({@code data-value = *annotation
     * [type-ref] core-value}), so the reader that actually builds the node never sees them; a non-node result
     * (object-binding mode) has nowhere to carry them and is returned untouched.
     */
    private static Object reattach(Object value, List<TsonAnnotation> annotations) {
        return value instanceof TsonValue node ? node.withAnnotations(annotations) : value;
    }
}
