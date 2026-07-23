package io.ltr8.tson.parser.ast.schema;

/**
 * {@code element-type = ( container-def / type-ref ) ["?"]} (Part 2 §12.1, §5.3) -- one position
 * inside a declaration-level {@link ArrayContainerDef} or {@link TupleContainerDef}. The optional
 * {@code ?} here is element/tuple-position optionality (a container-level fact), distinct from a
 * field's own {@code ?} (§5.2) even though both reuse the same token.
 */
public record ElementType(Expr expr, boolean optional) {

    /**
     * {@code container-def / type-ref} -- a nested bracket form (declaration-level syntax nests
     * inside itself, §5.3: {@code [[T; N]; N]}) or an ordinary type-ref. A {@code type-ref}
     * position, by contrast, never admits a nested {@link ContainerDef} -- only {@link
     * InlineArrayRef}/{@link InlineTupleRef}'s narrower inline shapes.
     */
    public sealed interface Expr {

        record Nested(ContainerDef container) implements Expr {
        }

        record Plain(TypeRef typeRef) implements Expr {
        }
    }
}
