package io.ltr8.tson.json;

import io.ltr8.annotation.Unbound;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.io.ByteSource;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.json.reader.ValueReaderFactoryRegistry;
import io.ltr8.tson.json.stream.JsonStream;
import io.ltr8.tson.schema.TsonLinkedSchema;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A record read against its schema into a bound class -- the same loop and rules as tree mode ({@code
 * RecordReader}), with the class checked against the schema when the reader is built and constructed only from
 * a value whose read reported nothing.
 */
class JsonBindRecordReadTest {

    private static final String SCHEMA = """
            !!id:"https://example.test/bind-1.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
            {
              address => { street: text  city: text }
              person  => {
                id: uuid
                name: text
                age: int32
                born: date?
                home: address
                level: int32 ~ 1
                kind: text = "person"
              }
              shape   => abstract { area: int32 }
              square  => shape & { side: int32 }
              unbound => { value: text }
              animal  => { name: text }
              dog     => animal & { breed: text }
              cat     => animal & { indoor: boolean }
              owner   => { pet: animal }
            }
            """;

    public record Address(String street, String city) {
    }

    public record Person(UUID id, String name, long age, LocalDate born, Address home, int level) {
    }

    public sealed interface Shape permits Square {
    }

    public record Square(int area, int side) implements Shape {
    }

    public sealed interface Animal permits Dog, Cat {
    }

    public record Dog(String name, String breed) implements Animal {
    }

    public record Cat(String name, boolean indoor) implements Animal {
    }

    public record Owner(Animal pet) {
    }

    private static final TsonLinkedSchema LINKED = Tson.standard().resolve(SCHEMA);

    private static final Map<String, Class<?>> BINDINGS = Map.of(
            "address", Address.class, "person", Person.class, "square", Square.class,
            "animal", Animal.class, "dog", Dog.class, "cat", Cat.class, "owner", Owner.class);

    private static JsonCompiledSchema compile(Map<String, Class<?>> bindings) {
        DataBindContext binding = DataBindContext.builder().nameBinder(DataNameBinder.ofMap(bindings))
                .registerAtoms(AtomContext.hostTypes()).build();
        return JsonSchemaCompiler.compile(LINKED, ValueReaderFactoryRegistry.bind(binding));
    }

    private static final JsonCompiledSchema COMPILED = compile(BINDINGS);

    private record Read(Object value, List<Diagnostic> problems) {
    }

