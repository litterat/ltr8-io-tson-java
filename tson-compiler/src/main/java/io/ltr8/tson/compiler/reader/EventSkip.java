package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.stream.AbsentEvent;
import io.ltr8.tson.compiler.stream.AnnotationEnd;
import io.ltr8.tson.compiler.stream.AnnotationStart;
import io.ltr8.tson.compiler.stream.ArrayEnd;
import io.ltr8.tson.compiler.stream.ArrayStart;
import io.ltr8.tson.compiler.stream.EmptyBraceEvent;
import io.ltr8.tson.compiler.stream.MapEnd;
import io.ltr8.tson.compiler.stream.MapStart;
import io.ltr8.tson.compiler.stream.RecordEnd;
import io.ltr8.tson.compiler.stream.RecordStart;
import io.ltr8.tson.compiler.stream.SchemaRef;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TypeRef;

import java.util.Optional;

/**
 * Shared event-stream grammar helpers every {@code *Reader} needs, beyond what each family's own
 * shape-specific decoding does: consuming a {@code data-value}'s leading {@code annotation* type-ref?}
 * framing (§2.3-§2.4) before a reader ever inspects its own core-value's shape, and discarding a
 * whole {@code data-value}'s worth of events outright when a reader has nothing to do with one --
 * either because it turned out to be the wrong shape entirely (a collecting-mode reader must still
 * fully consume it to keep the stream correctly positioned for whatever follows) or because a
 * record's own field name has no match in the compiled schema at all.
 *
 * <p>Structurally mirrors {@code TsonDataParser.EventReducer}'s own reduction shape exactly (the
 * same grammar), just discarding instead of building where {@link #dataValue}/{@link #coreValue}/
 * {@link #scopedValue} are used for that purpose.
 */
public final class EventSkip {

    private EventSkip() {
    }

    /**
     * Consumes every leading annotation (discarded) and an optional type-ref, returning the
     * type-ref's own name if present. Leaves the cursor positioned at the value's own core-value,
     * whatever it turns out to be. Every reader calls this first, before making its own shape
     * decision; only dispatch readers (record subtype/union/choice) consult the returned name,
     * everything else ignores it, matching how a plain {@code DataValue.typeRef()} used to be
     * ignored by every non-dispatch reader.
     *
     * <p>Discarding is the default because most readers have nowhere to put an annotation. A reader
     * that does -- {@code SchemalessTreeReader}, whose nodes each carry their own {@code
     * annotations()} -- captures them itself and then calls {@link #typeRef} for the rest of the
     * framing, rather than this method.
     */
    public static Optional<String> annotationsAndTypeRef(TsonReadContext ctx) {
        annotations(ctx);
        return typeRef(ctx);
    }

    /**
     * Consumes and discards every leading annotation, stopping at the type-ref or core-value that follows --
     * the first half of {@link #annotationsAndTypeRef}, split out so a reader that <em>captures</em>
     * annotations ({@code AnnotationCapture}) and one that drops them still agree on where the run ends.
     */
    public static void annotations(TsonReadContext ctx) {
        while (ctx.peek() instanceof AnnotationStart) {
            ctx.next();
            if (!(ctx.peek() instanceof AnnotationEnd)) {
                dataValue(ctx); // the annotation's own value -- discarded along with the annotation
            }
            ctx.next(); // AnnotationEnd
        }
    }

    /**
     * The event a data-value's {@code core-value} starts at -- or the {@code type-ref} before it -- found by
     * looking <em>past</em> the leading annotations and putting them back. Consumes nothing.
     *
     * <p><b>For a reader that has to decide something before the value is read.</b> {@code data-value =
     * *annotation [type-ref] core-value}, so anything keyed on the type-ref (a dispatcher choosing a
     * variant, a facade choosing the root type) sits behind a run that can be any length, and a single
     * {@link TsonReadContext#peek()} finds the first annotation and concludes there is none. Consuming the
     * run to get past it is not a substitute: the annotations belong to the value, and the reader that ends
     * up building it would never see them -- a silent loss, not a failure. See {@link
     * TsonReadContext#lookingAhead}.
     */
    public static TsonEvent aheadOfValue(TsonReadContext ctx) {
        return TsonReadContext.lookingAhead(ctx, lookahead -> {
            annotations(lookahead);
            return lookahead.peek();
        });
    }

    /** The name of the type-ref {@link #aheadOfValue} finds, if it found one. Consumes nothing. */
    public static Optional<String> typeRefAhead(TsonReadContext ctx) {
        return aheadOfValue(ctx) instanceof TypeRef typeRef ? Optional.of(typeRef.name()) : Optional.empty();
    }

    /**
     * Consumes an optional type-ref, returning its own name if present -- the second half of a
     * data-value's {@code annotation* type-ref?} framing, split out so a reader that captures the
     * leading annotations instead of discarding them still shares this half.
     */
    public static Optional<String> typeRef(TsonReadContext ctx) {
        if (ctx.peek() instanceof TypeRef tr) {
            ctx.next();
            return Optional.of(tr.name());
        }
        return Optional.empty();
    }

    /** Discards one full {@code data-value}: leading annotations/type-ref (see {@link #annotationsAndTypeRef}), then one core-value. */
    public static void dataValue(TsonReadContext ctx) {
        annotationsAndTypeRef(ctx);
        coreValue(ctx);
    }

    /** Discards {@code [ schema-directive ] data-value} -- record field values, map entry values, array elements. */
    public static void scopedValue(TsonReadContext ctx) {
        if (ctx.peek() instanceof SchemaRef) {
            ctx.next();
        }
        dataValue(ctx);
    }

    /**
     * Discards one core-value, whose own first event has *not* yet been consumed (only peeked) by
     * the caller -- the natural shape for a reader that peeked to decide "this isn't what I expected"
     * and now needs to fully discard whatever's actually there, including a nested container.
     */
    public static void coreValue(TsonReadContext ctx) {
        TsonEvent e = ctx.next();
        switch (e) {
            case RecordStart ignored -> {
                while (!(ctx.peek() instanceof RecordEnd)) {
                    ctx.next(); // FieldName
                    scopedValue(ctx);
                }
                ctx.next(); // RecordEnd
            }
            case MapStart ignored -> {
                while (!(ctx.peek() instanceof MapEnd)) {
                    dataValue(ctx); // key
                    ctx.next(); // MapArrow
                    scopedValue(ctx);
                }
                ctx.next(); // MapEnd
            }
            case ArrayStart ignored -> {
                while (!(ctx.peek() instanceof ArrayEnd)) {
                    scopedValue(ctx);
                }
                ctx.next(); // ArrayEnd
            }
            case TokenEvent ignored -> {
                // leaf, already consumed
            }
            case AbsentEvent ignored -> {
                // leaf, already consumed
            }
            case EmptyBraceEvent ignored -> {
                // leaf, already consumed
            }
            default -> throw new IllegalStateException("unexpected event while skipping a core-value: " + e);
        }
    }
}
