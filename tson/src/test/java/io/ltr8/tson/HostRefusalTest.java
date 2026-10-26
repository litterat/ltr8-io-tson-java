package io.ltr8.tson;

import io.ltr8.annotation.Tuple;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.TsonObjectReader;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bound class refusing what a read hands it -- a collection that refuses {@code null} meeting a void value, an
 * unboxing at a primitive, a compact constructor's own check -- is a diagnostic, never an exception on the
 * caller's stack. Under a schema it is {@code BIND_MISMATCH}, the class and the schema disagreeing; with no schema
 * the class is the contract, and it is {@code TYPE_MISMATCH}. What is known before any document -- a field or
 * position that can deliver no value, bound to a primitive -- is refused when the schema is compiled.
 */
class HostRefusalTest {

    private static final String ID = "https://example.test/host-refusal-1.tn";
    private static final String SCHEMA = """
            !!id:"https://example.test/host-refusal-1.tn"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            {
              voidable_map    => { m: {text => int32?} }
              plain_map       => { m: {text => int32} }
              voidable_array  => { a: [int32?] }
              plain_array     => { a: [int32] }
              checked         => { n: int32 }
              voidable_field  => { n: int32? }
              optional_field  => { n?: int32 }
              defaulted_field => { n?: int32 ~ 0 }
              voidable_ints   => { a: [int32?] }
              pair            => [text, int32?]
            }
            """;

    public record Counts(ConcurrentHashMap<String, Integer> m) {
    }

    public record Queue(Deque<Integer> a) {
    }

    public record Ints(int[] a) {
    }

    public record Count(int n) {
    }

    public record Checked(int n) {
        public Checked {
            if (n < 0) {
                throw new IllegalArgumentException("n must not be negative");
            }
        }
    }

    @Tuple
    public record Pair(String label, int count) {
    }

    private static final Map<String, Class<?>> BINDINGS = Map.of("voidable_map", Counts.class,
            "plain_map", Counts.class, "voidable_array", Queue.class, "plain_array", Queue.class,
            "checked", Checked.class, "defaulted_field", Count.class);

