package io.ltr8.tson.json;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.json.tree.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-JSON] §8.5's scoped positions, the open sum, read end to end: the value names its own type in an
 * annotation object, {@code $type} alone for the governing namespace (LOCAL) and {@code $schema} with {@code
 * $type} for a foreign one (EXTERN), and a value naming no type is refused in every mode.
 *
 * <p>Core's {@code declared}, {@code extern} and {@code dynamic}, and the {@code extern_of}/{@code extern_type}
 * narrowings, share one reader, so what is under test is the two constraint values and the annotation object's
 * two forms, not five code paths. {@code ScopedReadTest} is the TSON text peer.
 */
class JsonScopedReadTest {

    private static final String HOST = "https://example.test/json-scope-host.tn";
    private static final String CLAIM = "https://example.test/json-scope-claim.tn";
    private static final String REPORT = "https://example.test/json-scope-report.tn";

    private static final String HOST_SCHEMA = """
            !!id:"https://example.test/json-scope-host.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
            {
              note      => { body: text }
              memo      => { body: text  urgent: boolean }
              count     => !integer ^ { min: 0  max: 9 }
              envelope  => { local: declared  foreign: extern  either: dynamic }
              narrowed  => { one: extern_of<"https://example.test/json-scope-claim.tn"> }
              pinpoint  => { one: extern_type<"https://example.test/json-scope-claim.tn", claim> }
              inbox     => { items: [extern] }
            }
            """;

    private static final String CLAIM_SCHEMA = """
            !!id:"https://example.test/json-scope-claim.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
            {
              claim  => { id: text  amount: int32 }
              remark => { text: text }
              holder => { inner: claim }
            }
            """;

    private static final String REPORT_SCHEMA = """
            !!id:"https://example.test/json-scope-report.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
            {
              report => { study: text }
            }
            """;

    private static final Tson TSON = registered();

    private static Tson registered() {
        Tson tson = Tson.standard();
        tson.resolve(CLAIM_SCHEMA);
        tson.resolve(REPORT_SCHEMA);
        tson.resolve(HOST_SCHEMA);
        return tson;
    }

    private static final Json JSON = Json.standard().withSchemas(TSON.schemaRegistry());

    private static final String FOREIGN_REMARK = """
            {"$schema": "%s", "$type": "remark", "text": "t"}""".formatted(CLAIM);

    private static JsonValue read(String rootType, String json) {
        return JSON.treeReader().withSchema(HOST).readAs(json, rootType);
    }

    private static List<Diagnostic> problems(String rootType, String json) {
        return JSON.validate(json, HOST, rootType);
    }

    private static Diagnostic refusal(String rootType, String json) {
        List<Diagnostic> problems = problems(rootType, json);
        assertEquals(1, problems.size(), problems::toString);
        return problems.getFirst();
    }

    private static String envelope(String local, String foreign, String either) {
        return "{\"local\": " + local + ", \"foreign\": " + foreign + ", \"either\": " + either + "}";
    }

    // ── LOCAL: `$type` alone names a type the governing schema declares ──────────────────────────────

    /** `declared` takes any type the governing namespace holds; the choice of which is the data's. */
    @Test
    void aDeclaredPositionTakesAnyTypeTheGoverningSchemaDeclares() {
        for (String local : List.of("""
                {"$type": "note", "body": "hi"}""", """
                {"$type": "memo", "body": "hi", "urgent": true}""")) {
            JsonValue value = read("envelope", envelope(local, FOREIGN_REMARK, """
                    {"$type": "note", "body": "b"}"""));
            assertTrue(value.toString().startsWith("{\"local\":{\"body\":\"hi\""), value::toString);
        }
    }

    /** §3.3's wrapper carries a value of any shape -- an atom from the governing namespace, here. */
    @Test
    void aLocalAtomRidesInTheWrapperForm() {
        assertEquals(List.of(), problems("envelope", envelope("""
                {"$type": "count", "$value": 7}""", FOREIGN_REMARK, """
                {"$type": "int32", "$value": -1}""")));
    }

    /** The selected type validates in full: its facets hold inside the wrapper. */
    @Test
    void theSelectedLocalTypeValidatesInFull() {
        Diagnostic refusal = refusal("envelope", envelope("""
                {"$type": "count", "$value": 10}""", FOREIGN_REMARK, """
                {"$type": "note", "body": "b"}"""));
        assertTrue(refusal.path().orElseThrow().startsWith("/local"), refusal::toString);
    }

