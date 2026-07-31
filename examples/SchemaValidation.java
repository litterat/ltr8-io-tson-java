/// Validate a TSON data document against a TSON *schema* -- no build tool.
///
/// A single-file Java 25 program (compact source file + instance `main`), loading the `tson`
/// library via the module system:
///
///   ./gradlew :tson:modules
///   java --module-path tson/build/modules --add-modules io.ltr8.tson examples/SchemaValidation.java
///
/// `Tson.builder().build()` bootstraps the standard library (meta-kernel/meta.tn/core.tn); `compile`
/// turns a schema into fast, reusable per-type readers. DOM mode returns a plain Map, so no Java
/// class is involved -- the TSON schema itself is the source of truth.
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

    var compiled = tson.compile(schema, TsonSchemaCompiler.dom());
    var reader = compiled.compiledSchema().get("server");

    IO.println("valid:   " + reader.read("{ hostname: \"web-01\"  port: 8080 }"));   // {hostname=web-01, port=8080}

    // A bad value surfaces as a diagnostic rather than a wrong result. Collect every problem in one
    // pass instead of stopping at the first:
    var ctx = TsonReadContext.collecting("{ hostname: \"web-01\"  port: 99999999999999 }");   // out of int32 range
    reader.read(ctx);
    for (Diagnostic d : ctx.diagnostics()) {
        IO.println("problem: " + d.path() + " -- " + d.message());
    }
}
