package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.stream.AbsentEvent;
import io.ltr8.tson.compiler.stream.AnnotationEnd;
import io.ltr8.tson.compiler.stream.AnnotationStart;
import io.ltr8.tson.compiler.stream.ListEventSource;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.tree.TsonAnnotation;
import io.ltr8.tson.tree.TsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Captures a data-value's own leading wire annotations ([TSON-DATA] §3.1) as {@link TsonAnnotation}s, and --
 * when a governing schema is in scope -- resolves and checks each one against the type it names
 * ([TSON-SCHEMA] §6). The capturing counterpart to {@link EventSkip#annotationsAndTypeRef}, which discards.
 * Used by every tree-producing reader; a reader with nowhere to put an annotation keeps discarding.
 *
 * <p>Consumes only the {@code *annotation} half of a value's {@code annotation* type-ref?} framing, so a
 * caller follows this with {@link EventSkip#typeRef} (or lets whatever it delegates to consume the type-ref
 * itself -- see the "hoisting" note below).
 *
 * <p><b>An annotation's value is read by the reader for the type the annotation names</b>, straight off the
 * live cursor -- the value sits exactly where the annotation's {@code AnnotationStart} left it, and the
 * grammar guarantees the matching {@code AnnotationEnd} follows the one data-value that value consists of.
 * That is what makes {@code @doc:42} a diagnostic against a text-targeted {@code doc}: the check falls out of
 * using the right reader rather than needing a separate validation pass. With no schema in scope ({@link
 * AnnotationTypes#UNVALIDATED}) the value is read structurally instead, which is [TSON-DATA] §3.1's Class 1
 * treatment -- "preserved, ordered metadata with no further interpretation".
 *
 * <p><b>An unresolvable name is reported but the annotation is still kept.</b> [TSON-DATA] §1.5 requires a
 * processor to preserve annotations it does not act on, so a name the governing schema doesn't declare
 * produces {@code UNKNOWN_TYPE_REF} and then falls back to a structural read of the value. Dropping it would
 * trade one conformance rule for another.
 *
 * <p><b>Hoisting.</b> A tree reader that wraps or extends a reader which itself consumes the framing calls
 * this <em>first</em>, then delegates: every such reader discards the framing result rather than using it, so
 * the delegate's own call finds nothing left and is a no-op. That is what lets annotations reach the node
 * without widening any shared reader's signature -- the alternative, threading them out of a base class the
 * bind subclasses also use, would make every mode pay for a field only tree mode reads.
 */
final class AnnotationCapture {

    /** Reads an annotation's value when no schema is in scope, or when its name resolves to nothing. */
    private static final SchemalessTreeReader STRUCTURAL = new SchemalessTreeReader();

    private AnnotationCapture() {
    }

    /**
     * Consumes this value's own annotations, in source order with repeats preserved (§3.1: a name MAY appear
     * any number of times and every occurrence survives). Returns an empty list, allocating nothing, for the
     * overwhelmingly common unannotated case.
     */
    static List<TsonAnnotation> annotations(TsonReadContext ctx, AnnotationTypes types) {
        if (!(ctx.peek() instanceof AnnotationStart)) {
            return List.of();
        }
        List<TsonAnnotation> annotations = new ArrayList<>();
        while (ctx.peek() instanceof AnnotationStart start) {
            ctx.next();
            annotations.add(new TsonAnnotation(start.name(), value(ctx, start, types)));
        }
        return annotations;
    }

    /**
     * The value of the annotation whose {@code AnnotationStart} was just consumed, and the checking of it.
     * Empty for the valueless form ({@code @name}), where §6 makes bare {@code @T} shorthand for {@code @T:_}
     * -- so the type still has to admit the absent sentinel, which {@link #checkBareAdmitted} verifies rather
     * than assuming.
     */
    private static Optional<TsonNode> value(TsonReadContext ctx, AnnotationStart start, AnnotationTypes types) {
        Optional<TsonValueReader<?>> reader = types.readerFor(start.name());
        if (types.validating() && reader.isEmpty()) {
            ctx.report(Diagnostic.Code.UNKNOWN_TYPE_REF,
                    "annotation '@" + start.name() + "' names no type the governing schema declares (§6)",
                    "an annotation type in the governing schema's namespace", "@" + start.name());
        }
        if (ctx.peek() instanceof AnnotationEnd) {
            ctx.next();
            reader.ifPresent(r -> checkBareAdmitted(ctx, start, r));
            return Optional.empty();
        }
        TsonNode value = reader.isPresent() ? asNode(reader.get().read(ctx)) : STRUCTURAL.read(ctx);
        TsonEvent end = ctx.next();
        if (!(end instanceof AnnotationEnd)) {
            throw new IllegalStateException("expected the end of annotation '@" + start.name() + "', found " + end);
        }
        return Optional.ofNullable(value);
    }

    /**
     * §6's bare form, checked rather than assumed: {@code @T} is shorthand for {@code @T:_}, so {@code T} must
     * admit the absent sentinel -- true of the {@code void}-targeted markers the form exists for ({@code
     * @disjoint}, {@code @numeric}) and false of, say, a text-targeted {@code @doc}.
     *
     * <p>There is no value in the stream to hand the reader, so one absent event is synthesised at the
     * annotation's own position and read through a throwaway collecting context. Only whether it complained
     * is used -- its diagnostics carry that context's paths, not this read's, so a single reported problem
     * here is more useful than forwarding several with misleading locations.
     */
    private static void checkBareAdmitted(TsonReadContext ctx, AnnotationStart start, TsonValueReader<?> reader) {
        TsonReadContext probe =
                TsonReadContext.collecting(new ListEventSource(List.of(new AbsentEvent(start.position()))));
        boolean admitted;
        try {
            reader.read(probe);
            admitted = probe.diagnostics().isEmpty();
        } catch (RuntimeException e) {
            admitted = false;
        }
        if (!admitted) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                    "annotation '@" + start.name() + "' is written bare, which §6 treats as '@" + start.name()
                            + ":_', but '" + start.name() + "' does not admit the absent sentinel",
                    "a value of '" + start.name() + "'", "@" + start.name() + " (no value)");
        }
    }

    /** A compiled tree reader yields a node; a soft failure in collecting mode yields {@code null}, already reported. */
    private static TsonNode asNode(Object read) {
        return read instanceof TsonNode node ? node : null;
    }
}
