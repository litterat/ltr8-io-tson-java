package io.ltr8.tson.json;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.json.tree.JsonValue;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.util.List;
import java.util.UUID;

/**
 * Scratch: what each JSON rung of the ladder catches for one sensor document. Prints, does not assert --
 * the output is the research for the tson-io ladder page.
 */
class ScratchLadderJsonTest {

    public enum Kind { temperature, humidity }

    public sealed interface Zone permits Circle, Rect {}
    public record Circle(double cx, double cy, double radius) implements Zone {}
    public record Rect(double x, double y, double w, double h) implements Zone {}

    public record Sensor(UUID id, Inet4Address host, Kind kind, double reading, Integer interval, Zone zone) {}
    public record SensorNoZone(UUID id, Inet4Address host, Kind kind, double reading, Integer interval) {}

    static final String GOOD = """
            {
              "id": "9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09",
              "host": "10.0.0.1",
              "kind": "temperature",
              "reading": 21.5,
              "zone": { "cx": 0, "cy": 0, "radius": 3 }
            }""";

    static final String BAD = """
            {
              "id": "not-a-uuid",
              "host": "10.0.0.999",
              "kind": "pressure",
              "reading": 999,
              "interval": null,
              "colour": "red",
              "zone": { "cx": 0, "cy": 0, "radius": -3 }
            }""";

    private static void show(String label, Runnable r) {
        System.out.println("=== " + label);
        try {
            r.run();
        } catch (ReadException e) {
            System.out.println("  THROWN " + e.diagnostic().code() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            System.out.println("  THROWN " + e);
        }
    }

    private static void dump(List<Diagnostic> ds) {
        if (ds.isEmpty()) System.out.println("  (no problems)");
        for (Diagnostic d : ds) {
            System.out.println("  " + d.code() + " " + d.path().orElse("-") + " : " + d.message()
                    + (d.expectedIfStated().map(x -> "  [expected " + x + "]").orElse(""))
                    + (d.actualIfStated().map(x -> " [actual " + x + "]").orElse("")));
        }
    }

    @Test
    void rung1_jsonTree() {
        show("rung 1: Json.parse GOOD", () -> {
            JsonValue v = Json.parse(GOOD);
            System.out.println("  host is a " + v.get("host").getClass().getSimpleName() + " = " + v.get("host").asString());
            System.out.println("  zone is a " + v.get("zone").getClass().getSimpleName());
        });
        show("rung 1: Json.parse BAD (everything is fine as far as JSON knows)", () -> {
            JsonValue v = Json.parse(BAD);
            System.out.println("  host = " + v.get("host").asString() + ", kind = " + v.get("kind").asString()
                    + ", reading = " + v.get("reading").asDouble() + ", interval = " + v.get("interval"));
        });
        show("rung 1: what JSON does catch", () -> {
            DiagnosticsCollector c = new DiagnosticsCollector();
            JsonTreeReader.standard().withDiagnostics(c).read("{\"a\": 1, \"a\": 2, \"b\": [1,]}");
            dump(c.diagnostics());
        });
        show("rung 1: syntax", () -> Json.parse("{\"a\": 1, \"a\": 2, \"b\": [1,]}"));
    }

    @Test
    void rung2_jsonObjectReader() {
        JsonObjectReader reader = JsonObjectReader.standard();
        show("rung 2: GOOD into SensorNoZone", () -> {
            String noZone = GOOD.replace(",\n  \"zone\": { \"cx\": 0, \"cy\": 0, \"radius\": 3 }", "");
            System.out.println("  " + reader.read(noZone, SensorNoZone.class));
        });
        show("rung 2: GOOD into Sensor (with the sealed Zone)", () -> {
            DiagnosticsCollector c = new DiagnosticsCollector();
            Object o = reader.withDiagnostics(c).read(GOOD, Sensor.class);
            System.out.println("  result " + o);
            dump(c.diagnostics());
        });
        show("rung 2: BAD into SensorNoZone, collecting", () -> {
            String noZone = BAD.replace(",\n  \"zone\": { \"cx\": 0, \"cy\": 0, \"radius\": -3 }", "");
            DiagnosticsCollector c = new DiagnosticsCollector();
            Object o = reader.withDiagnostics(c).read(noZone, SensorNoZone.class);
            System.out.println("  result " + o);
            dump(c.diagnostics());
        });
        show("rung 2: reading 999 and radius -3 into doubles bind fine -- no facets", () -> {
            String s = GOOD.replace("21.5", "999");
            String noZone = s.replace(",\n  \"zone\": { \"cx\": 0, \"cy\": 0, \"radius\": 3 }", "");
            System.out.println("  " + reader.read(noZone, SensorNoZone.class));
        });
        show("rung 2: missing interval -> null, no default injected", () -> {
            String noZone = GOOD.replace(",\n  \"zone\": { \"cx\": 0, \"cy\": 0, \"radius\": 3 }", "");
            System.out.println("  interval = " + reader.read(noZone, SensorNoZone.class).interval());
        });
    }
}