    private static Read read(String type, String json) {
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of(json)) {
            JsonReadContext ctx = JsonReadContext.of(new JsonStream(bytes, ProcessorPolicy.defaults(), receiver),
                    receiver);
            return new Read(COMPILED.get(type).read(ctx), List.copyOf(problems));
        }
    }

    private static final String ADA = """
            {"id": "9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09", "name": "Ada", "age": 36,
             "home": {"street": "1 Analytical Way", "city": "London"}}""";

    @Test
    void aRecordBindsItsAtomsAndNestedRecordsAndInjectsItsDefault() {
        Read read = read("person", ADA);
        assertEquals(List.of(), read.problems());
        assertEquals(new Person(UUID.fromString("9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09"), "Ada", 36L, null,
                new Address("1 Analytical Way", "London"), 1), read.value());
    }

    /** An int32 field binds to a {@code long} component: the family's parser is bound to what the component holds. */
    @Test
    void anAtomIsReadIntoTheClassItsComponentHolds() {
        Person person = (Person) read("person", ADA.replace("\"age\": 36", "\"age\": 2147483647")).value();
        assertEquals(2147483647L, person.age());
    }

    @Test
    void anOptionalFieldTheDocumentStatesBinds() {
        Person person = (Person) read("person", ADA.replace("\"age\": 36", "\"age\": 36, \"born\": \"1815-12-10\""))
                .value();
        assertEquals(LocalDate.of(1815, 12, 10), person.born());
    }

    /** All-or-nothing: a record whose read reported anything is not constructed, and says why. */
    @Test
    void aRecordWhoseReadReportedAnythingBindsToNull() {
        Read read = read("person", ADA.replace("\"age\": 36,", ""));
        assertNull(read.value());
        assertEquals(Diagnostic.Code.FIELD_REQUIRED, read.problems().getFirst().code());
        assertEquals("/age", read.problems().getFirst().path().orElseThrow());
    }

    /** The FIXED field has no component, which is allowed; stating it still verifies it against the pin. */
    @Test
    void aFixedFieldNeedsNoComponentAndIsStillVerified() {
        Read read = read("person", ADA.replace("\"age\": 36", "\"age\": 36, \"kind\": \"robot\""));
        assertNull(read.value());
        assertEquals(Diagnostic.Code.FIELD_FIXED, read.problems().getFirst().code());
    }

    /** The dispatchers are mode-free: a tag at an abstract position selects the subtype's bound class. */
    @Test
    void aTagAtAnAbstractPositionBindsTheSubtypesClass() {
        Read read = read("shape", """
                {"$type": "square", "area": 4, "side": 2}""");
        assertEquals(List.of(), read.problems());
        assertInstanceOf(Square.class, read.value());
        assertEquals(new Square(4, 2), read.value());
    }

    // ── The class against the schema, checked when the reader is built ──

    public record PersonWithoutAge(UUID id, String name, LocalDate born, Address home, int level) {
    }

    public record PersonWithExtra(UUID id, String name, long age, LocalDate born, Address home, int level,
                                  String nickname) {
    }

    public record PersonWithUnboundExtra(UUID id, String name, long age, LocalDate born, Address home, int level,
                                         @Unbound String nickname) {
    }

    public record PersonWithStructuredName(UUID id, Address name, long age, LocalDate born, Address home,
                                           int level) {
    }

    @Test
    void aFieldWithNoComponentFailsTheCompile() {
        BindMismatchException e = assertThrows(BindMismatchException.class,
                () -> compile(Map.of("address", Address.class, "person", PersonWithoutAge.class)));
        assertTrue(e.getMessage().contains("no component for field 'age'"), e.getMessage());
    }

    @Test
    void aComponentNoFieldFillsFailsTheCompileUnlessTheClassOwnsIt() {
        BindMismatchException e = assertThrows(BindMismatchException.class,
                () -> compile(Map.of("address", Address.class, "person", PersonWithExtra.class)));
        assertTrue(e.getMessage().contains("component 'nickname' is filled by no field"), e.getMessage());
        compile(Map.of("address", Address.class, "person", PersonWithUnboundExtra.class));
    }

    @Test
    void anAtomFieldBoundToAStructuredComponentFailsTheCompile() {
        BindMismatchException e = assertThrows(BindMismatchException.class,
                () -> compile(Map.of("address", Address.class, "person", PersonWithStructuredName.class)));
        assertTrue(e.getMessage().contains("field 'name' is an atom"), e.getMessage());
    }

    /** No class at all is deferred -- a schema declares types a consumer never binds -- and thrown when read. */
    @Test
    void aTypeWithNoBoundClassCompilesAndThrowsWhenRead() {
        assertThrows(MissingBindingException.class, () -> read("unbound", """
                {"value": "x"}"""));
    }

    // ── An OPEN record family bound to a sealed interface ───────────────

    /** The record has no class of its own to build: a tag places the value at a subtype's class. */
    @Test
    void aTaggedValueAtARecordBoundToAUnionBindsTheSubtype() {
        Read read = read("animal", """
                {"$type": "dog", "name": "Rex", "breed": "corgi"}""");
        assertEquals(List.of(), read.problems());
        assertEquals(new Dog("Rex", "corgi"), read.value());
    }

    @Test
    void aFieldTypedByTheRecordHoldsTheSubtype() {
        Read read = read("owner", """
                {"pet": {"$type": "cat", "name": "Tom", "indoor": true}}""");
        assertEquals(List.of(), read.problems());
        assertEquals(new Owner(new Cat("Tom", true)), read.value());
    }

    /** Untagged, or tagged with the record itself, the value is the record's own -- and it has none. */
    @Test
    void anUntaggedValueHasNothingToBindTo() {
        for (String json : List.of("""
                {"name": "Rex"}""", """
                {"$type": "animal", "name": "Rex"}""")) {
            Read read = read("animal", json);
            assertNull(read.value());
            assertEquals(Diagnostic.Code.TYPE_MISMATCH, read.problems().getFirst().code());
            assertTrue(read.problems().getFirst().message().contains("no data of its own"),
                    read.problems().getFirst().message());
        }
    }

    public record Stray(String name, boolean indoor) {
    }

    /** A subtype's class that is not a member of the union is found at compile, not by the document that tags it. */
    @Test
    void aSubtypeWhoseClassIsNotAMemberFailsTheCompile() {
        Map<String, Class<?>> bindings = new java.util.HashMap<>(BINDINGS);
        bindings.put("cat", Stray.class);
        BindMismatchException e = assertThrows(BindMismatchException.class, () -> compile(bindings));
        assertTrue(e.getMessage().contains("subtype 'cat' binds"), e.getMessage());
    }
}
