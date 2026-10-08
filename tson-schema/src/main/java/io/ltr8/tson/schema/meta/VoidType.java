package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

/**
 * The meta-kernel's {@code void_type} constructor: an atom with no constraint vocabulary, whose parsing
 * contract admits only the void sentinel {@code _}. Its instances are the kernel's {@code void} and core's
 * sibling of the same name, both {@code !void_type {}} -- two type entities, one contract, recognised by this
 * body rather than by either name.
 */
@Typename(name = "void_type")
public record VoidType() implements Atom {
}
