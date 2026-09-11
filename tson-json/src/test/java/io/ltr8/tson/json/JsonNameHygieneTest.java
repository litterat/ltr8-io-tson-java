package io.ltr8.tson.json;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.io.ByteSource;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.base.policy.UnicodePolicy;
import io.ltr8.tson.json.tree.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-DATA] §8.2's name hygiene at the schema-directed JSON positions [TSON-JSON] §9.4 gives it.
 *
 * <p><b>A refusal is not a verdict, and the two halves are asserted separately</b>, exactly as the conformance
 * corpus's {@code refused/} vectors do: that something <em>was</em> refused, under one of the three codes that
 * mean policy, and that <em>nothing</em> was reported in one of §8.1's four categories. Before this, a
 * look-alike field name matched nothing and drew {@code UNRECOGNIZED_FIELD} -- a validation error for a policy
 * rule, in exactly the case the rule exists for, advising the sender to add a field already declared.
 *
 * <p>The confusable characters are written as escapes and never literally, per this project's own rule: a
 * literal Cyrillic {@code a} beside a Latin one is invisible to whoever reads the test next.
 */
class JsonNameHygieneTest {

    /** U+0430 CYRILLIC SMALL LETTER A -- the Latin {@code a}'s look-alike, and §8.2's whole point. */
    private static final String CYRILLIC_A = "\u0430";

    /** The three codes that mean policy, one per §8.2 rule. Which fired is what a consumer routes on. */
    private static final Set<Diagnostic.Code> POLICY = EnumSet.of(Diagnostic.Code.CONFUSABLE_NAMES,
            Diagnostic.Code.RESTRICTED_CHARACTER, Diagnostic.Code.RESTRICTED_SCRIPT);

    private static final String ID = "https://example.test/hygiene-1.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/hygiene-1.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              account => { password: text  note: text? }
              circle  => { radius: float64 }
              square  => { side: float64 }
              shape   => ( circle | square )
              holder  => { outline: shape }
              lookup  => { text => int32 }
            }
            """;

    private static final Json JSON = jsonUnder(ProcessorPolicy.defaults());

    private static Json jsonUnder(ProcessorPolicy policy) {
        Tson tson = Tson.standard();
        tson.resolve(SCHEMA);
        return Json.of(ProcessorConfig.defaults().withProcessorPolicy(policy)).withSchemas(tson.schemaRegistry());
    }

    private record Read(JsonValue value, List<Diagnostic> problems) {

        /** The refusal, asserting the other half too: nothing was reported as invalid. */
        Diagnostic refusal() {
            List<Diagnostic> policy = problems.stream().filter(d -> POLICY.contains(d.code())).toList();
            assertEquals(1, policy.size(), () -> "expected exactly one policy refusal, got " + problems);
            assertEquals(List.of(), problems.stream().filter(d -> !POLICY.contains(d.code())).toList(),
                    "§8.2: a refusal MUST NOT be reported in any of §8.1's four categories, so nothing else fires");
            return policy.getFirst();
        }
    }

    private static Read read(Json json, String typeName, String source) {
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of(source)) {
            JsonValue value = json.treeReader().withDiagnostics(receiver).withSchema(ID).readAs(bytes, typeName);
            return new Read(value, List.copyOf(problems));
        }
    }

    private static Read read(String typeName, String source) {
        return read(JSON, typeName, source);
    }

    // -- The case the rule exists for -------------------------------------

    /**
     * A member spelled with U+0430 where the record declares {@code password}. It matches no declared field,
     * so before this it drew {@code UNRECOGNIZED_FIELD}.
     */
    @Test
    void aLookAlikeFieldNameIsRefusedRatherThanCalledUnknown() {
        String lookAlike = "p" + CYRILLIC_A + "ssword";
        Diagnostic refusal = read("account",
                "{\"password\": \"s3cret\", \"" + lookAlike + "\": \"evil\"}").refusal();
        assertTrue(POLICY.contains(refusal.code()), refusal.code().toString());
        assertEquals("/" + lookAlike, refusal.path().orElseThrow());
    }

    /** A character outside the identifier profile is the other per-name rule, with its own code. */
    @Test
    void aRestrictedCharacterInAFieldNameIsRefused() {
        assertEquals(Diagnostic.Code.RESTRICTED_CHARACTER,
                read("account", "{\"password\": \"s3cret\", \"no te\": 1}").refusal().code());
    }

    /** §9.4 reaches every {@code $type} too: a look-alike variant name is refused, not called no variant. */
    @Test
    void aLookAlikeTypeNameIsRefused() {
        Diagnostic refusal = read("holder", "{\"outline\": {\"$type\": \"cir" + CYRILLIC_A
                + "le\", \"radius\": 1.0}}").refusal();
        assertTrue(POLICY.contains(refusal.code()), refusal.code().toString());
    }

    // -- The reach, which is narrower than "every name" -------------------

    /** A member matching a declared field carries that declaration's verdict, given when the schema loaded. */
    @Test
    void aMatchedFieldNameIsNotJudgedAgain() {
        assertEquals(List.of(), read("account", "{\"password\": \"s3cret\"}").problems());
    }

    /** §3.2: at a map position every member name is an ordinary key -- data, under the token policy. */
    @Test
    void aMapKeyIsNotJudgedByTheIdentifierPolicy() {
        assertEquals(List.of(), read("lookup", "{\"p" + CYRILLIC_A + "ssword\": 1}").problems());
    }

    /** A conforming document draws nothing, so the check costs an ordinary read no verdict. */
    @Test
    void anOrdinaryDocumentIsUnaffected() {
        assertEquals(List.of(), read("account", "{\"password\": \"s3cret\", \"note\": \"hi\"}").problems());
    }

    // -- The policy is configuration, and relaxing it is code -------------

    /**
     * §8.2 requires a relaxation be a code decision rather than an ambient one. Under an unrestricted
     * identifier policy the same document draws the ordinary closure verdict instead -- the honest answer
     * once nothing is refusing the name.
     */
    @Test
    void anUnrestrictedPolicyRefusesNothingAndTheClosureRuleSpeaks() {
        Json relaxed = jsonUnder(ProcessorPolicy.defaults().withIdentifierPolicy(UnicodePolicy.unrestricted()));
        List<Diagnostic> problems = read(relaxed, "account",
                "{\"password\": \"s3cret\", \"p" + CYRILLIC_A + "ssword\": \"evil\"}").problems();
        assertEquals(1, problems.size(), () -> String.valueOf(problems));
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, problems.getFirst().code());
        assertFalse(POLICY.contains(problems.getFirst().code()));
    }

    /** The schemaless door applies nothing: it holds no position, so a member name is not a field name. */
    @Test
    void theSchemalessReadJudgesNoName() {
        String source = "{\"p" + CYRILLIC_A + "ssword\": 1}";
        assertEquals(source.replace(": ", ":"), Json.standard().treeReader().read(source).toString());
    }
}
