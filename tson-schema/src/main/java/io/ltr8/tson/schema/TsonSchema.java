package io.ltr8.tson.schema;

import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A resolved schema (Part 2 §8): the kernel's own {@code schema} type, {@code map<type_name,
 * type_definition>} (§9), plus the governing-chain directives its own document header carried
 * ({@code !!id}?/{@code !!meta}/{@code !!import}*, §2.2) -- the "produced schema" this module
 * exists for, as opposed to {@code tson-parser}'s grammar-only {@code SchemaDocument}/{@code
 * SchemaMap}. {@code entries}' insertion order is preserved, matching {@code SchemaMap.
 * declarations}' own ordering guarantee.
 *
 * <p><b>A plain class, not a record</b> -- deliberately, so a bootstrap subclass representing a
 * pre-loaded schema (e.g. a hand-verified or specially-constructed resolution of meta-kernel
 * itself, which can't be resolved the ordinary way -- its own {@code !!meta} names itself, §1.5's
 * "one deliberate circularity in the series, closed by pre-loading rather than by resolution") can
 * {@code extend} it directly, rather than wrapping/delegating to it.
 */
public class TsonSchema {

    private final Optional<String> id;
    private final String meta;
    private final List<String> imports;
    private final Map<String, TypeDefinition> entries;

    public TsonSchema(Optional<String> id, String meta, List<String> imports, Map<String, TypeDefinition> entries) {
        this.id = id;
        this.meta = meta;
        this.imports = List.copyOf(imports);
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /** {@code !!id}'s URI argument, if present -- optional in the grammar, required for a published schema. */
    public Optional<String> id() {
        return id;
    }

    /** {@code !!meta}'s URI argument -- the governing meta-schema this schema's declarations are validated against. */
    public String meta() {
        return meta;
    }

    /** {@code !!import}'s URI arguments, in declaration order -- the namespace this schema's type-name tokens resolve against (§2.2.3), alongside its own declarations. */
    public List<String> imports() {
        return imports;
    }

    /** The resolved {@code type_name -> type_definition} map itself. */
    public Map<String, TypeDefinition> entries() {
        return entries;
    }
}
