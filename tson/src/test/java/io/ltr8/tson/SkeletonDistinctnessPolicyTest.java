package io.ltr8.tson;

import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.policy.IdentifierPolicy;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [TSON-DATA] §8.2's skeleton distinctness is relaxable on its own, as §8.2 requires of each mechanism, and the
 * one switch reaches every scope the rule runs over: a schemaless record's field names, a schema's declared
 * names and field names, and the keys of an identifier-keyed map in tree and bind reads alike.
 *
 * <p>Each scope is shown with a pair that only this mechanism refuses -- Latin {@code pass} beside an all-Cyrillic
 * look-alike, each single-script -- so the per-name rules have nothing to say and the switch is all that moves.
 */
class SkeletonDistinctnessPolicyTest {

    /** Latin {@code pass}'s all-Cyrillic look-alike: U+0440 U+0430 U+0455 U+0455. */
    private static final String CYRILLIC_PASS = "раѕѕ";

    private static final IdentifierPolicy LOOK_ALIKES = IdentifierPolicy.defaults().withSkeletonDistinctness(false);

    private static final String ID = "https://example.test/skeletons-1.tn";
    private static final String SCHEMA = """
            !!id:"https://example.test/skeletons-1.tn"
            !!meta:"https://tson.io/2026/37/m/meta.tn"
            !!import:"https://tson.io/2026/37/m/core.tn"
            {
              identifier => !identifier_type { continue_add: "-" }
              handlers => { identifier => text }
              registry => { handlers: { identifier => text } }
            }
            """;

    @Typename(name = "registry")
    public record Registry(Map<String, String> handlers) {
    }

    private static Tson tson(IdentifierPolicy identifiers) {
        SchemaSource source = uri -> {
            if (uri.equals(ID)) {
                return SCHEMA;
            }
            throw new SchemaFetchException(uri, SchemaFetchException.Reason.NOT_FOUND,
                    "this fixture serves only " + ID, null);
        };
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source))
                .withIdentifierPolicy(identifiers)
                .withDataBindContext(DataBindContext.builder()
                        .nameBinder(DataNameBinder.ofMap(Map.of("registry", Registry.class))
                                .orElse(SchemaMetaNameBinder.INSTANCE))
                        .registerAtoms(AtomContext.hostTypes()).build()));
    }

    private static List<Diagnostic.Code> codes(List<Diagnostic> problems) {
        return problems.stream().map(Diagnostic::code).toList();
    }

    @Test
    void aSchemalessRecordsFieldNames() {
        String document = "{ pass: 1  " + CYRILLIC_PASS + ": 2 }";
        assertEquals(List.of(Diagnostic.Code.CONFUSABLE_NAMES), codes(tson(IdentifierPolicy.defaults())
                .validate(document)));
        assertEquals(List.of(), tson(LOOK_ALIKES).validate(document));
    }

    @Test
    void aSchemasFieldNamesAndDeclaredNames() {
        for (String declarations : List.of("rec => { pass: text  " + CYRILLIC_PASS + ": text }",
                "pass => text  " + CYRILLIC_PASS + " => text")) {
            String schema = """
                    !!id:"https://example.test/skeletons-2.tn"
                    !!meta:"https://tson.io/2026/37/m/meta.tn"
                    !!import:"https://tson.io/2026/37/m/core.tn"
                    { %s }
                    """.formatted(declarations);
            assertEquals(List.of(Diagnostic.Code.CONFUSABLE_NAMES),
                    codes(tson(IdentifierPolicy.defaults()).validateSchema(schema)), declarations);
            assertEquals(List.of(), tson(LOOK_ALIKES).validateSchema(schema), declarations);
        }
    }

    @Test
    void theKeysOfAnIdentifierKeyedMap() {
        String document = "!!schema:\"" + ID + "\"\n!handlers { pass => a  " + CYRILLIC_PASS + " => b }";
        assertEquals(List.of(Diagnostic.Code.CONFUSABLE_NAMES), codes(tson(IdentifierPolicy.defaults())
                .validate(document)));
        assertEquals(List.of(), tson(LOOK_ALIKES).validate(document));
    }

    @Test
    void theSameKeysBoundToAnObject() {
        String document = "!!schema:\"" + ID + "\"\n!registry { handlers: { pass => a  " + CYRILLIC_PASS
                + " => b } }";
        DiagnosticsCollector strict = new DiagnosticsCollector();
        tson(IdentifierPolicy.defaults()).objectReader().withDiagnostics(strict).read(document, Registry.class);
        assertEquals(List.of(Diagnostic.Code.CONFUSABLE_NAMES), codes(strict.diagnostics()));

        DiagnosticsCollector relaxed = new DiagnosticsCollector();
        Registry read = tson(LOOK_ALIKES).objectReader().withDiagnostics(relaxed).read(document, Registry.class);
        assertEquals(List.of(), relaxed.diagnostics());
        assertEquals(2, read.handlers().size());
    }
}
