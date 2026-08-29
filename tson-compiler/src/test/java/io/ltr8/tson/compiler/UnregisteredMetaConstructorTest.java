package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A meta-schema declaring a constructor this library has no Java class for. The schema is <b>correct</b> --
 * the constructor is declared, and a governed schema applies it exactly as written -- so the diagnostic must
 * say what is actually missing.
 *
 * <p>It used to say the opposite. {@code TsonCompiledMetaSchema} paired each constructor with a
 * {@code ValueReaderFactory} and <em>dropped</em> the constructor when there was none, so a governing
 * meta-schema compiled and registered looking healthy while missing a constructor it declares, and the
 * complaint landed against a different document: the first governed schema to apply it was told
 * "'operation' is not a constructor 'meta-http.tn' declares", which is untrue and unactionable.
 */
class UnregisteredMetaConstructorTest {

    private static final Map<String, String> DOCUMENTS = new LinkedHashMap<>();

    static {
        DOCUMENTS.put("https://example.test/meta-unreg.tn", """
                !!id:"https://example.test/meta-unreg.tn"
                !!meta:"https://tson.io/2026/34/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/34/m/meta.tn"
                {
                  operation => ~top & { path: text }
                }
                """);
        DOCUMENTS.put("https://example.test/api-unreg.tn", """
                !!id:"https://example.test/api-unreg.tn"
                !!meta:"https://example.test/meta-unreg.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                {
                  search => !operation { path: "/search" }
                }
                """);
    }

    private static TsonCompiledMetaRegistry core() {
        TsonSchemaSource source = uri -> {
            for (Map.Entry<String, String> document : DOCUMENTS.entrySet()) {
                if (TsonCanonicalIdentity.sameIdentity(uri, document.getKey())) {
                    return document.getValue();
                }
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        return TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
    }

    /** The meta-schema itself is fine, and stays usable -- only applying the constructor can fail. */
    @Test
    void theMetaSchemaStillLoadsAndStillDeclaresTheConstructor() {
        assertTrue(core().resolveLinked("https://example.test/meta-unreg.tn").schema().entries()
                .containsKey("operation"));
        assertTrue(core().loadMeta("https://example.test/meta-unreg.tn").schema().entries()
                .containsKey("operation"));
    }

    /** And applying it names the gap, rather than denying that the meta-schema declares it. */
    @Test
    void applyingItNamesTheMissingClassRatherThanDenyingTheConstructor() {
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> core().resolveLinked("https://example.test/api-unreg.tn"));

        assertTrue(thrown.getMessage().contains("no bound Java class for 'operation'"), thrown.getMessage());
        assertFalse(thrown.getMessage().contains("is not a constructor"),
                "the meta-schema does declare it: " + thrown.getMessage());
    }
}
