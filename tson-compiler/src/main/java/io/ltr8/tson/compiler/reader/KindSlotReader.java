package io.ltr8.tson.compiler.reader;

import io.ltr8.annotation.Annotations;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.stream.AnnotationStart;
import io.ltr8.tson.compiler.stream.EventSkip;
import io.ltr8.tson.compiler.stream.ReferenceEvent;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

/**
 * A kind-typed position whose bound component is a {@link TypeRef}: {@code record_field.type}, typed {@code type_kind}
 * in the kernel, or a meta layer's own {@code request: kind<record>}. The value is a reference: a type's name, one
 * token, or an application, the data grammar's reference with an argument list ([TSON-DATA] §7.4, SPEC-FEEDBACK.md
 * #12), which the stream delivers as one value. A name is read by the kind's own atom reader (the grammar, and the
 * members where the document is data) into a {@code TypeRef}; in a schema document that reader reads the grammar
 * alone and records the name with the kind ({@link TsonReadContext#heldByName}), so the linker judges it a member of
 * the kind. An annotation written on the reference ({@code type: @doc:"x" text}) is kept on the {@code TypeRef}.
 *
 * <p><b>An application is a value here while a kernel body resolves</b>: resolved output holds one where an
 * application is still open (a record template's parents and field types), and a sugar form's lift writes one into
 * the slot it fills ({@code [kind<record>]} fills {@code element_type} with {@code kind<record>}), which the resolver
 * closes afterwards and judges the name that replaces it. In a value body ({@link TsonReadContext#readsValueBody()})
 * nothing lifts or mints, so an application is refused and the alias spelling named ([TSON-SCHEMA] §5.6). In a data
 * document the kind's reader judges an application through the entry the closure closed it to, and refuses one
 * nothing in the closure closed. A quoted token is never a reference.
 */
final class KindSlotReader implements TsonTypeReader<TypeRef> {

    private final AtomTypeReader<?> kind;
    private final AnnotationTypes annotationTypes;

    KindSlotReader(AtomTypeReader<?> kind, AnnotationTypes annotationTypes) {
        this.kind = kind;
        this.annotationTypes = annotationTypes;
    }

    @Override
    public TypeRef read(TsonReadContext ctx) {
        Annotations annotations = ctx.peek() instanceof AnnotationStart
                ? AnnotationCapture.bound(ctx, annotationTypes) : Annotations.empty();
        if (ReferenceNameReader.nameIn(ctx.peek()).isPresent()) {
            // The kind's reader records the name in a schema document, as it does whatever the host holds.
            return withAnnotations(kind.read(ctx), annotations);
        }
        if (ctx.peek() instanceof ReferenceEvent reference) {
            if (!ctx.readsSchemaNames()) {
                // A data document: the kind's reader judges the application through the entry it denotes.
                return withAnnotations(kind.read(ctx), annotations);
            }
            if (!ctx.readsValueBody()) {
                ctx.next();
                return reference.reference().withAnnotations(annotations);
            }
        }
        // A value body is never lifted or minted, so an application here would have no entry to be the name of: the
        // alias spelling gives it one, and the bare name then names it.
        String found = TypeRefCheck.describe(ctx.peek());
        ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected a type's name, found " + found
                + " -- a position typed by a kind holds the name of a type, one bare token"
                + (ctx.readsValueBody() ? "; to name an application here, declare an alias for it (order_box => "
                        + "box<order>) and write its name" : ", or an application (box<order>)"),
                "a type's name", found);
        EventSkip.annotationsAndTypeRef(ctx);
        EventSkip.coreValue(ctx);
        return null;
    }

    /** What the kind's reader read, as the reference it is, carrying the annotations written on it. */
    private static TypeRef withAnnotations(Object read, Annotations annotations) {
        return switch (read) {
            case null -> null; // a refusal is reported by the kind
            case TypeRef reference -> reference.withAnnotations(annotations);
            default -> new TypeRef(read.toString(), List.of(), annotations);
        };
    }
}
