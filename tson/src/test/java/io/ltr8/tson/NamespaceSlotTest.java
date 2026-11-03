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
 * The kernel's schema map and a record's fields at their named positions: the schema map is a value of the
 * namespace type {@code schema}, held as a {@link Namespace}, and a record's {@code fields} a value of {@code
 * field_map}, an ordered map keyed by text. At a named position either writes as the map alone -- {@code fields: {
 * ... }} -- and binds back as what the slot's host declares.
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
                .withDataBindContext(SuiteBindings.context()));
    }

    private static RecordBody person(Tson tson) {
        return assertInstanceOf(RecordBody.class, tson.resolve(SCHEMA).schema().entries().get("person"));
    }

    @Test
    void theSchemaMapIsANamespaceValueAndARecordsFieldsAnOrderedMap() {
        TsonSchema schema = tson().resolve(SCHEMA).schema();
        assertEquals(TsonSchema.SCHEMA, schema.entries().type());
        RecordBody person = assertInstanceOf(RecordBody.class, schema.entries().get("person"));
        assertEquals(List.of("name", "age", "nick"), List.copyOf(person.fields().keySet()));
    }

    @Test
    void aRecordsFieldsWriteAsTheMapAndBindBackInOrder() {
        Tson tson = tson();
        RecordBody person = person(tson);
        String written = tson.objectWriter().toTson(person);
        assertTrue(written.replaceAll("\\s+", " ").contains("fields: { \"name\" => "), written);
        assertFalse(written.contains("!field_map"), written);

        Map<String, Object> read = ResolvedForm.readResolved(tson, """
                !schema { person => !meta.record { fields: {
                  name => { type: text }
                  age  => { type: integer  optional: true }
                  nick => { type: text  optional: true }
                } } }""", List.of(TsonBundledSchemas.CORE_ID));
        RecordBody back = assertInstanceOf(RecordBody.class, read.get("person"));
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
        assertEquals(person.fields(), reversed.fields(), "a map's own equality leaves order to its owner");
        assertFalse(person.equals(reversed), "field_map is ordered, so the record compares its fields in order");
    }

    /** A key's annotations are part of the map they are written in, and output writes them ahead of the key. */
    @Test
    void aFieldKeysAnnotationsWriteAheadOfTheKey() {
        Tson tson = tson();
        RecordBody person = person(tson);
        RecordBody marked = person.withFields(person.fields().withAnnotations("age",
                io.ltr8.annotation.Annotations.of(List.of(io.ltr8.annotation.Annotation.of("note")))));
        String written = tson.objectWriter().toTson(marked).replaceAll("\\s+", " ");
        assertTrue(written.contains("@note \"age\" => "), written);
        assertFalse(written.contains("@note @note"), written);
    }

    @Test
    void aRootReadAskedForANamespaceDeliversTheConstructorsInstance() {
        Tson tson = tson();
        Namespace<?> read = tson.objectReader().withSchema(TsonBundledSchemas.META_ID)
                .readAs("{ person => !meta.record { fields: { name => !meta.record_field { type: text } } } }",
                        "meta.schema", Namespace.class);
        assertEquals(TsonSchema.SCHEMA, read.type());
        assertInstanceOf(RecordBody.class, read.get("person"));
    }
}
