package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.ast.AbsentValue;
import io.ltr8.tson.parser.ast.CoreValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.EmptyBrace;
import io.ltr8.tson.parser.ast.RecordValue;
import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Token;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link TsonParserFactory} for meta-kernel's {@code record} constructor (§5.2), and the
 * {@link TsonTypeParser} it builds -- the first real (non-test-local) composite factory, replacing
 * the minimal map-producing stand-in this class's own test ({@code RecordParserTest}, then still
 * named {@code IntegerTypeParserFactoryTest}) used before this class existed. Reads a schema
 * position's own {@link RecordField#state} directly, unlike {@code
 * TsonMapperReader.toRecord} (which only knows REQUIRED-or-not from a plain {@code DataClassField},
 * with no concept of a schema-composed default at all): an absent {@code REQUIRED_DEFAULT}/{@code
 * REQUIRED_FIXED}/{@code OPTIONAL_FIXED} field is filled from the schema's own literal {@link
 * RecordField#value} rather than erroring, the actual Class 2 behavior the schema-backed data
 * parser is for -- see this repo's own discussion of {@code boolean => !enum [true false]} for why
 * that distinction matters at all.
 *
 * <p><b>Produces a plain {@code Map<String, Object>}, not a bound Java object</b> -- deliberately.
 * Binding into a caller-declared POJO needs a schema-type-name -> Java-{@code Class} mapping that
 * doesn't exist anywhere yet (a distinct, not-yet-designed piece; see the "object vs. DOM vs.
 * validation mode" discussion this class's own factory registration is part of). This is real
 * record-parsing behavior (state handling, defaulting, absence), not a placeholder for it -- only
 * where the result finally lands is deferred.
 *
 * <p><b>Known gaps, not silently mishandled:</b> a {@code value_param} field modifier (a default
 * routed to one of the declaration's own type parameters, e.g. {@code array}'s {@code
 * element_type: type_ref = T}) throws rather than resolving -- meaningless before generic type-
 * parameter *substitution* exists anywhere in this codebase, which it doesn't yet (materialization
 * only gives an argument-bearing {@code type_ref} a flat synthesized name, see {@code
 * SchemaRegistry}'s own Javadoc -- it never actually substitutes a parameter through a template
 * body). {@link RecordBody#groups}'s own "at most one member present" constraint is not enforced --
 * group members already arrive as ordinary {@code OPTIONAL} fields in {@link RecordBody#fields}
 * (flattened there during resolution, see {@code SchemaResolver}'s own Javadoc), so they read
 * correctly as individual fields; only the cross-field exclusivity check is missing. Every field's
 * own {@code type: type_ref} is assumed argument-free (bare name) -- true for anything read from an
 * already-materialized {@link io.ltr8.tson.schema.TsonSchema}, per {@link CompilationContext}'s own
 * Javadoc.
 */
final class RecordParser implements TsonTypeParser<Map<String, Object>> {

    static final TsonParserFactory FACTORY = (name, definition, ctx) -> {
        RecordBody body = (RecordBody) definition.body();
        List<CompiledField> fields = new ArrayList<>();
        for (RecordField field : body.fields()) {
            fields.add(new CompiledField(field, ctx.resolve(field.type().name())));
        }
        return new RecordParser(name, fields);
    };

    private record CompiledField(RecordField schema, TsonTypeParser<?> parser) {
    }

    private final String name;
    private final List<CompiledField> fields;

    private RecordParser(String name, List<CompiledField> fields) {
        this.name = name;
        this.fields = fields;
    }

    @Override
    public Map<String, Object> read(DataValue value) {
        Map<String, DataValue> byName = fieldValuesByName(value);
        Map<String, Object> result = new LinkedHashMap<>();
        for (CompiledField field : fields) {
            DataValue fieldValue = byName.get(field.schema().name());
            result.put(field.schema().name(), isAbsent(fieldValue) ? defaultOrRequire(field) : field.parser().read(fieldValue));
        }
        return result;
    }

    private Map<String, DataValue> fieldValuesByName(DataValue value) {
        if (value == null) {
            throw new IllegalArgumentException("expected a record for '" + name + "', found no value");
        }
        CoreValue core = value.coreValue();
        Map<String, DataValue> byName = new LinkedHashMap<>();
        if (core instanceof RecordValue rv) {
            // "Last value wins" for a duplicate field name falls out of iterating in source order
            // and overwriting on put() -- the same rule TsonMapperReader.toRecord relies on (§2.5).
            for (RecordValue.Field f : rv.fields()) {
                byName.put(f.name(), f.value().value());
            }
        } else if (!(core instanceof EmptyBrace)) {
            throw new IllegalArgumentException("expected a record for '" + name + "', found " + core);
        }
        return byName;
    }

    private static boolean isAbsent(DataValue value) {
        return value == null || value.coreValue() instanceof AbsentValue;
    }

    private Object defaultOrRequire(CompiledField field) {
        RecordField schema = field.schema();
        return switch (schema.state()) {
            case REQUIRED -> throw new IllegalArgumentException(
                    "missing required field '" + schema.name() + "' for '" + name + "'");
            case OPTIONAL -> null;
            case REQUIRED_DEFAULT, REQUIRED_FIXED, OPTIONAL_FIXED -> readSchemaDefault(field);
        };
    }

    private Object readSchemaDefault(CompiledField field) {
        RecordField schema = field.schema();
        if (schema.valueParam().isPresent()) {
            throw new UnsupportedOperationException("'" + schema.name() + "' on '" + name + "' defaults via a "
                    + "type parameter ('= " + schema.valueParam().get() + "') -- parameter substitution isn't "
                    + "implemented anywhere in this codebase yet, so this can't resolve to a concrete value");
        }
        Token token = schema.value().orElseThrow(() -> new IllegalStateException("'" + schema.name() + "' on '"
                + name + "' is " + schema.state() + " but the schema carries neither a literal value nor a "
                + "value parameter for it -- SchemaResolver should never produce this"));
        DataValue synthetic = new DataValue(List.of(), Optional.empty(), toTokenValue(token));
        return field.parser().read(synthetic);
    }

    private static TokenValue toTokenValue(Token token) {
        return new TokenValue(token.text(), TokenForm.valueOf(token.form().name()));
    }
}
