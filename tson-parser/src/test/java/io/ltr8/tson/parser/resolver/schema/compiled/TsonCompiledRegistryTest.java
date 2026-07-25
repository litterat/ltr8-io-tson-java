package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.Parser;
import io.ltr8.tson.parser.ast.Document;
import io.ltr8.tson.parser.resolver.schema.CoreTn1Parser;
import io.ltr8.tson.parser.resolver.schema.MetaKernelParser;
import io.ltr8.tson.parser.resolver.schema.MetaTn1Parser;
import io.ltr8.tson.schema.MetaSchema;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads the real three-schema chain -- meta-kernel, meta.tn1, core.tn1 -- through {@link
 * TsonCompiledRegistry}, the same sequence {@link MetaKernelEndToEndTest}/{@link
 * MetaTn1CompiledEndToEndTest}/{@link CoreTn1CompiledEndToEndTest} already prove works, just
 * wrapped in the one registry a real caller would actually keep around instead of re-registering
 * and recompiling from scratch every time. Re-runs a representative assertion from each of those
 * three tests against readers pulled back out of the registry via {@link TsonCompiledRegistry#get},
 * not the fresh {@link TsonSchemaParser#compile} result {@link TsonCompiledRegistry#register}
 * itself returns -- proving the *stored*, looked-up-by-id reader is the genuinely reusable one, not
 * just a pass-through of whatever {@code register} handed back.
 */
class TsonCompiledRegistryTest {

    private static final String META_KERNEL_ID = "https://tson.io/2026/32/m/meta-kernel.tn1";
    private static final String META_ID = "https://tson.io/2026/32/m/meta.tn1";
    private static final String CORE_ID = "https://tson.io/2026/32/m/core.tn1";

    private static TsonCompiledRegistry loadStandardLibrary() {
        TsonCompiledRegistry registry = new TsonCompiledRegistry(ParserFactoryRegistry.dom());

        MetaSchema metaKernel = MetaKernelParser.parse();
        registry.register(metaKernel);
        registry.register(MetaTn1Parser.parse(metaKernel));
        registry.register(CoreTn1Parser.parse(
                registry.schemaRegistry().get(META_ID).orElseThrow()));

        return registry;
    }

    @SuppressWarnings("unchecked")
    private static <T> T read(TsonSchemaParser compiled, String rootName, String source) {
        Document document = new Parser(source).parseDocument();
        return (T) compiled.get(rootName).read(document.root());
    }

    @Test
    void allThreeSchemasAreRegisteredUnderTheirOwnRealIds() {
        TsonCompiledRegistry registry = loadStandardLibrary();

        assertTrue(registry.schemaRegistry().get(META_KERNEL_ID).isPresent());
        assertTrue(registry.schemaRegistry().get(META_ID).isPresent());
        assertTrue(registry.schemaRegistry().get(CORE_ID).isPresent());
    }

    @Test
    void metaKernelsCompiledReaderIsRetrievableAndReadsRealData() {
        TsonCompiledRegistry registry = loadStandardLibrary();
        TsonSchemaParser compiled = registry.get(META_KERNEL_ID).orElseThrow();

        // Same case MetaKernelEndToEndTest.bareTopWithNoTypeRefReadsAgainstItsOwnEmptyBody proves.
        assertEquals(Map.of(), read(compiled, "top", "{}"));

        Map<String, Object> integerSize = read(compiled, "integer_size", "{ bits: 32 signed: true }");
        assertEquals(BigInteger.valueOf(32), integerSize.get("bits"));
        assertEquals("true", integerSize.get("signed"));
    }

    @Test
    void metaTn1sCompiledReaderIsRetrievableAndReadsRealData() {
        TsonCompiledRegistry registry = loadStandardLibrary();
        TsonSchemaParser compiled = registry.get(META_ID).orElseThrow();

        // Same case MetaTn1CompiledEndToEndTest.readsBinaryEncodingEnumMembersAgainstRealData proves.
        assertEquals("BASE64", read(compiled, "binary_encoding", "BASE64"));
    }

    @Test
    void coreTn1sCompiledReaderIsRetrievableAndReadsRealData() {
        TsonCompiledRegistry registry = loadStandardLibrary();
        TsonSchemaParser compiled = registry.get(CORE_ID).orElseThrow();

        // Same cases CoreTn1CompiledEndToEndTest.readsAnInt32AsItsNaturalNarrowedType and
        // voidAcceptsOnlyTheAbsentSentinelAndReadsAsNull prove.
        assertEquals(42, (int) read(compiled, "int32", "42"));
        assertNull(read(compiled, "void", "_"));
    }

    @Test
    void unregisteredIdIsAbsentNotAnException() {
        TsonCompiledRegistry registry = loadStandardLibrary();

        assertTrue(registry.get("https://example.test/not-registered.tn1").isEmpty());
    }
}
