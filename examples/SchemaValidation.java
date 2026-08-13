/// Validate a TSON data document against a TSON *schema* -- no build tool.
///
/// A single-file Java 25 program (compact source file + instance `main`), loading the `tson`
/// library via the module system:
///
///   ./gradlew :tson:modules
///   java --module-path tson/build/modules --add-modules io.ltr8.tson examples/SchemaValidation.java
///
/// `Tson.builder().build()` bootstraps the standard library (meta-kernel/meta.tn/core.tn);
/// `treeRegistry().compile(...)` turns a resolved schema into fast, reusable per-type readers. Tree mode
/// returns an immutable, queryable TsonNode -- no Java class involved, the TSON schema is the source of
/// truth -- that preserves structure and types and is navigable with get/at.
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

    var compiled = tson.treeRegistry().compile(tson.resolve(schema));
    var reader = compiled.get("server");

    TsonNode server = (TsonNode) reader.read(TsonReadContext.document("{ hostname: \"web-01\"  port: 8080 }"));
    IO.println("hostname: " + server.get("hostname").asString().orElseThrow());   // web-01
    IO.println("port:     " + server.at("/port").asNumber().orElseThrow());        // 8080

    // A bad value surfaces as a diagnostic rather than a wrong result. Reading through a context built
    // with a collecting receiver gathers every problem in one pass instead of stopping at the first:
    var problems = TsonDiagnosticsReceiver.collecting();
    var bad = "{ hostname: \"web-01\"  port: 99999999999999 }";   // port is out of int32 range
    reader.read(TsonReadContext.document(bad, problems));
    for (Diagnostic d : problems.diagnostics()) {
        IO.println("problem: " + d.path() + " -- " + d.message());
    }
}
