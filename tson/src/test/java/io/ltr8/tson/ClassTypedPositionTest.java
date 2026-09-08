package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.TsonConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.TsonObjectReader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * <b>A read whose target is a Java class is a typed read, and answers what the schema it stands in for
 * answers.</b>
 *
 * <p>A {@code DataClass}-backed read already dictates the form everywhere but at a leaf: a record with a
 * member the class does not declare is refused, an array must meet a {@code DataClassArray}. The class types
 * an atom position on the same terms, so the family it names reads the token — which is what
 * [TSON-SCHEMA] §4.2 means by "typed by its position", and it is the answer a schema declaring the same
 * types already gives.
 *
 * <p><b>[TSON-DATA] §4.1's base type resolution is for a position nothing types</b>, which is tree mode:
 * a {@code TsonValue} read has no target and every token gets §4's classification. §4.1 speaks of
 * *documents* and has no term for a position a host type has typed, which is {@code SPEC-FEEDBACK.md} #7.
 *
 * <p>Every case here asserts the two agree rather than asserting a literal, so the schema is the oracle and
 * a change to either side that parts them fails.
 */
class ClassTypedPositionTest {

    private static final String ID = "https://example.test/typed.tn";

    /** Every component optional, so one record can carry one field at a time. */
    public record Box(Integer i, Byte b, String s, Double d, Boolean flag) {
    }

    private static final String SCHEMA = """
            !!id:"https://example.test/typed.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              box => { i: int32?  b: int8?  s: text?  d: float64?  flag: boolean? }
            }
            """;

    private static Tson tson() {
        DataBindContext context = DataBindContext.builder()
                .nameBinder(DataNameBinder.ofMap(Map.of("box", Box.class)))
                .registerAtoms(AtomContext.hostTypes())
                .build();
        Tson tson = Tson.of(TsonConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(ID, SCHEMA))))
                .withDataBindContext(context));
        tson.resolve(SCHEMA);
        return tson;
    }

    /** The codes a governed read reports for {@code body}, empty when it is valid. */
    private static List<Diagnostic.Code> underSchema(String body) {
        DiagnosticsCollector problems = new DiagnosticsCollector();
        tson().objectReader().withDiagnostics(problems).withSchema(ID).readAs(body, "box", Box.class);
        return problems.diagnostics().stream().map(Diagnostic::code).toList();
    }

    /** The codes a class-typed read with no schema at all reports for the same body. */
    private static List<Diagnostic.Code> underClass(String body) {
        DiagnosticsCollector problems = new DiagnosticsCollector();
        new TsonObjectReader().withDiagnostics(problems).read(body, Box.class);
        return problems.diagnostics().stream().map(Diagnostic::code).toList();
    }

    /** What a governed read binds, for a body both sides accept. */
    private static Box boundUnderSchema(String body) {
        return tson().objectReader().withSchema(ID).readAs(body, "box", Box.class);
    }

    private static Box boundUnderClass(String body) {
        return new TsonObjectReader().read(body, Box.class);
    }

    private static void agrees(String body) {
        assertEquals(underSchema(body), underClass(body), () -> "verdicts differ for " + body);
        if (underSchema(body).isEmpty()) {
            assertEquals(boundUnderSchema(body), boundUnderClass(body), () -> "values differ for " + body);
        }
    }

    // ── the form of a token is not consulted at a typed position ─────────

    @Test
    void a_quoted_and_an_unquoted_token_are_one_value() {
        agrees("{ i: 12 }");
        agrees("{ i: \"12\" }");
        assertEquals(Integer.valueOf(12), boundUnderClass("{ i: \"12\" }").i());
    }

    @Test
    void a_number_at_a_text_position_is_that_text() {
        // A token is a token: `text` reads the token's content, and 12 was written as `12`.
        agrees("{ s: 12 }");
        agrees("{ s: true }");
        assertEquals("12", boundUnderClass("{ s: 12 }").s());
        assertEquals("true", boundUnderClass("{ s: true }").s());
    }

    @Test
    void the_special_values_read_either_way() {
        agrees("{ d: .nan }");
        agrees("{ d: \".nan\" }");
        assertEquals(Double.valueOf(Double.NaN), boundUnderClass("{ d: \".nan\" }").d());
    }

    @Test
    void a_boolean_reads_from_either_form() {
        agrees("{ flag: true }");
        agrees("{ flag: \"false\" }");
        assertEquals(Boolean.FALSE, boundUnderClass("{ flag: \"false\" }").flag());
    }

    // ── and the refusals are the family's, in the family's own words ─────

    @Test
    void a_token_of_the_wrong_form_is_the_familys_refusal() {
        agrees("{ i: 1.0 }");
        agrees("{ i: nope }");
        assertEquals(List.of(Diagnostic.Code.ATOM_FORM_INVALID), underClass("{ i: 1.0 }"));
    }

    @Test
    void a_value_out_of_range_is_the_familys_bound() {
        agrees("{ b: 200 }");
        assertEquals(List.of(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION), underClass("{ b: 200 }"));
    }

    @Test
    void a_boolean_that_is_neither_member_is_refused_as_an_enum_miss() {
        agrees("{ flag: maybe }");
        assertEquals(List.of(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION), underClass("{ flag: maybe }"));
    }

    /** Bind mode is all-or-nothing, so a refused leaf costs the whole object on both paths. */
    @Test
    void a_refused_leaf_yields_no_object_either_way() {
        assertNull(new TsonObjectReader().withDiagnostics(new DiagnosticsCollector())
                .read("{ i: 1.0 }", Box.class));
    }
}
