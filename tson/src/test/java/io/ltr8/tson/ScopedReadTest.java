package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaFetchException;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.TsonTreeWriter;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.tree.TsonScopedValue;
import io.ltr8.tson.tree.TsonValue;

import java.util.List;
import java.util.Optional;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * meta.tn's {@code scoped} constructor read end to end: the value names its own type, and the instance says
 * which namespaces that name may be drawn from ([TSON-SCHEMA] §7.8, [TSON-DATA] §2.3).
 *
 * <p>Core's three named instances are the three subsets that have a name -- {@code declared} (LOCAL),
 * {@code extern} (EXTERN) and {@code dynamic} (both) -- and its two templates, {@code extern_of} and
 * {@code extern_type}, narrow the foreign side without declaring anything. One reader serves all five, so
 * what is under test here is the two constraint values and not five code paths.
 */
class ScopedReadTest {

    private static final String HOST = "https://example.test/scope-host.tn";
    private static final String CLAIM = "https://example.test/scope-claim.tn";
    private static final String REPORT = "https://example.test/scope-report.tn";

    private static final Map<String, String> SCHEMAS = Map.of(
            HOST, """
                    !!id:"https://example.test/scope-host.tn"
                    !!meta:"https://tson.io/2026/35/m/meta.tn"
                    !!import:"https://tson.io/2026/35/m/core.tn"
                    {
                      note      => { body: text }
                      memo      => { body: text  urgent: boolean }
                      envelope  => { local: declared  foreign: extern  either: dynamic }
                      closed    => { n: int32 }
                      narrowed  => { one: extern_of<"https://example.test/scope-claim.tn">
                                     two: extern_of<"https://example.test/scope-report.tn"> }
                      pinpoint  => { one: extern_type<"https://example.test/scope-claim.tn", claim> }
                      inbox     => { items: [extern] }
                    }
                    """,
            CLAIM, """
                    !!id:"https://example.test/scope-claim.tn"
                    !!meta:"https://tson.io/2026/35/m/meta.tn"
                    !!import:"https://tson.io/2026/35/m/core.tn"
                    {
                      claim  => { id: text  amount: int32 }
                      remark => { text: text }
                    }
                    """,
            REPORT, """
                    !!id:"https://example.test/scope-report.tn"
                    !!meta:"https://tson.io/2026/35/m/meta.tn"
                    !!import:"https://tson.io/2026/35/m/core.tn"
                    {
                      report => { study: text }
                    }
                    """);

    private static final TsonSchemaSource SOURCE = uri -> {
        for (Map.Entry<String, String> document : SCHEMAS.entrySet()) {
            if (TsonCanonicalIdentity.sameIdentity(uri, document.getKey())) {
                return document.getValue();
            }
        }
        throw new TsonSchemaFetchException(uri, TsonSchemaFetchException.Reason.NOT_FOUND, "not one of these", null);
    };

    private static Tson tson() {
        return Tson.builder().schemaSource(SOURCE).build();
    }

    private static TsonValue read(String data) {
        return tson().treeReader().withSchema(HOST).read("!!schema:\"" + HOST + "\"\n" + data);
    }

    private static List<Diagnostic> problems(String data) {
        return tson().validate("!!schema:\"" + HOST + "\"\n" + data);
    }

    // ── LOCAL: the value names a type the governing schema declares ────────────────────────────────

    /**
     * {@code declared} is the narrowest instance: the value chooses the type and the name resolves in the
     * governing namespace, exactly as any other type-ref in this schema does. What the position adds is that
     * the choice is the data's -- {@code note} and {@code memo} both stand where one field is declared.
     */
    @Test
    void aDeclaredPositionTakesAnyTypeTheGoverningSchemaDeclares() {
        for (String local : List.of("!note { body: hi }", "!memo { body: hi  urgent: true }")) {
            TsonValue value = read("!envelope { local: " + local
                    + "  foreign: !!schema:\"" + CLAIM + "\" !remark { text: t }"
                    + "  either: !note { body: b } }");

            assertEquals("hi", value.at("/local/body").asString().orElseThrow(), local);
        }
    }

