package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.compiler.stream.EventSkip;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.TypeRef;

/**
 * A kind-typed position whose bound component is a {@link TypeRef}: {@code record_field.type}, typed {@code type_kind}
 * in the kernel, or a meta layer's own {@code request: kind<record>}. The value is a type's name, read by the kind's own
 * atom reader (the type-name grammar, and the members where the document is data) into a {@code TypeRef} whose
 * {@code name} is the token, and recorded with the kind ({@link TsonReadContext#heldByName}) so the linker judges it a
 * member of the kind where the body is a meta layer's own, and by resolution where it is the kernel's.
 *
 * <p><b>An application is still a value here while a kernel body resolves.</b> Resolved output holds a bare name at
 * every kind-typed slot, since an inline application lifts to a synthetic entry; but a sugar form's lift writes the
 * application into the slot it fills ({@code [kind<record>]} fills {@code element_type} with {@code kind<record>}) and
 * the resolver closes it afterwards. So in a kernel body a braced value is read as the {@code type_ref} record it is,
 * by that record's own reader, and the closer mints its entry; the name that replaces it is judged then. In a value
 * body ({@link TsonReadContext#readsValueBody()}) nothing lifts or mints, so a braced value is refused and the alias
 * spelling named (SPEC-FEEDBACK.md #24).
 */
final class KindSlotReader implements TsonTypeReader<TypeRef> {

    private final AtomTypeReader<?> kind;
    private final TsonSchema.SlotType type;
    private final TsonTypeReaderResolver resolver;

    KindSlotReader(AtomTypeReader<?> kind, TsonTypeReaderResolver resolver) {
        this.kind = kind;
        this.type = kind.slotType();
        this.resolver = resolver;
    }

    @Override
    public TypeRef read(TsonReadContext ctx) {
        if (ReferenceNameReader.nameIn(ctx.peek()).isPresent()) {
            Object name = kind.read(ctx);
            if (name == null) {
                return null; // refused by the kind, and reported there
            }
            TypeRef reference = TypeRef.of(name.toString());
            ctx.heldByName(reference, type, null);
            return reference;
        }
        if (ctx.readsValueBody()) {
            // A value body is never lifted or minted, so an application here would have no entry to be the name
            // of: the alias spelling gives it one, and the bare name then names it.
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected a type's name, found " + TypeRefCheck.describe(ctx.peek())
                    + " -- a position typed by a kind holds the name of a type, one bare token; to name an "
                    + "application here, declare an alias for it (order_box => box<order>) and write its name",
                    "a type's name", TypeRefCheck.describe(ctx.peek()));
            EventSkip.annotationsAndTypeRef(ctx);
            EventSkip.coreValue(ctx);
            return null;
        }
        // Resolved on the read and not when the reader is built: the kernel's own readers are built in one pass.
        return (TypeRef) resolver.resolve("type_ref").read(ctx);
    }
}
