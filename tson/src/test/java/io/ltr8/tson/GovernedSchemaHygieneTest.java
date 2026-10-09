package io.ltr8.tson;

import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaRefusalException;
import io.ltr8.tson.base.policy.IdentifierPolicy;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.meta.Data;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-DATA] §8.2's name hygiene at an identifier-keyed map in a <em>schema</em> governed by a meta layer: the
 * map's keys are names there as in a data document. A {@code data} body binds to the consumer's own class, so the
 * linker cannot see into it; its payload is read under the identifier policy instead.
 *
 * <p>The shape is a meta layer for interfaces, {@code iface => data & { methods: {method_name => …} } }, each
 * interface written in a schema the meta governs, so every method name is a key of a map read as part of that
 * schema. The scope is isolated by Latin {@code pass} beside Cyrillic {@code раѕѕ}, each single-script, as
 * {@code IdentifierValueHygieneTest} does.
 */
class GovernedSchemaHygieneTest {

    /** {@code раѕѕ}: Cyrillic, single-script, and alike with Latin {@code pass}. */
    private static final String CYRILLIC_PASS = "раѕѕ";

    /** {@code аdmin}, its first letter U+0430: a mixed-script name. */
    private static final String MIXED = "аdmin";

    private static final String META_ID = "https://example.test/2026/38/iface-meta-1.tn";
    private static final String META = """
            !!id:"https://example.test/2026/38/iface-meta-1.tn"
            !!meta:"https://tson.io/2026/38/m/meta-kernel.tn"
            !!import:"https://tson.io/2026/38/m/meta.tn"
            {
              method_name => identifier
              iface       => data & { methods: {method_name => text} }
              routes      => @annotation {method_name => text}
            }""";

    private static final String DATA_ID = "https://example.test/2026/38/iface-data-1.tn";
    private static final String DATA_SCHEMA = """
            !!id:"https://example.test/2026/38/iface-data-1.tn"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            {
              identifier  => !identifier_type { continue_add: "-" }
              method_name => identifier
              iface       => { methods: {method_name => text} }
            }""";

    /** The meta's {@code iface}, bound so a governed schema's payload can be read at all. */
    @Typename(name = "iface")
    public record Iface(Map<String, String> methods) implements Data {
    }

    private static Tson tson() {
        return tson(IdentifierPolicy.defaults());
    }

    private static Tson tson(IdentifierPolicy identifiers) {
        SchemaSource source = SchemaSource.ofMap(Map.of(META_ID, META, DATA_ID, DATA_SCHEMA));
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source))
                .withIdentifierPolicy(identifiers)
                .withMetaNameBinder(DataNameBinder.ofMap(Map.of("iface", Iface.class))));
    }

    private static List<Diagnostic.Code> codes(List<Diagnostic> problems) {
        return problems.stream().map(Diagnostic::code).toList();
    }

    /** A schema governed by the meta above, declaring one interface whose method map is {@code methods}. */
    private static String governed(String methods) {
        return governed("", methods);
    }

    private static String governed(String annotation, String methods) {
        return """
                !!id:"https://example.test/2026/38/orders-1.tn"
                !!meta:"%s"
                {
                  orders => %s!iface { methods: { %s } }
                }""".formatted(META_ID, annotation, methods);
    }

    /** The control: in a data document, the map's keys are names. */
    @Test
    void inADataDocumentTheKeysAreNames() {
        Tson tson = tson();
        String head = "!!schema:\"" + DATA_ID + "\"\n";

        assertEquals(List.of(Diagnostic.Code.CONFUSABLE_NAMES),
                codes(tson.validate(head + "!iface { methods: { pass => a  " + CYRILLIC_PASS + " => b } }")));
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT),
                codes(tson.validate(head + "!iface { methods: { " + MIXED + " => a } }")));
    }

    @Test
    void aGovernedSchemaWithDistinctNamesLoads() {
        assertEquals(List.of(), tson().validateSchema(governed("pass => a  fail => b")));
    }

    /** Each refusal is reported against the declaration, under its rule's code, naming the key it refused. */
    @Test
    void inAGovernedSchemaTheKeysAreNamesToo() {
        List<Diagnostic> confusable = tson().validateSchema(governed("pass => a  " + CYRILLIC_PASS + " => b"));
        assertEquals(List.of(Diagnostic.Code.CONFUSABLE_NAMES), codes(confusable));
        assertEquals(Optional.of("/orders"), confusable.getFirst().schemaPointer());
        assertTrue(confusable.getFirst().message().contains("at /methods/" + CYRILLIC_PASS), confusable.toString());

        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT),
                codes(tson().validateSchema(governed(MIXED + " => a"))));
    }

    /** An annotation's value is as opaque to the linker as a data body, and is judged the same way. */
    @Test
    void anAnnotationValuesKeysAreNamesToo() {
        assertEquals(List.of(), tson().validateSchema(governed("@routes:{ pass => a } ", "pass => a")));
        assertEquals(List.of(Diagnostic.Code.CONFUSABLE_NAMES), codes(tson().validateSchema(
                governed("@routes:{ pass => a  " + CYRILLIC_PASS + " => b } ", "pass => a"))));
    }

    /** The fail-fast path throws the refusal, still telling it from a malformed schema by its type and code. */
    @Test
    void theFailFastPathThrowsARefusal() {
        SchemaRefusalException refused = assertThrows(SchemaRefusalException.class,
                () -> tson().resolve(governed(MIXED + " => a")));
        assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT, refused.code());
    }

    /** A refusal is the policy's, so a deployment that relaxes the policy admits the schema. */
    @Test
    void aRelaxedPolicyAdmitsTheSchema() {
        assertEquals(List.of(), tson(IdentifierPolicy.none())
                .validateSchema(governed("pass => a  " + CYRILLIC_PASS + " => b  " + MIXED + " => c")));
    }
}
