package io.ltr8.tson.parser.ast.schema;

import java.util.List;

/**
 * {@code paren-type = "(" type-ref "|" type-ref *("|" type-ref) ")"} (Part 2 §12.1, §5.4) -- a
 * choice type, at least two variants. Desugars to {@code !choice { variants: [...] }} at
 * resolution (§5.4, not implemented at this grammar-only stage).
 */
public record ChoiceRef(List<TypeRef> variants) implements TypeRef {

    public ChoiceRef {
        variants = List.copyOf(variants);
        if (variants.size() < 2) {
            throw new IllegalArgumentException("a choice type requires at least two variants, got " + variants.size());
        }
    }
}
