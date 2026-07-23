package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.schema.MetaSchema;
import io.ltr8.tson.schema.SchemaRegistry;
import io.ltr8.tson.schema.SchemaValidationException;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@code SchemaRegistry}/{@code SchemaValidator} (both in {@code tson-schema}) actually work
 * end-to-end against the real {@code meta-kernel.tn1} fixture -- this test lives here, not in
 * {@code tson-schema}'s own test tree, because it's the only module with both {@link
 * MetaKernelParser} and {@code tson-schema} available (that module has no dependency on {@code
 * tson-parser} at all).
 */
class MetaKernelSchemaRegistryTest {

    @Test
    void registersTheRealMetaKernelSchemaMaterializingEveryGenericFieldTypeRef() {
        MetaSchema raw = MetaKernelParser.parse();
        SchemaRegistry registry = new SchemaRegistry();

        TsonSchema registered = registry.register(raw);

        // 49 real declarations + one synthetic entry per distinct argument-bearing application:
        // enum's own `members: set<token>`, plus one `array<X>` per distinct X used through §5.3's
        // `[X]`/`[X]?` array-sugar field types elsewhere in the fixture (`arguments: [type_argument]?`,
        // `fields: [record_field]`, `groups: [field_group]?`, `supertypes`/`subtypes`/`parameters:
        // [type_name]?`/`[param_name]?` -- three separate `[type_name]?` uses correctly dedup to a
        // single `array_type_name_*` entry, not three -- `elements: [tuple_element]`, `variants:
        // [type_ref]`, `members: [field_name]`).
        Set<String> syntheticNames = new HashSet<>(registered.entries().keySet());
        syntheticNames.removeAll(raw.entries().keySet());
        Set<String> expectedHeads = Set.of("set_token", "array_tuple_element", "array_field_name",
                "array_type_ref", "array_type_name", "array_type_argument", "array_param_name",
                "array_field_group", "array_record_field");
        assertEquals(expectedHeads.size(), syntheticNames.size());
        for (String head : expectedHeads) {
            assertTrue(syntheticNames.stream().anyMatch(name -> name.startsWith(head + "_")),
                    "expected a synthetic entry with head '" + head + "', found: " + syntheticNames);
        }

        String setTokenName = syntheticNames.stream().filter(n -> n.startsWith("set_token_")).findFirst().orElseThrow();
        TypeDefinition enumDef = registered.entries().get("enum");
        RecordBody enumBody = (RecordBody) enumDef.body();
        RecordField membersField = enumBody.fields().stream()
                .filter(f -> f.name().equals("members"))
                .findFirst().orElseThrow();
        assertEquals(TypeRef.of(setTokenName), membersField.type());
    }

    @Test
    void registeringTheSameMetaKernelSchemaTwiceIntoTheSameRegistryThrows() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(MetaKernelParser.parse());

        assertThrows(SchemaValidationException.class, () -> registry.register(MetaKernelParser.parse()));
    }
}
