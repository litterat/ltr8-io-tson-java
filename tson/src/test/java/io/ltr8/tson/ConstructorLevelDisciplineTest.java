package io.ltr8.tson;
import io.ltr8.tson.base.ProcessorConfig;

import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.meta.TypeChain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Constructor level is inherited, not declared.</b> What makes an entry a constructor is that it IS-A {@code top}
 * ([TSON-SCHEMA] §4.1), and composition propagates the supertype chain, so an entry composing with a constructor is
 * one too, by construction: nothing can derive from a constructor and fail to be one.
 *
 * <p>Any schema may declare one (SPEC-FEEDBACK.md #7): the base kinds are the meta-kernel's, and every closure
 * reaching meta.tn holds them. A schema importing the one that declares a constructor applies it, and so does the
 * document declaring it, in wait order ([TSON-SCHEMA] §3.4.1): the instance waits on the constructor being ready.
 */
class ConstructorLevelDisciplineTest {

    private static final String ID = "https://example.test/m.tn";

    private static List<Diagnostic> problems(String meta, String imported, String declarations) {
        String source = """
                !!id:"https://example.test/m.tn"
                !!import:"%s"
                !!import:"%s"
                !schema {
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
                !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
                !schema {
                  c        => product & { id: identifier }
                  composed => c & { extra: identifier }
                }
                """;
        var entries = Tson.standard().resolve(source).schema().entries();

        assertTrue(TypeChain.supertypesOf(entries.get("c")).contains("top"), "the base-kind composition");
        assertTrue(TypeChain.supertypesOf(entries.get("composed")).contains("top"), "and everything deriving from it");
    }

    /** An ordinary schema composes a base kind: the kernel's {@code product} is in its namespace. */
    @Test
    void anOrdinarySchemaDeclaresAConstructor() {
        assertEquals(List.of(), problems(META, CORE, "  mine => product & { id: identifier }"));
    }

    /** A constructor an ordinary library declares is applied by a schema importing it. */
    @Test
    void anImporterAppliesALibrarysConstructor() {
        String libraryId = "https://example.test/lib.tn";
        String library = """
                !!id:"https://example.test/lib.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  steps => !namespace { key_type: identifier  value_type: text }
                }
                """;
        String importer = """
                !!id:"https://example.test/app.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://example.test/lib.tn"
                !schema {
                  plan => !steps { first => "a"  second => "b" }
                }
                """;
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(libraryId, library)))));

        assertEquals(List.of(), tson.validateSchema(library));
        assertEquals(List.of(), tson.validateSchema(importer));
    }

    /**
     * The document declaring a constructor applies it: the instance waits on the constructor being ready, whichever is
     * written first.
     */
    @Test
    void theDeclaringDocumentAppliesItsOwnConstructorInEitherOrder() {
        assertEquals(List.of(), problems(META, CORE, """
                  my_array => array ^ { ordered?: = false }
                  tags     => !my_array { element_type: text }
                """));
        assertEquals(List.of(), problems(META, CORE, """
                  tags     => !my_array { element_type: text }
                  my_array => array ^ { ordered?: = false }
                """));
    }

    /**
     * A constructor composing a base kind directly makes a new form, which a processor backs with readers and writers
     * of its own. Declaring one is sound; this library backs no form beyond the bundled ones, so an instance of one is
     * a capability gap, {@code NOT_IMPLEMENTED} at the instance, and never a verdict on the schema.
     */
    @Test
    void anInstanceOfANewFormIsAGap() {
        for (String kind : List.of("atom", "product", "sum")) {
            List<Diagnostic> problems = problems(META, CORE, """
                      c => %s & { extra: text }
                      x => !c { extra: "hello" }
                    """.formatted(kind));

            assertEquals(List.of(Diagnostic.Code.NOT_IMPLEMENTED), problems.stream().map(Diagnostic::code).toList(),
                    kind);
            assertEquals(Optional.of("/x"), problems.getFirst().schemaPointer(), kind);
        }
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
