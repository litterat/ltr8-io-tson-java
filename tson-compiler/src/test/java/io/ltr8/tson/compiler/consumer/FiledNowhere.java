package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

/**
 * A meta-layer body that declares none of its references. {@code ValuesByNameTest} shows the by-name one judged
 * anyway: the resolver's read records it, so nothing the class omits goes unchecked.
 */
@Typename(name = "filed_nowhere")
public record FiledNowhere(TypeRef request) implements Data {
}
