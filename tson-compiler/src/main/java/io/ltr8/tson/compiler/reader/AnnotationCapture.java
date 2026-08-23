package io.ltr8.tson.compiler.reader;

import io.ltr8.annotation.Annotation;
import io.ltr8.annotation.Annotations;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.stream.AbsentEvent;
import io.ltr8.tson.compiler.stream.AnnotationEnd;
import io.ltr8.tson.compiler.stream.AnnotationStart;
import io.ltr8.tson.compiler.stream.ListEventSource;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.tree.TsonAnnotation;
import io.ltr8.tson.tree.TsonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Captures a data-value's own leading wire annotations ([TSON-DATA] §3.1) as {@link TsonAnnotation}s, and --
 * when a governing schema is in scope -- resolves and checks each one against the type it names
 * ([TSON-SCHEMA] §6). The capturing counterpart to {@link EventSkip#annotationsAndTypeRef}, which discards.
 * Used by every tree-producing reader; a reader with nowhere to put an annotation still checks what it
 * drops ({@link #discard}).
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

    /**
     * Reads an annotation's value when its name resolves to nothing under a governing schema. Preserving,
     * deliberately: §1.5 already keeps an annotation this read cannot interpret, so rejecting the type-refs
     * inside its value would take back with one hand what that rule gives with the other. A read that is
     * <em>itself</em> schemaless passes its own reader instead (see {@link #annotations(TsonReadContext,
     * AnnotationTypes, SchemalessTreeReader)}), so an annotation value is checked exactly as strictly as the
     * value it annotates.
     */
    private static final SchemalessTreeReader STRUCTURAL = SchemalessTreeReader.preserving();

    private AnnotationCapture() {
    }

    /** {@link #annotations(TsonReadContext, AnnotationTypes, SchemalessTreeReader)} with the preserving structural fallback -- for a schema-driven reader, which has no schemaless reader of its own. */
    static List<TsonAnnotation> annotations(TsonReadContext ctx, AnnotationTypes types) {
        return annotations(ctx, types, STRUCTURAL);
    }

    /**
     * Consumes this value's own annotations as tree nodes, in source order with repeats preserved (§3.1: a
     * name MAY appear any number of times and every occurrence survives). {@code structural} reads the value
     * of an annotation nothing resolves.
     */
    static List<TsonAnnotation> annotations(TsonReadContext ctx, AnnotationTypes types,
                                            SchemalessTreeReader structural) {
        if (!capturing(ctx, types)) {
            return List.of();
        }
        List<TsonAnnotation> annotations = new ArrayList<>();
        while (ctx.peek() instanceof AnnotationStart start) {
            ctx.next();
            // A tree read's readers are tree readers, so a resolved annotation value is already a node; the
            // structural fallback yields one too. Anything else is a soft failure already reported.
            annotations.add(new TsonAnnotation(start.name(),
                    value(ctx, start, types, structural).filter(TsonValue.class::isInstance).map(TsonValue.class::cast)));
        }
        return annotations;
    }

    /**
     * The same capture, as the binding-layer carrier a record's {@code Annotations} component receives. The
     * only difference from {@link #annotations} is the value's Java form: object-binding readers bind an
     * annotation's value to the type §6 says it names, so it arrives as that type's own Java object rather
     * than a node. A name the governing schema does not declare still falls back to a structural read, so an
     * annotation nothing can interpret is preserved rather than dropped ([TSON-DATA] §1.5) -- which is
     * exactly why {@code Annotations} leaves the value as {@code Object}.
     */
    static Annotations bound(TsonReadContext ctx, AnnotationTypes types) {
        if (!capturing(ctx, types)) {
            return Annotations.empty();
        }
        Annotations.Builder annotations = new Annotations.Builder();
        while (ctx.peek() instanceof AnnotationStart start) {
            ctx.next();
            annotations.add(new Annotation(start.name(), value(ctx, start, types, STRUCTURAL)));
        }
        return annotations.build();
    }

    /**
     * Whether there is anything to capture and this mode wants it -- {@link #discard}ing when it does not,
     * so the cursor is correctly positioned either way. Returns false, allocating nothing, for the
     * overwhelmingly common unannotated case.
     */
    private static boolean capturing(TsonReadContext ctx, AnnotationTypes types) {
        if (!(ctx.peek() instanceof AnnotationStart)) {
            return false;
        }
        if (!types.capture()) {
            discard(ctx, types);
            return false;
        }
        return true;
    }

    /**
     * Consumes a run of annotations this position has nowhere to keep -- <b>checking each one under a
     * governing schema</b>, exactly as the capturing path does, and keeping nothing.
     *
     * <p><b>Checked even though it is dropped</b>, because the two are different questions. [TSON-SCHEMA] §6
     * makes {@code @T} name a type whose contract its value must satisfy; whether the reader has somewhere
     * to put the result is a fact about the bound Java class, and a document does not conform any better for
     * being read by a class that discards its annotations. Skipping the run outright made a document's
     * validity depend on the shape of a class it has never heard of -- the same annotation reported by a
     * class with an {@code Annotations} component and ignored by one without.
     *
     * <p>With no schema in scope there is nothing to check against and this is a plain skip, which is
     * {@link AnnotationTypes#DISCARDED}'s whole case.
     */
    static void discard(TsonReadContext ctx, AnnotationTypes types) {
        if (!types.validating()) {
            EventSkip.annotations(ctx);
            return;
        }
        while (ctx.peek() instanceof AnnotationStart start) {
            ctx.next();
            // Reads the value through the reader for the type the annotation names, reporting what does not
            // fit and consuming the AnnotationEnd either way. The value itself has nowhere to go.
            value(ctx, start, types, STRUCTURAL);
        }
    }

    /**
     * The value of the annotation whose {@code AnnotationStart} was just consumed, and the checking of it.
     * Left as {@code Object} because its Java form is whatever this read's own readers produce -- a node in
     * tree mode, a bound object in binding mode -- with the structural fallback yielding a node either way.
     * A soft failure in collecting mode yields {@code null}, already reported, and reads as absent here.
     * Empty for the valueless form ({@code @name}), where §6 makes bare {@code @T} shorthand for {@code @T:_}
     * -- so the type still has to admit the absent sentinel, which {@link #checkBareAdmitted} verifies rather
     * than assuming.
     */
    private static Optional<Object> value(TsonReadContext ctx, AnnotationStart start, AnnotationTypes types,
                                          SchemalessTreeReader structural) {
        Optional<TsonTypeReader<?>> reader = types.readerFor(start.name());
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
        Object value = reader.isPresent() ? reader.get().read(ctx) : structural.read(ctx);
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
     * annotation's own position and read through a throwaway context whose receiver discards what it is
     * given. Only whether the reader complained is used -- those diagnostics carry the probe's own paths,
     * not this read's, so a single reported problem here is more useful than forwarding several with
     * misleading locations.
     */
    private static void checkBareAdmitted(TsonReadContext ctx, AnnotationStart start, TsonTypeReader<?> reader) {
        TsonReadContext probe = TsonReadContext.of(
                new ListEventSource(List.of(new AbsentEvent(start.position()))), diagnostic -> { });
        boolean admitted;
        try {
            reader.read(probe);
            admitted = probe.reported() == 0;
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

}
