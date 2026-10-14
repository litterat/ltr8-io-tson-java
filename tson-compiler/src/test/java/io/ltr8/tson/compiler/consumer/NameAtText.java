package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.QualifiedName;

/**
 * A meta-layer body that declares a {@link QualifiedName} at a {@code text}-typed field -- the binding mistake
 * {@code ValuesByNameTest} refuses at compile: at an atom-typed position a token is a literal, never a name.
 */
@Typename(name = "name_at_text")
public record NameAtText(QualifiedName a) implements Data {
}
