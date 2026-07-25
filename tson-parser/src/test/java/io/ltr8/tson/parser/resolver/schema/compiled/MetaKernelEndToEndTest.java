package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.Parser;
import io.ltr8.tson.parser.ast.Document;
import io.ltr8.tson.parser.resolver.schema.MetaKernelParser;
import io.ltr8.tson.schema.MetaSchema;
import io.ltr8.tson.schema.SchemaRegistry;
import io.ltr8.tson.schema.TsonSchema;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The real proof this whole sketch works, not against small hand-built fragments: compiles the
 * ENTIRE real, registered {@code meta-kernel.tn1} schema (58 entries after materialization -- see
 * {@code MetaKernelSchemaRegistryTest}) with a registry covering every factory this codebase
 * currently has, then reads real TSON data text against several of its own genuinely useful record
 * types.
 */
class MetaKernelEndToEndTest {

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

    private static TsonSchemaParser compiled() {
        MetaSchema raw = MetaKernelParser.parse();
        TsonSchema registered = new SchemaRegistry().register(raw);
        return TsonSchemaParser.compile(registered, fullRegistry());
    }

    /**
     * 51 of the real schema's 58 entries compile cleanly. The 7 that don't are worth understanding
     * precisely, not just tolerating:
     *
     * <p>{@code map}/{@code set}/{@code array_min}/{@code array_max}/{@code array_ranged} are
     * parameterized type *constructors* ({@code <T>}, {@code <K,V>}, ...) with no known subtypes,
     * so {@link VariantParser} correctly refuses to compile them at all -- but that refusal never
     * matters in practice: meta-kernel never references any of these as a bare field type anywhere
     * (a field always references a *concrete* application, e.g. a synthesized {@code
     * array_field_name_*} entry, never {@code "map"}/{@code "set"} themselves), so nothing
     * legitimate ever calls {@code compiled.get("map")} directly. Confirmed, not assumed: nothing
     * else in this same run fails as a result of any of these five failing, which it would if
     * anything actually depended on them. {@code array} itself, despite also being parameterized,
     * *does* compile -- {@code set}/{@code array_min}/{@code array_max}/{@code array_ranged} all
     * compose with it, so it has real subtypes to dispatch among, unlike its own siblings.
     *
     * <p>{@code enum} is the one real gap: its own {@code members: set<token>} field never got a
     * genuine materialized {@code ArrayBody} the way {@code array<X>} applications do --
     * {@code SchemaValidator.instantiate} only builds real bodies for {@code array} applications,
     * not {@code set} ones (see its own Javadoc), so {@code set<token>} falls back to the old
     * placeholder {@code Reference}, which resolves down to the bare, parameterized {@code "set"}
     * constructor -- the same unreachable case as above, except this time something real (the
     * {@code enum} constructor's own vocabulary) actually needs it. The synthesized {@code
     * set_token_*} entry fails for the identical reason. A real fix needs {@code
     * SchemaValidator.instantiate} widened to cover {@code set} the same way it covers {@code
     * array} -- tracked separately (task list), not attempted here.
     */
    @Test
    void fiftyOneOfFiftyEightRealEntriesCompileCleanly() {
        MetaSchema raw = MetaKernelParser.parse();
        TsonSchema registered = new SchemaRegistry().register(raw);
        TsonSchemaParser compiled = TsonSchemaParser.compile(registered, fullRegistry());

        Set<String> expectedFailures = Set.of("map", "set", "array_min", "array_max", "array_ranged", "enum");
        int ok = 0;
        for (String name : registered.entries().keySet()) {
            boolean failed;
            try {
                compiled.get(name);
                failed = false;
            } catch (RuntimeException e) {
                failed = true;
            }
            boolean shouldFail = expectedFailures.contains(name) || name.startsWith("set_token_");
            assertEquals(shouldFail, failed, "'" + name + "' compiled unexpectedly " + (failed ? "failed" : "succeeded"));
            if (!failed) {
                ok++;
            }
        }
        assertEquals(51, ok);
    }

    @Test
    void readsIntegerSizeAgainstRealData() {
        TsonSchemaParser compiled = compiled();
        Document document = new Parser("{ bits: 32 signed: true }").parseDocument();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) compiled.get("integer_size").read(document.root());

        assertEquals(BigInteger.valueOf(32), result.get("bits"));
        assertEquals("true", result.get("signed")); // boolean => !enum [true false] -- text, not a Java boolean
    }

    @Test
    void readsFieldGroupAgainstRealData() {
        TsonSchemaParser compiled = compiled();
        Document document = new Parser("{ members: [foo bar] state: OPTIONAL }").parseDocument();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) compiled.get("field_group").read(document.root());

        assertEquals(List.of("foo", "bar"), result.get("members"));
        assertEquals("OPTIONAL", result.get("state"));
    }

    @Test
    void readsTupleElementAgainstRealNestedData() {
        TsonSchemaParser compiled = compiled();
        Document document = new Parser("{ element_type: { name: text arguments: [] } state: REQUIRED }").parseDocument();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) compiled.get("tuple_element").read(document.root());

        assertEquals("REQUIRED", result.get("state"));
        @SuppressWarnings("unchecked")
        Map<String, Object> elementType = (Map<String, Object>) result.get("element_type");
        assertEquals("text", elementType.get("name"));
        assertEquals(List.of(), elementType.get("arguments"));
    }
}
