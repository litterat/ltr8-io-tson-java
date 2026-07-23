package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.util.List;

/**
 * The meta-kernel's {@code tuple} constructor's own vocabulary, resolved (Part 2 §4.2, §5.3,
 * §8.1) -- {@code access_pattern}/{@code size_type} are fixed ({@code INDEX}/{@code FIXED}) and
 * never appear in output. {@code elements}' positional order is significant (§5.3: a tuple's
 * positions are fixed-arity and ordered), unlike {@link EnumBody}/{@link TypeDefinition}'s
 * supertype-style lists.
 */
@Typename(name = "tuple")
public record TupleBody(List<TupleElement> elements) implements TypeBody, Product {

    public TupleBody {
        elements = List.copyOf(elements);
    }
}
