package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [TSON-DATA] §8.2 on the schema-driven read path: a name this processor refused was never read, so
 * {@code RecordAbstractReader} holds no verdict about it either.
 *
 * <p>The peer of {@code tson-compiler}'s test of the same name, which covers the class-driven reader. Both
 * exist because a document's verdict must not depend on which of the two read it, and the two readers are
 * different classes applying one rule.
 *
 * <p>Here rather than in {@code tson-compiler} because reaching {@code RecordAbstractReader} means
 * resolving a schema, and that boundary is {@code Tson.validate}'s -- the same reason
 * {@code Class2ConformanceSuiteTest} lives in this module.
 *
 * <p>The homoglyph is {@code password} with U+0430 CYRILLIC SMALL LETTER A in place of the ASCII {@code a},
 * written with an escape because a literal one is indistinguishable from the letter it impersonates.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RefusedNameDrawsNoVerdictTest {

    private static final String HOMOGLYPH = "pаssword";

    private static final String SCHEMA = """
            !!id:"https://example.test/accounts.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              account => { password: text }
            }
            """;

    private static List<Diagnostic.Code> codes(String fieldName) {
        Tson tson = Tson.builder().build();
        tson.resolve(SCHEMA);
        return tson.validate("""
                !!schema:"https://example.test/accounts.tn"
                !account { %s: "x" }
                """.formatted(fieldName)).stream().map(Diagnostic::code).toList();
    }

    @Test
    void a_refused_name_draws_the_refusal_and_no_unrecognised_field_beside_it() {
        // Without the suppression this is [RESTRICTED_SCRIPT, UNRECOGNIZED_FIELD, FIELD_REQUIRED], and the
        // middle one tells the sender the type declares no such field when it declares exactly that field,
        // spelled properly.
        //
        // FIELD_REQUIRED stays, and stays because it is true and useful: `password` is REQUIRED here and
        // the document supplied nothing for it, the homoglyph having matched no field. The pair now reads
        // coherently -- this name was refused, and the field it was reaching for is missing -- where the
        // suppressed one contradicted both.
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT, Diagnostic.Code.FIELD_REQUIRED),
                codes(HOMOGLYPH));
    }

    @Test
    void an_ordinary_unknown_name_still_draws_its_verdict() {
        assertEquals(List.of(Diagnostic.Code.UNRECOGNIZED_FIELD, Diagnostic.Code.FIELD_REQUIRED),
                codes("nickname"));
    }

    @Test
    void a_well_spelled_document_still_reads_clean() {
        assertEquals(List.of(), codes("password"));
    }
}
