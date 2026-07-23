package io.ltr8.tson.schema;

import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The meta-kernel's own resolved schema (Part 2 §1.5) -- distinct from an ordinary {@link
 * TsonSchema} purely to mark that identity in the type system; it adds no fields or behavior of its
 * own. Produced by {@code io.ltr8.tson.mapper.schema.MetaKernelParser} (in {@code tson-mapper}, the
 * only module with both {@code SchemaResolver} and {@code TsonMapper} available in main scope) --
 * meta-kernel's own {@code !!meta} names itself, so it can't come from ordinary {@code
 * SchemaResolver} resolution alone (§1.5's "one deliberate circularity in the series, closed by
 * pre-loading rather than by resolution").
 */
public final class MetaSchema extends TsonSchema {

    public MetaSchema(Optional<String> id, String meta, List<String> imports, Map<String, TypeDefinition> entries) {
        super(id, meta, imports, entries);
    }
}