    private static TsonObjectReader underSchema(Map<String, Class<?>> bindings, List<Diagnostic> into) {
        SchemaSource source = uri -> {
            if (uri.startsWith(ID)) {
                return SCHEMA;
            }
            throw new SchemaFetchException(uri, SchemaFetchException.Reason.NOT_FOUND, "only " + ID, null);
        };
        DataBindContext context = DataBindContext.builder().nameBinder(DataNameBinder.ofMap(bindings))
                .registerAtoms(AtomContext.hostTypes()).build();
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source))
                .withDataBindContext(context)).objectReader().withSchema(ID).withDiagnostics(into::add);
    }

    private static List<Diagnostic.Code> codes(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(Diagnostic::code).toList();
    }

    @Nested
    class UnderASchema {

        private final List<Diagnostic> diagnostics = new ArrayList<>();
        private final TsonObjectReader reader = underSchema(BINDINGS, diagnostics);

        @Test
        void aVoidValueAMapCannotHoldIsABindMismatch() {
            assertNull(reader.readAs("{ m: { a => 1  b => _ } }", "voidable_map", Counts.class));
            assertEquals(List.of(Diagnostic.Code.BIND_MISMATCH), codes(diagnostics));
            assertTrue(diagnostics.getFirst().message().startsWith("ConcurrentHashMap rejected the entries"),
                    diagnostics.toString());
        }

        @Test
        void aVoidElementACollectionCannotHoldIsABindMismatch() {
            assertNull(reader.readAs("{ a: [1 _ 3] }", "voidable_array", Queue.class));
            assertEquals(List.of(Diagnostic.Code.BIND_MISMATCH), codes(diagnostics));
        }

        /** A refused value travels as null, and a collection that refuses null is never handed it. */
        @Test
        void aRefusedElementIsNeverHandedToTheCollection() {
            assertNull(reader.readAs("{ a: [1 x 3] }", "plain_array", Queue.class));
            assertEquals(List.of(Diagnostic.Code.ATOM_FORM_INVALID), codes(diagnostics));
        }

        @Test
        void aRefusedValueIsNeverHandedToTheMap() {
            assertNull(reader.readAs("{ m: { a => x } }", "plain_map", Counts.class));
            assertEquals(List.of(Diagnostic.Code.ATOM_FORM_INVALID), codes(diagnostics));
        }

        @Test
        void aConstructorRefusingAValueTheSchemaAdmitsIsABindMismatch() {
            assertNull(reader.readAs("{ n: -1 }", "checked", Checked.class));
            assertEquals(List.of(Diagnostic.Code.BIND_MISMATCH), codes(diagnostics));
            assertTrue(diagnostics.getFirst().message().contains("n must not be negative"), diagnostics.toString());
        }

        @Test
        void aDefaultedFieldBindsToAPrimitive() {
            assertEquals(new Count(0), reader.readAs("{}", "defaulted_field", Count.class));
            assertEquals(List.of(), diagnostics);
        }
    }

    /** Refused when the schema is compiled, so a document that never writes the void value is refused too. */
    @Nested
    class AtCompile {

        private final List<Diagnostic> diagnostics = new ArrayList<>();

        private String refusal(String type, Class<?> bound, String document) {
            Map<String, Class<?>> bindings = new HashMap<>(BINDINGS);
            bindings.put(type, bound);
            assertNull(underSchema(bindings, diagnostics).readAs(document, type, bound));
            assertEquals(List.of(Diagnostic.Code.BIND_MISMATCH), codes(diagnostics));
            return diagnostics.getFirst().message();
        }

        @Test
        void aVoidableFieldBoundToAPrimitive() {
            assertTrue(refusal("voidable_field", Count.class, "{ n: 1 }").contains("field 'n' is voidable, and "
                    + "component 'n' binds int, which has no null to hold it"));
        }

        @Test
        void anOptionalFieldWithNoDefaultBoundToAPrimitive() {
            assertTrue(refusal("optional_field", Count.class, "{ n: 1 }").contains("field 'n' may be left out "
                    + "with nothing injected, and component 'n' binds int"));
        }

        @Test
        void aVoidableArrayBoundToAPrimitiveArray() {
            assertTrue(refusal("voidable_ints", Ints.class, "{ a: [1] }").contains("field 'a' admits void "
                    + "elements, and component 'a' binds int[], which has no null to hold one"));
        }

        @Test
        void aVoidableTuplePositionBoundToAPrimitive() {
            assertTrue(refusal("pair", Pair.class, "[a 1]").contains("position 1 is voidable, and int has no "
                    + "null to hold a void value"));
        }
    }

    @Nested
    class WithNoSchema {

        private final List<Diagnostic> diagnostics = new ArrayList<>();
        private final TsonObjectReader reader = Tson.standard().objectReader().withDiagnostics(diagnostics::add);

        @Test
        void aVoidValueAMapCannotHoldIsATypeMismatch() {
            assertNull(reader.read("{ m: { a => 1  b => _ } }", Counts.class));
            assertEquals(List.of(Diagnostic.Code.TYPE_MISMATCH), codes(diagnostics));
        }

        @Test
        void aRefusedValueIsNeverHandedToTheMap() {
            assertNull(reader.read("{ m: { a => x } }", Counts.class));
            assertEquals(List.of(Diagnostic.Code.ATOM_FORM_INVALID), codes(diagnostics));
        }

        @Test
        void aConstructorRefusingAValueIsATypeMismatch() {
            assertNull(reader.read("{ n: -1 }", Checked.class));
            assertEquals(List.of(Diagnostic.Code.TYPE_MISMATCH), codes(diagnostics));
        }
    }
}
