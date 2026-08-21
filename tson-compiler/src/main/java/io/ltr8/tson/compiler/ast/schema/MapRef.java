package io.ltr8.tson.compiler.ast.schema;

import java.util.Optional;

/**
 * {@code "{" ws map-key ws "=>" ws element-type [ws ";" ws size-spec] ws "}"} (Part 2 §12.1, §5.3) -- a map
 * type, legal at every type-ref position, mirroring the data notation's own {@code {k => v}} the way
 * {@link ArrayRef} mirrors {@code [a b]}.
 *
 * <p>The key is a {@link SimpleRef} or a {@link GenericRef} and nothing else ({@code map-key = type-name
 * ["<" type-args ">"]}): keeping it to a simple ref is what holds the record/map brace dispatch (§12.2) to
 * one consumed token plus one of lookahead, and a composite key type is expected to earn a named
 * declaration.
 *
 * <p>The value is an {@link ElementType} for symmetry with the bracket forms, but a map declares no
 * {@code state} field, so its {@code ?} is rejected at parse time rather than carried.
 */
public record MapRef(TypeRef keyType, ElementType valueType, Optional<SizeSpec> size) implements TypeRef {
}
