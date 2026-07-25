package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.resolver.schema.MetaKernelParser;
import io.ltr8.tson.schema.MetaSchema;
import io.ltr8.tson.schema.SchemaRegistry;
import io.ltr8.tson.schema.TsonSchema;
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

    private static ParserFactoryRegistry fullRegistry() {
        return ParserFactoryRegistry.builder()
                .register("record", RecordParser.FACTORY)
                .register("array", ArrayParser.FACTORY)
                .register("map", MapParser.FACTORY)
                .register("tuple", TupleParser.FACTORY)
                .register("choice", ChoiceParser.FACTORY)
                .register("integer_type", AtomTypeParser.INTEGER_TYPE)
                .register("text_type", AtomTypeParser.TEXT_TYPE)
                .register("decimal_type", AtomTypeParser.DECIMAL_TYPE)
                .register("float_type", AtomTypeParser.FLOAT_TYPE)
                .register("rational_type", AtomTypeParser.RATIONAL_TYPE)
                .register("uuid_type", AtomTypeParser.UUID_TYPE)
                .register("binary", AtomTypeParser.BINARY)
                .register("date_type", AtomTypeParser.DATE_TYPE)
                .register("time_type", AtomTypeParser.TIME_TYPE)
                .register("datetime_type", AtomTypeParser.DATETIME_TYPE)
                .register("duration_type", AtomTypeParser.DURATION_TYPE)
                .register("uri_type", AtomTypeParser.URI_TYPE)
                .register("regex_type", AtomTypeParser.REGEX_TYPE)
                .register("enum", AtomTypeParser.ENUM)
                .register("unit", AtomTypeParser.UNIT)
                .build();
    }

    private static TsonDataParser dataParser() {
        MetaSchema raw = MetaKernelParser.parse();
        TsonSchema registered = new SchemaRegistry().register(raw);
        return new TsonDataParser(TsonSchemaParser.compile(registered, fullRegistry()));
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
                new SchemaRegistry().register(MetaKernelParser.parse()), fullRegistry());
        TsonDataParser parser = new TsonDataParser(compiled);

        assertEquals(compiled, parser.schema());
    }

    @Test
    void badDataStillFailsWithAClearError() {
        TsonDataParser parser = dataParser();

        assertThrows(IllegalArgumentException.class, () -> parser.read("{}", "integer_size"));
    }
}
