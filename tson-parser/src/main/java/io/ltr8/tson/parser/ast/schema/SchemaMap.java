package io.ltr8.tson.parser.ast.schema;

import io.ltr8.tson.parser.ast.Annotation;

import java.util.List;

/**
 * {@code schema-map = *(annotation ws) "{" ws schema-map-entry *(separator schema-map-entry) ws
 * "}"} (Part 2 §12.1, §2.1) -- the schema document's body: an annotated, braced declaration map
 * requiring at least one entry ({@code {}} at schema-body position is a parse error, unlike an
 * ordinary data map). {@code annotations} bind to the schema map itself, the document's own
 * annotation anchor (§2.1).
 */
public record SchemaMap(List<Annotation> annotations, List<Declaration> declarations) {

    public SchemaMap {
        annotations = List.copyOf(annotations);
        declarations = List.copyOf(declarations);
        if (declarations.isEmpty()) {
            throw new IllegalArgumentException("a schema map requires at least one declaration");
        }
    }

    /**
     * {@code schema-map-entry = *(annotation ws) type-name ws "=>" ws *(annotation ws) type-def}
     * -- {@code nameAnnotations} bind to the key (the {@code type_name} token itself, §2.1: "the
     * resolver does not hoist annotations from key to value"); {@code typeDefAnnotations} bind to
     * the type definition.
     */
    public record Declaration(List<Annotation> nameAnnotations, String name,
                               List<Annotation> typeDefAnnotations, TypeDef typeDef) {

        public Declaration {
            nameAnnotations = List.copyOf(nameAnnotations);
            typeDefAnnotations = List.copyOf(typeDefAnnotations);
        }
    }
}
