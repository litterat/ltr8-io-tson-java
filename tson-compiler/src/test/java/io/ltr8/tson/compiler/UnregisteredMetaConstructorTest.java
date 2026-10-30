package io.ltr8.tson.compiler;

import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.tree.TsonRecord;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A schema declaring a constructor this library has no Java class for. The schema is <b>correct</b> -- the
 * constructor is declared, and a schema importing it applies it exactly as written -- and so is the importer: an
 * instance of a constructor whose instances are no types ({@code operation} composes {@code top}, not {@code type})
 * is held as the value it is, as a tree where no class binds it, so both schemas load and validate without host
 * classes. A schema obtained to bind refuses it ({@code SameDocumentConstructorTest} in the front door).
 */
class UnregisteredMetaConstructorTest {

    private static final Map<String, String> DOCUMENTS = new LinkedHashMap<>();

    static {
        DOCUMENTS.put("https://example.test/meta-unreg.tn", """
                !!id:"https://example.test/meta-unreg.tn"
                !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema {
                  operation => top & { path: text }
                }
                """);
        DOCUMENTS.put("https://example.test/api-unreg.tn", """
                !!id:"https://example.test/api-unreg.tn"
                !!import:"https://example.test/meta-unreg.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  search => !operation { path: "/search" }
                }
                """);
    }

    private static TsonCompiledMetaRegistry core() {
        SchemaSource source = uri -> {
            for (Map.Entry<String, String> document : DOCUMENTS.entrySet()) {
                if (CanonicalIdentity.sameIdentity(uri, document.getKey())) {
                    return document.getValue();
                }
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        return TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
    }

    /** The declaring schema itself is fine, and stays usable. */
    @Test
    void theMetaSchemaStillLoadsAndStillDeclaresTheConstructor() {
        assertTrue(core().resolveLinked("https://example.test/meta-unreg.tn").schema().entries()
                .containsKey("operation"));
        assertTrue(core().loadMeta("https://example.test/meta-unreg.tn").schema().entries()
                .containsKey("operation"));
    }

    /** And applying it holds the instance as a tree, rather than refusing the schema for a missing class. */
    @Test
    void applyingItHoldsTheInstanceAsATree() {
        Top search = core().resolveLinked("https://example.test/api-unreg.tn").schema().entries().get("search");

        ValueEntry entry = assertInstanceOf(ValueEntry.class, search);
        assertEquals("operation", entry.type());
        TsonRecord tree = assertInstanceOf(TsonRecord.class, entry.value());
        assertEquals("/search", tree.get("path").asString().orElseThrow());
    }
}
