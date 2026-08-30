package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonDocumentHeader;
import io.ltr8.tson.compiler.lexer.Xid;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.suite.Sidecar;
import io.ltr8.tson.suite.Vectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static io.ltr8.tson.suite.Sidecar.fieldText;
import static io.ltr8.tson.suite.Sidecar.fieldTextArray;
import static io.ltr8.tson.suite.Sidecar.hasField;
import static io.ltr8.tson.suite.Sidecar.outcomeOf;
import static io.ltr8.tson.suite.Sidecar.outcomePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs the shared corpus's {@code class2/} vectors -- the Class 2 half of {@code ltr8-io-tson-test-suite},
 * against the {@link Tson} front door.
 *
 * <p><b>{@code RUNNER.md} in that repo is normative for this class</b>, exactly as it is for the Class 1
 * runner ({@code ConformanceSuiteTest}), and the two share what the rules are about: {@link Sidecar} reads a
 * sidecar and splices a subject's header, {@link Vectors} walks the tree.
 *
 * <p><b>Why the front door rather than the pipeline stages.</b> A Class 2 vector is about a phase boundary
 * -- did this schema resolve, did it link, does this document validate against it -- and those boundaries
 * are {@link Tson#validateSchema} and {@link Tson#validate}'s to own, not a test's to reassemble. Driving
 * the resolver and linker directly would measure a pipeline no consumer runs, and would quietly answer
 * "does it report?" differently from the library.
 *
 * <p><b>The three layers, and what each one's expected side is.</b>
 * <ul>
 *   <li>{@code schema/} -- [TSON-SCHEMA] §1.3 makes producing a resolved schema value a MUST and §8 fixes
 *       its serialization, so a valid vector states the resolved output in the spec's own form and this
 *       compares entry for entry, through {@link ResolvedForm}'s normalisations.</li>
 *   <li>{@code link/} -- individual facts about the linked namespace: §2.2.3's import closure, §5.4's
 *       derived disjointness, §8.2's {@code subtypes} index.</li>
 *   <li>{@code validate/} -- a data document against a schema that loaded, where the expected side of a
 *       failure is §8.1's category and the RFC 6901 pointer into the data, and nothing else.</li>
 * </ul>
 *
 * <p><b>A fresh {@link Tson} per vector.</b> Each holds its own schema registry, and a vector registering a
 * schema must not be able to satisfy the next one's reference to it -- a corpus whose vectors depend on
 * their own running order measures the order.
 */
class Class2ConformanceSuiteTest {

    @TestFactory
    Stream<DynamicTest> schemaVectors() {
        return Vectors.in("class2", "schema", Class2ConformanceSuiteTest::checkSchemaVector);
    }

    @TestFactory
    Stream<DynamicTest> linkVectors() {
        return Vectors.in("class2", "link", Class2ConformanceSuiteTest::checkLinkVector);
    }

    @TestFactory
    Stream<DynamicTest> validateVectors() {
        return Vectors.in("class2", "validate", Class2ConformanceSuiteTest::checkValidateVector);
    }

    private static Tson newTson() {
        // The schema.meta-resolving context, because a `schema/` vector binds a resolved-form document back
        // into the value model to compare it. It changes nothing for the other two layers.
        return Tson.builder().dataBindContext(SchemaMetaNameBinder.defaultContext()).build();
    }

    // ── Schema-layer vectors: §8's resolved output ───────────────────────

    /**
     * A schema document, and what §8 says it resolves to.
     *
     * <p>The expected side is read with this implementation's own meta.tn-governed reader, so a vector that
     * writes something meta.tn does not admit fails here rather than being compared as-is -- which is what
     * makes the corpus's expected side validated data rather than a transcript.
     */
    private static void checkSchemaVector(String bucket, java.nio.file.Path subject, RecordValue sidecar)
            throws IOException {
        Tson tson = newTson();
        String source = Sidecar.splicedSource(subject, sidecar);
        List<Diagnostic> problems = tson.validateSchema(source);

        switch (outcomeOf(sidecar)) {
            case "valid" -> {
                assertEquals(List.of(), problems, "a valid schema vector must load cleanly");
                Map<String, TypeDefinition> ours = ResolvedForm.ownEntries(tson, identityOf(source));
                Map<String, TypeDefinition> expected =
                        ResolvedForm.readResolved(tson, fieldText(outcomePayload(sidecar), "resolved"));
                assertEquals(new TreeSet<>(expected.keySet()), new TreeSet<>(ours.keySet()),
                        "the resolved schema does not declare the entries the vector states");
                expected.forEach((name, definition) -> assertEquals(
                        ResolvedForm.rendered(definition), ResolvedForm.rendered(ours.get(name)),
                        name + " does not resolve to what the vector states"));
                // §8.2 puts the derived @synthetic marker on the key of every entry the resolver
                // materialised from a sugar form and on no other, so the marked set is a claim in its own
                // right -- and one a bound comparison cannot make, a key-position annotation being dropped
                // when a resolved-form document is read back.
                assertEquals(ResolvedForm.markedSynthetics(fieldText(outcomePayload(sidecar), "resolved")),
                        ResolvedForm.ourSynthetics(tson, identityOf(source)),
                        "the entries marked @synthetic are not the ones the vector marks");
            }
            case "error" -> assertSchemaLoadFailed(sidecar, problems);
            case "refused" -> assertSchemaRefused(sidecar, problems);
            default -> fail("unknown schema-layer outcome: " + outcomeOf(sidecar));
        }
    }

    /**
     * §8.1's fifth outcome at the schema layer: the schema is <b>refused by this processor</b> under one of
     * §8.2's name-hygiene mechanisms, over the scopes [TSON-SCHEMA] §11.4 supplies.
     *
     * <p>{@code checkRefusedVector}'s peer, asserting the same two halves for the same reason: something was
     * refused, and nothing was reported as a verdict on the schema's correctness. A processor that reported a
     * confusable declaration the way it reports an unresolved reference has not passed the vector -- §8.2
     * keeps these out of validity because they read data the UCD does not freeze, and being able to tell them
     * apart is the whole of it.
     */
    private static void assertSchemaRefused(RecordValue sidecar, List<Diagnostic> problems) {
        RecordValue refusal = outcomePayload(sidecar);
        String stated = fieldText(refusal, "unicode");
        Assumptions.assumeTrue(Xid.UNICODE_VERSION.equals(stated),
                "vector computed against UTS #39 data for Unicode " + stated + "; this implementation "
                        + "carries " + Xid.UNICODE_VERSION);

        assertFalse(problems.isEmpty(), "the schema is refused, but it loaded without a diagnostic");
        assertTrue(problems.stream().anyMatch(diagnostic -> isPolicyRefusal(diagnostic.code())),
                "expected a §8.2 refusal (" + fieldText(refusal, "mechanism") + "); got " + problems);
        problems.forEach(diagnostic -> assertTrue(isPolicyRefusal(diagnostic.code()),
                "a refused schema must not also be reported invalid: " + diagnostic));
    }

    /**
     * The two codes that mean <em>refused under a stated policy</em> rather than <em>wrong</em>: §8.2's
     * mechanism 1 over a scope, and its mechanisms 2 and 3 over a name. Every other code is a verdict on the
     * schema, which is exactly what a refusal is not.
     */
    private static boolean isPolicyRefusal(Diagnostic.Code code) {
        return code == Diagnostic.Code.CONFUSABLE_NAMES || code == Diagnostic.Code.RESTRICTED_TOKEN;
    }

    // ── Link-layer vectors: §2.2.3, §5.4, §5.10.1, §8.2 ──────────────────

    /** What linking makes of a schema that has already resolved. */
    private static void checkLinkVector(String bucket, java.nio.file.Path subject, RecordValue sidecar)
            throws IOException {
        Tson tson = newTson();
        String source = Sidecar.splicedSource(subject, sidecar);
        List<Diagnostic> problems = tson.validateSchema(source);

        switch (outcomeOf(sidecar)) {
            case "valid" -> {
                assertEquals(List.of(), problems, "a valid link vector must load cleanly");
                assertLinkedNamespaceMatches(outcomePayload(sidecar),
                        tson.bindRegistry().core().resolveLinked(identityOf(source)));
            }
            case "error" -> assertSchemaLoadFailed(sidecar, problems);
            default -> fail("unknown link-layer outcome: " + outcomeOf(sidecar));
        }
    }

    private static void assertLinkedNamespaceMatches(RecordValue expected, TsonLinkedSchema linked) {
        var entries = linked.schema().entries();

        if (hasField(expected, "binds")) {
            // Names normalised on both sides: a materialised entry's trailing content hash is not
            // normative (§8.2, RUNNER.md rule 6), and a vector that stated one would be testing a hash
            // function. Everything else about a name is compared as written.
            var bound = new TreeSet<String>();
            entries.keySet().forEach(name -> bound.add(ResolvedForm.withoutHash(name)));
            for (String name : fieldTextArray(expected, "binds")) {
                assertTrue(bound.contains(ResolvedForm.withoutHash(name)),
                        "the linked namespace does not bind '" + name + "'; it binds " + bound);
            }
        }
        if (hasField(expected, "disjoint")) {
            for (RecordValue claim : Sidecar.fieldRecordArray(expected, "disjoint")) {
                String name = fieldText(claim, "name");
                TypeDefinition definition = definitionOf(entries, name);
                assertEquals(Boolean.parseBoolean(fieldText(claim, "value")),
                        definition.disjoint().orElseThrow(() ->
                                new AssertionError(name + " has no derived disjointness at all")),
                        "§5.4 disjointness of " + name);
            }
        }
        if (hasField(expected, "subtypes")) {
            for (RecordValue claim : Sidecar.fieldRecordArray(expected, "subtypes")) {
                String name = fieldText(claim, "name");
                assertEquals(new TreeSet<>(fieldTextArray(claim, "subtypes")),
                        new TreeSet<>(definitionOf(entries, name).subtypes()),
                        "§8.2 subtypes index of " + name);
            }
        }
    }

    private static TypeDefinition definitionOf(Map<String, TypeDefinition> entries, String name) {
        TypeDefinition definition = entries.get(name);
        if (definition == null) {
            throw new AssertionError("the linked namespace binds no entry '" + name + "'");
        }
        return definition;
    }

    // ── Validate-layer vectors: data against a schema that loaded ────────

    /**
     * A data document read against the schema its sidecar names -- the layer where [TSON-DATA] §8.1's
     * {@code validation} category is finally reachable, being "reserved for data checked against a
     * successfully loaded schema".
     *
     * <p>Read through a collecting receiver, so a document with two problems reports both and a vector
     * naming one of them is not claiming it is the only one.
     */
    private static void checkValidateVector(String bucket, java.nio.file.Path subject, RecordValue sidecar)
            throws IOException {
        byte[] raw = Sidecar.subjectBytes(subject, sidecar);
        List<Diagnostic> reported = new ArrayList<>();
        newTson().treeReader().withDiagnostics(reported::add).read(new ByteArrayInputStream(raw));

        switch (outcomeOf(sidecar)) {
            case "valid" -> assertEquals(List.of(), reported, "a valid document must read without a diagnostic");
            case "error" -> {
                assertFalse(reported.isEmpty(), "the document is invalid, but nothing was reported");
                RecordValue expected = outcomePayload(sidecar);
                String category = fieldText(expected, "category");
                String path = hasField(expected, "path") ? fieldText(expected, "path") : null;
                assertTrue(reported.stream().anyMatch(diagnostic -> matches(diagnostic, category, path)),
                        "no diagnostic is a " + category + " error"
                                + (path == null ? "" : " at path '" + path + "'") + "; got " + reported);
            }
            default -> fail("unknown validate-layer outcome: " + outcomeOf(sidecar));
        }
    }

    private static boolean matches(Diagnostic diagnostic, String category, String path) {
        return categoryOf(diagnostic).equals(category)
                && (path == null || diagnostic.path().map(path::equals).orElse(false));
    }

    // ── §8.1 categories (RUNNER.md rule 3) ───────────────────────────────

    /**
     * A schema-layer or link-layer {@code error} vector: the schema did not load, which is what
     * [TSON-DATA] §8.1 calls a resolver error whatever rule it broke.
     *
     * <p><b>The category is the phase's here, not the diagnostic code's</b>, and §8.1 says so outright:
     * "every error that makes a schema fail to load or ingest -- incoherent constraint values, invalid
     * defaults, refuted assertions, failed ingest checks -- is a resolver error, however value-like the
     * violated rule, because it is detected while resolving the schema. Validation errors are reserved for
     * data checked against a successfully loaded schema." A schema-authoring mistake that this library
     * happens to catch through the meta-schema's own compiled reader arrives carrying a record-shaped code,
     * and it is still a resolver error: the code says which rule, the phase says which category.
     *
     * <p>What is still checked per diagnostic is that each one is a <em>verdict</em>. A gap, a binding
     * mismatch or an unobtainable schema says the vector could not be judged, and letting one stand for a
     * refusal is how a corpus comes to pass on the strength of not having been run.
     */
    private static void assertSchemaLoadFailed(RecordValue sidecar, List<Diagnostic> problems) {
        assertEquals("resolver", fieldText(outcomePayload(sidecar), "category"),
                "every Class 2 schema- and link-layer error is a resolver error (§8.1)");
        assertFalse(problems.isEmpty(), "the schema is invalid, but it loaded without a diagnostic");
        problems.forEach(Class2ConformanceSuiteTest::assertIsAVerdict);
    }

    /**
     * §8.1's four categories, from a {@link Diagnostic} reported over data. Three of this library's codes
     * are deliberately not a verdict on the document and so belong to no category at all, and two more are
     * §8.2 policy refusals, which §8.1 says MUST NOT be reported in any of the four -- a vector that lands
     * on one of those five is not a vector that passed.
     */
    private static String categoryOf(Diagnostic diagnostic) {
        assertIsAVerdict(diagnostic);
        return switch (diagnostic.code()) {
            case FIELD_REQUIRED, FIELD_FIXED, TYPE_MISMATCH, WRONG_ARITY, UNRECOGNIZED_FIELD,
                 ATOM_CONSTRAINT_VIOLATION, VALIDATION_ERROR -> "validation";
            case UNKNOWN_TYPE_REF, UNKNOWN_TYPE, DUPLICATE_FIELD, DUPLICATE_MAP_KEY, SCHEMA_ERROR -> "resolver";
            case RESTRICTED_TOKEN, CONFUSABLE_NAMES -> fail(
                    "§8.2 name hygiene is a policy refusal, which §8.1 says MUST NOT be reported in any of "
                            + "the four categories: " + diagnostic);
            default -> fail("unclassified diagnostic code: " + diagnostic);
        };
    }

    private static void assertIsAVerdict(Diagnostic diagnostic) {
        switch (diagnostic.code()) {
            case NOT_IMPLEMENTED, BIND_MISMATCH, SCHEMA_UNAVAILABLE -> fail(
                    "this is not a verdict on the document -- it says the vector could not be checked: "
                            + diagnostic);
            default -> {
            }
        }
    }

    /** The identity a subject claims for itself, which is what its resolved form is registered under. */
    private static String identityOf(String source) {
        return TsonDocumentHeader.peek(source).id().orElseThrow(() ->
                new AssertionError("a class2/ subject must carry its own !!id"));
    }
}
