/// Bind a TSON document straight to a Java record -- no schema, no build tool.
///
/// A single-file Java 25 program (compact source file + instance `main`). The `tson`
/// library is loaded via the module system:
///
///   ./gradlew :tson:modules
///   java --module-path tson/build/modules --add-modules io.ltr8.tson examples/ObjectBinding.java
///
/// `import module io.ltr8.tson;` pulls in the front door and its transitive modules;
/// `import module java.base;` (implicit for a compact source file) gives `UUID`/`LocalDate`/etc.
import module io.ltr8.tson;

void main() {
    Server server = new TsonObjectReader().read("""
            {
                hostname: "web-01"
                address: !ipv4 192.0.2.10
                id: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09
                deployedOn: !date 2026-01-15
            }""", Server.class);

    IO.println("hostname:   " + server.hostname());
    IO.println("address:    " + server.address());
    IO.println("id:         " + server.id());
    IO.println("deployedOn: " + server.deployedOn());
}

// The target shape. Records, Map/List, tuples, enums and sealed unions all bind with no custom code;
// the built-in vocabulary (!ipv4/!uuid/!date/...) maps to the matching java.base types. It must be
// `public` so the library (a different module) can bind it reflectively.
public record Server(String hostname, Inet4Address address, UUID id, LocalDate deployedOn) {}
