package io.ltr8.tson.parser.ast.schema;

import java.util.Optional;

/**
 * {@code "[" element-type [ws ";" ws size-spec] ws "]"} (Part 2 §12.1, §5.3) -- a declaration-level
 * array type, with an optional size specifier. {@code size} absent means unconstrained (plain
 * {@code [T]} spelled at declaration level rather than as {@link InlineArrayRef}).
 */
public record ArrayContainerDef(ElementType elementType, Optional<SizeSpec> size) implements ContainerDef {
}
