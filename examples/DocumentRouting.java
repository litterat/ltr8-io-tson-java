/// Read a document's header without reading the document -- route first, read second, one stream.
///
/// A single-file Java 25 program (compact source file + instance `main`), loading the `tson`
/// library via the module system:
///
///   ./gradlew :tson:modules
///   java --module-path tson/build/modules --add-modules io.ltr8.tson examples/DocumentRouting.java
///
/// [TSON-DATA] §7.1: classification reads the header and the root's tag and never the root's value,
/// so a stream, preview or content sniffer can classify a document from its opening bytes.
import module io.ltr8.tson;

void main() {
    String order = """
            !!id:"https://example.com/orders/1"
            !!import:"https://example.com/order-2.tn"
            !order { id: 1  note: "two boxes" }""";

    // What does it declare? No schema needed, no value read.
    TsonDocumentPeek peek = TsonDocumentPeek.of(order);
    IO.println("id:      " + peek.header().id().orElse("(none)"));
    IO.println("imports: " + peek.header().imports());
    IO.println("schema document? " + peek.isSchemaDocument());

    // A schema document says so with its root's tag, !schema -- the same peek classifies it.
    TsonDocumentPeek schema = TsonDocumentPeek.of("""
            !!id:"https://example.com/order-2.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            !schema { order => { id: int32 } }""");
    IO.println("schema document? " + schema.isSchemaDocument() + " (imports " + schema.header().imports() + ")");

    // A one-shot stream -- an HTTP request body, say -- is never rewound: the peek holds the rest of
    // the document, and the reader chosen from the header continues on that same stream.
    InputStream body = new ByteArrayInputStream(order.getBytes(StandardCharsets.UTF_8));
    TsonDocumentPeek peeked = TsonDocumentPeek.of(body);
    IO.println("routing to " + peeked.header().imports().getFirst());

    TsonValue value = new TsonTreeReader().preservingUnknownTypeRefs().read(peeked);   // a Tson instance in real use
    IO.println("note: " + value.get("note").asString().orElseThrow());
}
