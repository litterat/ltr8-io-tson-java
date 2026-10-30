package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Optional;

/**
 * A kind- or reference-typed slot whose host component is neither a {@link TypeRef} nor a {@code Ref} -- a {@code
 * String}, say: the host receives what its bound atom reader produces, and in a schema document that reader records
 * the name read there with the slot's declared type ({@link TsonReadContext#heldByName}), so the linker judges it as it
 * judges the same name under a {@code TypeRef} host. Whether a name is judged is the slot's to say, never the host's.
 *
 * <p><b>The spelling is the slot's too.</b> What is no name -- a quoted token, a braced value, a framed name -- is
 * refused as the {@code TypeRef} host's reader refuses it ({@code refusing}): at a reference-typed slot in any schema
 * document, and at a kind-typed slot in a value body, where nothing lifts or mints. A data document's read is the
 * bound reader's alone.
 */
final class RecordedNameReader implements TsonTypeReader<Object> {

    private final TsonTypeReader<?> inner;
    private final TsonSchema.SlotType type;
    private final TsonTypeReader<?> refusing;

    /**
     * @param inner    the slot's reader bound to the host
     * @param type     the slot's declared type, each name read here recorded with it
     * @param refusing the reader a {@code TypeRef} host would have, which reports what is no name
     */
    RecordedNameReader(TsonTypeReader<?> inner, TsonSchema.SlotType type, TsonTypeReader<?> refusing) {
        this.inner = inner;
        this.type = type;
        this.refusing = refusing;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        if (!ctx.readsSchemaDocument()) {
            return inner.read(ctx);
        }
        Optional<String> name = ReferenceNameReader.nameIn(ctx.peek());
        if (name.isEmpty()) {
            boolean refused = type.role() == TsonSchema.SlotType.Role.REFERENCE || ctx.readsValueBody();
            return refused ? refusing.read(ctx) : inner.read(ctx);
        }
        return inner.read(ctx);
    }
}
