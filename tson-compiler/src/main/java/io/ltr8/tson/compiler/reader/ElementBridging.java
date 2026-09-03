package io.ltr8.tson.compiler.reader;

import io.ltr8.bind.DataClass;
import io.ltr8.tson.compiler.TsonTypeReader;

/**
 * Turns the reader for a collection's element into one that hands back the element's <em>object</em> form,
 * applying the element {@link DataClass}'s own bridge where it has one.
 *
 * <p>A record field needs no such wrapper: {@code tson-bind} collects a record's constructor arguments
 * through their bridges, so a scalar {@code TypeKind} field arrives as a {@code TypeKind}. A collection's
 * elements do not go through a constructor -- they are appended one at a time through the collection's own
 * access bridge, which converts nothing -- so without this an enum element reaches a {@code List<SomeEnum>}
 * as the {@code String} the enum reader produced. That compiles (erasure) and reads back wrong: the list is
 * heap-polluted, and the first thing to notice is the <em>writer</em>, whose bridge is typed for the enum
 * and fails with a {@code ClassCastException} naming a class the caller never mentioned.
 *
 * <p>Returns {@code element} unchanged when the element class carries no bridge, which is the common case,
 * so a caller can wrap unconditionally -- the same shape as {@link AnnotationBoxing#wrap}, and applied at
 * the same place for the same reason.
 */
final class ElementBridging {

    private ElementBridging() {
    }

    static TsonTypeReader<?> wrap(TsonTypeReader<?> element, DataClass target) {
        if (target == null || target.bridge().isEmpty()) {
            return element;
        }
        var bridge = target.bridge().get();
        return ctx -> {
            Object read = element.read(ctx);
            if (read == null) {
                return null;
            }
            try {
                return bridge.toObject().invoke(read);
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(
                        "failed to bridge a collection element to " + target.typeClass(), t);
            }
        };
    }
}
