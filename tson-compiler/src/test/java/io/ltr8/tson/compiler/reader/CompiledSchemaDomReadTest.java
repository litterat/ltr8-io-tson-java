package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonDataParser;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.compiler.resolver.MetaKernelBootstrapResolver;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaLinker;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The whole pipeline, end to end, through the one entry point a real caller would actually use --
 * everything {@link MetaKernelEndToEndTest} proved piece by piece (a real, fully-registered
 * meta-kernel schema; every composite kind; every atom family; {@code top}'s own polymorphism),
 * now exercised through a single {@code compiled.get(typeName).read(document.root())} call, the
 * same one-line shape {@code TsonMapperReader.toObject(String, Class)} already offers for Class 1.
 */
class CompiledSchemaDomReadTest {

    private static TsonCompiledSchema compiled() {
        TsonSchema raw = MetaKernelBootstrapResolver.getMetaKernelSchema();
        TsonLinkedSchema linked = TsonSchemaLinker.linkBootstrap(raw);
        TsonCompiledSchema placeholder = new TsonCompiledSchema(linked, Map.of());
        TsonCompiledMetaSchema bootstrapMeta = new TsonCompiledMetaSchema(placeholder, ValueReaderFactoryRegistry.dom());
        return TsonSchemaCompiler.compile(linked, bootstrapMeta);
    }

    private static Object read(TsonCompiledSchema compiled, String source, String typeName) {
        return compiled.get(typeName).read(new TsonDataParser(source).parseDocument().root());
    }

    @Test
    void readsARecordInOneCall() {
        TsonCompiledSchema compiled = compiled();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) read(compiled, "{ bits: 32 signed: true }", "integer_size");

        assertEquals(BigInteger.valueOf(32), result.get("bits"));
        assertEquals("true", result.get("signed"));
    }

    @Test
    void readsBareTopInOneCall() {
        TsonCompiledSchema compiled = compiled();

        Object result = read(compiled, "{}", "top");

        assertEquals(Map.of(), result);
    }

    @Test
    void readsEnumMembersInOneCall() {
        TsonCompiledSchema compiled = compiled();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) read(compiled, "{ members: [true false] }", "enum");

        assertEquals(List.of("true", "false"), result.get("members"));
    }

    @Test
    void badDataStillFailsWithAClearError() {
        TsonCompiledSchema compiled = compiled();

        assertThrows(TsonReadException.class, () -> read(compiled, "{}", "integer_size"));
    }
}
