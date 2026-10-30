package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaRefusalException;
import io.ltr8.tson.base.policy.LimitsPolicy;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.tree.TsonRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §3.4.1's wait order in an ordinary schema ({@code SPEC-FEEDBACK.md #4}): reading a value against a
 * type waits on that type being ready, so a constructor and its instance, a type and a value of it, share a document
 * in either order; entries waiting on each other are refused naming the cycle; a name at a type position orders
 * nothing; and the longest chain of waits is bounded by §11.5's wait depth.
 */
class WaitOrderTest {

    private static final String ID = "https://example.test/wait.tn";

    private static String schema(String declarations) {
        return """
                !!id:"https://example.test/wait.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                %s}
                """.formatted(declarations);
    }

    private static Tson tson(ProcessorConfig config, String declarations) {
        return Tson.of(config.withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(ID, schema(declarations))))));
    }

    private static Tson tson(String declarations) {
        return tson(ProcessorConfig.defaults(), declarations);
    }

    private static List<Diagnostic> validateSchema(String declarations) {
        return tson(declarations).validateSchema(schema(declarations));
    }

    private static List<Diagnostic> validate(Tson tson, String data) {
        return tson.validate("!!import:\"" + ID + "\"\n" + data);
    }

    // ── A read waits on what it is read against ───────────────────────────

    /** A constructor and its instance, in either order: the instance is a type data is read against. */
    @Test
    void aConstructorAndItsInstanceShareADocumentInEitherOrder() {
        for (String declarations : List.of("""
                  counted => array ^ { ordered?: = false }
                  counts  => !counted { element_type: int32 }
                """, """
                  counts  => !counted { element_type: int32 }
                  counted => array ^ { ordered?: = false }
                """)) {
            Tson tson = tson(declarations);
            assertEquals(List.of(), tson.validateSchema(schema(declarations)));

            ArrayBody counts = assertInstanceOf(ArrayBody.class,
                    tson.schemaRegistry().get(ID).orElseThrow().schema().entries().get("counts"));
            assertEquals(TypeRef.of("int32"), counts.elementType());
            assertFalse(counts.ordered());
            assertEquals(List.of(), validate(tson, "!counts [1 2]"));
            assertEquals(1, validate(tson, "!counts [\"one\"]").size());
        }
    }

    /** A value of a record type the same document declares, reaching another local type through a field. */
    @Test
    void aValueOfASameDocumentTypeWaitsOnItAndOnWhatItsFieldsReach() {
        for (String declarations : List.of("""
                  origin => !line { from: { x: 1  y: 2 }  to: { x: 3  y: 4 } }
                  line   => { from: point  to: point }
                  point  => { x: int32  y: int32 }
                """, """
                  point  => { x: int32  y: int32 }
                  line   => { from: point  to: point }
                  origin => !line { from: { x: 1  y: 2 }  to: { x: 3  y: 4 } }
                """)) {
            ValueEntry origin = assertInstanceOf(ValueEntry.class,
                    tson(declarations).resolve(schema(declarations)).schema().entries().get("origin"));
            assertEquals("line", origin.type());
            TsonRecord from = assertInstanceOf(TsonRecord.class,
                    assertInstanceOf(TsonRecord.class, origin.value()).get("from"));
            assertEquals(1, from.get("x").asInt().orElseThrow());
        }

        List<Diagnostic> problems = validateSchema("""
                  origin => !point { x: "left"  y: 2 }
                  point  => { x: int32  y: int32 }
                """);
        assertEquals(1, problems.size(), problems::toString);
        assertEquals("/origin", problems.getFirst().schemaPointer().orElseThrow());
    }

    // ── A name orders nothing ──────────────────────────────────────────────

    /** A loop of references is no cycle: a field type is a name, judged once every entry is ready. */
    @Test
    void aLoopOfReferencesIsSound() {
        Tson tree = tson("  tree => { label: text  children?: [tree] }\n");
        assertEquals(List.of(), tree.validateSchema(schema("  tree => { label: text  children?: [tree] }\n")));
        assertEquals(List.of(), validate(tree, "!tree { label: \"a\"  children: [{ label: \"b\" }] }"));

        assertEquals(List.of(), validateSchema("""
                  even => { next?: odd }
                  odd  => { next?: even }
                """));
    }

    /** An alias loop is no wait cycle either: §8.3 refuses an alias chain with no terminal. */
    @Test
    void anAliasLoopIsRefusedAsAnAliasChainNotAWaitCycle() {
        List<Diagnostic> problems = validateSchema("""
                  a => b
                  b => a
                """);

        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().noneMatch(problem -> problem.message().contains("waits on")),
                problems::toString);
    }

    // ── Rule 4: a wait cycle is refused ────────────────────────────────────

    /** An entry whose declaration is read against itself waits on itself. */
    @Test
    void anEntryWaitingOnItselfIsRefusedNamingIt() {
        List<Diagnostic> problems = validateSchema("  step => !step { }\n");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code());
        assertEquals("/step", problems.getFirst().schemaPointer().orElseThrow());
        assertTrue(problems.getFirst().message().startsWith("'step' waits on 'step'"), problems::toString);
    }

    /** Two entries each read against the other: the cycle is named in wait order, from where it was entered. */
    @Test
    void twoEntriesWaitingOnEachOtherAreRefusedNamingTheCycle() {
        List<Diagnostic> problems = validateSchema("""
                  c1 => !c2 { }
                  c2 => !c1 { }
                """);

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().startsWith("'c1' waits on 'c2' waits on 'c1'"), problems::toString);

        // A head and an operand are one edge: an instance whose constructor composes it waits through both.
        problems = validateSchema("""
                  made => !maker { }
                  maker => top & made & { }
                """);
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("waits on 'maker' waits on 'made'")
                || problems.getFirst().message().contains("waits on 'made' waits on 'maker'"), problems::toString);
    }

    // ── Rule 7: the wait depth ─────────────────────────────────────────────

    /** {@code w0 => w1 & {}} ... {@code wN => {}}: a chain of {@code N} waits, written forward or backward. */
    private static String chain(int waits, boolean reversed) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < waits; i++) {
            lines.add("  w" + i + " => w" + (i + 1) + " & { f" + i + ": text }\n");
        }
        lines.add("  w" + waits + " => { last: text }\n");
        if (reversed) {
            java.util.Collections.reverse(lines);
        }
        return String.join("", lines);
    }

    /** At the default the limit is 64 waits: a chain of 64 loads, one of 65 is refused at its head, in any order. */
    @Test
    void theWaitDepthAdmitsTheDefaultAndRefusesOneMore() {
        assertEquals(LimitsPolicy.DEFAULT_MAX_WAIT_DEPTH, LimitsPolicy.defaults().maxWaitDepth());
        for (boolean reversed : List.of(false, true)) {
            assertEquals(List.of(), validateSchema(chain(64, reversed)));

            List<Diagnostic> problems = validateSchema(chain(65, reversed));
            assertEquals(1, problems.size(), problems::toString);
            assertEquals(Diagnostic.Code.LIMIT_EXCEEDED, problems.getFirst().code());
            assertFalse(problems.getFirst().code().verdict());
            assertEquals("/w0", problems.getFirst().schemaPointer().orElseThrow(), problems::toString);
            assertTrue(problems.getFirst().message().contains("waits on a chain of 65 entries"), problems::toString);
            assertTrue(problems.getFirst().message().contains("wait depth of 64"), problems::toString);
        }
    }

    /** The limit is the deployment's: a configured depth is counted, and a fail-fast resolve throws the refusal. */
    @Test
    void aConfiguredWaitDepthIsCounted() {
        ProcessorConfig two = ProcessorConfig.defaults().withLimits(LimitsPolicy.defaults().withMaxWaitDepth(2));
        assertEquals(List.of(), tson(two, chain(2, false)).validateSchema(schema(chain(2, false))));

        List<Diagnostic> problems = tson(two, chain(3, true)).validateSchema(schema(chain(3, true)));
        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.LIMIT_EXCEEDED, problems.getFirst().code());
        assertEquals("/w0", problems.getFirst().schemaPointer().orElseThrow());

        SchemaRefusalException thrown = assertThrows(SchemaRefusalException.class,
                () -> tson(two, chain(3, false)).resolve(schema(chain(3, false))));
        assertEquals(Diagnostic.Code.LIMIT_EXCEEDED, thrown.code());
    }

    /**
     * A wait through a read counts whichever order the entries are reached in: {@code v}'s payload is read against
     * {@code point}, two waits deep, so {@code v} is three, whether {@code point} was made ready by {@code v}'s read or
     * before it.
     */
    @Test
    void aWaitThroughAReadIsCountedInEitherOrder() {
        ProcessorConfig two = ProcessorConfig.defaults().withLimits(LimitsPolicy.defaults().withMaxWaitDepth(2));
        String types = """
                  line  => { from: point }
                  point => p1 & { }
                  p1    => p2 & { }
                  p2    => { x: int32 }
                """;
        String value = "  v => !line { from: { x: 1 } }\n";
        for (String declarations : List.of(value + types, types + value)) {
            List<Diagnostic> problems = tson(two, declarations).validateSchema(schema(declarations));
            assertEquals(1, problems.size(), problems::toString);
            assertEquals(Diagnostic.Code.LIMIT_EXCEEDED, problems.getFirst().code());
            assertEquals("/v", problems.getFirst().schemaPointer().orElseThrow(), problems::toString);
            assertTrue(problems.getFirst().message().contains("'v' waits on 'point' waits on 'p1' waits on 'p2'"),
                    problems::toString);
        }
    }

    // ── A constructor no class backs ───────────────────────────────────────

    private static final String STEPS = """
              step => top & { takes: type_kind }
              order => { id: int32 }
              s => !step { takes: order }
            """;

    /**
     * An instance of a constructor whose instances are no types, which no class binds: held as a tree, so the schema
     * loads and validates and a tree registry compiles it; a schema obtained to bind refuses it at the entry.
     */
    @Test
    void anInstanceNoClassBindsIsHeldAsATreeAndRefusedWhereTheSchemaIsBound() {
        Tson tson = tson(ProcessorConfig.defaults().withDataBindContext(DataBindContext.builder().build()), STEPS);
        assertEquals(List.of(), tson.validateSchema(schema(STEPS)));

        ValueEntry s = assertInstanceOf(ValueEntry.class,
                tson.bindRegistry().core().resolveLinked(ID).schema().entries().get("s"));
        assertEquals("step", s.type());
        assertInstanceOf(TsonRecord.class, s.value());
        assertNotNull(tson.treeRegistry().get(ID));

        List<Diagnostic> problems = new ArrayList<>();
        tson.bindRegistry().get(ID, problems::add);
        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.BIND_MISMATCH, problems.getFirst().code());
        assertEquals("/s", problems.getFirst().schemaPointer().orElseThrow());
    }

    /** The consumer's class for {@code step}. */
    public record Step(TypeRef takes) {
    }

    /** Where a class binds the constructor, the instance is that class's object, and the schema binds. */
    @Test
    void anInstanceAClassBindsIsThatClasssObject() {
        Tson tson = tson(ProcessorConfig.defaults().withDataBindContext(DataBindContext.builder()
                .nameBinder(DataNameBinder.ofMap(Map.of("step", Step.class))).build()), STEPS);

        ValueEntry s = assertInstanceOf(ValueEntry.class,
                tson.bindRegistry().core().resolveLinked(ID).schema().entries().get("s"));
        assertEquals(new Step(TypeRef.of("order")), s.value());
        assertNotNull(tson.bindRegistry().get(ID));
    }
}
