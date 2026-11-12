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
 * A position typed by a reference that names types, whose bound component is a {@link TypeRef}: a type slot's name,
 * typed {@code meta.type_ref} in the kernel, or a meta layer's own {@code request: meta.ref<record>}. The value is a
 * reference: a type's name, one token, or an application, the data grammar's reference with an argument list
 * ([TSON-DATA] §7.4), which the stream delivers as one value. A name is read by the reference's own
 * atom reader (the grammar, and the members where the document is data) into a {@code TypeRef}; in a schema document
 * that reader reads the grammar alone and records the name with the reference ({@link TsonReadContext#heldByName}), so
 * the linker judges it a member. An annotation written on the reference ({@code type: @doc:"x" text}) is kept
 * on the {@code TypeRef}.
 *
 * <p><b>An application is a value here while a kernel body resolves</b>: resolved output holds one where an
 * application is still open (an open record's parents and field types), and a sugar form's construction writes one
 * into the slot it fills ({@code [meta.ref<record>]} fills {@code element_type} with {@code meta.ref<record>}), which
 * the resolver closes afterwards. In a value body ({@link TsonReadContext#readsValueBody()}) nothing is closed, so an
 * application is refused and the declaration to write named ([TSON-SCHEMA] §5.6). In a data document a reference is
 * a name ([TSON-SCHEMA] §4.2): the kind's reader refuses an application, whether or not the schema closes it. A quoted
 * token is never a reference.
 */
final class TypeNameSlotReader implements TsonTypeReader<TypeRef> {

    private final AtomTypeReader<?> kind;
    private final AnnotationTypes annotationTypes;

    TypeNameSlotReader(AtomTypeReader<?> kind, AnnotationTypes annotationTypes) {
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
        if (ctx.peek() instanceof ReferenceEvent) {
            // A reference is a name, in a schema as in data: the kind's reader refuses the application, saying how
            // one is written, as the encodings share that rule.
            return withAnnotations(kind.read(ctx), annotations);
        }
        String found = TypeRefCheck.describe(ctx.peek());
        ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected a type's name, found " + found
                + " -- a position typed by a kind holds the name of a type, one bare token", "a type's name", found);
        EventSkip.annotationsAndTypeRef(ctx);
        EventSkip.coreValue(ctx);
        return null;
    }

    /** What the kind's reader read, as the reference it is, carrying the annotations written on it. */
    private static TypeRef withAnnotations(Object read, Annotations annotations) {
        return switch (read) {
            case null -> null; // a refusal is reported by the kind
            case TypeRef reference -> reference.withAnnotations(annotations);
            default -> TypeRef.of(read.toString(), annotations, TypeRef.Closure.NONE);
        };
    }
}
