package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every scalar family may carry a {@code ~}/{@code =} value on a field ([TSON-SCHEMA] §5.2), which
 * {@code TsonSchemaLinker.checkFieldValue} verifies by asking {@code AtomParsers} for the field type's
 * own parser.
 *
 * <p>So the test is really of that table's completeness: a family missing from it is reported as "not a
 * scalar type", which is a verdict on the author's schema for a gap in this library. {@code period} was
 * missing, and nothing caught it because the reader stack carried a second table of its own that had the
 * entry -- the two are one table now.
 */
class ScalarFieldDefaultTest {
    @Test
    void aPeriodTypedFieldMayCarryADefault() {
        List<Diagnostic> problems = Tson.builder().build().validateSchema("""
                !!id:"https://example.test/period-default.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                {
                  plan => { term: period ~ P1Y }
                }
                """);
        assertEquals(List.of(), problems, problems::toString);
    }
    @Test
    void aDurationTypedFieldMayToo() {
        List<Diagnostic> problems = Tson.builder().build().validateSchema("""
                !!id:"https://example.test/duration-default.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                {
                  plan => { gap: duration ~ PT30M }
                }
                """);
        assertEquals(List.of(), problems, problems::toString);
    }
}
