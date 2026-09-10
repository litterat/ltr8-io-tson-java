package io.ltr8.tson.json;

import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A linked schema with one {@link JsonTypeReader} compiled per entry -- the compile stage's noun, produced by
 * {@link JsonSchemaCompiler#compile} and holding no build logic of its own.
 *
 * <p><b>What crosses from the schema pipeline is {@link TsonLinkedSchema}, and that is the whole of it.</b>
 * Resolution, linking and registration are encoding-neutral -- they run over {@code schema.meta} values and
 * have never heard of an encoding -- so this module consumes their output and none of their machinery. That
 * output lives in {@code tson-schema}, a module requiring only {@code tson-base} and one {@code tson-atom}
 * already re-exports, which is why the schema-directed stack here costs no dependency on {@code
 * tson-compiler}.
 *
 * <p>Deliberately not {@code sealed} the way {@code TsonCompiledSchema} is: a governing meta-schema compiles
 * only for its own resolution, which is the TSON side's business, and [TSON-JSON] §3.4 gives this encoding no
 * schema documents of its own to govern.
 */
public final class JsonCompiledSchema {

    /** How many declared names {@link #unknownTypeMessage} spells out before summarizing the rest. */
    private static final int NAMES_IN_MESSAGE = 8;

    private final TsonLinkedSchema linkedSchema;
    private final Map<String, JsonTypeReader<?>> entries;

    public JsonCompiledSchema(TsonLinkedSchema linkedSchema, Map<String, JsonTypeReader<?>> entries) {
        this.linkedSchema = linkedSchema;
        this.entries = Map.copyOf(entries);
    }

    /** The reader for {@code typeName}, or a refusal naming what this schema does declare. */
    public JsonTypeReader<?> get(String typeName) {
        JsonTypeReader<?> reader = entries.get(typeName);
        if (reader == null) {
            throw new IllegalArgumentException(unknownTypeMessage(typeName));
        }
        return reader;
    }

    /**
     * The reader for {@code typeName}, or empty if this schema has none -- the non-throwing counterpart to
     * {@link #get}, for a caller that treats an absent entry as a normal outcome.
     */
    public Optional<JsonTypeReader<?>> find(String typeName) {
        return Optional.ofNullable(entries.get(typeName));
    }

    /** The resolved schema this was compiled from. */
    public TsonSchema schema() {
        return linkedSchema.schema();
    }

    /** The linked schema this was compiled from -- what {@link JsonSchemaCompiler} was handed. */
    public TsonLinkedSchema linkedSchema() {
        return linkedSchema;
    }

    /**
     * Every declared type name, in schema order -- the closed set a root binding may name ([TSON-JSON] §3.4),
     * for the machine-readable {@code expected} of a diagnostic. Order comes from the resolved schema's own
     * insertion-ordered entries, not from {@link #entries}, which is an unordered immutable copy.
     */
    public String declaredTypeNames() {
        return String.join(" | ", schema().entries().keySet());
    }

    /**
     * Why {@code typeName} does not resolve, told the way a closed field list is told: name what <em>is</em>
     * declared, so the fix takes one step instead of a guess.
     *
     * <p>An {@code !!import} flattens the imported schema's entries into this one, so a schema declaring one
     * type over core.tn has ~50 names and spelling all of them buries the one the caller wrote. Only the
     * first few are listed; the full set stays available through {@link #declaredTypeNames}.
     */
    public String unknownTypeMessage(String typeName) {
        Collection<String> names = schema().entries().keySet();
        List<String> shown = names.stream().limit(NAMES_IN_MESSAGE).toList();
        StringBuilder message = new StringBuilder("'").append(typeName)
                .append("' is not in this compiled schema, whose types are (").append(String.join(" | ", shown));
        if (names.size() > shown.size()) {
            message.append(", and ").append(names.size() - shown.size()).append(" more");
        }
        return message.append(")").toString();
    }

    /**
     * Where a read entering through {@code typeName} roots its schema pointer: the name the caller named, not
     * the entry it resolves to. The two differ exactly when the name aliases something the resolver minted,
     * and the minted entry's reader -- shared by every entry point -- cannot know which name a given read
     * arrived through. Empty for a name this schema does not declare.
     */
    Optional<JsonSchemaLocation> rootDeclaration(String typeName) {
        return Optional.ofNullable(schema().entries().get(typeName))
                .map(entry -> JsonSchemaLocation.of(linkedSchema.originOf(typeName), typeName, entry.position()));
    }
}
