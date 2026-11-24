package io.ltr8.tson.compiler.ast.schema;

import java.util.List;
import java.util.Optional;

/**
 * {@code [definition-mark ws] [type-params] structural-def} (Part 2 §12.1, §5.2, §5.10) -- a composition/subtraction or
 * a fresh record, optionally marked and optionally parameterized. The mark is this production's alone: it is sugar
 * for the {@code extension} of the record the definition resolves to, so no other definition carries one.
 *
 * <p>There is no constructor marker. What makes an entry a constructor is that it IS-A {@code top} (§4.1),
 * which its supertype chain says: a declaration composing with a base kind is one, and IS-A stops at
 * construction, so an instance or a fresh record is not. The grammar carries no separate assertion of it.
 */
public record StructuralTypeDef(Optional<DefinitionMark> mark, List<String> typeParams, StructuralDef body)
        implements TypeDef {

    public StructuralTypeDef {
        typeParams = List.copyOf(typeParams);
    }
}
