/// Walk a TSON document as a generic tree -- no schema, no target class.
///
/// A single-file Java 25 program (compact source file + instance `main`), loading the `tson`
/// library via the module system:
///
///   ./gradlew :tson:modules
///   java --module-path tson/build/modules --add-modules io.ltr8.tson examples/TreeWalk.java
///
/// `TsonDataParser` builds an AST (`Document` -> `CoreValue`: `RecordValue` | `MapValue` |
/// `ArrayValue` | `TokenValue` | ...) you can navigate -- good for tooling, transformation, or when
/// you don't know the shape ahead of time.
import module io.ltr8.tson;

void main() {
    Document doc = new TsonDataParser("{ name: \"Ada\"  tags: [a b c] }").parseDocument();
    CoreValue root = doc.root().coreValue();

    IO.println("root is a " + root.getClass().getSimpleName());
    if (root instanceof RecordValue record) {
        for (var field : record.fields()) {   // ast.Field -- named with `var` to avoid a clash with java.lang.reflect.Field
            IO.println("  field: " + field.name());
        }
    }
}
