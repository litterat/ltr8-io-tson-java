package io.ltr8.tson.compiler.reader;

import io.ltr8.annotation.Annotations;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.stream.AnnotationStart;
import io.ltr8.tson.compiler.stream.EventSkip;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

/**
 * A position typed by a reference that names types, whose bound component is a {@link TypeRef}: a type slot's name,
 * typed {@code meta.type_ref} in the kernel, or a meta layer's own {@code request: meta.ref<record>}. The value is a
 * type's name, one token ([TSON-SCHEMA] §4.2). It is read by the reference's own atom reader (the grammar, and the
 * members where the document is data) into a {@code TypeRef}; in a schema document that reader reads the grammar
 * alone and records the name with the reference ({@link TsonReadContext#heldByName}), so the linker judges it a
 * member. An annotation written on the reference ({@code type: @doc:"x" text}) is kept on the {@code TypeRef}.
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
