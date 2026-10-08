package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

/**
 * The meta-kernel's {@code value_type} constructor: an atom with no constraint vocabulary, whose one instance
 * is {@code value => !value_type {}}, the escape hatch. Its contract is the encoding's -- a value position is
 * read by whatever base type resolution the encoding defines -- so the body carries nothing, and the
 * constructor alone is what tells {@code value} apart from every other atom.
 */
@Typename(name = "value_type")
public record ValueType() implements Atom {
}
