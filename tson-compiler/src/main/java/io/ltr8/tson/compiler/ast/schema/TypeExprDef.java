package io.ltr8.tson.compiler.ast.schema;

import java.util.List;

/**
 * {@code [type-params] type-expr} (Part 2 §12.1, §8.3) -- a declaration whose body is a {@link TypeExpr}: a
 * reference such as {@code id => uuid}, an application ({@code page_order => page<order>}), or a structural form
 * ({@code contact_method => (email | phone | address)}). Resolves to an alias, a construction, or the record an
 * application closes to depending on what {@code ref} turns out to name -- a semantic-layer distinction (§5.6,
 * §5.10), not one this grammar-only stage makes.
 */
public record TypeExprDef(List<String> typeParams, TypeExpr ref) implements TypeDef {

    public TypeExprDef {
        typeParams = List.copyOf(typeParams);
    }
}
