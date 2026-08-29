/// Read a *self-describing* TSON document -- one that names its own schema -- validated automatically.
///
/// A single-file Java 25 program (compact source file + instance `main`), loading the `tson`
/// library via the module system:
///
///   ./gradlew :tson:modules
///   java --module-path tson/build/modules --add-modules io.ltr8.tson examples/SchemaAwareRead.java
///
/// A schema-aware `TsonTreeReader` -- from `tson.treeReader()`, over a configured schema source --
/// reads a document's own `!!schema` directive, resolves and validates against it, and returns a
/// queryable `TsonValue`. Handed a document that declares no `!!schema`, the same reader falls back to a
/// schemaless read. It's the "hand me a document, work out whether a schema applies" entry point, the
/// value-returning peer of `tson.validate`; `tson.objectReader().read(doc, YourClass.class)` is the
/// object-binding twin (see ObjectBinding.java for the binding side).
import module io.ltr8.tson;

void main() {
    // A tiny schema, handed to the reader on demand by URI. A real app plugs in a disk/HTTP-backed
    // source with its own fetch policy; here a one-liner just returns our schema text.
    String schema = """
            !!id:"https://example.com/2026/34/app/server-1.tn"
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
            {
                server => { hostname: text  port: int32 }
            }""";

    Tson tson = Tson.builder()
            .schemaSource(uri -> schema)
            .build();

    // Self-describing: the document names its own schema and root type. No other arguments needed --
    // the reader resolves the schema, selects the `server` type, and validates as it builds the tree.
    TsonValue server = tson.treeReader().read("""
            !!schema:"https://example.com/2026/34/app/server-1.tn"
            !server { hostname: "web-01"  port: 8080 }""");
    IO.println("validated hostname: " + server.get("hostname").asString().orElseThrow());   // web-01
    IO.println("validated port:     " + server.at("/port").asInt().orElseThrow());         // 8080

    // The same reader, given a document with no `!!schema`, reads schemalessly -- straight off the wire.
    TsonValue raw = tson.treeReader().read("{ hostname: \"db-01\"  port: 5432 }");
    IO.println("schemaless port:    " + raw.at("/port").asInt().orElseThrow());             // 5432

    // A value that violates the schema is rejected fail-fast, rather than returned wrong.
    try {
        tson.treeReader().read("""
                !!schema:"https://example.com/2026/34/app/server-1.tn"
                !server { hostname: "bad"  port: 99999999999999 }""");   // out of int32 range
        IO.println("unexpected: bad port was accepted");
    } catch (TsonReadException rejected) {
        IO.println("rejected out-of-range port: " + rejected.getMessage());
    }
}
