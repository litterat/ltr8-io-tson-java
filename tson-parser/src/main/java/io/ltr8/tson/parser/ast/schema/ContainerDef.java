package io.ltr8.tson.parser.ast.schema;

import java.util.List;
import java.util.Optional;

/**
 * {@code container-def} (Part 2 §12.1, §5.3) -- the declaration-level-only array/tuple forms:
 * legal only as a declaration's top-level type-def body, or nested inside another {@code
 * container-def}'s element position, never at an ordinary type-ref position (§5.3's inline/
 * declaration-level split; {@link InlineArrayRef}/{@link InlineTupleRef} cover the inline-legal
 * subset instead). What distinguishes this tier is the extra syntax it admits: a size specifier
 * after {@code ;} on arrays, and an element/position {@code ?} -- both parse errors inside a
 * plain {@link TypeRef}.
 */
public sealed interface ContainerDef permits ArrayContainerDef, TupleContainerDef {
}
