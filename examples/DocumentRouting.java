/// Read a document's header without reading the document -- route first, read second.
///
/// A single-file Java 25 program (compact source file + instance `main`), loading the `tson`
/// library via the module system:
///
///   ./gradlew :tson:modules
///   java --module-path tson/build/modules --add-modules io.ltr8.tson examples/DocumentRouting.java
///
/// [TSON-DATA] §7.1: classification needs at most two directives of lookahead and no value parsing,
/// so a stream, preview or content sniffer can classify a document from its opening bytes.
import module io.ltr8.tson;

void main() {
    String order = """
            !!id:"https://example.com/orders/1"
            !!schema:"https://example.com/order-2.tn"
            { id: 1  note: "two boxes" }""";

    // What does it declare? No schema needed, no value read.
    TsonDocumentHeader header = TsonDocumentHeader.peek(order);
    IO.println("id:     " + header.id().orElse("(none)"));
    IO.println("schema: " + header.schema().orElse("(none)"));
    IO.println("schema document? " + header.isSchemaDocument());

    // A schema document says so with !!meta -- the same peek classifies it.
    TsonDocumentHeader schema = TsonDocumentHeader.peek("""
            !!id:"https://example.com/order-2.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            { order => { id: int32 } }""");
    IO.println("schema document? " + schema.isSchemaDocument() + " (meta " + schema.meta().orElseThrow() + ")");

    // A one-shot stream -- an HTTP request body, say -- is handed back whole, header included,
    // so the reader that follows sees the document from its first byte.
    InputStream body = new ByteArrayInputStream(order.getBytes(StandardCharsets.UTF_8));
    TsonDocumentPeek peeked = TsonDocumentHeader.peekResumable(body);
    IO.println("routing to " + peeked.header().schema().orElseThrow());

    TsonValue value = new TsonTreeReader().read(peeked.document());   // schemaless here; withSchema(...) in real use
    IO.println("note: " + value.get("note").asString().orElseThrow());
}
