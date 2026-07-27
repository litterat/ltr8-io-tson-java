package io.ltr8.tson.parser.compiler;

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
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link TsonParserFactory} for meta-kernel's {@code record} constructor (§5.2), and the
 * {@link TsonSchemaTypeParser} it builds -- the first real (non-test-local) composite factory, replacing
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
 * <p><b>Generic over its own result type {@code R}, driven by a {@link RecordShapeFactory}</b> --
 * this class's own field-iteration/absence/defaulting logic (below) has no opinion on what a
 * record value finally becomes; only {@link RecordShape}/{@link RecordBuilder} (resolved once per
 * compiled type, see {@link #factory}) decide that. {@link #FACTORY} keeps DOM mode's own,
 * previously-hardcoded behavior (a plain {@code Map<String, Object>}) as a compatibility constant;
 * an object-binding mode (a schema-type-name -> Java-{@code Class} table, producing real bound
 * {@code schema.meta} objects) is a separate {@link RecordShapeFactory} implementation, not a
 * change to this class at all -- see {@code ObjectRecordShapeFactory}.
 *
 * <p><b>Known gaps, not silently mishandled:</b> a {@code value_param} field modifier (a default
 * routed to one of the declaration's own type parameters, e.g. {@code array}'s {@code
 * element_type: type_ref = T}) throws rather than resolving -- meaningless before generic type-
 * parameter *substitution* exists anywhere in this codebase, which it doesn't yet (materialization
 * only gives an argument-bearing {@code type_ref} a flat synthesized name, see {@code
 * TsonSchemaRegistry}'s own Javadoc -- it never actually substitutes a parameter through a template
 * body). {@link RecordBody#groups}'s own "at most one member present" constraint is not enforced --
 * group members already arrive as ordinary {@code OPTIONAL} fields in {@link RecordBody#fields}
 * (flattened there during resolution, see {@code DefinitionResolver}'s own Javadoc), so they read
 * correctly as individual fields; only the cross-field exclusivity check is missing. Every field's
 * own {@code type: type_ref} is assumed argument-free (bare name) -- true for anything read from an
 * already-materialized {@link io.ltr8.tson.schema.TsonSchema}, per {@link CompilationContext}'s own
 * Javadoc.
 */
public final class RecordParser<R> implements TsonSchemaTypeParser<R> {

    /**
     * How {@link RecordParser} turns one record value's own field values into a result -- resolved
     * once per compiled type (see {@link RecordShapeFactory}), reused across every {@link
     * RecordParser#read} call for that type. {@link #begin()} is the cheap, per-read half: DOM
     * mode's own shape has no real per-type state to hold at all (a fresh {@code LinkedHashMap} is
     * just as cheap to build lazily), but an object-binding mode's shape holds an already-resolved
     * constructor/field descriptor (reflection paid once, not once per read) and {@link #begin()}
     * just opens a fresh accumulator against it. Nested here, not a standalone top-level type --
     * the only implementation outside this package is object-binding mode's own {@code
     * ObjectRecordShapeFactory} (in {@code io.ltr8.tson.parser.binder}), which is exactly why {@link
     * RecordParser} itself, and {@link #factory}, are {@code public} despite being otherwise pure
     * internal machinery -- see {@link #factory}'s own Javadoc.
     */
    public interface RecordShape<R> {

        RecordBuilder<R> begin();
    }

    /**
     * Accumulates one record value's own field values, in schema field-declaration order (the same
     * order {@link RecordParser#read} already iterates in), then finalizes them into a result. One
     * instance per {@link RecordParser#read} call -- see {@link RecordShape#begin()}.
     */
    public interface RecordBuilder<R> {

        void field(String name, Object value);

        R build();
    }

    /**
     * Resolves a {@link RecordShape} for one compiled {@code record}-shaped entry -- called once,
     * from inside {@link RecordParser#factory}'s own {@link TsonParserFactory} lambda, at compile
     * time, not once per read. This is deliberately where any per-type reflective cost (an
     * object-binding mode's own constructor/field descriptor lookup) belongs: paid once per
     * compiled type, the same way {@link RecordParser}'s own child-field parsers are already
     * resolved once via {@link CompilationContext#resolve} rather than per read.
     *
     * <p>Takes the whole {@link TypeDefinition}, not just its {@code body()}, mirroring {@link
     * TsonParserFactory}'s own convention -- a factory that only needs {@code body} still gets the
     * full definition for free, in case a future implementation wants more (e.g. {@code
     * definition.source()} for a diagnostic message).
     */
    @FunctionalInterface
    public interface RecordShapeFactory<R> {

        RecordShape<R> shapeFor(String typeName, TypeDefinition definition, RecordBody body);
    }

    static final TsonParserFactory FACTORY = factory(DomRecordShapeFactory.INSTANCE);

    /**
     * Builds a {@link TsonParserFactory} for {@code record} that produces {@code R}-typed results
     * via {@code shapeFactory} -- {@link RecordShapeFactory#shapeFor} runs exactly once here, at
     * compile time (the same point child fields are resolved via {@code ctx.resolve}), not once
     * per read; see {@link RecordShape}'s own Javadoc for why that matters.
     *
     * <p>{@code public}, unlike the rest of this package's own internal machinery, specifically so
     * {@code io.ltr8.tson.parser.binder} (object-binding mode, moved out of this package 2026-07-27)
     * can build its own {@code "record"} factory entry from its own {@code RecordShapeFactory}
     * implementation without this package needing any awareness that caller exists.
     */
    public static TsonParserFactory factory(RecordShapeFactory<?> shapeFactory) {
        return (_, name, definition, ctx) -> {
            RecordBody body = (RecordBody) definition.body();
            List<CompiledField> fields = new ArrayList<>();
            for (RecordField field : body.fields()) {
                fields.add(new CompiledField(field, ctx.resolve(field.type().name())));
            }
            return build(name, fields, shapeFactory, definition, body);
        };
    }

    // A small generic helper so the wildcard in RecordShapeFactory<?> is captured once, cleanly,
    // rather than fought with inline in the lambda above.
    private static <R> RecordParser<R> build(String name, List<CompiledField> fields,
                                              RecordShapeFactory<R> shapeFactory,
                                              TypeDefinition definition, RecordBody body) {
        RecordShape<R> shape = shapeFactory.shapeFor(name, definition, body);
        return new RecordParser<>(name, fields, shape);
    }

    private record CompiledField(RecordField schema, TsonSchemaTypeParser<?> parser) {
    }

    private final String name;
    private final List<CompiledField> fields;
    private final RecordShape<R> shape;

    private RecordParser(String name, List<CompiledField> fields, RecordShape<R> shape) {
        this.name = name;
        this.fields = fields;
        this.shape = shape;
    }

    @Override
    public R read(DataValue value) {
        Map<String, DataValue> byName = fieldValuesByName(value);
        RecordBuilder<R> builder = shape.begin();
        for (CompiledField field : fields) {
            DataValue fieldValue = byName.get(field.schema().name());
            builder.field(field.schema().name(),
                    isAbsent(fieldValue) ? defaultOrRequire(field) : field.parser().read(fieldValue));
        }
        return builder.build();
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
                + "value parameter for it -- DefinitionResolver should never produce this"));
        DataValue synthetic = new DataValue(List.of(), Optional.empty(), toTokenValue(token));
        return field.parser().read(synthetic);
    }

    private static TokenValue toTokenValue(Token token) {
        return new TokenValue(token.text(), TokenForm.valueOf(token.form().name()));
    }

    /**
     * DOM mode's own {@link RecordShapeFactory} -- a plain {@code LinkedHashMap} wrapper, the exact
     * same runtime behavior this class's {@code read()} built inline before {@link RecordShape} was
     * introduced. No real per-type work to cache (unlike an object-binding mode's own constructor
     * lookup), so {@link #shapeFor} just returns a constant shape.
     */
    private static final class DomRecordShapeFactory implements RecordShapeFactory<Map<String, Object>> {
        static final DomRecordShapeFactory INSTANCE = new DomRecordShapeFactory();

        private static final RecordShape<Map<String, Object>> SHAPE = DomRecordBuilder::new;

        @Override
        public RecordShape<Map<String, Object>> shapeFor(String typeName, TypeDefinition definition, RecordBody body) {
            return SHAPE;
        }
    }

    private static final class DomRecordBuilder implements RecordBuilder<Map<String, Object>> {
        private final Map<String, Object> values = new LinkedHashMap<>();

        @Override
        public void field(String name, Object value) {
            values.put(name, value);
        }

        @Override
        public Map<String, Object> build() {
            return values;
        }
    }
}
