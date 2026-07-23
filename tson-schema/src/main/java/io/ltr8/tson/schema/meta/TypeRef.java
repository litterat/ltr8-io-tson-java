package io.ltr8.tson.schema.meta;

import java.util.List;

/**
 * The meta-kernel's {@code type_ref} record (Part 2 §8.1): a resolved reference to a named entry
 * -- {@code name} is its only REQUIRED field, so an argument-free reference always writes in the
 * positional (bare-token) form, never {@code !type_ref { name: ... }} (§5.6's positional-form
 * rule, general over schema-backed data). {@code arguments}, when present, is a resolved {@code
 * type_argument} list; empty means "no {@code <...>}" was applied, i.e. a simple reference.
 *
 * <p>Same name as {@code tson-parser}'s grammar-layer {@code io.ltr8.tson.parser.ast.schema.TypeRef}
 * -- a different package, a deliberately different concept (source-text reference vs. resolved
 * reference), matching the kernel's own choice to call both "type_ref" too.
 */
public record TypeRef(String name, List<TypeArgument> arguments) {

    public TypeRef {
        arguments = List.copyOf(arguments);
    }

    /** A bare reference with no type arguments, e.g. a plain field type like {@code integer}. */
    public static TypeRef of(String name) {
        return new TypeRef(name, List.of());
    }
}
