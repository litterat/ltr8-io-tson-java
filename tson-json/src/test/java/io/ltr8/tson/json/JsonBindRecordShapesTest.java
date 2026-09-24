package io.ltr8.tson.json;

import io.ltr8.annotation.Transparent;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The record shapes bind mode reaches past a plain record -- a labelled choice bound to a sealed interface, a
 * component whose class is a bridged wrapper over a record, and a {@code value} slot read as its component's
 * class -- each read in JSON and checked against what the TSON reader binds from the same value.
 */
class JsonBindRecordShapesTest {

    private static final String ID = "https://example.test/bind-shapes-1.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/bind-shapes-1.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
            {
              argument => { ( name: text | count: int32 ) }
              call     => { arg: argument }
              address  => { street: text  city: text }
              letter   => { to: address }
            }
            """;

    private static final String FACETS = "https://example.test/bind-facets-1.tn";

    /**
     * {@code value} is meta-kernel.tn's, and meta-kernel.tn and core.tn both declare {@code void}: a schema reaching
     * the escape hatch imports the kernel in place of the standard library.
     */
    private static final String FACETS_SCHEMA = """
            !!id:"https://example.test/bind-facets-1.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/meta-kernel.tn"
            {
              facet => { limit: value  scale: value  label: value }
            }
            """;

    public sealed interface Argument permits Named, Counted {
    }

    public record Named(String name) implements Argument {
    }

    public record Counted(int count) implements Argument {
    }

    public record Call(Argument arg) {
    }

    public record Address(String street, String city) {
    }

    /** Framing over {@code address}: its wire form is the address's. */
    @Transparent
    public record Recipient(Address address) {
    }

    public record Letter(Recipient to) {
    }

    public record Facet(Duration limit, BigDecimal scale, String label) {
    }

    private static final Map<String, Class<?>> BINDINGS = Map.of("argument", Argument.class, "call", Call.class,
            "address", Address.class, "letter", Letter.class, "facet", Facet.class);

    private static ProcessorConfig config(Map<String, Class<?>> bindings) {
        DataBindContext binding = DataBindContext.builder().nameBinder(DataNameBinder.ofMap(bindings))
                .registerAtoms(AtomContext.hostTypes()).build();
        return ProcessorConfig.defaults().withDataBindContext(binding);
    }

    private static final Tson TSON = Tson.of(config(BINDINGS));
    private static final Json JSON;

    static {
        TSON.resolve(SCHEMA);
        TSON.resolve(FACETS_SCHEMA);
        JSON = Json.of(config(BINDINGS)).withSchemas(TSON.schemaRegistry());
    }

    /** The JSON read, asserted equal to the TSON read of the same value. */
    private static <T> T both(String type, Class<T> as, String tson, String json) {
        return both(ID, type, as, tson, json);
    }

    private static <T> T both(String schema, String type, Class<T> as, String tson, String json) {
        T fromJson = JSON.objectReader().withSchema(schema).readAs(json, type, as);
        T fromTson = TSON.objectReader().withSchema(schema).readAs(tson, type, as);
        assertEquals(fromTson, fromJson, "the two encodings bind this value differently");
        return fromJson;
    }

    @Test
    void theFieldPresentChoosesTheMemberOfALabelledChoice() {
        assertEquals(new Named("width"), both("argument", Argument.class, "{ name: width }",
                "{\"name\": \"width\"}"));
        assertEquals(new Counted(3), both("argument", Argument.class, "{ count: 3 }", "{\"count\": 3}"));
    }

    @Test
    void aLabelledChoiceBindsAtAComponent() {
        assertEquals(new Call(new Counted(3)), both("call", Call.class, "{ arg: { count: 3 } }",
                "{\"arg\": {\"count\": 3}}"));
    }

    /** The group admits exactly one: two present, or none, is reported and binds nothing. */
    @Test
    void aLabelledChoiceWithTwoFieldsOrNoneBindsNothing() {
        Map<String, Diagnostic.Code> cases = Map.of("{\"name\": \"width\", \"count\": 3}",
                Diagnostic.Code.TYPE_MISMATCH, "{}", Diagnostic.Code.FIELD_REQUIRED);
        cases.forEach((json, code) -> {
            List<Diagnostic> problems = new ArrayList<>();
            assertNull(JSON.objectReader().withDiagnostics(problems::add).withSchema(ID)
                    .readAs(json, "argument", Argument.class), json);
            assertEquals(List.of(code), problems.stream().map(Diagnostic::code).toList(), problems.toString());
        });
    }

    public sealed interface Loose permits Only {
    }

    public record Only(String name) implements Loose {
    }

    /** A union that does not line up with the group is the wiring's mistake, named when the schema compiles. */
    @Test
    void aUnionThatDoesNotLabelEveryFieldIsABindMismatch() {
        Json json = Json.of(config(Map.of("argument", Loose.class))).withSchemas(TSON.schemaRegistry());
        List<Diagnostic> problems = new ArrayList<>();
        assertNull(json.objectReader().withDiagnostics(problems::add).withSchema(ID)
                .readAs("{\"name\": \"width\"}", "argument", Loose.class));
        assertEquals(Diagnostic.Code.BIND_MISMATCH, problems.getFirst().code());
        assertTrue(problems.getFirst().message().contains("labelled choice"), problems.getFirst().message());
    }

    /** A component typed by a bridged wrapper over the field's record receives the wrapper. */
    @Test
    void aBridgedWrapperComponentReceivesTheRecordThroughItsBridge() {
        Letter letter = both("letter", Letter.class, "{ to: { street: \"1 Way\"  city: Here } }",
                "{\"to\": {\"street\": \"1 Way\", \"city\": \"Here\"}}");
        assertEquals(new Letter(new Recipient(new Address("1 Way", "Here"))), letter);
    }

    /** A schema type bound to the wrapper itself builds the wrapper, not the record its bridge carries. */
    @Test
    void aSchemaTypeBoundToABridgedWrapperBuildsTheWrapper() {
        Json json = Json.of(config(Map.of("address", Recipient.class))).withSchemas(TSON.schemaRegistry());
        assertEquals(new Recipient(new Address("1 Way", "Here")), json.objectReader().withSchema(ID)
                .readAs("{\"street\": \"1 Way\", \"city\": \"Here\"}", "address", Recipient.class));
    }

    /**
     * A {@code value} slot is decoded as a boolean, number or string, and read as what its component holds: a
     * string where a {@code Duration} is held is a duration, and an integer where a {@code BigDecimal} is held is
     * widened.
     */
    @Test
    void aValueSlotIsReadAsTheClassItsComponentHolds() {
        Facet facet = both(FACETS, "facet", Facet.class, "{ limit: \"PT30M\"  scale: 2  label: wide }",
                "{\"limit\": \"PT30M\", \"scale\": 2, \"label\": \"wide\"}");
        assertEquals(new Facet(Duration.ofMinutes(30), BigDecimal.valueOf(2), "wide"), facet);
    }

    @Test
    void aValueSlotItsComponentsAtomRefusesIsReported() {
        List<Diagnostic> problems = new ArrayList<>();
        assertNull(JSON.objectReader().withDiagnostics(problems::add).withSchema(FACETS).readAs(
                "{\"limit\": \"soon\", \"scale\": 2, \"label\": \"wide\"}", "facet", Facet.class));
        assertEquals(1, problems.size(), problems.toString());
        assertEquals("/limit", problems.getFirst().path().orElseThrow());
    }
}
