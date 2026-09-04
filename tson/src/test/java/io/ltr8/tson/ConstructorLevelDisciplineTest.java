package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Constructor level is inherited, not declared.</b> With the {@code ~} marker gone, what makes an entry a
 * constructor is that it IS-A {@code top} ([TSON-SCHEMA] §4.1) — and composition propagates the supertype
 * chain, so an entry composing with a constructor is one too, by construction. There is no level discipline
 * rule left to enforce: nothing can derive from a constructor and fail to be one.
 *
 * <p><b>What used to be protected is protected by §2.2.2 instead, and better.</b> The old rule refused an
 * unmarked declaration deriving from a constructor, anywhere. The eligibility rule refuses <em>any</em>
 * declaration of an entry that IS-A {@code top} outside a meta-kernel-governed schema — so an ordinary type
 * library still cannot reach constructor level by composing its way there, and a meta-schema, where
 * extending a constructor's vocabulary is the whole point, simply may.
 */
class ConstructorLevelDisciplineTest {

    private static final String ID = "https://example.test/m.tn";

    private static List<Diagnostic> problems(String meta, String declarations) {
        String source = """
                !!id:"https://example.test/m.tn"
                !!meta:"%s"
                !!import:"https://tson.io/2026/35/m/meta-kernel.tn"
                {
                %s }
                """.formatted(meta, declarations);
        return Tson.builder().schemaSource(TsonSchemaSource.ofMap(Map.of(ID, source))).build()
                .validateSchema(source);
    }

    private static final String KERNEL = "https://tson.io/2026/35/m/meta-kernel.tn";
    private static final String META = "https://tson.io/2026/35/m/meta.tn";

    /** In a meta-schema, deriving from a constructor is ordinary — and the result is one. */
    @Test
    void aMetaSchemaMayDeriveFromAConstructor() {
        assertEquals(List.of(), problems(KERNEL, """
                  c        => product & { id: identifier }
                  composed => c & { extra: identifier }
                """));
    }

    /** Refining a kernel constructor is the same case. */
    @Test
    void aMetaSchemaMayRefineAConstructor() {
        assertEquals(List.of(), problems(KERNEL, "  refined => array ^ { unordered: = true }"));
    }

    /**
     * The derived entry IS-A {@code top}, so it is constructor level and applicable — which is the whole
     * point of extending a vocabulary, and what the old rule refused for want of a repeated marker.
     */
    @Test
    void theDerivedEntryIsItselfConstructorLevel() {
        String source = """
                !!id:"https://example.test/m.tn"
                !!meta:"https://tson.io/2026/35/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/35/m/meta-kernel.tn"
                {
                  c        => product & { id: identifier }
                  composed => c & { extra: identifier }
                }
                """;
        var entries = Tson.builder().build().resolve(source).schema().entries();

        assertTrue(entries.get("c").supertypes().contains("top"), "the base-kind composition");
        assertTrue(entries.get("composed").supertypes().contains("top"), "and everything deriving from it");
    }

    /**
     * §2.2.2 is what an ordinary schema meets, and it now catches the case level discipline used to: an
     * application schema cannot reach constructor level by composing its way there, because declaring an
     * entry that IS-A {@code top} is the thing it may not do.
     */
    @Test
    void anOrdinarySchemaMayNotDeclareOneAtAll() {
        List<Diagnostic> problems = problems(META, "  mine => product & { id: identifier }");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code());
        assertTrue(problems.getFirst().message().contains("IS-A 'top'"), problems::toString);
        assertTrue(problems.getFirst().message().contains("§2.2.2"), problems::toString);
    }

    /** An ordinary type over an ordinary type is untouched — the overwhelmingly common case. */
    @Test
    void anOrdinaryDerivationIsUnaffected() {
        assertEquals(List.of(), problems(META, """
                  base    => { id: identifier }
                  derived => base & { extra: identifier }
                """));
    }
}
