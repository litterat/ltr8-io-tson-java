package io.ltr8.tson.compiler.ast.schema;

/**
 * {@code "{" ws map-key ws "=>" ws type-ref ws "}"} (Part 2 §12.1, §5.3) -- the map sugar at an inline
 * type-ref position, mirroring the data notation's own {@code {k => v}} the way {@link InlineArrayRef}
 * mirrors {@code [a b]}. Desugars to {@code !map { key_type: K  value_type: V }}.
 *
 * <p>The key is a {@link SimpleRef} or a {@link GenericRef} and nothing else ({@code map-key = type-name
 * ["<" type-args ">"]}): keeping it to a simple ref is what holds the record/map brace dispatch (§12.2) to
 * one consumed token plus one of lookahead, and a composite key type is expected to earn a named
 * declaration. No size specifier and no {@code ?} on either side: the size specifier is
 * declaration-level-only syntax, modeled by {@link MapContainerDef} instead, and {@code map} has no {@code
 * state} field for a {@code ?} to bind.
 */
public record InlineMapRef(TypeRef keyType, TypeRef valueType) implements TypeRef {
}
