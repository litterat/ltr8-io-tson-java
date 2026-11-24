package io.ltr8.tson.json;

import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.json.tree.JsonValue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A wrapper's {@code $value} is the annotated value itself and no annotation object ([TSON-JSON] §3.3): a value's
 * type is written once, so where the selected type is not map-typed an object there leading with an annotation member
 * is refused, at the member, in every mode. What {@code $value} holds at its own positions is annotated as anywhere.
 */
class JsonOneTypeNameTest {

    private static final String ID = "https://example.test/json-one-type-name.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/json-one-type-name.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              shape    => abstract { name: text }
              circle   => shape & { r: int32 }
              square   => shape & { side: int32 }
              person   => { name: text }
              employee => person & { dept: text }
              memo     => { note: text }
              small    => !int32 ^ { max: 10 }
              names    => [shape]
              lookup   => { text => int32 }
              atlas    => { text => shape }
              box      => { s: shape }
              item     => ( shape | lookup | small | names )
              figure   => ( shape | memo )
              h  => { i?: item  p?: person  m?: memo  any?: meta.declared }
              fh => { f: figure  m: memo }
            }
            """;

    private static final ProcessorConfig CONFIG = ProcessorConfig.defaults()
            .withSchemaAccess(SchemaAccess.of((SchemaSource) uri -> SCHEMA));

    private static final Tson TSON = Tson.of(CONFIG);

    static {
        TSON.resolve(SCHEMA);
    }

    private static final Json JSON = Json.of(CONFIG).withSchemas(TSON.schemaRegistry());

    private record Read(JsonValue value, List<Diagnostic> problems) {
    }

    private static Read read(String root, String json) {
        List<Diagnostic> problems = new ArrayList<>();
        JsonValue value = JSON.treeReader().withSchema(ID).withDiagnostics(problems::add).readAs(json, root);
        return new Read(value, problems);
    }

    private static List<String> refusals(String root, String json) {
        return read(root, json).problems().stream().map(d -> d.code() + " " + d.path().orElse("")).toList();
    }

    private static void assertClean(String root, String json) {
        assertEquals(List.of(), read(root, json).problems().stream().map(Diagnostic::message).toList(), json);
    }

    /** The one spelling: the subtype named by the one {@code $type}. */
    @Test
    void aSubtypeIsNamedByTheOneType() {
        assertClean("h", """
                {"i": {"$type": "circle", "name": "a", "r": 2}, "p": {"$type": "employee", "name": "a", "dept": "d"}}""");
    }

    @Test
    void anInlineAnnotationObjectInAWrappersValueIsRefusedAtItsType() {
        assertEquals(List.of("UNRECOGNIZED_FIELD /i/$value/$type"), refusals("h", """
                {"i": {"$type": "shape", "$value": {"$type": "circle", "name": "a", "r": 2}}}"""));
        assertEquals(List.of("UNRECOGNIZED_FIELD /p/$value/$type"), refusals("h", """
                {"p": {"$type": "person", "$value": {"$type": "employee", "name": "a", "dept": "d"}}}"""));
        assertEquals(List.of("UNRECOGNIZED_FIELD /m/$value/$type"), refusals("h", """
                {"m": {"$type": "memo", "$value": {"$type": "memo", "note": "n"}}}"""));
        Diagnostic refusal = read("h", """
                {"i": {"$type": "shape", "$value": {"$type": "circle", "name": "a", "r": 2}}}""").problems().getFirst();
        assertEquals("'$value' is the annotated value itself, and no annotation object: a value's type is written "
                + "once, in the wrapper's '$type', which names a subtype directly (§3.3)", refusal.message());
    }

    @Test
    void aWrapperInAWrappersValueIsRefused() {
        assertEquals(List.of("UNRECOGNIZED_FIELD /i/$value/$type"), refusals("h", """
                {"i": {"$type": "small", "$value": {"$type": "small", "$value": 5}}}"""));
        assertEquals(List.of("UNRECOGNIZED_FIELD /i/$value/$value"), refusals("h", """
                {"i": {"$type": "small", "$value": {"$value": 5}}}"""));
    }

    /** An {@code $import} there is the one refusal an {@code $import} meets at every value position. */
    @Test
    void anImportInAWrappersValueIsRefusedAsAtEveryValuePosition() {
        assertEquals(List.of("SCOPE_NOT_ADMITTED /i/$value/$import"), refusals("h", """
                {"i": {"$type": "shape", "$value": {"$import": ["https://example.test/x.tn"], "name": "a"}}}"""));
    }

    @Test
    void theValuesAWrappersValueHoldsAreAnnotatedAsAtAnyPosition() {
        assertClean("h", """
                {"i": {"$type": "names", "$value": [{"$type": "circle", "name": "a", "r": 1}]}}""");
        assertClean("h", """
                {"any": {"$type": "box", "$value": {"s": {"$type": "square", "name": "a", "side": 1}}}}""");
        assertClean("h", """
                {"any": {"$type": "atlas", "$value": {"k": {"$type": "circle", "name": "a", "r": 1}}}}""");
    }

    /** At a map-typed selection the members of {@code $value} are keys (§3.2, §8.3.1), the three among them. */
    @Test
    void aMapTypedValueHoldsTheThreeAsKeys() {
        Read choice = read("h", """
                {"i": {"$type": "lookup", "$value": {"$type": 1, "$value": 2}}}""");
        assertEquals(List.of(), choice.problems());
        assertEquals("{\"i\":{\"$type\":1,\"$value\":2}}", JSON.treeWriter().toJson(choice.value()));
        assertClean("h", """
                {"any": {"$type": "lookup", "$value": {"$import": 3}}}""");
    }

    @Test
    void theRootsWrapperIsAWrapperToo() {
        assertEquals(List.of("UNRECOGNIZED_FIELD /$value/$type"), refusals("person", """
                {"$type": "person", "$value": {"$type": "employee", "name": "a", "dept": "d"}}"""));
        assertClean("person", """
                {"$type": "person", "$value": {"name": "a"}}""");
    }

    // ── bind mode ──

    public sealed interface Figure permits Shape, Memo {
    }

    public sealed interface Shape extends Figure permits Circle, Square {
    }

    @Typename(name = "circle")
    public record Circle(String name, int r) implements Shape {
    }

    @Typename(name = "square")
    public record Square(String name, int side) implements Shape {
    }

    @Typename(name = "memo")
    public record Memo(String note) implements Figure {
    }

    public record Fh(Figure f, Memo m) {
    }

    private static Fh bind(String json, List<Diagnostic> problems) {
        Map<String, Class<?>> names = Map.of("figure", Figure.class, "shape", Shape.class, "circle", Circle.class,
                "square", Square.class, "memo", Memo.class, "fh", Fh.class);
        DataBindContext binding = DataBindContext.builder().nameBinder(DataNameBinder.ofMap(names)).build();
        return Json.of(CONFIG.withDataBindContext(binding)).withSchemas(TSON.schemaRegistry()).objectReader()
                .withSchema(ID).withDiagnostics(problems::add).readAs(json, "fh", Fh.class);
    }

    @Test
    void bindModeRefusesATypeInsideAWrappersValueAndBindsTheOneSpelling() {
        List<Diagnostic> problems = new ArrayList<>();
        assertNull(bind("""
                {"f": {"$type": "shape", "$value": {"$type": "circle", "name": "a", "r": 2}},
                 "m": {"$type": "memo", "$value": {"$type": "memo", "note": "n"}}}""", problems));
        assertEquals(List.of("UNRECOGNIZED_FIELD /f/$value/$type", "UNRECOGNIZED_FIELD /m/$value/$type"),
                problems.stream().map(d -> d.code() + " " + d.path().orElse("")).toList());

        List<Diagnostic> none = new ArrayList<>();
        Fh read = bind("""
                {"f": {"$type": "circle", "name": "a", "r": 2}, "m": {"$type": "memo", "$value": {"note": "n"}}}""",
                none);
        assertTrue(none.isEmpty(), none.toString());
        assertEquals(new Fh(new Circle("a", 2), new Memo("n")), read);
    }
}
