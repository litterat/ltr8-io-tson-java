package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.compiler.stream.ArrayStart;
import io.ltr8.tson.compiler.stream.EmptyBraceEvent;
import io.ltr8.tson.compiler.stream.EventSkip;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.ValueParser;
import io.ltr8.tson.compiler.stream.MapStart;
import io.ltr8.tson.compiler.stream.RecordStart;
import io.ltr8.tson.compiler.stream.ReferenceEvent;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.schema.meta.TypeExpr;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dispatch-by-{@code !typeName} over a fixed candidate-name set. Reads the value's own type-ref (via {@link
 * EventSkip#annotationsAndTypeRef}), requires it names one of {@code candidateNames}, and delegates to that
 * candidate's own reader, resolved lazily at dispatch time. A variant written in place has no name a tag could
 * give, so it is reached by untagged recovery alone.
 *
 * <p><b>Untagged recovery.</b> When {@code untaggedRecovery} is non-empty and the value carries no type-ref,
 * the value is instead recovered structurally: its own §4 base-type class selects the variant (§5.4) -- a scalar
 * token's resolved class, a reference token's string class, and an opening brace or bracket its delimiter's class.
 * The map is built (by {@link ChoiceReader}) only where that's sound -- a disjoint choice whose variants each have a
 * distinct class. When it's empty, an untagged value is the usual error (the tag is required).
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
    private final Map<DiscriminationClass, TypeExpr> untaggedRecovery;

    NamedDispatchReader(String positionName, String missingTypeRefMessage, String candidateNoun,
                         Set<String> candidateNames, TsonTypeReaderResolver resolver,
                         Map<DiscriminationClass, TypeExpr> untaggedRecovery) {
        this.positionName = positionName;
        this.missingTypeRefMessage = missingTypeRefMessage;
        this.candidateNoun = candidateNoun;
        this.candidateNames = candidateNames;
        this.resolver = resolver;
        this.untaggedRecovery = untaggedRecovery;
    }

    /** Whether an untagged value of {@code valueClass} is recovered to a variant rather than refused. */
    boolean recovers(DiscriminationClass valueClass) {
        return untaggedRecovery.containsKey(valueClass);
    }

    /** The variant an untagged value of {@code valueClass} is recovered to, or {@code null} where none is. */
    TypeExpr recoveredTo(DiscriminationClass valueClass) {
        return untaggedRecovery.get(valueClass);
    }

    /**
     * This dispatch with an untagged value of each class in {@code readers} read by that reader rather than by its
     * variant's own: how a bound slot reads a type slot's choice ({@link BindTargets}) -- a name through the reader a
     * {@code TypeRef} component gets, which keeps the annotations written on the name, and a type written in place
     * through one that keeps the constructor its body is tagged with.
     */
    Recovering recoveringWith(Map<DiscriminationClass, TsonTypeReader<?>> readers) {
        return new Recovering(this, Map.copyOf(readers));
    }

    /** {@link #recoveringWith}'s dispatch: an untagged value of a class it holds to its reader, else as is. */
    record Recovering(NamedDispatchReader dispatch, Map<DiscriminationClass, TsonTypeReader<?>> readers)
            implements TsonTypeReader<Object> {

        @Override
        public Object read(TsonReadContext ctx) {
            TsonEvent ahead = EventSkip.aheadOfValue(ctx);
            DiscriminationClass valueClass = ahead instanceof io.ltr8.tson.compiler.stream.TypeRef ? null
                    : classOf(ahead);
            TsonTypeReader<?> reader = valueClass == null ? null : readers.get(valueClass);
            return reader != null ? reader.read(ctx) : dispatch.read(ctx);
        }
    }

    @Override
    public Object read(TsonReadContext ctx) {
        Optional<String> typeRef = EventSkip.typeRefAhead(ctx);
        if (typeRef.isPresent()) {
            String ref = typeRef.get();
            if (!candidateNames.contains(ref)) {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH, "'" + ref + "' is not a " + candidateNoun + " of '"
                                + positionName + "' -- expected one of " + candidateNames,
                        "one of " + candidateNames, ref);
                EventSkip.dataValue(ctx); // framing included: nothing consumed it, this value being unreadable
                return null;
            }
            return resolver.resolve(ref).read(ctx);
        }
        if (!untaggedRecovery.isEmpty()) {
            return recoverUntagged(ctx);
        }
        ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                "'" + positionName + "' " + missingTypeRefMessage + ": " + candidateNames,
                "one of " + candidateNames, "no type annotation");
        EventSkip.dataValue(ctx); // framing included: nothing consumed it, this value being unreadable
        return null;
    }

    /**
     * The class of the untagged value an event opens: a scalar token's §4 base-type class, a reference token's
     * string class (one unquoted token, like a name, §5.4), a brace's or a bracket's; {@code null} for anything else.
     */
    private static DiscriminationClass classOf(TsonEvent event) {
        return switch (event) {
            case TokenEvent token -> DiscriminationClass.ofValue(
                    ValueParser.INSTANCE.read(new TokenValue(token.text(), token.form())));
            case ReferenceEvent ignored -> DiscriminationClass.STRING;
            case RecordStart ignored -> DiscriminationClass.BRACE;
            case MapStart ignored -> DiscriminationClass.BRACE;
            case EmptyBraceEvent ignored -> DiscriminationClass.BRACE;
            case ArrayStart ignored -> DiscriminationClass.BRACKET;
            case null, default -> null;
        };
    }

    /** Recovers an untagged value to the variant of its own §4 base-type class (see the class Javadoc). */
    private Object recoverUntagged(TsonReadContext ctx) {
        // Past the annotations, which are still unread: they belong to the value, and the variant's own
        // reader takes the whole data-value from the cursor as it stands.
        TsonEvent event = EventSkip.aheadOfValue(ctx);
        DiscriminationClass valueClass = classOf(event);
        TypeExpr variant = valueClass == null ? null : untaggedRecovery.get(valueClass);
        if (variant != null) {
            return resolver.resolve(variant).read(ctx);
        }
        ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                "'" + positionName + "' has no variant matching this untagged value -- expected a value of one "
                        + "of " + candidateNames + ", or an explicit type annotation",
                "one of " + candidateNames, String.valueOf(event));
        EventSkip.dataValue(ctx); // framing included: nothing consumed it, this value being unreadable
        return null;
    }
}
