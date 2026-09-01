package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

/**
 * A consumer's meta-layer constructor that gets {@link Data#references()} wrong in the one way a careful
 * author does: it returns an OPTIONAL bound component directly.
 *
 * <p>The binder hands an omitted field to the constructor as {@code null} and does not normalise it, so a
 * document writing no {@code delivers} makes this return {@code null} — which the linker iterates. The
 * correct spelling is {@code delivers == null ? List.of() : delivers}, and what this class exists to pin is
 * that getting it wrong is reported against <em>this</em> class rather than escaping as a
 * {@code NullPointerException} that reads as a fault in the library.
 */
@Typename(name = "webhook")
public record Webhook(String path, List<TypeRef> delivers) implements Data {

    @Override
    public List<TypeRef> references() {
        return delivers;
    }
}
