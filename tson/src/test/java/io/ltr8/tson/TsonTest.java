package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.TsonDataParser;
import io.ltr8.tson.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.compiler.mapper.TsonMapperReader;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same real scenario {@code TinySchemaImportsCoreTn1Test} proves by hand-assembling
 * {@code TsonSchemaRegistry}/{@code TsonCompiledRegistry}/{@code DefaultTsonCompiledSchemaLoader}
 * directly -- a small, user-defined schema importing core.tn -- but through this class's own
 * public front door instead, confirming the builder genuinely replaces that wiring.
 */
class TsonTest {

    private static final String TINY_DOCUMENT = """
            !!id:"https://example.test/tson-test.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            {
              my_int => int32
              my_percentage => !positive_integer ^ { max: 100 }
            }
            """;

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

        TsonCompiledMetaSchema compiled = tson.compile(linked, TsonSchemaCompiler.dom());

        // int32 has a real bit-width (size: {bits: 32 signed: true}), so IntegerParser narrows to
        // Integer -- atom reading is shared verbatim between DOM and bind mode (CLAUDE.md), so this
        // holds regardless of TsonSchemaCompiler.dom() here.
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
        Tson tson = Tson.builder().build();

        TsonCompiledMetaSchema compiled = tson.compile(TINY_DOCUMENT, TsonSchemaCompiler.dom());

        Object myInt = compiled.compiledSchema().get("my_int")
                .read(new TsonDataParser("7").parseDocument().root());
        assertEquals(7, myInt);
    }

    @Test
    void theStandardLibraryItselfIsReachableThroughTheLoader() {
        Tson tson = Tson.builder().build();

        TsonCompiledMetaSchema meta = tson.loader().load(TsonBundledSchemas.META_ID);

        assertTrue(meta.schema().entries().containsKey("text_type"));
    }

    @Test
    void mapperReaderAndWriterAreBoundToTheConfiguredDataBindContext() {
        DataBindContext context = DataBindContext.builder().build();
        Tson tson = Tson.builder().dataBindContext(context).build();

        assertSame(context, tson.dataBindContext());
    }

    @Test
    void mapperReaderIsUsableWithTheDefaultDataBindContext() throws Exception {
        Tson tson = Tson.builder().build();
        assertNotNull(tson.dataBindContext());

        TsonMapperReader reader = tson.mapperReader();

        Point point = reader.toObject("{ x: 1 y: 2 }", Point.class);
        assertEquals(new Point(1, 2), point);
    }

    /** Public, not local/package-private -- {@code tson-bind}'s own reflective binding only ever finds *public* constructors (see {@code CliDiagnostic}'s own Javadoc in {@code tson-cli} for the identical gotcha). */
    public record Point(int x, int y) {
    }
}
