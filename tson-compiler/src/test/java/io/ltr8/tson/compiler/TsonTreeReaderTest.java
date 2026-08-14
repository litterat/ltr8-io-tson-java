package io.ltr8.tson.compiler;

import io.ltr8.tson.tree.TsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schemaless {@link TsonTreeReader}: TSON text straight to a {@link TsonNode} tree with no schema,
 * structure and types coming from the wire. Proves records stay records and arrays stay arrays (no schema
 * to distinguish tuple), leaves are base-resolved (or built-in-typed when tagged), wire type-refs are
 * captured, and null/absent/empty-brace map to the right kinds.
 *
 * <p>The type-ref rules a schemaless read applies -- a built-in name must sit on a token and its token must
 * satisfy the atom, any other name resolves to nothing and is reported unless preserved -- are the second
 * half, from {@link #aBuiltInTypeRefOnAContainerIsAMismatch} down. {@link #READER} is preserving so the
 * structural fixtures can go on using {@code !person} as scenery; {@link #STRICT} is the default reader.
 */
class TsonTreeReaderTest {

    private static final TsonTreeReader READER = new TsonTreeReader().preservingUnknownTypeRefs();

    private static final TsonTreeReader STRICT = new TsonTreeReader();

    /** Every problem from a strict schemaless read of {@code source}, rather than the first as an exception. */
    private static List<Diagnostic> problemsIn(String source) {
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        STRICT.withDiagnostics(problems).read(source);
        return problems.diagnostics();
    }

    @Test
    void readsRecordsMapsArraysAndTypedLeavesWithNoSchema() {
        TsonNode node = READER.read("""
                !person {
                  name: "Ada"
                  age: 30
                  id: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09
                  joined: !date 1843-12-10
                  address: { city: "London" }
                  skills: ["a" "b"]
                  active: true
                  note: null
                  nickname: _
                }
                """);

        assertTrue(node.isRecord());
        assertEquals(Optional.of("person"), node.typeRef());                          // wire type-ref captured
        assertEquals(Optional.of("Ada"), node.get("name").asString());
        assertEquals(BigInteger.valueOf(30), node.get("age").asBigInteger().orElseThrow()); // schemaless -> BigInteger
        assertEquals(Optional.of("uuid"), node.get("id").typeRef());
        assertTrue(node.get("id").as(UUID.class).isPresent());                        // built-in atom typing
        assertEquals(LocalDate.of(1843, 12, 10), node.at("/joined").as(LocalDate.class).orElseThrow());
        assertTrue(node.get("address").isRecord());                                   // nested record stays a record
        assertEquals(Optional.of("London"), node.at("/address/city").asString());
        assertTrue(node.get("skills").isArray());                                     // array, never tuple (schemaless)
        assertEquals(Optional.of("b"), node.at("/skills/1").asString());
        assertEquals(Boolean.TRUE, node.get("active").asBoolean().orElseThrow());
        assertTrue(node.get("note").isNull());                                        // the null token
        assertTrue(node.get("nickname").isAbsent());                                  // the _ sentinel
    }

    @Test
    void emptyBraceReadsAsAnEmptyRecord() {
        TsonNode node = READER.read("{}");
        assertTrue(node.isRecord());
        assertTrue(node.get("anything").isMissing());
    }

    @Test
    void readsAMapWithTypedKeys() {
        TsonNode node = READER.read("{ \"a\" => 1  \"b\" => 2 }");
        assertTrue(node.isMap());
        assertEquals(BigInteger.ONE, node.get("a").asBigInteger().orElseThrow());
        assertEquals(BigInteger.TWO, node.get("b").asBigInteger().orElseThrow());
    }

    @Test
    void readsARootAtom() {
        assertEquals(BigInteger.valueOf(42), READER.read("42").asBigInteger().orElseThrow());
        assertEquals(Optional.of("hi"), READER.read("\"hi\"").asString());
    }

    @Test
    void malformedInputThrows() {
        assertThrows(RuntimeException.class, () -> READER.read("{ unterminated"));
    }

    // ── Type-ref checking (TypeRefCheck's rules) ─────────────────────────

    @Test
    void aBuiltInTypeRefOnAContainerIsAMismatch() {
        List<Diagnostic> problems = problemsIn("!uuid { a: 1 }");

        assertEquals(1, problems.size());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, problems.get(0).code());
        assertEquals("", problems.get(0).path());
        assertTrue(problems.get(0).message().contains("!uuid"));

        assertEquals(Diagnostic.Code.TYPE_MISMATCH, problemsIn("!date [1 2]").get(0).code());
    }

    /** Reported, but the value is still read -- so a collecting pass finds the atom problem underneath too. */
    @Test
    void aMismatchedContainerIsStillReadStructurally() {
        List<Diagnostic> problems = problemsIn("!uuid { a: !int8 999 }");

        assertEquals(2, problems.size());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, problems.get(0).code());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, problems.get(1).code());
        assertEquals("/a", problems.get(1).path());
    }

    @Test
    void aTypeRefNamingNoBuiltInTypeIsUnknown() {
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF, problemsIn("!nosuchtype { a: 1 }").get(0).code());

        List<Diagnostic> nested = problemsIn("{ a: !nosuchtype 1 }");
        assertEquals(1, nested.size());
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF, nested.get(0).code());
        assertEquals("/a", nested.get(0).path());
    }

    /** §5.1 is case-sensitive, so a near-miss of a built-in name is exactly what this rule is for. */
    @Test
    void aCaseTypoOfABuiltInNameIsNotSilentlyIgnored() {
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF,
                problemsIn("{ id: !Uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09 }").get(0).code());
    }

    @Test
    void aTokenTheBuiltInAtomRejectsIsAConstraintViolation() {
        List<Diagnostic> problems = problemsIn("{ a: !uuid nope }");

        assertEquals(1, problems.size());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, problems.get(0).code());
        assertEquals("/a", problems.get(0).path());
        assertTrue(problems.get(0).dataPosition().isPresent());
    }

    /** Fail-fast is the default receiver, so the same problem arrives as a TsonReadException carrying the diagnostic. */
    @Test
    void aRejectedTokenThrowsUnderTheDefaultReceiver() {
        TsonReadException thrown = assertThrows(TsonReadException.class, () -> STRICT.read("{ a: !uuid nope }"));
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, thrown.diagnostic().code());
    }

    /** The failed leaf keeps its place and its wire type-ref; only its value is gone. */
    @Test
    void aRejectedTokenLeavesANullPlaceholderInTheTree() {
        TsonNode node = STRICT.withDiagnostics(TsonDiagnosticsReceiver.collecting())
                .read("{ a: !uuid nope  b: 2 }");

        assertTrue(node.at("/a").isNull());
        assertEquals(Optional.of("uuid"), node.at("/a").typeRef());
        assertEquals(BigInteger.TWO, node.at("/b").asBigInteger().orElseThrow());
    }

    @Test
    void preservingKeepsAnUnlinkedTypeRefWithoutReportingIt() {
        TsonNode node = READER.read("!person { a: !nosuchtype 1 }");

        assertEquals(Optional.of("person"), node.typeRef());
        assertEquals(Optional.of("nosuchtype"), node.at("/a").typeRef());
        assertEquals(BigInteger.ONE, node.at("/a").asBigInteger().orElseThrow()); // base-resolved, §4
    }

    /** Preserving relaxes rule 3 only -- a built-in name is still held to its own contract. */
    @Test
    void preservingStillChecksBuiltInTypeRefs() {
        assertThrows(TsonReadException.class, () -> READER.read("{ a: !uuid nope }"));
    }

    /** An annotation's value is a data-value (§3.1), so the same rules reach inside it. */
    @Test
    void anAnnotationsOwnValueIsCheckedToo() {
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, problemsIn("@since:!date nope 1").get(0).code());
    }

    @Test
    void aProblemInsideAMapEntryIsPathedByItsKey() {
        List<Diagnostic> problems = problemsIn("{ \"a\" => !uuid nope }");

        assertEquals(1, problems.size());
        assertEquals("/a", problems.get(0).path());
    }
}
