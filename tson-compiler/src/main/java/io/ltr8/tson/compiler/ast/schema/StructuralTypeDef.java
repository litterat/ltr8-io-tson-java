package io.ltr8.tson.compiler.ast.schema;

import java.util.List;

/**
 * {@code [type-params] structural-def} (Part 2 §12.1, §5.10) -- a refinement, composition/subtraction, or
 * fresh record, optionally parameterized.
 *
 * <p>There is no constructor marker. What makes an entry a constructor is that it IS-A {@code top} (§4.1),
 * which its supertype chain says: a declaration composing with a base kind is one, and IS-A stops at
 * construction, so an instance or a fresh record is not. The grammar carries no separate assertion of it.
 */
public record StructuralTypeDef(List<String> typeParams, StructuralDef body) implements TypeDef {

    public StructuralTypeDef {
        typeParams = List.copyOf(typeParams);
    }
}
