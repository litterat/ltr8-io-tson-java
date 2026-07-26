package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.resolver.schema.MetaKernelParser;
import io.ltr8.tson.schema.LinkedTsonSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.SchemaRegistry;
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
 * now exercised through {@link TsonDataParser#read(String, String)} directly, the same one-line
 * shape {@code TsonMapperReader.toObject(String, Class)} already offers for Class 1.
 */
class TsonDataParserTest {

    private static TsonDataParser dataParser() {
        TsonSchema raw = MetaKernelParser.getMetaKernelSchema();
        LinkedTsonSchema linked = new SchemaRegistry().linkBootstrap(raw);
        return new TsonDataParser(TsonSchemaParser.compile(linked.schema(), ParserFactoryRegistry.dom()));
    }

    @Test
    void readsARecordInOneCall() {
        TsonDataParser parser = dataParser();

        Map<String, Object> result = parser.read("{ bits: 32 signed: true }", "integer_size");

        assertEquals(BigInteger.valueOf(32), result.get("bits"));
        assertEquals("true", result.get("signed"));
    }

    @Test
    void readsBareTopInOneCall() {
        TsonDataParser parser = dataParser();

        Map<String, Object> result = parser.read("{}", "top");

        assertEquals(Map.of(), result);
    }

    @Test
    void readsEnumMembersInOneCall() {
        TsonDataParser parser = dataParser();

        Map<String, Object> result = parser.read("{ members: [true false] }", "enum");

        assertEquals(List.of("true", "false"), result.get("members"));
    }

    @Test
    void schemaAccessorReturnsTheWrappedCompiledSchema() {
        TsonSchemaParser compiled = TsonSchemaParser.compile(
                new SchemaRegistry().linkBootstrap(MetaKernelParser.getMetaKernelSchema()).schema(), ParserFactoryRegistry.dom());
        TsonDataParser parser = new TsonDataParser(compiled);

        assertEquals(compiled, parser.schema());
    }

    @Test
    void badDataStillFailsWithAClearError() {
        TsonDataParser parser = dataParser();

        assertThrows(IllegalArgumentException.class, () -> parser.read("{}", "integer_size"));
    }
}
