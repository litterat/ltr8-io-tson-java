package io.ltr8.tson.parser.ast.schema;

import io.ltr8.tson.parser.ast.Annotation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code schema-map = *(annotation ws) "{" ws schema-map-entry *(separator schema-map-entry) ws
 * "}"} (Part 2 §12.1, §2.1) -- the schema document's body: an annotated, braced declaration map
 * requiring at least one entry ({@code {}} at schema-body position is a parse error, unlike an
 * ordinary data map). {@code annotations} bind to the schema map itself, the document's own
 * annotation anchor (§2.1).
 *
 * <p>{@code declarations} is keyed by declaration name (insertion order preserved, a {@link
 * LinkedHashMap} under the hood) rather than a plain list, so a resolver can look an entry up by
 * name directly -- exactly the shape §3.4.1's Pass 1 needs ("populated with skeleton {@code
 * type_definition} records keyed by name") and the document's own target type, {@code
 * map<type_name, type_definition>} (§9). Two declarations sharing a name are not rejected here: the
 * later one simply overwrites the earlier one's map entry, the same "detection is a resolver-layer
 * concern, not a grammar one" treatment [TSON-DATA] §2.5/§2.6 already give ordinary duplicate
 * record fields and map keys -- real duplicate-name detection belongs to schema resolution
 * (Pass 1), not this grammar-only layer.
 */
public record SchemaMap(List<Annotation> annotations, Map<String, Declaration> declarations) {

    public SchemaMap {
        annotations = List.copyOf(annotations);
        declarations = Collections.unmodifiableMap(new LinkedHashMap<>(declarations));
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
