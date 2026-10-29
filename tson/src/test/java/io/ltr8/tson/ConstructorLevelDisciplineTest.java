package io.ltr8.tson;
import io.ltr8.tson.base.ProcessorConfig;

import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.meta.TypeChain;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Constructor level is inherited, not declared.</b> What makes an entry a constructor is that it IS-A {@code top}
 * ([TSON-SCHEMA] §4.1), and composition propagates the supertype chain, so an entry composing with a constructor is
 * one too, by construction: nothing can derive from a constructor and fail to be one.
 *
 * <p>Where a constructor may be declared is §2.2.2's: only in a schema the meta-kernel governs. An ordinary schema
 * meets §2.2.3 first, since a base kind is the meta-kernel's and an ordinary schema may not import a kernel-governed
 * one, so it has no base kind to compose with.
 */
class ConstructorLevelDisciplineTest {

    private static final String ID = "https://example.test/m.tn";

    private static List<Diagnostic> problems(String meta, String imported, String declarations) {
        String source = """
                !!id:"https://example.test/m.tn"
                !!meta:"%s"
                !!import:"%s"
                {
                %s }
                """.formatted(meta, imported, declarations);
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(ID, source)))))
                .validateSchema(source);
    }

    private static final String KERNEL = "https://tson.io/2026/39/m/meta-kernel.tn";
    private static final String META = "https://tson.io/2026/39/m/meta.tn";
    private static final String CORE = "https://tson.io/2026/39/m/core.tn";

    /** In a meta-schema, deriving from a constructor is ordinary — and the result is one. */
    @Test
    void aMetaSchemaMayDeriveFromAConstructor() {
        assertEquals(List.of(), problems(KERNEL, KERNEL, """
                  c        => product & { id: identifier }
                  composed => c & { extra: identifier }
                """));
    }

    /** Refining a kernel constructor is the same case. */
    @Test
    void aMetaSchemaMayRefineAConstructor() {
        assertEquals(List.of(), problems(KERNEL, KERNEL, "  refined => array ^ { ordered?: = false }"));
    }

    /** The derived entry IS-A {@code top}, so it is constructor level and applicable. */
    @Test
    void theDerivedEntryIsItselfConstructorLevel() {
        String source = """
                !!id:"https://example.test/m.tn"
                !!meta:"https://tson.io/2026/39/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
                {
                  c        => product & { id: identifier }
                  composed => c & { extra: identifier }
                }
                """;
        var entries = Tson.standard().resolve(source).schema().entries();

        assertTrue(TypeChain.supertypesOf(entries.get("c")).contains("top"), "the base-kind composition");
        assertTrue(TypeChain.supertypesOf(entries.get("composed")).contains("top"), "and everything deriving from it");
    }

    /**
     * An ordinary schema cannot reach constructor level by composing its way there: a base kind is the
     * meta-kernel's, and importing a kernel-governed schema into an ordinary one is refused (§2.2.3), in one
     * diagnostic naming the import rather than every declaration that would have used it.
     */
    @Test
    void anOrdinarySchemaHasNoBaseKindToComposeWith() {
        List<Diagnostic> problems = problems(META, KERNEL, "  mine => product & { id: identifier }");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code());
        assertTrue(problems.getFirst().message().contains("governing level"), problems::toString);
        assertTrue(problems.getFirst().message().contains("§2.2.3"), problems::toString);
    }

    /** An ordinary type over an ordinary type is untouched — the overwhelmingly common case. */
    @Test
    void anOrdinaryDerivationIsUnaffected() {
        assertEquals(List.of(), problems(META, CORE, """
                  base    => { id: text }
                  derived => base & { extra: text }
                """));
    }
}
