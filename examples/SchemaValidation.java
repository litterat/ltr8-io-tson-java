/// Validate a TSON data document against a TSON *schema* -- no build tool.
///
/// A single-file Java 25 program (compact source file + instance `main`), loading the `tson`
/// library via the module system:
///
///   ./gradlew :tson:modules
///   java --module-path tson/build/modules --add-modules io.ltr8.tson examples/SchemaValidation.java
///
/// `Tson.builder().build()` bootstraps the standard library (meta-kernel/meta.tn/core.tn); `resolve`
/// registers a schema under its own `!!id`. This data isn't self-describing, so the reader is told which
/// schema and type apply -- `withSchema(uri).readAs(source, type)`. Tree mode returns an immutable,
/// queryable TsonValue -- no Java class involved, the TSON schema is the source of truth -- that preserves
/// structure and types and is navigable with get/at.
import module io.ltr8.tson;

void main() {
    Tson tson = Tson.builder().build();

    String schema = """
            !!id:"https://example.com/2026/32/app/server-1.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            {
                server => { hostname: text  port: int32 }
            }""";

    tson.resolve(schema);                       // registers it under its own !!id
    var reader = tson.treeReader().withSchema("https://example.com/2026/32/app/server-1.tn");

    TsonValue server = reader.readAs("{ hostname: \"web-01\"  port: 8080 }", "server");
    IO.println("hostname: " + server.get("hostname").asString().orElseThrow());   // web-01
    IO.println("port:     " + server.at("/port").asInt().orElseThrow());        // 8080

    // A bad value surfaces as a diagnostic rather than a wrong result. Deriving a reader with a
    // collecting receiver gathers every problem in one pass instead of stopping at the first:
    var problems = TsonDiagnosticsReceiver.collecting();
    var bad = "{ hostname: \"web-01\"  port: 99999999999999 }";   // port is out of int32 range
    reader.withDiagnostics(problems).readAs(bad, "server");
    for (Diagnostic d : problems.diagnostics()) {
        // path is Optional because "" is RFC 6901's root, a real location, not an absence.
        IO.println("problem: " + d.path().orElse("(no data location)") + " -- " + d.message());
    }
}
