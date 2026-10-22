package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.schema.meta.TypeRef;

/**
 * A kind-typed position whose bound component is a {@link TypeRef}: {@code record_field.type}, typed {@code type_kind}
 * in the kernel, or a meta layer's own {@code request: kind<record>}. The value is a type's name, read by the kind's own
 * atom reader (the type-name grammar, and the members where the document is data) into a {@code TypeRef} whose
 * {@code name} is the token, and recorded ({@link TsonReadContext#heldByName}) so the linker judges it a member of the
 * kind where the body is a meta layer's own, and by resolution where it is the kernel's.
 *
 * <p><b>An application is still a value here while the schema resolves.</b> Resolved output holds a bare name at every
 * kind-typed slot, since an inline application lifts to a synthetic entry; but a sugar form's lift writes the
 * application into the slot it fills ({@code [kind<record>]} fills {@code element_type} with {@code kind<record>}) and
 * the resolver closes it afterwards. So a braced value is read as the {@code type_ref} record it is, by that record's
 * own reader, and the closer mints its entry; the name that replaces it is judged then.
 */
final class KindSlotReader implements TsonTypeReader<TypeRef> {

    private final AtomTypeReader<?> kind;
    private final TsonTypeReaderResolver resolver;

    KindSlotReader(AtomTypeReader<?> kind, TsonTypeReaderResolver resolver) {
        this.kind = kind;
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
            ctx.heldByName(reference, null);
            return reference;
        }
        // Resolved on the read and not when the reader is built: the kernel's own readers are built in one pass.
        return (TypeRef) resolver.resolve("type_ref").read(ctx);
    }
}
