package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.QualifiedName;

/**
 * A meta-layer body that declares a {@link QualifiedName} at a {@code type_ref}-typed field -- refused at compile,
 * since a bare token there fills {@code type_ref}'s own {@code name} positionally and is a type reference.
 */
@Typename(name = "name_at_type_ref")
public record NameAtTypeRef(QualifiedName a) implements Data {
}
