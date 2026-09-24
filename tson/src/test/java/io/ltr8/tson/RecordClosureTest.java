package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.TsonBundledSchemas;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A record is closed under its type ([TSON-SCHEMA] §7.2) and nothing relaxes it: no annotation, no mark and
 * no kernel field designates a field that absorbs members the type does not declare. Open-ended data is a
 * declared map-typed field, which every encoding writes the same way (§7.2).
 *
 * <p><b>Why the pair.</b> The refusal alone would pin a name that does not resolve, which any misspelling
 * satisfies. What makes it a statement about closure is the shape beside it: the thing the retired directive
 * was for is expressible today, in one line, with no mark on it -- so the refusal costs an author nothing but
 * a spelling.
 */
class RecordClosureTest {

    private static final String HEADER = """
            !!id:"https://example.test/%s.tn"
            !!meta:"%s"
            !!import:"%s"
            """.formatted("%s", TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    private static String schema(String name, String body) {
        return HEADER.formatted(name) + body;
    }

    /**
     * <b>Nothing knows the name.</b> meta.tn declares no {@code rest}, so the directive spelling is the
     * ordinary unresolved-name error of §3.3.3. A processor keeping the retired declaration would resolve it
     * and park it in the author-annotation channel, where it changes nothing -- leaving a schema that reads
     * as though it had asked for a flatten no encoding performs, which is the outcome this pins against.
     *
     * <p>Asserted against meta.tn rather than the meta-kernel, whose bootstrap resolves no annotation at all:
     * a kernel-governed schema would ignore the name instead of refusing it.
     */
    @Test
    void theFlattenDirectiveNamesNoType() {
        List<Diagnostic> diagnostics = Tson.standard().validateSchema(schema("retired", """
                {
                  config => {
                    name: text
                    @rest extras: {text => text}
                  }
                }"""));

        String messages = diagnostics.stream().map(Diagnostic::message).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(messages.contains("does not name a type"), messages);
    }

    /** The shape that carries open-ended data, and the whole of what the directive was for. */
    @Test
    void aMapTypedFieldCarriesTheOpenEndedData() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("open", """
                {
                  config => {
                    name: text
                    extras: {text => text}
                  }
                }""")));
    }
}
