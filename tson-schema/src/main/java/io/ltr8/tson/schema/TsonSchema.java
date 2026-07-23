package io.ltr8.tson.schema;

import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A resolved schema (Part 2 §8): the kernel's own {@code schema} type, {@code map<type_name,
 * type_definition>} (§9), materialised as a Java value -- the "produced schema" this module exists
 * for, as opposed to {@code tson-parser}'s grammar-only {@code SchemaMap}. Insertion order
 * preserved, matching {@code SchemaMap.declarations}' own ordering guarantee.
 */
public record TsonSchema(Map<String, TypeDefinition> entries) {

    public TsonSchema {
        entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }
}
