package io.ltr8.tson.json;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.io.ByteSource;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.json.stream.JsonStream;
import io.ltr8.tson.json.tree.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-JSON] §3.2's reserved namespace and §3.3's annotation object, at the position §6.1.5 defines them
 * for: a record, where {@code $type} is the JSON spelling of {@code !employee} at a {@code person} field.
 */
class JsonAnnotationObjectTest {

    private static final String SCHEMA = """
            !!id:"https://example.test/tagged-1.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              person   => { name: text }
              employee => person & { department: text }
              robot    => { serial: text }
              holder   => { who: person }
              boxed    => { count: int32 }
              lookup   => { text => int32 }
            }
            """;

    private static final JsonCompiledSchema COMPILED = JsonSchemaCompiler.compile(Tson.standard().resolve(SCHEMA));

    private record Read(JsonValue value, List<Diagnostic> problems) {

        JsonValue accepted() {
            assertEquals(List.of(), problems.stream().map(Diagnostic::message).toList(), "expected a clean read");
            return value;
        }

        Diagnostic refusal() {
            assertEquals(1, problems.size(), () -> "expected exactly one problem, got " + problems);
            return problems.getFirst();
        }
    }

    private static Read read(String typeName, String json) {
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of(json)) {
            JsonReadContext ctx = JsonReadContext.of(new JsonStream(bytes, ProcessorPolicy.defaults(), receiver),
                    receiver);
            ctx = COMPILED.rootDeclaration(typeName).map(ctx::underDeclaration).orElse(ctx);
            return new Read((JsonValue) COMPILED.get(typeName).read(ctx), List.copyOf(problems));
        }
    }

    // ── §6.1.5 subsumption ───────────────────────────────────────────────

    /** At a `person` position, `$type: employee` selects the subtype and the value validates as it in full. */
    @Test
    void aTagSelectsASubtypeAndTheValueValidatesAsIt() {
        assertEquals("""
                {"name":"Ada","department":"Engines"}""", read("person", """
                {"$type": "employee", "name": "Ada", "department": "Engines"}""").accepted().toString());
    }

    /** Without the tag the value is exactly the position's type: there is no structural recovery of a subtype. */
    @Test
    void withoutATagTheValueIsExactlyThePositionsType() {
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, read("person", """
                {"name": "Ada", "department": "Engines"}""").refusal().code());
    }

    /** The selected type is validated in full -- a missing field of the subtype is still missing. */
    @Test
    void theSelectedTypeIsValidatedInFull() {
        Diagnostic refusal = read("person", """
                {"$type": "employee", "name": "Ada"}""").refusal();
        assertEquals(Diagnostic.Code.FIELD_REQUIRED, refusal.code());
        assertEquals("/department", refusal.path().orElseThrow());
    }

    /** §8.1: a tag is never wrong -- a redundant one restating the position's own type changes nothing. */
    @Test
    void aRedundantTagIsAdmitted() {
        assertEquals("""
                {"name":"Ada"}""", read("person", """
                {"$type": "person", "name": "Ada"}""").accepted().toString());
    }

    /** §7.2: a tag may name this type or one of its subtypes, and nothing else. */
    @Test
    void aTagNamingATypeThePositionDoesNotAdmitIsRefused() {
        Diagnostic refusal = read("person", """
                {"$type": "robot", "serial": "R2"}""").refusal();
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refusal.code());
        assertTrue(refusal.expected().contains("employee"), refusal.expected());
    }

    /** Member order carries no meaning, so a tag is found wherever it sits (§6.1.6). */
    @Test
    void aTagIsFoundWhereverItSitsInTheObject() {
        assertEquals("""
                {"name":"Ada","department":"Engines"}""", read("person", """
                {"name": "Ada", "department": "Engines", "$type": "employee"}""").accepted().toString());
    }

    /** A tag reaches a nested position too -- recognition is per object, not per document. */
    @Test
    void aTagWorksAtANestedPosition() {
        assertEquals("""
                {"who":{"name":"Ada","department":"Engines"}}""", read("holder", """
                {"who": {"$type": "employee", "name": "Ada", "department": "Engines"}}""")
                .accepted().toString());
    }

    // ── §3.3's wrapper form ──────────────────────────────────────────────

    @Test
    void theWrapperFormAnnotatesTheValueItHolds() {
        assertEquals("""
                {"name":"Ada","department":"Engines"}""", read("person", """
                {"$type": "employee", "$value": {"name": "Ada", "department": "Engines"}}""")
                .accepted().toString());
    }

    /** §3.3: in wrapper form any member but the reserved three is a resolver error -- it is apparatus, not a record. */
    @Test
    void theWrapperAdmitsNothingBesideTheReservedMembers() {
        Diagnostic refusal = read("person", """
                {"$type": "person", "$value": {"name": "Ada"}, "stray": 1}""").refusal();
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, refusal.code());
        assertEquals("/stray", refusal.path().orElseThrow());
    }

    @Test
    void aWrapperWithNoValueIsRefused() {
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read("person", """
                {"$type": "person", "$value": null}""").problems().getFirst().code());
    }

    // ── §3.2's closed set ────────────────────────────────────────────────

    /** §3.2: the set is closed. There is no unknown-reserved-member category and no extension mechanism. */
    @Test
    void aDollarNameOutsideTheClosedSetIsRefused() {
        Diagnostic refusal = read("person", """
                {"$comment": "hi", "name": "Ada"}""").refusal();
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, refusal.code());
        assertEquals("/$comment", refusal.path().orElseThrow());
    }

    /** An object carrying reserved members but no `$type` names no type, and a tag must name one. */
    @Test
    void reservedMembersWithNoTypeAreRefused() {
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read("person", """
                {"$value": {"name": "Ada"}}""").refusal().code());
    }

    /** §8.5 admits `$schema` only at a scoped position; §3.3 makes it a resolver error anywhere else. */
    @Test
    void aSchemaMemberIsRefusedAtARecordPosition() {
        Diagnostic refusal = read("person", """
                {"$schema": "https://example.test/other.tn", "$type": "person", "name": "Ada"}""").refusal();
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, refusal.code());
        assertEquals("/$schema", refusal.path().orElseThrow());
    }

    /** §3.2's carve-out: at a map position every member name is an ordinary key, `$`-initial or not. */
    @Test
    void nothingIsReservedAtAMapPosition() {
        assertEquals("""
                {"$type":1,"$comment":2}""", read("lookup", """
                {"$type": 1, "$comment": 2}""").accepted().toString());
    }

    // ── The lookahead itself ─────────────────────────────────────────────

    /**
     * The scan rewinds, so an untagged object reads exactly as it would have with no scan at all -- every
     * member, its defaults, and its diagnostics unchanged.
     */
    @Test
    void anUntaggedObjectIsUnaffectedByTheScan() {
        assertEquals("""
                {"count":7}""", read("boxed", """
                {"count": 7}""").accepted().toString());
        assertEquals("/count", read("boxed", """
                {"count": "x"}""").refusal().path().orElseThrow());
    }

    /** A scan that ran over a nested object leaves the cursor where it started, not inside one. */
    @Test
    void theScanIsTransparentToNesting() {
        assertEquals("""
                {"who":{"name":"Ada"}}""", read("holder", """
                {"who": {"name": "Ada"}}""").accepted().toString());
    }
}
