package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.schema.MetaSchema;
import io.ltr8.tson.schema.SchemaRegistry;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.Unit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link CoreTn1Parser} against the real {@code core.tn1} fixture -- register meta-kernel,
 * then meta.tn1 (via {@link MetaTn1Parser}), then core.tn1 on top, and confirm all 48 declarations
 * resolve and register, including {@code boolean} (hand-picked, see {@link CoreTn1Parser}'s own
 * Javadoc) and {@code void} (a fresh core-level sibling of meta-kernel's own {@code void}, both
 * {@code !unit {}} instances -- distinct type entities under the same name, per core.tn1's own doc
 * comment).
 *
 * <p>core.tn1 declares no {@code !!import} of its own -- every declaration is a constructor
 * application or atom refinement resolved purely against meta.tn1's own *registered* structure
 * namespace (§3.3.1), which is exactly what motivated widening {@code SchemaValidator} to also
 * consult the governing meta-schema for {@code source} reference validation (previously it only
 * knew about {@code !!import}-merged entries) -- see {@code SchemaValidator}'s own Javadoc.
 */
class CoreTn1ParserTest {

    private static TsonSchema registerCore() {
        MetaSchema metaKernel = MetaKernelParser.parse();
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(metaKernel);
        TsonSchema meta = registry.register(MetaTn1Parser.parse(metaKernel));
        return registry.register(CoreTn1Parser.parse(meta));
    }

    @Test
    void allFortyEightCoreDeclarationsResolveAndRegister() {
        TsonSchema core = registerCore();

        assertEquals(48, core.entries().size());
    }

    @Test
    void booleanIsHandPickedRatherThanSilentlyDropped() {
        TsonSchema core = registerCore();

        assertEquals(new EnumBody(List.of("true", "false")), core.entries().get("boolean").body());
    }

    @Test
    void voidIsAFreshSiblingOfMetaKernelsOwnVoidButStillJustUnit() {
        TsonSchema core = registerCore();

        assertEquals(new Unit(), core.entries().get("void").body());
        assertTrue(core.entries().get("void").source().isPresent());
        assertEquals("unit", core.entries().get("void").source().get().name());
    }

    @Test
    void int32IsAnEightBitRefinementOfIntegerViaCoresOwnMetaChain() {
        TsonSchema core = registerCore();

        IntegerType int32 = (IntegerType) core.entries().get("int32").body();
        assertTrue(int32.size().isPresent());
        assertEquals(32, int32.size().get().bits().intValueExact());
        assertTrue(int32.size().get().signed());
    }
}
