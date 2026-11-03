package io.ltr8.tson;

import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A width's range is judged by bit length, so any {@code bits} a schema states loads and reads: a range is never
 * materialised, at load ({@code IntegerType}'s coherence and narrowing) or at read ({@code IntegerParser}).
 */
class IntegerWidthTest {

    private static final String WIDE = """
            !!id:"https://example.test/wide.tn"
            !!import:"https://tson.io/2026/39/m/meta.tn"
            !schema {
              wide       => !integer ^ { size: { bits: 3000000000  signed: true } }
              wide_plain => !integer ^ { size: { bits: 3000000000  signed: false } }
              byte_ish   => !integer ^ { size: { bits: 8  signed: true } }
            }
            """;

    /** A three-billion-bit width loads, and a value reads against it in either signedness. */
    @Test
    void aSchemaWithAHugeWidthLoadsAndReads() {
        assertEquals(List.of(), Tson.of(ProcessorConfig.defaults()).validateSchema(WIDE));
        Tson tson = Tson.of(ProcessorConfig.defaults());
        TsonCompiledSchema compiled = tson.treeRegistry().compile(tson.resolve(WIDE));

        TsonValue wide = (TsonValue) compiled.get("wide").read(TestDocuments.document("-123456789012345678901234567890"));
        assertEquals(new BigInteger("-123456789012345678901234567890"), wide.asBigInteger().orElseThrow());
        assertTrue(compiled.get("wide_plain").read(TestDocuments.document("42")) instanceof TsonValue);
        assertThrows(RuntimeException.class, () -> compiled.get("wide_plain").read(TestDocuments.document("-1")));
        assertThrows(RuntimeException.class, () -> compiled.get("byte_ish").read(TestDocuments.document("128")));
        assertTrue(compiled.get("byte_ish").read(TestDocuments.document("-128")) instanceof TsonValue);
    }

    /**
     * Widening one wide width to a wider one is refused exactly as widening {@code int8} to {@code int16} is: two
     * widths are compared by their bits, however many.
     */
    @Test
    void wideningAWideWidthIsRefused() {
        List<?> problems = Tson.of(ProcessorConfig.defaults()).validateSchema("""
                !!id:"https://example.test/wider.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema {
                  w5000 => !integer ^ { size: { bits: 5000  signed: false } }
                  w6000 => !w5000 ^ { size: { bits: 6000  signed: false } }
                }
                """);
        assertFalse(problems.isEmpty(), "a 6000-bit width widens a 5000-bit one");
        assertTrue(problems.toString().contains("wider than the source's own"), problems::toString);
    }

    /** A stated bound outside a wide width is incoherent, judged without building the width's ceiling. */
    @Test
    void aBoundOutsideAWideWidthIsIncoherent() {
        List<?> problems = Tson.of(ProcessorConfig.defaults()).validateSchema("""
                !!id:"https://example.test/outside.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema {
                  w => !numeric.integer_type { size: { bits: 5000  signed: false }  max: -1 }
                }
                """);
        assertFalse(problems.isEmpty(), "max -1 leaves nothing of an unsigned width");
    }
}
