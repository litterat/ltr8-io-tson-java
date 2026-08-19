package io.ltr8.tson.compiler.ast.schema;

import java.util.Optional;

/**
 * {@code "{" ws map-key ws "=>" ws map-value [ws ";" ws size-spec] ws "}"} (Part 2 §12.1, §5.3) -- a
 * declaration-level map type, with an optional size specifier. {@code size} absent means unconstrained
 * (plain {@code {K => V}} spelled at declaration level rather than as {@link InlineMapRef}).
 *
 * <p>The value position is {@code map-value = container-def / type-ref}, so a declaration-level array,
 * tuple or map nests directly inside it -- the same {@link ElementType.Expr} an array element position
 * carries, minus the {@code ?} a map value has no {@code state} field to bind.
 */
public record MapContainerDef(TypeRef keyType, ElementType.Expr valueType, Optional<SizeSpec> size)
        implements ContainerDef {
}
