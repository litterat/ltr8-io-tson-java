package io.ltr8.tson.parser.ast.schema;

import java.util.List;

/**
 * {@code [type-params] container-def} (Part 2 §12.1, §5.3) -- a declaration whose entire body is
 * a declaration-level array or tuple form (a size specifier, or an element/position {@code ?}),
 * such as {@code score_list => [integer; 1..]} or, inside a template, {@code grid => <T, N> [[T;
 * N]; N]}.
 */
public record ContainerTypeDef(List<String> typeParams, ContainerDef container) implements TypeDef {

    public ContainerTypeDef {
        typeParams = List.copyOf(typeParams);
    }
}
