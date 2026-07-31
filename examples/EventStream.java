/// Pull a TSON document as a lazy event stream -- the tier below the AST.
///
/// A single-file Java 25 program (compact source file + instance `main`), loading the `tson`
/// library via the module system:
///
///   ./gradlew :tson:modules
///   java --module-path tson/build/modules --add-modules io.ltr8.tson examples/EventStream.java
///
/// `TsonDataStream` never materializes a tree -- memory held is proportional to nesting depth, not
/// document size -- the right tool for very large documents or a custom consumer.
import module io.ltr8.tson;

void main() {
    var stream = new TsonDataStream("{ name: \"Ada\" }");
    while (stream.hasNext()) {
        TsonEvent event = stream.next();   // DocumentStart, RecordStart, FieldName, TokenEvent, ...
        IO.println(event.getClass().getSimpleName());
    }
}
