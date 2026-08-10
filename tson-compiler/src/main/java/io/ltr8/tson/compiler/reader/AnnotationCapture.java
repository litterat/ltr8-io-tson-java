package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
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
 * Captures a data-value's own leading wire annotations ([TSON-DATA] §3.1) as {@link TsonAnnotation}s -- the
 * capturing counterpart to {@link EventSkip#annotationsAndTypeRef}, which discards them. Used by every
 * tree-producing reader; a reader with nowhere to put an annotation keeps discarding.
 *
 * <p>Consumes only the {@code *annotation} half of a value's {@code annotation* type-ref?} framing, so a
 * caller follows this with {@link EventSkip#typeRef} (or lets whatever it delegates to consume the type-ref
 * itself -- see the "hoisting" note below).
 *
 * <p><b>Annotation values are read schemalessly, even during a schema-driven read.</b> An annotation names a
 * type resolved one hop against the governing target's namespace ([TSON-SCHEMA] §3.3.3), which is a different
 * namespace from the one the surrounding compiled readers were built against -- there is no compiled reader
 * for an arbitrary annotation type at an arbitrary position. Reading the value structurally is the honest
 * treatment and matches Part 1's own "preserved, ordered metadata with no further interpretation".
 *
 * <p><b>Hoisting.</b> A tree reader that wraps or extends a reader which itself consumes the framing calls
 * this <em>first</em>, then delegates: every such reader discards the framing result rather than using it, so
 * the delegate's own call finds nothing left and is a no-op. That is what lets annotations reach the node
 * without widening any shared reader's signature -- the alternative, threading them out of a base class the
 * bind subclasses also use, would make every mode pay for a field only tree mode reads.
 */
final class AnnotationCapture {

    /** Stateless, so one shared instance serves every annotation value read anywhere. */
    private static final SchemalessTreeReader VALUES = new SchemalessTreeReader();

    private AnnotationCapture() {
    }

    /**
     * Consumes this value's own annotations, in source order with repeats preserved (§3.1: a name MAY appear
     * any number of times and every occurrence survives). Returns an empty list, allocating nothing, for the
     * overwhelmingly common unannotated case.
     */
    static List<TsonAnnotation> annotations(TsonReadContext ctx) {
        if (!(ctx.peek() instanceof AnnotationStart)) {
            return List.of();
        }
        List<TsonAnnotation> annotations = new ArrayList<>();
        while (ctx.peek() instanceof AnnotationStart start) {
            ctx.next();
            annotations.add(new TsonAnnotation(start.name(), value(ctx)));
        }
        return annotations;
    }

    /**
     * The value of the annotation whose {@code AnnotationStart} was just consumed -- empty for the valueless
     * form ({@code @name}), where presence is the whole of the information.
     *
     * <p>An annotation's value is itself a full data-value that may carry annotations of its own ({@code
     * @a:@b:val target}), so rather than special-casing that recursion the value's events are buffered and
     * replayed through an ordinary tree read: the nested annotations then fall out of the normal path.
     * Annotations bracket properly in the stream, so the matching {@code AnnotationEnd} is the first one seen
     * at depth zero. Only a single annotation's events are ever buffered -- never a document body -- so this
     * does not defeat streaming.
     */
    private static Optional<TsonNode> value(TsonReadContext ctx) {
        if (ctx.peek() instanceof AnnotationEnd) {
            ctx.next();
            return Optional.empty();
        }
        List<TsonEvent> events = new ArrayList<>();
        int depth = 0;
        while (true) {
            TsonEvent e = ctx.next();
            if (e instanceof AnnotationEnd && depth == 0) {
                break;
            }
            if (e instanceof AnnotationStart) {
                depth++;
            } else if (e instanceof AnnotationEnd) {
                depth--;
            }
            events.add(e);
        }
        return Optional.of(VALUES.read(TsonReadContext.throwing(new ListEventSource(events))));
    }
}
