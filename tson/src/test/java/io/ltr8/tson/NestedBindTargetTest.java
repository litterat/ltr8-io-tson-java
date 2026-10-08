package io.ltr8.tson;

import io.ltr8.annotation.Tuple;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.TsonObjectReader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A component's class is the target at every depth below it, not only at its own field: a collection's element,
 * a map's key and value and a tuple's position each receive what the class declares there. Without it a
 * {@code List<Long>} holds the {@code Integer}s an {@code int32} element reads to, which erasure lets through
 * until a consumer unboxes one; and what the class cannot hold is refused when the schema is compiled.
 */
class NestedBindTargetTest {

    private static final String ID = "https://example.test/nested-bind-1.tn";
    private static final String SCHEMA = """
            !!id:"https://example.test/nested-bind-1.tn"
            !!meta:"https://tson.io/2026/37/m/meta.tn"
            !!import:"https://tson.io/2026/37/m/core.tn"
            {
              counts   => { values: [int32] }
              tally    => { values: {text => int32} }
              keyed    => { values: {int32 => text} }
              grid     => { rows: [[int32]] }
              holes    => { rows: [[int32?]] }
              ranked   => !map { key_type: text  value_type: int32  ordered: true }
              tables   => { maps: [ranked] }
              pairs    => { values: [[text, int32]] }
              point    => { x: int32  y: int32 }
              points   => { values: [point] }
              groups   => { values: {text => set<int32>} }
            }
            """;

    public record Counts(List<Long> values) {
    }

    public record Tally(Map<String, Long> values) {
    }

    public record Keyed(Map<Long, String> values) {
    }

    public record Grid(List<int[]> rows) {
    }

    public record GridOfText(List<String> rows) {
    }

    public record Tables(List<SequencedMap<String, Integer>> maps) {
    }

    public record HashTables(List<HashMap<String, Integer>> maps) {
    }

    @Tuple
    public record Pair(String label, int count) {
    }

    public record Pairs(List<Pair> values) {
    }

    public record Point(int x, int y) {
    }

    public record Other(int x, int y) {
    }

    public record Points(List<Other> values) {
    }

    public record Groups(Map<String, TreeSet<Integer>> values) {
    }

    private final List<Diagnostic> diagnostics = new ArrayList<>();

    private <T> T read(String type, Class<T> bound, String document) {
        SchemaSource source = uri -> {
            if (uri.startsWith(ID)) {
                return SCHEMA;
            }
            throw new SchemaFetchException(uri, SchemaFetchException.Reason.NOT_FOUND, "only " + ID, null);
        };
        DataBindContext context = DataBindContext.builder()
                .nameBinder(DataNameBinder.ofMap(Map.of(type, bound, "point", Point.class)))
                .registerAtoms(AtomContext.hostTypes()).build();
        TsonObjectReader reader = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source))
                .withDataBindContext(context)).objectReader().withSchema(ID).withDiagnostics(diagnostics::add);
        return reader.readAs(document, type, bound);
    }

    private String refusal(String type, Class<?> bound, String document) {
        assertNull(read(type, bound, document));
        assertEquals(List.of(Diagnostic.Code.BIND_MISMATCH), diagnostics.stream().map(Diagnostic::code).toList());
        return diagnostics.getFirst().message();
    }

    @Test
    void anElementIsNarrowedToTheClassItsCollectionDeclares() {
        Counts counts = read("counts", Counts.class, "{ values: [1 2] }");
        assertEquals(List.of(1L, 2L), counts.values());
        assertInstanceOf(Long.class, counts.values().getFirst());
    }

    @Test
    void aMapsKeyAndValueAreNarrowedToTheClassesItDeclares() {
        assertInstanceOf(Long.class, read("tally", Tally.class, "{ values: { a => 1 } }").values().get("a"));
        assertEquals(Map.of(7L, "seven"), read("keyed", Keyed.class, "{ values: { 7 => seven } }").values());
    }

    @Test
    void aNestedArrayIsBuiltAsTheClassItsElementDeclares() {
        Grid grid = read("grid", Grid.class, "{ rows: [[1 2] [3]] }");
        assertArrayEquals(new int[] {1, 2}, grid.rows().get(0));
        assertArrayEquals(new int[] {3}, grid.rows().get(1));
    }

    @Test
    void aNestedInlineTupleBindsToTheElementsTupleClass() {
        assertEquals(new Pairs(List.of(new Pair("a", 1), new Pair("b", 2))),
                read("pairs", Pairs.class, "{ values: [[a 1] [b 2]] }"));
    }

    @Test
    void aNestedOrderedMapKeepsTheOrderTheDocumentWrote() {
        Tables tables = read("tables", Tables.class, "{ maps: [{ zeta => 1  alpha => 2 }] }");
        assertEquals(List.of("zeta", "alpha"), List.copyOf(tables.maps().getFirst().keySet()));
    }

    @Test
    void aNestedOrderedMapBoundToAMapThatDoesNotKeepOrderIsRefused() {
        assertTrue(refusal("tables", HashTables.class, "{ maps: [] }").contains("field 'maps''s element is an "
                + "ordered map, and List's element binds java.util.HashMap, which does not keep insertion order"));
    }

    @Test
    void aNestedVoidElementBoundToAPrimitiveArrayIsRefused() {
        assertTrue(refusal("holes", Grid.class, "{ rows: [] }").contains("field 'rows''s element admits void "
                + "elements, and List's element binds int[], which has no null to hold one"));
    }

    @Test
    void aNestedArrayBoundToAnAtomIsRefused() {
        assertTrue(refusal("grid", GridOfText.class, "{ rows: [] }").contains("field 'rows''s element is an "
                + "array, and List's element binds java.lang.String"));
    }

    @Test
    void aNestedRecordBoundToAnotherClassIsRefused() {
        assertTrue(refusal("points", Points.class, "{ values: [] }").contains("field 'values''s element binds "
                + Point.class.getName() + ", which List's element (" + Other.class.getName() + ") cannot hold"));
    }

    /** A {@code TreeSet} has no capacity constructor, and is built through its no-argument one. */
    @Test
    void aNestedSortedSetIsBuiltAsTheSetItsMapDeclares() {
        TreeSet<Integer> group = read("groups", Groups.class, "{ values: { a => [3 1 2] } }").values().get("a");
        assertEquals(List.of(1, 2, 3), List.copyOf(group));
    }
}
