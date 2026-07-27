package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.resolver.MetaKernelBootstrapResolver;
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
 * now exercised through {@link SchemaValidatingParser#read(String, String)} directly, the same one-line
 * shape {@code TsonMapperReader.toObject(String, Class)} already offers for Class 1.
 */
class SchemaValidatingParserTest {

    private static SchemaValidatingParser dataParser() {
        TsonSchema raw = MetaKernelBootstrapResolver.getMetaKernelSchema();
        TsonLinkedSchema linked = TsonSchemaLinker.linkBootstrap(raw);
        return new SchemaValidatingParser(TsonSchemaCompiler.compile(linked.schema(), TsonParserFactoryRegistry.dom()));
    }

    @Test
    void readsARecordInOneCall() {
        SchemaValidatingParser parser = dataParser();

        Map<String, Object> result = parser.read("{ bits: 32 signed: true }", "integer_size");

        assertEquals(BigInteger.valueOf(32), result.get("bits"));
        assertEquals("true", result.get("signed"));
    }

    @Test
    void readsBareTopInOneCall() {
        SchemaValidatingParser parser = dataParser();

        Map<String, Object> result = parser.read("{}", "top");

        assertEquals(Map.of(), result);
    }

    @Test
    void readsEnumMembersInOneCall() {
        SchemaValidatingParser parser = dataParser();

        Map<String, Object> result = parser.read("{ members: [true false] }", "enum");

        assertEquals(List.of("true", "false"), result.get("members"));
    }

    @Test
    void schemaAccessorReturnsTheWrappedCompiledSchema() {
        TsonCompiledSchema compiled = TsonSchemaCompiler.compile(
                TsonSchemaLinker.linkBootstrap(MetaKernelBootstrapResolver.getMetaKernelSchema()).schema(), TsonParserFactoryRegistry.dom());
        SchemaValidatingParser parser = new SchemaValidatingParser(compiled);

        assertEquals(compiled, parser.schema());
    }

    @Test
    void badDataStillFailsWithAClearError() {
        SchemaValidatingParser parser = dataParser();

        assertThrows(IllegalArgumentException.class, () -> parser.read("{}", "integer_size"));
    }
}
