package io.ltr8.tson.parser.config;

import io.ltr8.tson.parser.TsonDataParser;
import io.ltr8.tson.parser.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.parser.compiler.ValueReaderFactoryRegistry;
import io.ltr8.tson.schema.TsonLinkedSchema;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same real scenario {@code TinySchemaImportsCoreTn1Test} proves by hand-assembling
 * {@code TsonSchemaRegistry}/{@code TsonCompiledRegistry}/{@code DefaultTsonCompiledSchemaLoader}
 * directly -- a small, user-defined schema importing core.tn1 -- but through this class's own
 * public front door instead, confirming the builder genuinely replaces that wiring.
 */
class TsonStandardLibraryTest {

    private static final String TINY_DOCUMENT = """
            !!id:"https://example.test/tson-standard-library-test.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn1"
            !!import:"https://tson.io/2026/32/m/core.tn1"
            {
              my_int => int32
              my_percentage => !positive_integer ^ { max: 100 }
            }
            """;

    @Test
    void resolvesAndCompilesATinySchemaThatImportsCoreTn1() {
        TsonStandardLibrary library = TsonStandardLibrary.builder().build();

        TsonLinkedSchema linked = library.resolve(TINY_DOCUMENT);
        // Merged view: the two local declarations, plus core.tn1's own 48 imported entries --
        // TsonSchemaLinker.link copies an import's own entries in, unlike the raw resolved TsonSchema
        // resolve()'s own resolution step produces internally, which stays local-only.
        assertEquals(50, linked.schema().entries().size());
        assertTrue(linked.schema().entries().containsKey("my_int"));
        assertTrue(linked.schema().entries().containsKey("my_percentage"));

        TsonCompiledMetaSchema compiled = library.compile(linked, ValueReaderFactoryRegistry.dom());

        // int32 has a real bit-width (size: {bits: 32 signed: true}), so IntegerParser narrows to
        // Integer -- atom reading is shared verbatim between DOM and bind mode (CLAUDE.md), so this
        // holds regardless of ValueReaderFactoryRegistry.dom() here.
        Object myInt = compiled.compiledSchema().get("my_int")
                .read(new TsonDataParser("42").parseDocument().root());
        assertEquals(42, myInt);

        // my_percentage never sets size, so IntegerParser falls back to BigInteger.
        Object myPercentage = compiled.compiledSchema().get("my_percentage")
                .read(new TsonDataParser("50").parseDocument().root());
        assertEquals(BigInteger.valueOf(50), myPercentage);
    }

    @Test
    void theSingleCallCompileConvenienceMatchesResolveThenCompile() {
        TsonStandardLibrary library = TsonStandardLibrary.builder().build();

        TsonCompiledMetaSchema compiled = library.compile(TINY_DOCUMENT, ValueReaderFactoryRegistry.dom());

        Object myInt = compiled.compiledSchema().get("my_int")
                .read(new TsonDataParser("7").parseDocument().root());
        assertEquals(7, myInt);
    }

    @Test
    void theStandardLibraryItselfIsReachableThroughTheLoader() {
        TsonStandardLibrary library = TsonStandardLibrary.builder().build();

        TsonCompiledMetaSchema meta = library.loader().load(io.ltr8.tson.parser.resolver.BundledSchemaSource.META_TN1_ID);

        assertTrue(meta.schema().entries().containsKey("text_type"));
    }
}
