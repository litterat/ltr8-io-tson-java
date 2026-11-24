package io.ltr8.tson;

import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaRefusalException;
import io.ltr8.tson.base.policy.IdentifierPolicy;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-DATA] §8.2's name hygiene at an identifier-keyed map in a namespace value a <em>schema</em> holds: the
 * map's keys are names there as in a data document. A namespace value's values bind to the consumer's own class,
 * so the linker cannot see into them; its payload is read under the identifier policy instead.
 *
 * <p>The shape is a vocabulary for interfaces, {@code iface => { methods: {method_name => …} } } held in a namespace
 * type {@code ifaces}, each interface written in a schema importing it, so every method name is a key of a map
 * read as part of that schema. The scope is isolated by Latin {@code pass} beside Cyrillic {@code раѕѕ}, each
 * single-script, as {@code IdentifierValueHygieneTest} does.
 */
class NamespaceValueHygieneTest {

    /** {@code раѕѕ}: Cyrillic, single-script, and alike with Latin {@code pass}. */
    private static final String CYRILLIC_PASS = "раѕѕ";

    /** {@code аdmin}, its first letter U+0430: a mixed-script name. */
    private static final String MIXED = "аdmin";

    private static final String IFACE_VOCABULARY_ID = "https://example.test/2026/40/iface-meta-1.tn";
    private static final String VOCABULARY = """
            !!id:"https://example.test/2026/40/iface-meta-1.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !schema {
              method_name => meta.identifier
              iface       => { methods: {method_name => text} }
              ifaces      => !meta.namespace { key_type: meta.identifier  value_type: iface }
              routes      => @meta.annotation {method_name => text}
            }""";

    private static final String DATA_ID = "https://example.test/2026/40/iface-data-1.tn";
    private static final String DATA_SCHEMA = """
            !!id:"https://example.test/2026/40/iface-data-1.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              slug  => !unicode.identifier_type { continue_add: "-" }
              method_name => slug
              iface       => { methods: {method_name => text} }
            }""";

    /** The vocabulary's {@code iface}, bound so an importing schema's payload can be read at all. */
    @Typename(name = "iface")
    public record Iface(Map<String, String> methods) {
    }

    private static Tson tson() {
        return tson(IdentifierPolicy.defaults());
    }

    private static Tson tson(IdentifierPolicy identifiers) {
        SchemaSource source = SchemaSource.ofMap(Map.of(IFACE_VOCABULARY_ID, VOCABULARY, DATA_ID, DATA_SCHEMA));
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source))
                .withIdentifierPolicy(identifiers)
                .withDataBindContext(io.ltr8.bind.DataBindContext.builder()
                        .nameBinder(DataNameBinder.ofMap(Map.of("iface", Iface.class))).build()));
    }

    private static List<Diagnostic.Code> codes(List<Diagnostic> problems) {
        return problems.stream().map(Diagnostic::code).toList();
    }

    /**
     * A schema importing the vocabulary above, declaring one interface, {@code orders}, whose method map is {@code
     * methods}.
     */
    private static String importing(String methods) {
        return importing("", methods);
    }

    private static String importing(String annotation, String methods) {
        return """
                !!id:"https://example.test/2026/40/orders-1.tn"
                !!import:"%s"
                !schema {
                  apis => %s!ifaces { orders => { methods: { %s } } }
                }""".formatted(IFACE_VOCABULARY_ID, annotation, methods);
    }

    /** The control: in a data document, the map's keys are names. */
    @Test
    void inADataDocumentTheKeysAreNames() {
        Tson tson = tson();
        String head = "!!import:\"" + DATA_ID + "\"\n";

        assertEquals(List.of(Diagnostic.Code.CONFUSABLE_NAMES),
                codes(tson.validate(head + "!iface { methods: { pass => a  " + CYRILLIC_PASS + " => b } }")));
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT),
                codes(tson.validate(head + "!iface { methods: { " + MIXED + " => a } }")));
    }

    @Test
    void aSchemaWithDistinctNamesLoads() {
        assertEquals(List.of(), tson().validateSchema(importing("pass => a  fail => b")));
    }

    /** Each refusal is reported against the declaration, under its rule's code, naming the key it refused. */
    @Test
    void inASchemaTheKeysAreNamesToo() {
        List<Diagnostic> confusable = tson().validateSchema(importing("pass => a  " + CYRILLIC_PASS + " => b"));
        assertEquals(List.of(Diagnostic.Code.CONFUSABLE_NAMES), codes(confusable));
        assertEquals(Optional.of("/apis"), confusable.getFirst().schemaPointer());
        assertTrue(confusable.getFirst().message().contains("at /orders/methods/" + CYRILLIC_PASS),
                confusable.toString());

        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT),
                codes(tson().validateSchema(importing(MIXED + " => a"))));
    }

    /** An annotation's value is as opaque to the linker as a namespace value, and is judged the same way. */
    @Test
    void anAnnotationValuesKeysAreNamesToo() {
        assertEquals(List.of(), tson().validateSchema(importing("@routes:{ pass => a } ", "pass => a")));
        assertEquals(List.of(Diagnostic.Code.CONFUSABLE_NAMES), codes(tson().validateSchema(
                importing("@routes:{ pass => a  " + CYRILLIC_PASS + " => b } ", "pass => a"))));
    }

    /** The fail-fast path throws the refusal, still telling it from a malformed schema by its type and code. */
    @Test
    void theFailFastPathThrowsARefusal() {
        SchemaRefusalException refused = assertThrows(SchemaRefusalException.class,
                () -> tson().resolve(importing(MIXED + " => a")));
        assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT, refused.code());
    }

    /** A refusal is the policy's, so a deployment that relaxes the policy admits the schema. */
    @Test
    void aRelaxedPolicyAdmitsTheSchema() {
        assertEquals(List.of(), tson(IdentifierPolicy.none())
                .validateSchema(importing("pass => a  " + CYRILLIC_PASS + " => b  " + MIXED + " => c")));
    }
}
