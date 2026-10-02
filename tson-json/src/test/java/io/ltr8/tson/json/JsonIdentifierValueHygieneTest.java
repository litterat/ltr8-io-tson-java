package io.ltr8.tson.json;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.policy.IdentifierPolicy;
import io.ltr8.tson.base.io.ByteSource;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [TSON-DATA] §8.2's name hygiene at a value whose type is an identifier family, in this encoding: a member name
 * keying an identifier-keyed map, and a string at an identifier-typed member, meet the per-name rules, and the keys
 * of such a map are one naming scope ([TSON-SCHEMA] §11.4). The same verdicts as TSON text, from the same rules.
 *
 * <p>Confusable characters are written as escapes, never literally.
 */
class JsonIdentifierValueHygieneTest {

    /** {@code аdmin}, its first letter U+0430: a mixed-script name. */
    private static final String MIXED = "аdmin";

    /** {@code раѕѕ} in Cyrillic: single-script, and read alike with Latin {@code pass}. */
    private static final String CYRILLIC_PASS = "раѕѕ";

    private static final String ID = "https://example.test/json-names-1.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/json-names-1.tn"
            !!meta:"https://tson.io/2026/37/m/meta.tn"
            !!import:"https://tson.io/2026/37/m/core.tn"
            {
              identifier => !identifier_type { continue_add: "-" }
              handlers => { identifier => text }
              labels => { text => text }
              route => { name: identifier }
            }
            """;

    private static final Json JSON = json(IdentifierPolicy.defaults());

    private static Json json(IdentifierPolicy identifiers) {
        Tson tson = Tson.standard();
        tson.resolve(SCHEMA);
        return Json.of(ProcessorConfig.defaults().withIdentifierPolicy(identifiers)).withSchemas(tson.schemaRegistry());
    }

    private static List<Diagnostic> read(String typeName, String source) {
        return read(JSON, typeName, source);
    }

    private static List<Diagnostic> read(Json json, String typeName, String source) {
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of(source)) {
            json.treeReader().withDiagnostics(receiver).withSchema(ID).readAs(bytes, typeName);
        }
        return problems;
    }

    private static Diagnostic only(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), problems.toString());
        return problems.getFirst();
    }

    @Test
    void aMixedScriptKeyIsRefusedAsAName() {
        Diagnostic refused = only(read("handlers", "{\"admin\": \"a\", \"" + MIXED + "\": \"b\"}"));
        assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT, refused.code());
        assertEquals(Optional.of("/" + MIXED), refused.path());
    }

    @Test
    void twoKeysThatReadAlikeAreRefusedAtTheSecond() {
        Diagnostic refused = only(read("handlers", "{\"pass\": \"a\", \"" + CYRILLIC_PASS + "\": \"b\"}"));
        assertEquals(Diagnostic.Code.CONFUSABLE_NAMES, refused.code());
        assertEquals(Optional.of("/" + CYRILLIC_PASS), refused.path());
    }

    /** Skeleton distinctness is the policy's to switch off, and the one switch reaches this scope too. */
    @Test
    void keysThatReadAlikeAreAdmittedWithoutSkeletonDistinctness() {
        assertEquals(List.of(), read(json(IdentifierPolicy.defaults().withSkeletonDistinctness(false)), "handlers",
                "{\"pass\": \"a\", \"" + CYRILLIC_PASS + "\": \"b\"}"));
    }

    @Test
    void aTextKeyedMapIsNotAScope() {
        assertEquals(List.of(), read("labels", "{\"pass\": \"a\", \"" + CYRILLIC_PASS + "\": \"b\", \"" + MIXED
                + "\": \"c\"}"));
    }

    @Test
    void anIdentifierTypedMemberValueIsAName() {
        Diagnostic refused = only(read("route", "{\"name\": \"" + MIXED + "\"}"));
        assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT, refused.code());
        assertEquals(Optional.of("/name"), refused.path());
    }
}
