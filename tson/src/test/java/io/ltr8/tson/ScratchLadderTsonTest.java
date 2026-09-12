package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.TsonObjectReader;
import io.ltr8.tson.compiler.TsonTreeReader;
import io.ltr8.tson.tree.TsonValue;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scratch: what each TSON rung of the ladder catches for one sensor document. Prints, does not assert --
 * the output is the research for the tson-io ladder page. The JSON rungs are ScratchLadderJsonTest.
 */
class ScratchLadderTsonTest {

    public enum Kind { temperature, humidity }

    public sealed interface Zone permits Circle, Rect {}
    public record Circle(double cx, double cy, double radius) implements Zone {}
    public record Rect(double x, double y, double w, double h) implements Zone {}

    public record Sensor(UUID id, Inet4Address host, Kind kind, double reading, Integer interval, Zone zone, String label) {}

    static final String SCHEMA_ID = "https://example.com/sensors-1.tn";
    static final String SCHEMA = """
            !!id:"https://example.com/sensors-1.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              kind    => !enum [temperature humidity]
              reading => !number ^ { min: -50  max: 150 }
              radius  => !number ^ { exclusive_min: 0 }
              circle  => { cx: number  cy: number  radius: radius }
              rect    => { x: number  y: number  w: number  h: number }
              zone    => (circle | rect)
              sensor  => {
                id:       uuid
                host:     ipv4
                kind:     kind
                reading:  reading
                interval: int32 ~ 60
                zone:     zone
                label:    text?
              }
            }""";

    static final String GOOD = """
            {
              id:      !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09
              host:    !ipv4 10.0.0.1
              kind:    temperature
              reading: 21.5
              zone:    !circle { cx: 0  cy: 0  radius: 3 }
            }""";

