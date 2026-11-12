package io.ltr8.tson.compiler.ast.schema;

import java.util.Optional;

/**
 * {@code "{" ws map-key ws "=>" ws element-type [ws ";" ws size-spec] ws "}"} (Part 2 §12.1, §5.3) -- a map
 * type, legal at every type expression position, mirroring the data notation's own {@code {k => v}} the way
 * {@link ArrayExpr} mirrors {@code [a b]}.
 *
 * <p>The key is a {@link TypeRef} and never a structural form ({@code map-key = type-name ["<" type-args ">"]}):
 * keeping it to a reference is what holds the record/map brace dispatch (§12.2) to one consumed token plus one of
 * lookahead, and a composite key type is expected to earn a named declaration.
 *
 * <p>The value is an {@link ElementType}, so {@code {K => V?}} makes it voidable exactly as {@code [T?]} makes an
 * element; the key takes no {@code ?} ([TSON-DATA] §2.9).
 */
public record MapExpr(TypeRef keyType, ElementType valueType, Optional<SizeSpec> size) implements TypeExpr {
}
