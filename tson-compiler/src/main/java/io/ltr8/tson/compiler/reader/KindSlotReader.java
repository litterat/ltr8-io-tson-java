package io.ltr8.tson.compiler.reader;

import io.ltr8.annotation.Annotations;
import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomRefusal;
import io.ltr8.tson.atom.ReferenceGrammar;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.stream.AnnotationStart;
import io.ltr8.tson.compiler.stream.EventSkip;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

/**
 * A kind-typed position whose bound component is a {@link TypeRef}: {@code record_field.type}, typed {@code type_kind}
 * in the kernel, or a meta layer's own {@code request: kind<record>}. The value is a reference: a type's name, read by
 * the kind's own atom reader (the type-name grammar, and the members where the document is data) into a {@code
 * TypeRef} whose {@code name} is the token. In a schema document the kind's reader reads the grammar alone and records
 * the name with the kind ({@link TsonReadContext#heldByName}), so the linker judges it a member of the kind. An
 * annotation written on the reference ({@code type: @doc:"x" text}) is kept on the {@code TypeRef}.
 *
 * <p><b>An application is a reference too</b>, written as one quoted token since {@code <} is reserved in data
 * ({@code "result<T>"}, SPEC-FEEDBACK.md #12), and read by {@link ReferenceGrammar}. It is a value here while a kernel
 * body resolves: resolved output holds one where an application is still open (a record template's parents and field
 * types), and a sugar form's lift writes one into the slot it fills ({@code [kind<record>]} fills {@code
 * element_type} with {@code "kind<record>"}), which the resolver closes afterwards and judges the name that replaces
 * it. In a value body ({@link TsonReadContext#readsValueBody()}) nothing lifts or mints, so an application is refused
 * and the alias spelling named ([TSON-SCHEMA] §5.6). In a data document the kind's reader judges an application
 * through the entry the closure records it as denoting. A quoted token that is no reference is refused with the
 * grammar's reason, and one that is no application is text, never a name.
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
            Object name = kind.read(ctx);
            return name == null ? null : new TypeRef(name.toString(), List.of(), annotations);
        }
        if (ctx.peek() instanceof TokenEvent token && token.form() != TokenForm.UNQUOTED
                && token.text().indexOf('<') >= 0) {
            if (!ctx.readsSchemaNames()) {
                // A data document: the kind's reader judges the application through the entry it denotes.
                Object read = kind.read(ctx);
                return read == null ? null : ReferenceGrammar.parse(read.toString()).withAnnotations(annotations);
            }
            TypeRef application;
            try {
                application = ReferenceGrammar.parse(token.text());
            } catch (AtomParseException e) {
                AtomRefusal refusal = AtomRefusal.of(e, token.text(), TypeRef.class);
                ctx.report(refusal.code(), refusal.message(), refusal.expected(), refusal.actual());
                ctx.next();
                return null;
            }
            if (!ctx.readsValueBody()) {
                ctx.next();
                return application.withAnnotations(annotations);
            }
        }
        // A value body is never lifted or minted, so an application here would have no entry to be the name of: the
        // alias spelling gives it one, and the bare name then names it.
        String found = TypeRefCheck.describe(ctx.peek());
        ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected a type's name, found " + found
                + " -- a position typed by a kind holds the name of a type, one bare token"
                + (ctx.readsValueBody() ? "; to name an application here, declare an alias for it (order_box => "
                        + "box<order>) and write its name" : ", or an application written quoted (\"box<order>\")"),
                "a type's name", found);
        EventSkip.annotationsAndTypeRef(ctx);
        EventSkip.coreValue(ctx);
        return null;
    }
}
