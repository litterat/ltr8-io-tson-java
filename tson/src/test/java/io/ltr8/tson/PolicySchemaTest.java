package io.ltr8.tson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.ltr8.unicode.RestrictionLevel;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.base.policy.LimitsPolicy;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import io.ltr8.tson.tree.TsonAtom;
import io.ltr8.tson.tree.TsonRecord;
import org.junit.jupiter.api.Test;

/**
 * {@code policy.tn}, the processor policy's vocabulary ([TSON-DATA] §8.2, §9.1), against the code that applies it:
 * {@code restriction_level} is UTS #39 §5.2's six levels, which {@link RestrictionLevel} also states, and the
 * two must list the same members in the same order, so a report and the configuration that produced it agree.
 */
class PolicySchemaTest {

    @Test
    void restrictionLevelMirrorsScriptPolicyLevel() {
        EnumBody declared = assertInstanceOf(EnumBody.class, Tson.standard().bindRegistry().core()
                .resolveLinked(TsonBundledSchemas.POLICY_ID).schema().entries().get("restriction_level"));

        assertEquals(Arrays.stream(RestrictionLevel.values()).map(Enum::name).toList(), declared.members());
    }

    /**
     * {@code limits} declares the whole of [TSON-DATA] §9.1's table and [TSON-SCHEMA] §11.5's, each field optional
     * with the spec's default, so a deployment's document states only what it raises and reads back complete.
     */
    @Test
    void theLimitsTableIsCompleteAndDefaultsToTheSpecsValues() {
        var limits = (TsonRecord) Tson.standard().treeReader().withSchema(TsonBundledSchemas.POLICY_ID)
                .readAs("{ max_document_size: 33554432 }", "limits");
        Map<String, Long> expected = new LinkedHashMap<>();
        expected.put("max_depth", 64L);
        expected.put("max_token_length", 1_048_576L);
        expected.put("max_text_length", 1_048_576L);
        expected.put("max_numeric_literal_length", 4_096L);
        expected.put("max_binary_size", 16_777_216L);
        expected.put("max_document_size", 33_554_432L);
        expected.put("max_elements", 1_048_576L);
        expected.put("max_entries", 1_048_576L);
        expected.put("max_fields", 65_536L);
        expected.put("max_annotations", 64L);
        expected.put("max_values", 16_777_216L);
        expected.put("max_import_closure", 64L);
        expected.put("max_schema_entries", 65_536L);
        expected.put("max_reference_chain", 64L);
        expected.put("max_supertype_chain", 64L);
        expected.put("max_materialisation_depth", 64L);
        Map<String, Long> actual = new LinkedHashMap<>();
        limits.fields().forEach((name, value) -> actual.put(name, ((Number) ((TsonAtom) value).value()).longValue()));
        assertEquals(expected, actual);
    }

    /**
     * {@link LimitsPolicy} carries the same table as {@code limits}, component for field in the same order and
     * each default the schema's, so the policy a processor reports reads back as the record it states.
     */
    @Test
    void limitsPolicyMirrorsTheLimitsRecord() throws ReflectiveOperationException {
        var declared = (TsonRecord) Tson.standard().treeReader().withSchema(TsonBundledSchemas.POLICY_ID)
                .readAs("{}", "limits");
        Map<String, Long> schema = new LinkedHashMap<>();
        declared.fields().forEach((name, value) -> schema.put(name, ((Number) ((TsonAtom) value).value()).longValue()));
        Map<String, Long> policy = new LinkedHashMap<>();
        for (RecordComponent component : LimitsPolicy.class.getRecordComponents()) {
            String name = component.getName().replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT);
            policy.put(name, ((Number) component.getAccessor().invoke(LimitsPolicy.defaults())).longValue());
        }
        assertEquals(List.copyOf(schema.entrySet()), List.copyOf(policy.entrySet()));
    }

    /** A deployment's policy document states no data version: the tables are the processor's, not its choice. */
    @Test
    void aDeploymentsPolicyDocumentValidates() {
        assertEquals(List.of(), Tson.standard().validate("""
                !!import:"https://tson.io/2026/39/m/policy.tn"
                !policy {
                  identifier_policy: { level: HIGHLY_RESTRICTIVE  per_segment: false
                                       skeleton_distinctness: true  permitting: [] }
                  token_policy: { level: UNRESTRICTED  permitting: [] }
                  limits: { max_depth: 64 }
                }
                """));
    }
}
