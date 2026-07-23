package io.ltr8.tson.parser.ast.schema;

import java.util.List;

/**
 * {@code [type-params] type-ref} (Part 2 §12.1, §8.3) -- a declaration whose body is a plain type
 * reference, such as {@code id => uuid}, a fully- or partially-bound generic application ({@code
 * text_keyed_map => <V> map<text, V>}), or inline sugar ({@code contact_method => (email | phone |
 * address)}). Resolves to a {@code REFERENCE}-kind entry, a construction, or an open template
 * depending on what {@code ref} turns out to name -- a semantic-layer distinction (§5.6, §5.10),
 * not one this grammar-only stage makes.
 */
public record ReferenceTypeDef(List<String> typeParams, TypeRef ref) implements TypeDef {

    public ReferenceTypeDef {
        typeParams = List.copyOf(typeParams);
    }
}