    /** A name the governing namespace does not hold is a verdict on the document. */
    @Test
    void aDeclaredPositionRefusesANameTheGoverningSchemaDoesNotHold() {
        Diagnostic refusal = refusal("envelope", envelope("""
                {"$type": "claim", "id": "a", "amount": 1}""", FOREIGN_REMARK, """
                {"$type": "note", "body": "b"}"""));
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE, refusal.code());
        assertEquals("/local", refusal.path().orElseThrow());
    }

    // ── A value naming no type ───────────────────────────────────────────────────────────────────────

    /** §8.5: a bare value at a scoped position is a validation error -- there is no permissive reading. */
    @Test
    void aBareValueIsRefusedInEveryShape() {
        for (String bare : List.of("\"hi\"", "3", "true", "[1, 2]", "{\"body\": \"hi\"}")) {
            Diagnostic refusal = refusal("envelope", envelope(bare, FOREIGN_REMARK, """
                    {"$type": "note", "body": "b"}"""));
            assertEquals(Diagnostic.Code.VALIDATION_ERROR, refusal.code(), bare);
            assertEquals("/local", refusal.path().orElseThrow(), bare);
        }
    }

    /** An annotation object without `$type` names no type either. */
    @Test
    void aWrapperWithNoTypeIsRefused() {
        Diagnostic refusal = refusal("envelope", envelope("""
                {"$value": 1}""", FOREIGN_REMARK, """
                {"$type": "note", "body": "b"}"""));
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, refusal.code());
    }

    // ── EXTERN: `$schema` opens a scope for the annotated value alone ────────────────────────────────

    /** The foreign type is read by the foreign schema's reader, inline where it reads the value as a record. */
    @Test
    void anExternPositionReadsTheForeignTypeInline() {
        JsonValue value = read("envelope", envelope("""
                {"$type": "note", "body": "hi"}""", """
                {"$schema": "%s", "$type": "claim", "id": "C-1", "amount": 450}""".formatted(CLAIM), """
                {"$type": "note", "body": "b"}"""));
        assertTrue(value.toString().contains("\"foreign\":{\"id\":\"C-1\",\"amount\":450}"), value::toString);
    }

    /** And in the wrapper form, for which a record is as good a value as any other. */
    @Test
    void anExternPositionReadsTheForeignTypeWrapped() {
        assertEquals(List.of(), problems("envelope", envelope("""
                {"$type": "note", "body": "hi"}""", """
                {"$schema": "%s", "$type": "claim", "$value": {"id": "C-1", "amount": 450}}""".formatted(CLAIM), """
                {"$type": "note", "body": "b"}""")));
    }

    /** The foreign value validates in full, against the foreign schema, and its diagnostic says so. */
    @Test
    void theForeignValueIsValidatedByTheForeignSchema() {
        Diagnostic refusal = refusal("envelope", envelope("""
                {"$type": "note", "body": "hi"}""", """
                {"$schema": "%s", "$type": "claim", "id": "C-1"}""".formatted(CLAIM), """
                {"$type": "note", "body": "b"}"""));
        assertEquals(Diagnostic.Code.FIELD_REQUIRED, refusal.code());
        assertEquals("/foreign/amount", refusal.path().orElseThrow());
        assertEquals(io.ltr8.tson.base.CanonicalIdentity.canonicalize(CLAIM), refusal.schemaId());
    }

    /** Everything below the pushed value resolves in the foreign schema, by construction. */
    @Test
    void theScopeReachesTheWholeForeignValue() {
        assertEquals(List.of(), problems("envelope", envelope("""
                {"$type": "note", "body": "hi"}""", """
                {"$schema": "%s", "$type": "holder", "inner": {"id": "C-1", "amount": 1}}""".formatted(CLAIM), """
                {"$type": "note", "body": "b"}""")));
    }

    /** `dynamic` holds both cells: either spelling is admitted at one position. */
    @Test
    void aDynamicPositionTakesBothCells() {
        assertEquals(List.of(), problems("envelope", envelope("""
                {"$type": "note", "body": "hi"}""", FOREIGN_REMARK, FOREIGN_REMARK)));
    }

    /** A heterogeneous `[extern]` array: each element opens its own scope. */
    @Test
    void eachArrayElementOpensItsOwnScope() {
        assertEquals(List.of(), problems("inbox", """
                {"items": [
                  {"$schema": "%s", "$type": "claim", "id": "C-1", "amount": 1},
                  {"$schema": "%s", "$type": "report", "study": "RAD-9"}
                ]}""".formatted(CLAIM, REPORT)));
    }

    // ── The cells an instance does not hold ──────────────────────────────────────────────────────────

    /** §8.5: "a `$schema` at a `declared` position" refuses the value, as a validation error. */
    @Test
    void aDeclaredPositionRefusesAScopePush() {
        Diagnostic refusal = refusal("envelope", envelope(FOREIGN_REMARK, FOREIGN_REMARK, """
                {"$type": "note", "body": "b"}"""));
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, refusal.code());
        assertEquals("/local", refusal.path().orElseThrow());
    }

    /** And "a bare `$type` at an `extern` one". */
    @Test
    void anExternPositionRequiresAScopePush() {
        Diagnostic refusal = refusal("envelope", envelope("""
                {"$type": "note", "body": "hi"}""", """
                {"$type": "note", "body": "hi"}""", """
                {"$type": "note", "body": "b"}"""));
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, refusal.code());
        assertEquals("/foreign", refusal.path().orElseThrow());
    }

    /** §8.5: a missing `$type` on the EXTERN cell is the same validation error text gives it. */
    @Test
    void aScopePushNamingNoTypeIsRefused() {
        Diagnostic refusal = refusal("envelope", envelope("""
                {"$type": "note", "body": "hi"}""", """
                {"$schema": "%s", "id": "C-1", "amount": 1}""".formatted(CLAIM), """
                {"$type": "note", "body": "b"}"""));
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, refusal.code());
    }

    /** A `$schema` that is not a string names no schema. */
    @Test
    void aSchemaMemberThatIsNotAStringIsRefused() {
        Diagnostic refusal = refusal("envelope", envelope("""
                {"$type": "note", "body": "hi"}""", """
                {"$schema": 1, "$type": "claim", "id": "C-1", "amount": 1}""", """
                {"$type": "note", "body": "b"}"""));
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refusal.code());
        assertEquals("/foreign/$schema", refusal.path().orElseThrow());
    }

    // ── Narrowing: `schemas` is a closed set, matched by canonical identity ──────────────────────────

    @Test
    void anExternOfPositionAdmitsItsSchemaAndNoOther() {
        assertEquals(List.of(), problems("narrowed", """
                {"one": {"$schema": "%s", "$type": "remark", "text": "t"}}""".formatted(CLAIM)));
        // Canonical identity: the scheme is not part of it ([TSON-DATA] §2.2.1).
        assertEquals(List.of(), problems("narrowed", """
                {"one": {"$schema": "%s", "$type": "remark", "text": "t"}}"""
                .formatted(CLAIM.replace("https:", "http:"))));

        Diagnostic refusal = refusal("narrowed", """
                {"one": {"$schema": "%s", "$type": "report", "study": "s"}}""".formatted(REPORT));
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, refusal.code());
    }

    @Test
    void anExternTypePositionAdmitsItsTypeAndNoOther() {
        assertEquals(List.of(), problems("pinpoint", """
                {"one": {"$schema": "%s", "$type": "claim", "id": "C-1", "amount": 1}}""".formatted(CLAIM)));

        Diagnostic refusal = refusal("pinpoint", """
                {"one": {"$schema": "%s", "$type": "remark", "text": "t"}}""".formatted(CLAIM));
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, refusal.code());
    }

    // ── Reaching the foreign schema, which is not a verdict on the document ──────────────────────────

    /** A schema nothing supplies was never read against: `SCHEMA_NOT_FOUND`, not a verdict. */
    @Test
    void aForeignSchemaNothingSuppliesIsNotAVerdict() {
        Diagnostic refusal = refusal("envelope", envelope("""
                {"$type": "note", "body": "hi"}""", """
                {"$schema": "https://example.test/nowhere.tn", "$type": "claim", "id": "C-1"}""", """
                {"$type": "note", "body": "b"}"""));
        assertEquals(Diagnostic.Code.SCHEMA_NOT_FOUND, refusal.code());
        assertFalse(refusal.code().verdict());
    }

    /** A type the foreign schema does not declare is named against that schema's own declarations. */
    @Test
    void aTypeTheForeignSchemaDoesNotDeclareIsUnknown() {
        Diagnostic refusal = refusal("envelope", envelope("""
                {"$type": "note", "body": "hi"}""", """
                {"$schema": "%s", "$type": "note", "body": "hi"}""".formatted(CLAIM), """
                {"$type": "note", "body": "b"}"""));
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE, refusal.code());
        assertTrue(refusal.expected().contains("claim"), refusal.expected());
    }

    /** A compile with no registry behind it can reach no foreign schema, which is a fact about the deployment. */
    @Test
    void aStandaloneCompileReachesNoForeignSchema() {
        JsonCompiledSchema compiled = JsonSchemaCompiler.compile(Tson.standard().resolve(HOST_SCHEMA));
        List<Diagnostic> problems = new ArrayList<>();
        try (var bytes = io.ltr8.tson.base.io.ByteSource.of(FOREIGN_REMARK)) {
            JsonReadContext ctx = JsonReadContext.of(new io.ltr8.tson.json.stream.JsonStream(bytes,
                    io.ltr8.tson.base.policy.ProcessorPolicy.defaults(), problems::add), problems::add);
            compiled.get("extern").read(ctx);
        }
        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.SCHEMA_NOT_PERMITTED, problems.getFirst().code());
    }

    // ── The scope is the annotated value's alone ─────────────────────────────────────────────────────

    /** Inside the pushed value, a `$schema` at a position that is not scoped is refused as anywhere else. */
    @Test
    void aSchemaMemberInsideTheForeignValueIsStillRefused() {
        Diagnostic refusal = refusal("envelope", envelope("""
                {"$type": "note", "body": "hi"}""", """
                {"$schema": "%s", "$type": "holder",
                 "inner": {"$schema": "%s", "$type": "claim", "id": "C-1", "amount": 1}}""".formatted(CLAIM, CLAIM), """
                {"$type": "note", "body": "b"}"""));
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, refusal.code());
        assertEquals("/foreign/inner/$schema", refusal.path().orElseThrow());
    }

    /** `$schema` leads (§3.3): one after `$type` is misplaced, and the object is not read as a scope push. */
    @Test
    void aSchemaMemberThatDoesNotLeadIsRefused() {
        List<Diagnostic> problems = problems("envelope", envelope("""
                {"$type": "note", "body": "hi"}""", """
                {"$type": "claim", "$schema": "%s", "id": "C-1", "amount": 1}""".formatted(CLAIM), """
                {"$type": "note", "body": "b"}"""));
        assertFalse(problems.isEmpty());
        assertEquals("/foreign", problems.getFirst().path().orElseThrow(), problems::toString);
    }

    // ── Bind mode ────────────────────────────────────────────────────────────────────────────────────

    public record Claim(String id, int amount) {
    }

    public record Pinpoint(Claim one) {
    }

    /**
     * {@code extern_type<S, T>} is the shape bind mode can state -- one type in one schema, so the component
     * names its class -- and the pushed value binds to it as the object, unwrapped: a bound class has nowhere
     * to carry the scope. {@code ScopedReadTest} binds the same shape from TSON text.
     */
    private static Json bound() {
        DataBindContext binding = DataBindContext.builder()
                .nameBinder(DataNameBinder.ofMap(Map.of("pinpoint", Pinpoint.class, "claim", Claim.class)))
                .registerAtoms(AtomContext.hostTypes()).build();
        return Json.of(ProcessorConfig.defaults().withDataBindContext(binding)).withSchemas(TSON.schemaRegistry());
    }

    @Test
    void bindModeReadsAPushedValueIntoTheClassTheForeignTypeNames() {
        List<Diagnostic> problems = new ArrayList<>();
        Pinpoint read = bound().objectReader().withDiagnostics(problems::add).withSchema(HOST).readAs("""
                {"one": {"$schema": "%s", "$type": "claim", "id": "C-1", "amount": 450}}""".formatted(CLAIM),
                "pinpoint", Pinpoint.class);
        assertEquals(List.of(), problems);
        assertEquals(new Pinpoint(new Claim("C-1", 450)), read);
    }

    /** A bind read refuses a value naming no type exactly as a tree read does. */
    @Test
    void bindModeRefusesAnUntypedValue() {
        List<Diagnostic> problems = new ArrayList<>();
        Pinpoint read = bound().objectReader().withDiagnostics(problems::add).withSchema(HOST).readAs("""
                {"one": {"id": "C-1", "amount": 450}}""", "pinpoint", Pinpoint.class);
        assertNull(read);
        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, problems.getFirst().code());
    }
}