    static final String BAD = """
            {
              id:       !uuid not-a-uuid
              host:     !ipv4 10.0.0.999
              kind:     pressure
              reading:  999
              interval: _
              colour:   red
              zone:     { cx: 0  cy: 0  radius: -3 }
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
                    + (d.actualIfStated().map(x -> " [actual " + x + "]").orElse(""))
                    + (d.schemaPointer().map(x -> " [schema " + x + "]").orElse("")));
        }
    }

    @Test
    void rung3_tsonTreeSchemaless() {
        show("rung 3: schemaless tree GOOD", () -> {
            DiagnosticsCollector c = new DiagnosticsCollector();
            TsonValue v = new TsonTreeReader().withDiagnostics(c).read(GOOD);
            System.out.println("  host -> " + v.get("host").as(Inet4Address.class) + " typeRef " + v.get("host").typeRef());
            System.out.println("  id   -> " + v.get("id").as(UUID.class));
            System.out.println("  reading -> " + v.get("reading").asBigDecimal() + " / kind -> " + v.get("kind").asString());
            System.out.println("  zone typeRef " + v.get("zone").typeRef() + " isRecord " + v.get("zone").isRecord());
            System.out.println("  interval -> " + v.get("interval"));
            dump(c.diagnostics());
        });
        show("rung 3: schemaless tree GOOD, preserving unknown type-refs", () -> {
            DiagnosticsCollector c = new DiagnosticsCollector();
            TsonValue v = new TsonTreeReader().preservingUnknownTypeRefs().withDiagnostics(c).read(GOOD);
            System.out.println("  zone typeRef " + v.get("zone").typeRef());
            dump(c.diagnostics());
        });
        show("rung 3: schemaless tree BAD, collecting", () -> {
            DiagnosticsCollector c = new DiagnosticsCollector();
            TsonValue v = new TsonTreeReader().withDiagnostics(c).read(BAD);
            System.out.println("  reading -> " + v.get("reading").asBigDecimal() + " / kind -> " + v.get("kind").asString()
                    + " / colour -> " + v.get("colour").asString() + " / interval absent? " + v.get("interval").isAbsent());
            dump(c.diagnostics());
        });
        show("rung 3: duplicate field", () -> {
            DiagnosticsCollector c = new DiagnosticsCollector();
            new TsonTreeReader().withDiagnostics(c).read("{ a: 1  a: 2 }");
            dump(c.diagnostics());
        });
    }

    @Test
    void rung4_tsonObjectSchemaless() {
        TsonObjectReader reader = new TsonObjectReader();
        show("rung 4: schemaless object GOOD into Sensor", () -> {
            DiagnosticsCollector c = new DiagnosticsCollector();
            Sensor s = reader.withDiagnostics(c).read(GOOD, Sensor.class);
            System.out.println("  " + s);
            dump(c.diagnostics());
        });
        show("rung 4: schemaless object BAD into Sensor, collecting", () -> {
            DiagnosticsCollector c = new DiagnosticsCollector();
            Sensor s = reader.withDiagnostics(c).read(BAD, Sensor.class);
            System.out.println("  " + s);
            dump(c.diagnostics());
        });
        show("rung 4: !rect dispatches too; !triangle does not", () -> {
            System.out.println("  " + reader.read(GOOD.replace("!circle { cx: 0  cy: 0  radius: 3 }",
                    "!rect { x: 0  y: 0  w: 2  h: 2 }"), Sensor.class).zone());
            DiagnosticsCollector c = new DiagnosticsCollector();
            reader.withDiagnostics(c).read(GOOD.replace("!circle", "!triangle"), Sensor.class);
            dump(c.diagnostics());
        });
    }

    private static Tson tson() {
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(SCHEMA_ID, SCHEMA)))));
    }

    private static String describe(String body) {
        return "!!schema:\"" + SCHEMA_ID + "\"\n!sensor " + body;
    }

    @Test
    void rung5_tsonWithSchema() {
        Tson tson = tson();
        show("rung 5: validateSchema", () -> dump(tson.validateSchema(SCHEMA)));
        show("rung 5: validate GOOD", () -> dump(tson.validate(describe(GOOD))));
        show("rung 5: tree read GOOD -- what the schema added", () -> {
            TsonValue v = tson.treeReader().read(describe(GOOD));
            System.out.println("  interval -> " + v.get("interval") + " (injected default)");
            System.out.println("  reading  -> " + v.get("reading").asBigDecimal() + " typeRef " + v.get("reading").typeRef());
            System.out.println("  host     -> " + v.get("host").as(Inet4Address.class));
            System.out.println("  zone     -> typeRef " + v.get("zone").typeRef());
            System.out.println("  label    -> " + v.get("label"));
        });
        show("rung 5: validate BAD -- every problem at once", () -> dump(tson.validate(describe(BAD))));
        show("rung 5: untagged zone alone", () -> dump(tson.validate(describe(
                GOOD.replace("!circle ", "")))));
        show("rung 5: unpinned type-refs are unnecessary: host without !ipv4 still an ipv4", () -> {
            TsonValue v = tson.treeReader().read(describe(GOOD.replace("!ipv4 ", "").replace("!uuid ", "")));
            System.out.println("  host -> " + v.get("host").as(Inet4Address.class) + " id -> " + v.get("id").as(UUID.class));
            dump(tson.validate(describe("{ id: 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09  host: 10.0.0.999  kind: humidity  reading: 1  zone: !rect { x: 0 y: 0 w: 1 h: 1 } }")));
        });
        show("rung 5: object read under the schema", () -> {
            DiagnosticsCollector c = new DiagnosticsCollector();
            Sensor s = tson.objectReader().withDiagnostics(c).read(describe(GOOD), Sensor.class);
            System.out.println("  " + s);
            dump(c.diagnostics());
        });
        show("rung 5: wrong hash pin", () -> dump(tson.validate(
                "!!schema:\"" + SCHEMA_ID + "?sha256=" + "0".repeat(64) + "\"\n!sensor " + GOOD)));
        show("rung 5: a schema nobody serves", () -> dump(tson.validate(
                "!!schema:\"https://example.com/other.tn\"\n!sensor " + GOOD)));
        show("rung 5: a bad schema -- author error at load, not at first read", () -> dump(tson.validateSchema(
                SCHEMA.replace("min: -50  max: 150", "min: 150  max: -50").replace("sensors-1", "sensors-bad"))));
    }

    public record SensorShort(UUID id, Inet4Address host, Kind kind, double reading, Integer interval, Zone zone) {}

    private static Tson tsonBound(Map<String, Class<?>> names) {
        DataBindContext ctx = DataBindContext.builder()
                .nameBinder(DataNameBinder.ofMap(names).orElse(SchemaMetaNameBinder.INSTANCE))
                .registerAtoms(AtomContext.hostTypes())
                .build();
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(SCHEMA_ID, SCHEMA))))
                .withDataBindContext(ctx));
    }

    @Test
    void rung5b_objectReadUnderSchema() {
        show("rung 5b: object read GOOD with bind context", () -> {
            Tson tson = tsonBound(Map.of("sensor", Sensor.class, "circle", Circle.class, "rect", Rect.class));
            System.out.println("  " + tson.objectReader().read(describe(GOOD), Sensor.class));
        });
        show("rung 5b: object read BAD with bind context, collecting", () -> {
            Tson tson = tsonBound(Map.of("sensor", Sensor.class, "circle", Circle.class, "rect", Rect.class));
            DiagnosticsCollector c = new DiagnosticsCollector();
            Sensor s = tson.objectReader().withDiagnostics(c).read(describe(BAD), Sensor.class);
            System.out.println("  " + s);
            dump(c.diagnostics());
        });
        show("rung 5b: a class that disagrees with the schema (no label) fails at compile", () -> {
            Tson tson = tsonBound(Map.of("sensor", SensorShort.class, "circle", Circle.class, "rect", Rect.class));
            System.out.println("  " + tson.objectReader().read(describe(GOOD), SensorShort.class));
        });
    }

    @Test
    void pinExperiment() {
        String zero = "!!schema:\"" + SCHEMA_ID + "?sha256=" + "0".repeat(64) + "\"\n!sensor " + GOOD;
        String aaa = "!!schema:\"" + SCHEMA_ID + "?sha256=" + "a".repeat(64) + "\"\n!sensor " + GOOD;
        show("pin: fresh instance, zero pin first", () -> dump(tson().validate(zero)));
        show("pin: fresh instance, a-pin first", () -> dump(tson().validate(aaa)));
        show("pin: plain then zero pin", () -> { Tson t = tson(); t.validate(describe(GOOD)); dump(t.validate(zero)); });
        show("pin: plain then a-pin", () -> { Tson t = tson(); t.validate(describe(GOOD)); dump(t.validate(aaa)); });
        show("pin: validateSchema, then zero pin", () -> { Tson t = tson(); t.validateSchema(SCHEMA); dump(t.validate(zero)); });
        show("pin: treeReader plain, then zero pin", () -> { Tson t = tson(); t.treeReader().read(describe(GOOD)); dump(t.validate(zero)); });
    }

    @Test
    void radiusAndTaggedBad() {
        String taggedBad = BAD.replace("zone:     { cx: 0  cy: 0  radius: -3 }", "zone:     !circle { cx: 0  cy: 0  radius: -3 }");
        show("rung 5: BAD with the zone tagged -- the radius facet now reachable", () -> dump(tson().validate(describe(taggedBad))));
        show("rung 4: schemaless object, tagged bad radius binds fine", () -> {
            DiagnosticsCollector c = new DiagnosticsCollector();
            Sensor s = new TsonObjectReader().withDiagnostics(c).read(GOOD.replace("radius: 3", "radius: -3"), Sensor.class);
            System.out.println("  " + s);
            dump(c.diagnostics());
        });
        show("rung 5: number exactness -- 19.90 stays 19.90; 0x1F reads as 31", () -> {
            TsonValue v = new TsonTreeReader().read("{ a: 19.90  b: 0x1F  c: 1_000_000 }");
            System.out.println("  a=" + v.get("a").asBigDecimal().orElseThrow().toPlainString() + " b=" + v.get("b").asBigInteger() + " c=" + v.get("c").asBigInteger());
        });
    }
}
