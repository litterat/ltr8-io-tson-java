package io.ltr8.tson.json;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.base.policy.UnicodePolicy;
import io.ltr8.tson.compiler.TsonObjectReader;
import io.ltr8.tson.compiler.TsonTreeReader;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * One rule for every read: a document whose read reported anything reads to nothing, in tree mode and bind mode
 * and in both encodings, and the diagnostics are the answer. A partial value cannot say which of its parts to
 * trust -- a tree's placeholder for a refused value is the same node as a real absent one.
 */
class AllOrNothingReadTest {

    private static final String ID = "https://example.test/all-or-nothing-1.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/all-or-nothing-1.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
            {
              point  => { x: int32  y: int32 }
              route  => { name: text  stops: [point] }
            }
            """;

    public record Point(int x, int y) {
    }

    public record Route(String name, List<Point> stops) {
    }

    private static ProcessorConfig config() {
        DataBindContext binding = DataBindContext.builder()
                .nameBinder(DataNameBinder.ofMap(Map.of("point", Point.class, "route", Route.class)))
                .registerAtoms(AtomContext.hostTypes()).build();
        return ProcessorConfig.defaults().withDataBindContext(binding);
    }

    private static final Tson TSON = Tson.of(config());
    private static final Json JSON;

    static {
        TSON.resolve(SCHEMA);
        JSON = Json.of(config()).withSchemas(TSON.schemaRegistry());
    }

    /** One refusal two levels down: the second stop's {@code x}. */
    private static final String BAD_TSON = "{ name: loop  stops: [ { x: 1  y: 2 } { x: a  y: 2 } ] }";
    private static final String BAD_JSON = """
            {"name": "loop", "stops": [{"x": 1, "y": 2}, {"x": "a", "y": 2}]}""";

    private static void refusedAtTheStop(DiagnosticsCollector problems) {
        assertEquals(List.of("/stops/1/x"), problems.diagnostics().stream()
                .map(d -> d.path().orElse("?")).toList(), problems.diagnostics().toString());
    }

    @Test
    void aTreeReadThatReportedAnythingReadsToNothingInBothEncodings() {
        DiagnosticsCollector fromTson = new DiagnosticsCollector();
        assertNull(TSON.treeReader().withDiagnostics(fromTson).withSchema(ID).readAs(BAD_TSON, "route"));
        refusedAtTheStop(fromTson);

        DiagnosticsCollector fromJson = new DiagnosticsCollector();
        assertNull(JSON.treeReader().withDiagnostics(fromJson).withSchema(ID).readAs(BAD_JSON, "route"));
        refusedAtTheStop(fromJson);
    }

    @Test
    void aBindReadThatReportedAnythingReadsToNothingInBothEncodings() {
        DiagnosticsCollector fromTson = new DiagnosticsCollector();
        assertNull(TSON.objectReader().withDiagnostics(fromTson).withSchema(ID)
                .readAs(BAD_TSON, "route", Route.class));
        refusedAtTheStop(fromTson);

        DiagnosticsCollector fromJson = new DiagnosticsCollector();
        assertNull(JSON.objectReader().withDiagnostics(fromJson).withSchema(ID)
                .readAs(BAD_JSON, "route", Route.class));
        refusedAtTheStop(fromJson);
    }

    /** The same documents made valid read whole, so the nulls above are the rule and not a broken read. */
    @Test
    void aCleanReadReadsWhole() {
        String tson = BAD_TSON.replace("x: a", "x: 3");
        String json = BAD_JSON.replace("\"x\": \"a\"", "\"x\": 3");
        assertNotNull(TSON.treeReader().withSchema(ID).readAs(tson, "route"));
        assertNotNull(JSON.treeReader().withSchema(ID).readAs(json, "route"));
        Route expected = new Route("loop", List.of(new Point(1, 2), new Point(3, 2)));
        assertEquals(expected, TSON.objectReader().withSchema(ID).readAs(tson, "route", Route.class));
        assertEquals(expected, JSON.objectReader().withSchema(ID).readAs(json, "route", Route.class));
    }

    public record Note(String text) {
    }

    /**
     * A token refusal reaches the receiver from the stream, past every read context -- counted at the receiver,
     * it still leaves nothing to read, in every mode and both encodings.
     */
    @Test
    void aTokenRefusalLeavesNothingInEveryModeAndBothEncodings() {
        String text = "p\u0430ssword";
        UnicodePolicy ascii = UnicodePolicy.asciiOnly();

        Map<String, Object> read = new LinkedHashMap<>();
        DiagnosticsCollector tsonTree = new DiagnosticsCollector();
        read.put("tson tree", new TsonTreeReader().withTokenPolicy(ascii).withDiagnostics(tsonTree)
                .read("{ text: \"" + text + "\" }"));
        DiagnosticsCollector tsonBind = new DiagnosticsCollector();
        read.put("tson bind", new TsonObjectReader().withTokenPolicy(ascii).withDiagnostics(tsonBind)
                .read("{ text: \"" + text + "\" }", Note.class));

        ProcessorPolicy policy = ProcessorPolicy.defaults().withTokenPolicy(ascii);
        DiagnosticsCollector jsonTree = new DiagnosticsCollector();
        read.put("json tree", JsonTreeReader.standard().withProcessorPolicy(policy).withDiagnostics(jsonTree)
                .read("{\"text\": \"" + text + "\"}"));
        DiagnosticsCollector jsonBind = new DiagnosticsCollector();
        read.put("json bind", JsonObjectReader.standard().withProcessorPolicy(policy).withDiagnostics(jsonBind)
                .read("{\"text\": \"" + text + "\"}", Note.class));

        Map<String, Object> nothing = new LinkedHashMap<>();
        read.keySet().forEach(mode -> nothing.put(mode, null));
        assertEquals(nothing, read);
        for (DiagnosticsCollector problems : List.of(tsonTree, tsonBind, jsonTree, jsonBind)) {
            assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT), problems.diagnostics().stream()
                    .map(Diagnostic::code).toList(), problems.diagnostics().toString());
        }
    }
}
