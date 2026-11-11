package io.ltr8.tson.compiler.ast.schema;

import java.util.List;

/**
 * {@code paren-type = "(" type-ref "|" type-ref *("|" type-ref) ")"} (Part 2 §12.1, §5.4) -- a choice type, at least
 * two variants, each a {@link TypeExpr}. {@code SchemaDesugarer} rewrites it to the construction {@code
 * !meta.choice { variants: [...] }} (§5.4), written in place at a slot.
 */
public record ChoiceExpr(List<TypeExpr> variants) implements TypeExpr {

    public ChoiceExpr {
        variants = List.copyOf(variants);
        if (variants.size() < 2) {
            throw new IllegalArgumentException("a choice type requires at least two variants, got " + variants.size());
        }
    }
}
