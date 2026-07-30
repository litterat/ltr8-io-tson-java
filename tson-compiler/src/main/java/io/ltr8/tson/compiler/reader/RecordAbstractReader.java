package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.compiler.ast.AbsentValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.EmptyBrace;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.ScopedValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.Token;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything {@link RecordDomReader} and {@link RecordBindReader} share verbatim: the compiled
 * per-field list, the name -> position lookup, resolving a record-shaped {@link DataValue} into its
 * field list, and precomputing every {@code REQUIRED_DEFAULT}/{@code REQUIRED_FIXED}/{@code
 * OPTIONAL_FIXED} field's own literal schema value once at construction rather than per read. Each
 * subclass's own {@code read()} still differs in shape (a {@code Map} vs. a real bound object's own
 * constructor arguments) and stays there, along with anything specific to only one of them (object
 * mode's own target-type narrowing, for instance).
 *
 * <p>{@link #precomputedValue} is stored raw here -- the natural host value {@code readSchemaDefault}
 * produces, with no narrowing applied. {@link RecordBindReader} overwrites its own entries in place,
 * once, right after calling this class's own constructor, narrowing each one to its bound field's
 * target type; {@link RecordDomReader} leaves them exactly as this class computed them.
 *
 * <p><b>Positional form (§5.6):</b> a record whose fields include exactly one bare {@code REQUIRED}
 * one (never {@code REQUIRED_DEFAULT}/{@code REQUIRED_FIXED}/{@code OPTIONAL}/{@code
 * OPTIONAL_FIXED}, even if it's the only field present) can be filled by a bare, non-braced
 * value standing in for that one field -- {@code !enum [true false]}'s own {@code [true false]}
 * filling {@code enum}'s sole required field, {@code members}, without ever writing {@code {
 * members: [true false] } }. {@link #positionalFieldIndex} (that field's own schema position, or
 * {@code -1} if the record doesn't qualify) is computed once, in the constructor, by counting bare
 * {@code REQUIRED} fields in the same pass that already visits every field for {@link
 * #precomputedValue} -- no separate pass, and nothing paid per read: {@link #dataFields} only
 * consults it on the one path that isn't already record-shaped, wrapping the whole incoming value as
 * that single field's own value ({@code List.of(new RecordValue.Field(...))}, one entry) rather than
 * demanding a caller synthesize a full {@link RecordValue} first.
 */
abstract class RecordAbstractReader<T> implements TsonValueReader<T> {

    record CompiledField(RecordField schema, TsonValueReader<?> parser) {
    }

    final String name;
    final List<CompiledField> fields;
    final Map<String, Integer> fieldIndex;
    final Object[] precomputedValue;
    final int[] fixedFieldIndices;
    final int positionalFieldIndex;
    final Optional<SourcePosition> schemaPosition;

    RecordAbstractReader(String name, RecordBody body, TsonValueReaderResolver resolver,
                          Optional<SourcePosition> schemaPosition) {
        this.name = name;
        this.schemaPosition = schemaPosition;
        this.fields = buildFields(body, resolver);
        this.fieldIndex = new HashMap<>();
        this.precomputedValue = new Object[fields.size()];
        List<Integer> fixedIndices = new ArrayList<>();
        int solePositionalField = -1;
        int bareRequiredCount = 0;
        for (int i = 0; i < fields.size(); i++) {
            CompiledField field = fields.get(i);
            fieldIndex.put(field.schema().name(), i);
            FieldState state = field.schema().state();
            if (state == FieldState.REQUIRED_DEFAULT || state == FieldState.REQUIRED_FIXED
                    || state == FieldState.OPTIONAL_FIXED) {
                precomputedValue[i] = readSchemaDefault(field);
            }
            if (state == FieldState.REQUIRED_FIXED || state == FieldState.OPTIONAL_FIXED) {
                fixedIndices.add(i);
            }
            if (state == FieldState.REQUIRED) {
                bareRequiredCount++;
                solePositionalField = i;
            }
        }
        this.fixedFieldIndices = fixedIndices.stream().mapToInt(Integer::intValue).toArray();
        this.positionalFieldIndex = bareRequiredCount == 1 ? solePositionalField : -1;
    }

    private static List<CompiledField> buildFields(RecordBody body, TsonValueReaderResolver resolver) {
        List<CompiledField> fields = new ArrayList<>(body.fields().size());
        for (RecordField field : body.fields()) {
            fields.add(new CompiledField(field, resolver.resolve(field.type().name())));
        }
        return fields;
    }

    /**
     * Returns {@code null} (never a real field list) if the value's own shape doesn't match at all --
     * a {@link Diagnostic.Code#TYPE_MISMATCH} has already been reported to {@code ctx} by the time
     * this returns (or, in fail-fast mode, {@code ctx} already threw before returning at all). A
     * caller in collecting mode must check for {@code null} and stop processing this record entirely
     * rather than also reporting every one of its own fields as separately missing, which would be
     * misleading noise on top of the one real problem (the value was never record-shaped to begin
     * with).
     */
    final List<RecordValue.Field> dataFields(DataValue value, TsonReadContext ctx) {
        if (value == null) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected a record for '" + name + "', found no value",
                    "a record", "no value");
            return null;
        }
        CoreValue core = value.coreValue();
        if (core instanceof RecordValue rv) {
            return rv.fields();
        }
        if (core instanceof EmptyBrace) {
            return List.of();
        }
        if (positionalFieldIndex >= 0) {
            String fieldName = fields.get(positionalFieldIndex).schema().name();
            return List.of(new RecordValue.Field(fieldName, new ScopedValue(Optional.empty(), value)));
        }
        ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected a record for '" + name + "', found " + core,
                "a record", String.valueOf(core));
        return null;
    }

    static boolean isAbsent(DataValue value) {
        return value == null || value.coreValue() instanceof AbsentValue;
    }

    static boolean isFixed(FieldState state) {
        return state == FieldState.REQUIRED_FIXED || state == FieldState.OPTIONAL_FIXED;
    }

    /**
     * {@code REQUIRED_FIXED}/{@code OPTIONAL_FIXED} fields are pre-seeded from {@link
     * #fixedFieldIndices} before this can ever be reached for them. {@code ctx} is expected to still
     * be scoped to the *enclosing* record (not yet descended into the missing field) -- this itself
     * descends one level via {@link TsonReadContext#field} with a {@code null} value (there's nothing
     * for the missing field's own {@link TsonReadContext#position()} to point at), so the reported
     * {@link Diagnostic#path()} still names the missing field while its own {@link
     * Diagnostic#schemaPosition()} falls back to the enclosing record's (no per-field schema position
     * exists yet).
     */
    final Object defaultOrRequireNonFixed(int schemaIndex, TsonReadContext ctx) {
        RecordField schema = fields.get(schemaIndex).schema();
        return switch (schema.state()) {
            case REQUIRED -> {
                ctx.field(schema.name(), null).report(Diagnostic.Code.FIELD_REQUIRED,
                        "missing required field '" + schema.name() + "' for '" + name + "'",
                        "a value for '" + schema.name() + "'", "(absent)");
                yield null;
            }
            case OPTIONAL -> null;
            case REQUIRED_DEFAULT -> precomputedValue[schemaIndex];
            case REQUIRED_FIXED, OPTIONAL_FIXED -> throw new IllegalStateException("unreachable: '" + schema.name()
                    + "' is fixed and is always pre-seeded before this fallback runs");
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
}
