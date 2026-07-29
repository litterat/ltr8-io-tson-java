package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.TsonValueReader;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.RecordValue;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DOM mode's own {@code record} reader -- reads a record-shaped value into a plain {@code
 * Map<String, Object>}, one entry per schema field. {@code resolver} turns a field's own type-ref
 * name into the {@link TsonValueReader} that reads its value.
 *
 * <p><b>{@link #read} walks the incoming data once, not the schema's own field list per field, and
 * needs no separate "already filled" tracker.</b> Every {@code REQUIRED_FIXED}/{@code
 * OPTIONAL_FIXED} field's own precomputed value ({@link RecordAbstractReader#precomputedValue}) is
 * written into {@code result} up front, before the data is ever consulted -- a fixed field's value
 * is immutable, so data can never override it, and pre-seeding it means the ordinary data pass never
 * needs to special-case "is this field fixed" at all: {@code result.containsKey(name)} already
 * being {@code true} makes it skip straight past, the same way it skips an already-filled duplicate.
 * The one data pass runs *backward*, so the first occurrence found for a still-unfilled field is
 * genuinely its last in source order (§2.5's "last value wins"). A schema field neither fixed nor
 * mentioned by the data still needs its own required-or-default handling, but that second pass over
 * the schema's own field list only runs when {@code result} came out smaller than the full field
 * count -- a document supplying every non-fixed field skips it entirely. No {@code boolean[]}
 * tracker anywhere: {@code result} itself, via {@code containsKey}, is the only "have I resolved
 * this field yet" signal needed, at the cost of a {@code Map} lookup instead of an array read. One
 * consequence worth knowing: the returned map's own iteration order is no longer guaranteed to match
 * schema-declaration order (fixed fields land first, then whatever order the backward data scan
 * finds the rest, defaulted ones last) -- nothing in this codebase relies on a DOM record's own key
 * order today, so this trades that incidental property for the allocation savings.
 *
 * <p>Everything shared with {@link RecordBindReader} -- the compiled field list, the name lookup,
 * unwrapping a record-shaped {@link DataValue}, precomputing default/fixed values -- lives on
 * {@link RecordAbstractReader}; this class holds only what's genuinely different about producing a
 * plain {@code Map} instead of a bound object.
 */
final class RecordDomReader extends RecordAbstractReader<Map<String, Object>> {

    public RecordDomReader(String name, RecordBody body, ValueReaderResolver resolver) {
        super(name, body, resolver);
    }

    /**
     * Validates {@code typeDefinition} is record-shaped before ever constructing a {@link
     * RecordDomReader} for it -- no {@link io.ltr8.bind.DataBindContext} needed, since DOM mode
     * targets no Java type. When {@code typeDefinition.subtypes()} is non-empty, wraps the result in
     * a {@link VariantSchemaReader} instead of returning it directly -- {@code ownParser} is always
     * reachable here (DOM mode has nothing resembling {@link RecordBindReader.Factory}'s "pure
     * marker interface" case, since an empty record body reads to a perfectly ordinary empty {@code
     * Map}), so this is the simpler of the two record factories' own subtype handling.
     */
    public static final class Factory implements ValueReaderFactory {

        @Override
        public TsonValueReader<?> create(String name, TypeDefinition typeDefinition, ValueReaderResolver resolver) {
            if (!(typeDefinition.body() instanceof RecordBody body)) {
                throw new IllegalArgumentException(
                        "'" + name + "' is not record-shaped: " + typeDefinition.body());
            }
            RecordDomReader ownParser = new RecordDomReader(name, body, resolver);
            if (typeDefinition.subtypes().isEmpty()) {
                return ownParser;
            }
            return new VariantSchemaReader(name, ownParser, typeDefinition.subtypes(), resolver);
        }
    }

    @Override
    public Map<String, Object> read(DataValue value) {
        List<RecordValue.Field> dataFields = dataFields(value);
        Map<String, Object> result = new LinkedHashMap<>();

        for (int schemaIndex : fixedFieldIndices) {
            result.put(fields.get(schemaIndex).schema().name(), precomputedValue[schemaIndex]);
        }

        for (int i = dataFields.size() - 1; i >= 0; i--) {
            RecordValue.Field dataField = dataFields.get(i);
            Integer schemaIndex = fieldIndex.get(dataField.name());
            if (schemaIndex == null) {
                continue;
            }
            CompiledField field = fields.get(schemaIndex);
            String fieldName = field.schema().name();
            if (result.containsKey(fieldName)) {
                continue;
            }
            DataValue fieldValue = dataField.value().value();
            result.put(fieldName,
                    isAbsent(fieldValue) ? defaultOrRequireNonFixed(schemaIndex) : field.parser().read(fieldValue));
        }

        if (result.size() < fields.size()) {
            for (int i = 0; i < fields.size(); i++) {
                String fieldName = fields.get(i).schema().name();
                if (!result.containsKey(fieldName)) {
                    result.put(fieldName, defaultOrRequireNonFixed(i));
                }
            }
        }
        return result;
    }
}
