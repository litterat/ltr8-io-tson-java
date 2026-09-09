package io.ltr8.tson.compiler.reader;

import io.ltr8.bind.DataClass;
import io.ltr8.tson.compiler.TsonTypeReader;

/**
 * Turns a reader into one that hands back its target's <em>object</em> form, applying that
 * {@link DataClass}'s own bridge where it has one.
 *
 * <p><b>Every schema-driven position needs this, not only a collection's elements.</b> {@code tson-bind}
 * applies a bridge as it collects a record's constructor arguments, and a schema-driven read is precisely
 * the path that does not go through it: {@link RecordBindReader} fills its own argument array from the
 * compiled field readers, and a collection appends its elements one at a time through the collection's own
 * access bridge, which converts nothing. Both would otherwise hand back the wire value.
 *
 * <p>The two failures differ only in when they are noticed. An enum element reaches a {@code
 * List<SomeEnum>} as the {@code String} the enum reader produced -- that compiles (erasure) and reads back
 * wrong, the list heap-polluted, with the <em>writer</em> the first thing to complain. A record field fails
 * at once, in the constructor's own cast, naming a class the document never mentioned -- which is what a
 * consumer's bridged or {@code @Transparent} atom meets at every schema-governed read without this.
 *
 * <p>Returns {@code reader} unchanged when the target carries no bridge, which is the common case, so a
 * caller can wrap unconditionally -- the same shape as {@link AnnotationBoxing#wrap}, and applied at the
 * same place for the same reason.
 */
final class ElementBridging {

    private ElementBridging() {
    }

    static TsonTypeReader<?> wrap(TsonTypeReader<?> reader, DataClass target) {
        if (target == null || target.bridge().isEmpty()) {
            return reader;
        }
        var bridge = target.bridge().get();
        return ctx -> {
            Object read = reader.read(ctx);
            if (read == null) {
                return null;
            }
            try {
                return bridge.toObject().invoke(read);
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException("failed to bridge a value to " + target.typeClass(), t);
            }
        };
    }
}
