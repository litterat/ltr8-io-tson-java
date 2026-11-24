package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a checkpoint's compile cannot build as the full closure's would: an entry whose class disagrees with it, and
 * a record whose reader depends on subtypes not yet held. Either is a read's refusal only where the full closure
 * refuses the read too ([TSON-SCHEMA] §3.4.1); a data document's compile is unchanged.
 */
class CheckpointCompileTest {

    private static final String ID = "https://example.test/checkpoint.tn";

    private static final String HEADER = """
            !!id:"https://example.test/checkpoint.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
            """;

    /** No component for {@code currency}: a bind mismatch with {@code order => { sku: text  currency: text }}. */
    public record Order(String sku) {
    }

    public sealed interface Shape permits Circle, Square {
    }

    public record Circle(BigInteger r) implements Shape {
    }

    public record Square(BigInteger side) implements Shape {
    }

    public record Holder(Shape s) {
    }

    public record A(String x) {
    }

    private static final DataBindContext HOST = DataBindContext.builder().nameBinder(DataNameBinder.ofMap(Map.of(
            "order", Order.class, "shape", Shape.class, "circle", Circle.class, "square", Square.class,
            "holder", Holder.class, "a", A.class))).build();

    private static Tson tson(String declarations) {
        return Tson.of(ProcessorConfig.defaults().withDataBindContext(HOST)
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(ID, HEADER + declarations + "}\n")))));
    }

    private static List<Diagnostic> validateSchema(String declarations) {
        return tson(declarations).validateSchema(HEADER + declarations + "}\n");
    }

    // ── A bind mismatch ───────────────────────────────────────────────────

    private static final String MISMATCHED = """
              order => { sku: text  currency: text }
              w     => { a: text }
              v     => !w { a: "x" }
            """;

    /** A checkpoint holding the mismatched entry reads every other value; the mismatch is a read's that reaches it. */
    @Test
    void aBindMismatchAtACheckpointIsTheReadsThatReachesIt() {
        assertEquals(List.of(), validateSchema(MISMATCHED));

        List<Diagnostic> problems = validateSchema(MISMATCHED + "  o => !order { sku: \"A\"  currency: \"B\" }\n");
        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.BIND_MISMATCH, problems.getFirst().code(), problems::toString);
        assertEquals("/o", problems.getFirst().schemaPointer().orElseThrow(), problems::toString);
    }

    /** A data document's compile still refuses the class where the schema meets it, before any document. */
    @Test
    void aDataDocumentsCompileStillRefusesTheMismatchAtCompile() {
        BindMismatchException thrown = assertThrows(BindMismatchException.class,
                () -> tson("  order => { sku: text  currency: text }\n").bindRegistry().get(ID));
        assertTrue(thrown.getMessage().contains("no component for field 'currency'"), thrown.getMessage());
    }

    // ── A record whose reader depends on its subtypes ────────────────────

    /**
     * {@code a}'s annotation is read before {@code circle} and {@code square} are ready, so at its checkpoint {@code
     * shape} has no subtype and its sealed interface no reader. The value's tag decides first: the read waits on
     * {@code square} alone, as the full closure's read does, and not on {@code circle}, which waits on {@code a}.
     */
    @Test
    void aReaderACheckpointCannotBuildDecidesByTheTagFirst() {
        assertEquals(List.of(), validateSchema("""
                  shape  => { }
                  holder => { s: shape }
                  a      => @holder:{ s: !square { side: 1 } } { x: text }
                  circle => @a:{ x: "q" } shape & { r: integer }
                  square => shape & { side: integer }
                """));
    }

    /**
     * Untagged, the value names no type, so the read waits on a declaration that may be a subtype and is refused, once
     * {@code shape} has its subtypes, as the full closure refuses it: a value naming no member of a family with no
     * data of its own, never a reader this library lacks.
     */
    @Test
    void anUntaggedValueThereIsRefusedAsInTheFullClosure() {
        List<Diagnostic> problems = validateSchema("""
                  shape  => { }
                  holder => { s: shape }
                  a      => @holder:{ s: { side: 1 } } { x: text }
                  square => shape & { side: integer }
                """);
        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code(), problems::toString);
        assertTrue(problems.getFirst().message().contains("has no data of its own to bind"), problems::toString);

        List<Diagnostic> full = validateSchema("""
                  shape  => { }
                  square => shape & { side: integer }
                  holder => { s: shape }
                  a      => { x: text }
                  b      => @holder:{ s: { side: 1 } } { x: text }
                """);
        assertEquals(1, full.size(), full::toString);
        assertEquals(problems.getFirst().code(), full.getFirst().code(), full::toString);
    }
}
