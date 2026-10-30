package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.suite.SuiteBindings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A namespace type is an instance of the kernel's {@code namespace}, so it states that constructor's facets and
 * nothing else: a value of it is the map {@code key_type}, {@code value_type} and {@code ordered} describe, and a
 * field beside them is refused by the constructor's own vocabulary.
 */
class NamespaceFacetsTest {

    private static final String META = "https://example.test/meta-facets.tn";

    private static List<Diagnostic> link(String declarations) {
        String text = """
                !!id:"https://example.test/meta-facets.tn"
                !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema {
                  method => { request?: kind<record> }
                  %s
                }
                """.formatted(declarations);
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, META)) {
                return text;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        List<Diagnostic> problems = new ArrayList<>();
        TsonCompiledMetaRegistry.withStandardLibrary(SuiteBindings.context(), source)
                .resolveLinked(META, problems::add);
        return problems;
    }

    private static Diagnostic refused(List<Diagnostic> problems, String... expected) {
        assertEquals(1, problems.size(), problems::toString);
        Diagnostic only = problems.getFirst();
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, only.code(), only::toString);
        for (String fragment : expected) {
            assertTrue(only.message().contains(fragment), "expected '" + fragment + "' in: " + only.message());
        }
        return only;
    }

    @Test
    void theFacetsAlonePass() {
        assertEquals(List.of(), link("""
                api     => !namespace { key_type: identifier  value_type: method }
                ordered => !namespace { key_type: identifier  value_type: method  ordered: true }
                """));
    }

    @Test
    void aFieldBesideTheFacetsIsRefused() {
        refused(link("""
                api => !namespace { key_type: identifier  value_type: method  extends: [x] }
                """), "'api'", "unknown field 'extends' on 'namespace'");
    }

    /** {@code key_type} is optional: absent, the host of a value supplies the family, as a record's fields are keyed. */
    @Test
    void theKeyFamilyMayBeLeftToTheHost() {
        assertEquals(List.of(), link("""
                hosted => !namespace { value_type: method }
                """));
    }
}
