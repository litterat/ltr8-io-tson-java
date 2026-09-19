package io.ltr8.tson.json;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.base.bind.AtomContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bind-mode front door: {@code Json.withSchemas(loader).objectReader().withSchema(uri).readAs(source, type,
 * Class)}, the peer of {@code TsonObjectReader.withSchema(uri).readAs(...)}.
 */
class JsonObjectReaderSchemaTest {

    private static final String ID = "https://example.test/object-reader-1.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/object-reader-1.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
            {
              line    => { sku: text  quantity: int32  price: float64 }
              order   => { customer: text  lines: [line]  note: text ~ "none" }
              orphan  => { value: text }
              at_least_one => !integer ^ { min: 1  max: 100 }
              positive => { count: at_least_one }
            }
            """;

    public record Line(String sku, int quantity, double price) {
    }

    public record Order(String customer, List<Line> lines, String note) {
    }

    public record Positive(int count) {
    }

    private static Json json(Map<String, Class<?>> bindings) {
        Tson tson = Tson.standard();
        tson.resolve(SCHEMA);
        DataBindContext binding = DataBindContext.builder().nameBinder(DataNameBinder.ofMap(bindings))
                .registerAtoms(AtomContext.hostTypes()).build();
        return Json.of(ProcessorConfig.defaults().withDataBindContext(binding)).withSchemas(tson.schemaRegistry());
    }

    private static final Json JSON = json(Map.of("line", Line.class, "order", Order.class,
            "positive", Positive.class));

    private static final String ORDER = """
            {"customer": "Ada", "lines": [{"sku": "A-1", "quantity": 2, "price": 9.99}]}""";

    @Test
    void aDocumentIsValidatedAgainstTheSchemaAndBuiltIntoTheClass() {
        Order order = JSON.objectReader().withSchema(ID).readAs(ORDER, "order", Order.class);
        assertEquals(new Order("Ada", List.of(new Line("A-1", 2, 9.99)), "none"), order);
    }

    /** What the class alone could not check -- a facet -- is checked against the schema. */
    @Test
    void theSchemasOwnRulesAreApplied() {
        List<Diagnostic> problems = new ArrayList<>();
        Positive value = JSON.objectReader().withDiagnostics(problems::add).withSchema(ID)
                .readAs("{\"count\": 0}", "positive", Positive.class);
        assertNull(value);
        assertEquals("/count", problems.getFirst().path().orElseThrow());
    }

    @Test
    void aFailFastReaderThrowsAtTheFirstProblem() {
        assertThrows(ReadException.class, () -> JSON.objectReader().withSchema(ID)
                .readAs("{\"customer\": \"Ada\"}", "order", Order.class));
    }

    @Test
    void aCollectingReaderReportsEveryProblemAndBindsNothing() {
        List<Diagnostic> problems = new ArrayList<>();
        Order order = JSON.objectReader().withDiagnostics(problems::add).withSchema(ID)
                .readAs("{\"lines\": [{\"sku\": 1}]}", "order", Order.class);
        assertNull(order);
        assertTrue(problems.size() >= 3, problems.toString());
    }

    @Test
    void aSchemaNothingSuppliesIsADiagnosticNotAVerdict() {
        List<Diagnostic> problems = new ArrayList<>();
        assertNull(JSON.objectReader().withDiagnostics(problems::add).withSchema("https://example.test/none.tn")
                .readAs(ORDER, "order", Order.class));
        assertEquals(Diagnostic.Code.SCHEMA_NOT_FOUND, problems.getFirst().code());
    }

    @Test
    void aTypeTheSchemaDoesNotDeclareIsUnknown() {
        List<Diagnostic> problems = new ArrayList<>();
        assertNull(JSON.objectReader().withDiagnostics(problems::add).withSchema(ID)
                .readAs(ORDER, "invoice", Order.class));
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE, problems.getFirst().code());
    }

    /** A root type bound to a class the caller cannot hold is refused before anything is read. */
    @Test
    void aRootTypeBoundToAnotherClassIsRefusedBeforeTheRead() {
        List<Diagnostic> problems = new ArrayList<>();
        assertNull(JSON.objectReader().withDiagnostics(problems::add).withSchema(ID)
                .readAs(ORDER, "order", Line.class));
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, problems.getFirst().code());
    }

    public record OrderWithoutNote(String customer, List<Line> lines) {
    }

    /** The schema and the bound classes disagreeing is the application's wiring, reported as such. */
    @Test
    void aClassThatDoesNotMatchItsSchemaIsABindMismatch() {
        Json json = json(Map.of("line", Line.class, "order", OrderWithoutNote.class));
        List<Diagnostic> problems = new ArrayList<>();
        assertNull(json.objectReader().withDiagnostics(problems::add).withSchema(ID)
                .readAs(ORDER, "order", OrderWithoutNote.class));
        assertEquals(Diagnostic.Code.BIND_MISMATCH, problems.getFirst().code());
    }

    @Test
    void aTypeWithNoBoundClassReachesTheCallerAsItself() {
        assertThrows(MissingBindingException.class, () -> JSON.objectReader().withSchema(ID)
                .readAs("{\"value\": \"x\"}", "orphan", Object.class));
    }

    @Test
    void aReaderWithoutSchemasCanNameNone() {
        assertThrows(IllegalStateException.class, () -> Json.standard().objectReader().withSchema(ID));
    }
}
