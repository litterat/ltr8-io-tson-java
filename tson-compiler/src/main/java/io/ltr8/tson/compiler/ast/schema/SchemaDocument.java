package io.ltr8.tson.compiler.ast.schema;

import java.util.List;
import java.util.Optional;

/**
 * A schema document (Part 2 §2.1, §12.1): a header -- optional {@code !!id}, then any number of {@code !!import}s in
 * declaration order -- followed by the {@link SchemaMap} body, the root tagged {@code !schema}. Every directive value
 * is a URL string, preserved uninterpreted here (§2.2): resolving them against the schema library (§10) is a
 * semantic-layer concern this grammar-only stage doesn't implement.
 */
public record SchemaDocument(Optional<String> id, List<String> imports, SchemaMap body) {

    public SchemaDocument {
        imports = List.copyOf(imports);
    }
}
