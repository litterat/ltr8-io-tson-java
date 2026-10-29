package io.ltr8.tson;

import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.suite.SuiteBindings;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kernel's two namespace types at their named positions: the schema map is a value of {@code schema} and a
 * record's {@code fields} a value of {@code field_map}, so the model holds both as {@link Namespace} values. At a
 * named position the value writes as the map alone -- {@code fields: { ... }}, the slot's type naming the
 * constructor -- and binds back as a {@code Namespace} of the slot's type.
 */
class NamespaceSlotTest {

    private static final String SCHEMA = """
            !!id:"https://example.test/slots.tn"
            !!import:"%s"
            !!import:"%s"
            !schema {
              person => { name: text  age?: integer  nick?: text }
            }""".formatted(TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    /** A fresh instance, binding the schema model as resolved output does. */
    private static Tson tson() {
        return Tson.of(ProcessorConfig.defaults()
                .withDataBindContext(SuiteBindings.context())
                .withMetaNameBinder(SuiteBindings.names()));
    }

    private static RecordBody person(Tson tson) {
        return assertInstanceOf(RecordBody.class, tson.resolve(SCHEMA).schema().entries().get("person"));
    }

    @Test
    void theSchemaMapAndARecordsFieldsAreValuesOfTheKernelsNamespaceTypes() {
        TsonSchema schema = tson().resolve(SCHEMA).schema();
        assertEquals(TsonSchema.SCHEMA, schema.entries().type());
        RecordBody person = assertInstanceOf(RecordBody.class, schema.entries().get("person"));
        assertEquals(RecordBody.FIELD_MAP, person.fields().type());
        assertEquals(List.of("name", "age", "nick"), List.copyOf(person.fields().keySet()));
    }

    @Test
    void aRecordsFieldsWriteAsTheMapAndBindBackAsANamespaceOfTheSlotsType() {
        Tson tson = tson();
        RecordBody person = person(tson);
        String written = tson.objectWriter().toTson(person);
        assertTrue(written.replaceAll("\\s+", " ").contains("fields: { name => "), written);
        assertFalse(written.contains("!field_map"), written);

        Map<String, Top> read = ResolvedForm.readResolved(tson, """
                !schema { person => !record { fields: {
                  name => { type: text }
                  age  => { type: integer  optional: true }
                  nick => { type: text  optional: true }
                } } }""");
        RecordBody back = assertInstanceOf(RecordBody.class, read.get("person"));
        assertEquals(RecordBody.FIELD_MAP, back.fields().type());
        assertEquals(List.of("name", "age", "nick"), List.copyOf(back.fields().keySet()));
        assertEquals(ResolvedForm.rendered(person.fields()), ResolvedForm.rendered(back.fields()));
    }

    @Test
    void fieldsInAnotherOrderAreAnotherRecord() {
        RecordBody person = person(tson());
        SequencedMap<String, RecordField> fields = new LinkedHashMap<>();
        List.copyOf(person.fields().entrySet()).reversed().forEach(field -> fields.put(field.getKey(),
                field.getValue()));
        RecordBody reversed = person.withFields(RecordBody.fieldMap(fields));
        assertEquals(person.fields(), reversed.fields(), "field_map's own equality leaves order to its owner");
        assertFalse(person.equals(reversed), "field_map is ordered, so the record compares its fields in order");
    }

    @Test
    void aRootReadAskedForANamespaceDeliversTheConstructorsInstance() {
        Tson tson = tson();
        Namespace<?> read = tson.objectReader().withSchema(TsonBundledSchemas.META_ID)
                .readAs("{ person => !record { fields: { name => !record_field { type: text } } } }", "schema",
                        Namespace.class);
        assertEquals(TsonSchema.SCHEMA, read.type());
        assertInstanceOf(RecordBody.class, read.get("person"));
    }
}