    /** And nothing else: a name the governing namespace does not hold is a verdict on the document. */
    @Test
    void aDeclaredPositionRefusesANameTheGoverningSchemaDoesNotHold() {
        List<Diagnostic> problems = problems("!envelope { local: !claim { id: a  amount: 1 }"
                + "  foreign: !!schema:\"" + CLAIM + "\" !claim { id: a  amount: 1 }"
                + "  either: !note { body: b } }");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE, problems.getFirst().code());
        assertEquals(Optional.of("/local"), problems.getFirst().path());
    }

    /**
     * A {@code declared} position takes a type <em>this</em> schema declares, so a value opening a scope onto
     * another one is refused there -- the LOCAL/EXTERN split is what the instance's {@code scope} is for, and
     * the two cells refuse each other's values from the one reader.
     */
    @Test
    void aDeclaredPositionRefusesAScopePush() {
        List<Diagnostic> problems = problems("!envelope { local: !!schema:\"" + CLAIM + "\" !claim { id: a  amount: 1 }"
                + "  foreign: !!schema:\"" + CLAIM + "\" !claim { id: a  amount: 1 }"
                + "  either: !note { body: b } }");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, problems.getFirst().code());
        assertEquals(Optional.of("/local"), problems.getFirst().path());
        assertTrue(problems.getFirst().message().contains("cannot open a scope"), problems::toString);
    }

    // ── EXTERN: §7.8's scope push ─────────────────────────────────────────────────────────────────

    /**
     * The scope push itself: the directive names the schema, the value's own type-ref names the type in it,
     * and the foreign schema's own compiled reader validates the value in full. Tree mode keeps the push, so
     * the schema the value belongs to survives the read -- and navigation goes straight through it, a
     * consumer that does not care about scopes never having to unwrap one.
     */
    @Test
    void anExternPositionReadsTheValueAgainstTheSchemaTheValueNames() {
        TsonValue value = read("!envelope { local: !note { body: b }"
                + "  foreign: !!schema:\"" + CLAIM + "\" !claim { id: CLM-1  amount: 450 }"
                + "  either: !note { body: b } }");

        assertEquals("CLM-1", value.at("/foreign/id").asString().orElseThrow());
        assertEquals(450, value.at("/foreign/amount").asInt().orElseThrow());

        TsonScopedValue scoped = assertInstanceOf(TsonScopedValue.class, value.get("foreign"));
        assertEquals(CLAIM, scoped.schema());
        assertEquals("claim", scoped.typeRef().orElseThrow());
    }

    /** The foreign schema validates in full: its own constraints are the ones that judge the value. */
    @Test
    void thePushedSchemaValidatesTheValueInFull() {
        List<Diagnostic> problems = problems("!envelope { local: !note { body: b }"
                + "  foreign: !!schema:\"" + CLAIM + "\" !claim { id: CLM-1 }"
                + "  either: !note { body: b } }");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.FIELD_REQUIRED, problems.getFirst().code());
        assertEquals(Optional.of("/foreign/amount"), problems.getFirst().path());
    }

    /** The scope pops by returning: what follows the pushed value resolves in the governing namespace again. */
    @Test
    void theScopePopsWhenTheValueEnds() {
        List<Diagnostic> problems = problems("!envelope { local: !note { body: b }"
                + "  foreign: !!schema:\"" + CLAIM + "\" !claim { id: a  amount: 1 }"
                + "  either: !claim { id: a  amount: 1 } }");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Optional.of("/either"), problems.getFirst().path(),
                () -> "'claim' is the foreign schema's name and must not resolve after the pop: " + problems);
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE, problems.getFirst().code());
    }

    /** {@code extern} is the foreign side alone, so a value naming no schema has nothing to resolve in. */
    @Test
    void anExternPositionRequiresAScopePush() {
        List<Diagnostic> problems = problems("!envelope { local: !note { body: b }"
                + "  foreign: !note { body: b }"
                + "  either: !note { body: b } }");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, problems.getFirst().code());
        assertEquals(Optional.of("/foreign"), problems.getFirst().path());
    }

    /**
     * §7.8's "the discriminant is required": a scoped position is open, so there is nothing to infer a type
     * from and a value naming none is a validation error rather than a vocabulary-only read.
     */
    @Test
    void aScopedValueNamingNoTypeIsAnError() {
        List<Diagnostic> problems = problems("!envelope { local: { body: b }"
                + "  foreign: !!schema:\"" + CLAIM + "\" !claim { id: a  amount: 1 }"
                + "  either: !note { body: b } }");

        assertEquals(1, problems.size(), problems::toString);
        // A validation error, not a resolver one: nothing failed to resolve -- §7.8 says so outright.
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, problems.getFirst().code());
        assertEquals(Optional.of("/local"), problems.getFirst().path());
    }

    /** {@code dynamic} is the widest instance: both cells, so the same field takes either kind of value. */
    @Test
    void aDynamicPositionTakesBothCells() {
        for (String either : List.of("!note { body: b }",
                "!!schema:\"" + REPORT + "\" !report { study: RAD-1 }")) {
            List<Diagnostic> problems = problems("!envelope { local: !note { body: b }"
                    + "  foreign: !!schema:\"" + CLAIM + "\" !claim { id: a  amount: 1 }"
                    + "  either: " + either + " }");

            assertEquals(List.of(), problems, either);
        }
    }

    // ── The narrowing templates ───────────────────────────────────────────────────────────────────

    /** {@code extern_of<S>} names one schema, and a value from any other is refused without loading it. */
    @Test
    void externOfAdmitsOnlyTheSchemaItNames() {
        assertEquals(List.of(), problems("!narrowed { one: !!schema:\"" + CLAIM + "\" !remark { text: t }"
                + "  two: !!schema:\"" + REPORT + "\" !report { study: R } }"));

        // Each field's own argument, swapped: two applications of one template are two types, and each
        // refuses the other's schema. §8.2 keys identity on the application, so the two must not share an
        // entry -- a derived name that rendered the `schemas` map as one opaque mark made them one, and this
        // is the shape that catches it, a collision being invisible from either field alone.
        List<Diagnostic> problems = problems("!narrowed { one: !!schema:\"" + REPORT + "\" !report { study: R }"
                + "  two: !!schema:\"" + CLAIM + "\" !remark { text: t } }");
        assertEquals(2, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, problems.getFirst().code());
        assertEquals(Optional.of("/one"), problems.getFirst().path());
        assertEquals(Optional.of("/two"), problems.get(1).path());
    }

    /** {@code extern_type<S, T>} names one type in one schema, and the schema's other types are not it. */
    @Test
    void externTypeAdmitsOnlyTheTypeItNames() {
        assertEquals(List.of(),
                problems("!pinpoint { one: !!schema:\"" + CLAIM + "\" !claim { id: a  amount: 1 } }"));

        List<Diagnostic> problems =
                problems("!pinpoint { one: !!schema:\"" + CLAIM + "\" !remark { text: t } }");
        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, problems.getFirst().code());
        assertTrue(problems.getFirst().message().contains("remark"), problems::toString);
    }

    // ── Containers, availability, and the positions that admit none of this ────────────────────────

    /** §7.8's heterogeneous array: {@code [extern]}, each element carrying its own directive. */
    @Test
    void anArrayOfExternTakesADirectivePerElement() {
        TsonValue value = read("!inbox { items: ["
                + " !!schema:\"" + CLAIM + "\" !claim { id: CLM-1  amount: 1 }"
                + " !!schema:\"" + REPORT + "\" !report { study: RAD-1 } ] }");

        assertEquals("CLM-1", value.at("/items/0/id").asString().orElseThrow());
        assertEquals("RAD-1", value.at("/items/1/study").asString().orElseThrow());
        assertEquals(CLAIM, assertInstanceOf(TsonScopedValue.class, value.at("/items/0")).schema());
        assertEquals(REPORT, assertInstanceOf(TsonScopedValue.class, value.at("/items/1")).schema());
    }

    /**
     * A schema nobody would supply is one of the five {@code SCHEMA_*} codes and never a verdict: the value
     * was never read, so whether it conforms is unknown, and the surrounding document still gets its own.
     */
    @Test
    void aScopeNothingWouldSupplyIsNotAVerdict() {
        List<Diagnostic> problems = problems("!envelope { local: !note { body: b }"
                + "  foreign: !!schema:\"https://example.test/absent.tn\" !claim { id: a  amount: 1 }"
                + "  either: !note { body: b } }");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.SCHEMA_NOT_FOUND, problems.getFirst().code());
        assertFalse(problems.getFirst().code().verdict(), "not a verdict on the document");
        assertEquals(Optional.of("/foreign"), problems.getFirst().path());
    }

    /**
     * §7.8's typed-position restriction: cross-schema acceptance is authored intent, so a position the schema
     * did not declare scoped refuses a push rather than quietly reading the value as though there were none,
     * which is what every container did while no reader consumed the directive.
     */
    @Test
    void aPositionThatIsNotScopedRefusesAPush() {
        List<Diagnostic> problems = problems("!closed { n: !!schema:\"" + CLAIM + "\" 1 }");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, problems.getFirst().code());
        assertTrue(problems.getFirst().message().contains("not a scoped type"), problems::toString);
    }

    /** And a document with no schema of its own opens no scope at all -- see {@code SPEC-FEEDBACK.md}. */
    @Test
    void aSchemalessDocumentOpensNoScope() {
        List<Diagnostic> problems = tson().validate("{ a: !!schema:\"" + CLAIM + "\" !claim { id: a } }");

        assertEquals(Diagnostic.Code.VALIDATION_ERROR, problems.getFirst().code(), problems::toString);
        assertTrue(problems.getFirst().message().contains("opens no schema scope"), problems::toString);
        // The directive is consumed and the value read as though it had none, so the read carries on -- and
        // in a schemaless read `!claim` is then an unresolved type-ref, which is the ordinary Class 1 verdict.
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF, problems.get(1).code(), problems::toString);
    }

    /**
     * <b>Bind mode reads the pushed value and hands back the object, unwrapped.</b> A bound class has nowhere
     * to carry a schema URI, and inventing somewhere would change what a consumer's own class means -- so the
     * scope is a tree-mode fact, the same asymmetry {@code TsonAbsent} already makes for [TSON-DATA] §2.9.
     *
     * <p>{@code extern_type<S, T>} is the shape bind mode can state: one type in one schema, so the component
     * has a static type to be. The wider instances are read the same way and land in an {@code Object}
     * component, the position's whole point being that the class of the value is the data's choice.
     */
    @Test
    void bindModeReadsAPushedValueIntoTheClassTheForeignTypeNames() {
        Tson tson = Tson.builder().schemaSource(SOURCE)
                .bindings(Map.of("pinpoint", Pinpoint.class, "claim", Claim.class)).build();

        Pinpoint read = tson.objectReader().read("!!schema:\"" + HOST + "\"\n!pinpoint { one: "
                + "!!schema:\"" + CLAIM + "\" !claim { id: CLM-1  amount: 450 } }", Pinpoint.class);

        assertEquals(new Claim("CLM-1", 450), read.one());
    }

    public record Claim(String id, int amount) { }

    public record Pinpoint(Claim one) { }

    /** The tree keeps the directive where the author put it, so a read round-trips through the writer. */
    @Test
    void aPushedScopeIsWrittenBackWhereItWasRead() {
        String data = "!envelope { local: !note { body: b }"
                + "  foreign: !!schema:\"" + CLAIM + "\" !claim { id: CLM-1  amount: 450 }"
                + "  either: !note { body: b } }";

        String written = new TsonTreeWriter().toTson(read(data));

        assertTrue(written.contains("!!schema:\"" + CLAIM + "\""), written);
        assertEquals(List.of(), tson().validate("!!schema:\"" + HOST + "\"\n" + written), written);
    }
}
