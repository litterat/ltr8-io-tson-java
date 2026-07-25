package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.Parser;
import io.ltr8.tson.parser.ast.Document;
import io.ltr8.tson.parser.resolver.schema.CoreTn1Parser;
import io.ltr8.tson.parser.resolver.schema.MetaKernelParser;
import io.ltr8.tson.parser.resolver.schema.MetaTn1Parser;
import io.ltr8.tson.schema.MetaSchema;
import io.ltr8.tson.schema.SchemaRegistry;
import io.ltr8.tson.schema.TsonSchema;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same proof {@link MetaKernelEndToEndTest} gives for meta-kernel.tn1, one rung further up the
 * schema ladder: compiles the ENTIRE real, registered {@code core.tn1} (meta-kernel + meta.tn1 +
 * core.tn1, chained exactly the way {@link CoreTn1ParserTest} registers them) and reads real TSON
 * data text against several of core's own genuinely useful atom types.
 *
 * <p>{@code void}'s own read is the concrete proof of the {@link VoidParser}/{@link
 * io.ltr8.tson.parser.resolver.vocab.ValueParser}/{@link io.ltr8.tson.parser.resolver.vocab.TokenParser}
 * split (see {@code AtomTypeParser#UNIT}'s own Javadoc): before that split, {@code unit}'s single
 * shared parser accepted any token whatsoever and rejected the absent sentinel outright -- exactly
 * backwards from {@code void}'s real contract. This test reads core.tn1's own {@code void} sibling
 * (a fresh instance under the same name as meta-kernel's, per core.tn1's own doc comment), not
 * meta-kernel's.
 */
class CoreTn1CompiledEndToEndTest {

    private static TsonSchemaParser compiled() {
        MetaSchema metaKernel = MetaKernelParser.parse();
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(metaKernel);
        TsonSchema meta = registry.register(MetaTn1Parser.parse(metaKernel));
        TsonSchema core = registry.register(CoreTn1Parser.parse(meta));
        return TsonSchemaParser.compile(core, ParserFactoryRegistry.dom());
    }

    @SuppressWarnings("unchecked")
    private static <T> T read(TsonSchemaParser compiled, String rootName, String source) {
        Document document = new Parser(source).parseDocument();
        return (T) compiled.get(rootName).read(document.root());
    }

    /**
     * {@code complex}/{@code email}/{@code ipv4}/{@code ipv6}/{@code cidr4}/{@code cidr6}/{@code
     * mac}/{@code unknown} are the eight core.tn1 entries whose constructors ({@code complex_type},
     * {@code email_type}, ...) were added to {@code io.ltr8.tson.schema.meta} "record-only,
     * deliberately with no resolver.vocab parser at all" (see that package's own Javadoc) -- real,
     * resolved {@link io.ltr8.tson.schema.meta.TypeDefinition}s, just with no {@link
     * TsonParserFactory} registered for them yet at this compiled-parser layer. A pre-existing,
     * already-documented gap, not something this test introduces.
     */
    private static final Set<String> NO_COMPILED_FACTORY_YET =
            Set.of("complex", "email", "ipv4", "ipv6", "cidr4", "cidr6", "mac", "unknown");

    @Test
    void everyOtherRealCoreEntryCompilesCleanly() {
        TsonSchemaParser compiled = compiled();
        MetaSchema metaKernel = MetaKernelParser.parse();
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(metaKernel);
        TsonSchema meta = registry.register(MetaTn1Parser.parse(metaKernel));
        TsonSchema core = registry.register(CoreTn1Parser.parse(meta));

        assertEquals(48, core.entries().size());
        for (String name : core.entries().keySet()) {
            if (NO_COMPILED_FACTORY_YET.contains(name)) {
                assertThrows(IllegalStateException.class, () -> compiled.get(name), name + " unexpectedly compiled");
                continue;
            }
            compiled.get(name);
        }
    }

    @Test
    void readsAnInt32AsItsNaturalNarrowedType() {
        TsonSchemaParser compiled = compiled();

        // int32's own declared width narrows to a plain int, not the unbounded BigInteger a
        // width-less "integer" would produce -- IntegerParser.read(TokenValue)'s natural host type.
        assertEquals(42, (int) read(compiled, "int32", "42"));
    }

    @Test
    void voidAcceptsOnlyTheAbsentSentinelAndReadsAsNull() {
        TsonSchemaParser compiled = compiled();

        assertNull(read(compiled, "void", "_"));
    }

    @Test
    void voidRejectsAnOrdinaryToken() {
        TsonSchemaParser compiled = compiled();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> read(compiled, "void", "hello"));
        assertTrue(thrown.getMessage().contains("void"));
    }

    @Test
    void readsBooleanEnumMembersAsRawText() {
        TsonSchemaParser compiled = compiled();

        assertEquals("true", read(compiled, "boolean", "true"));
    }
}
