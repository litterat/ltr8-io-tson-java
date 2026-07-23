package io.ltr8.tson.parser.ast.schema;

import java.util.List;
import java.util.Optional;

/**
 * A schema document (Part 2 §2.1, §12.1): a fixed-shape header -- optional {@code !!id}, mandatory
 * {@code !!meta} (exactly once), repeatable {@code !!import} in declaration order -- followed by
 * the {@link SchemaMap} body. Every directive value is a URL string, preserved uninterpreted here
 * (§2.2): resolving them against the schema library (§10) is a semantic-layer concern this
 * grammar-only stage doesn't implement.
 */
public record SchemaDocument(Optional<String> id, String meta, List<String> imports, SchemaMap body) {

    public SchemaDocument {
        imports = List.copyOf(imports);
    }
}
