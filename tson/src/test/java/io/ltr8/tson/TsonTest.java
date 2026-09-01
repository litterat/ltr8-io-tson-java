package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonObjectReader;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same real scenario {@code TinySchemaImportsCoreTn1Test} proves by hand-assembling
 * {@code TsonSchemaRegistry}/{@code TsonCompiledMetaRegistry} directly -- a small, user-defined schema
 * importing core.tn -- but through this class's own public front door instead, confirming the builder
 * genuinely replaces that wiring.
 */
class TsonTest {

    private static final String TINY_DOCUMENT = """
            !!id:"https://example.test/tson-test.tn"
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
            {
              my_int => int32
              my_percentage => !positive_integer ^ { max: 100 }
            }
            """;

    /**
     * The `!!import`-vs-`!!meta` confusion, end to end: core.tn is a type library, not a meta-schema, so it
     * can govern nothing. This is an authoring error in the schema, which is why it arrives as a {@link
     * TsonSchemaValidationException} a caller can catch around {@code resolve} rather than as an unchecked
     * library-fault type — and why {@code tson validate} can tell it apart from a bug in this tool.
     */
    @Test
    void rejectsASchemaThatNamesATypeLibraryAsItsMeta() {
        Tson tson = Tson.builder().build();

        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> tson.resolve("""
                        !!id:"https://example.test/oops.tn"
                        !!meta:"https://tson.io/2026/34/m/core.tn"
                        {
                          my_thing => uuid
                        }
                        """));
        assertTrue(thrown.getMessage().contains("core.tn"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("!!import"), thrown.getMessage());
    }

    @Test
    void resolvesAndCompilesATinySchemaThatImportsCoreTn1() {
        Tson tson = Tson.builder().build();

        TsonLinkedSchema linked = tson.resolve(TINY_DOCUMENT);
        // Merged view: the two local declarations, plus core.tn's own 48 imported entries --
        // TsonSchemaLinker.link copies an import's own entries in, unlike the raw resolved TsonSchema
        // resolve()'s own resolution step produces internally, which stays local-only.
        assertEquals(50, linked.schema().entries().size());
        assertTrue(linked.schema().entries().containsKey("my_int"));
        assertTrue(linked.schema().entries().containsKey("my_percentage"));

        TsonCompiledSchema compiled = tson.treeRegistry().compile(linked);

        // int32 has a real bit-width (size: {bits: 32 signed: true}), so IntegerParser narrows to Integer;
        // atom reading is shared verbatim across read modes, so the tree leaf holds that same typed value.
        TsonValue myInt = (TsonValue) compiled.get("my_int").read(TestDocuments.document("42"));
        assertEquals(Optional.of(42), myInt.as(Integer.class)); // as(Class) asserts the host type; asInt() would take either

        // my_percentage never sets size, so IntegerParser falls back to BigInteger.
        TsonValue myPercentage = (TsonValue) compiled.get("my_percentage").read(TestDocuments.document("50"));
        assertEquals(BigInteger.valueOf(50), myPercentage.asBigInteger().orElseThrow());
    }

    /**
     * Every resolved definition carries where it was declared. {@code PositionalReadErrorsTest} proves the
     * pieces connect by handing {@code DefinitionResolver} a position itself; this is the production path,
     * where {@code Tson.resolve} passes {@code TsonSchemaParser.declarationPositions()} through. Without
     * that hop {@code TypeDefinition.position()} is empty for every entry, and so is the {@code
     * schemaPosition} on every read diagnostic derived from it.
     */
    @Test
    void resolvingKeepsEachDeclarationsOwnSourcePosition() {
        TsonLinkedSchema linked = Tson.builder().build().resolve(TINY_DOCUMENT);

        // Declared on lines 5 and 6 of TINY_DOCUMENT (1-based, counting the three header lines and `{`).
        assertEquals(5, linked.schema().entries().get("my_int").position().orElseThrow().line());
        assertEquals(6, linked.schema().entries().get("my_percentage").position().orElseThrow().line());
    }

    /** An imported entry keeps its *own* schema's position, not one relative to the importer. */
    @Test
    void anImportedEntryKeepsThePositionItsOwnSchemaGaveIt() {
        TsonLinkedSchema linked = Tson.builder().build().resolve(TINY_DOCUMENT);

        // int32 comes from core.tn, which declares it far below this three-line document could reach.
        int int32Line = linked.schema().entries().get("int32").position().orElseThrow().line();
        assertTrue(int32Line > 10, "expected core.tn's own line for int32, got " + int32Line);
    }

    @Test
    void resolveThenCompileThroughTheTreeRegistry() {
        Tson tson = Tson.builder().build();

        TsonCompiledSchema compiled = tson.treeRegistry().compile(tson.resolve(TINY_DOCUMENT));

        TsonValue myInt = (TsonValue) compiled.get("my_int").read(TestDocuments.document("7"));
        assertEquals(7, myInt.asInt().orElseThrow());
    }

    @Test
    void theStandardLibraryItselfIsReachableThroughTheLoader() {
        Tson tson = Tson.builder().build();

        TsonCompiledMetaSchema meta = tson.loader().loadMeta(TsonBundledSchemas.META_ID);

        assertTrue(meta.schema().entries().containsKey("text_type"));
    }

    @Test
    void objectReaderAndWriterAreBoundToTheConfiguredDataBindContext() {
        DataBindContext context = DataBindContext.builder().build();
        Tson tson = Tson.builder().dataBindContext(context).build();

        assertSame(context, tson.dataBindContext());
    }

    @Test
    void objectReaderIsUsableWithTheDefaultDataBindContext() throws Exception {
        Tson tson = Tson.builder().build();
        assertNotNull(tson.dataBindContext());

        TsonObjectReader reader = tson.objectReader();

        Point point = reader.read("{ x: 1 y: 2 }", Point.class);
        assertEquals(new Point(1, 2), point);
    }

    /** Public, not local/package-private -- {@code tson-bind}'s own reflective binding only ever finds *public* constructors (see {@code CliDiagnostic}'s own Javadoc in {@code tson-cli} for the identical gotcha). */
    public record Point(int x, int y) {
    }
}
