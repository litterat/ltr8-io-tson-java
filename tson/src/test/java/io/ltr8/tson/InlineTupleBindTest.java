package io.ltr8.tson;

import io.ltr8.annotation.Tuple;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.TsonObjectReader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tuple written inline at a record field binds to the field's own {@code @Tuple} class. Its entry has a minted
 * name no author would bind, and a tuple has no natural Java form to fall back on as an array has its
 * {@code List}, so the component's class is the only one there is -- checked against the tuple's arity and its
 * voidable positions when the schema is compiled.
 */
class InlineTupleBindTest {

    private static final String ID = "https://example.test/inline-tuple-1.tn";
    private static final String SCHEMA = """
            !!id:"https://example.test/inline-tuple-1.tn"
            !!meta:"https://tson.io/2026/37/m/meta.tn"
            !!import:"https://tson.io/2026/37/m/core.tn"
            {
              holder   => { pair: [text, int32] }
              loose    => { pair: [text, int32?] }
              triple   => { pair: [text, int32, boolean] }
              unbound  => [text, int32]
            }
            """;

    @Tuple
    public record Pair(String label, int count) {
    }

    @Tuple
    public record LoosePair(String label, Integer count) {
    }

    public record Holder(Pair pair) {
    }

    public record LooseHolder(LoosePair pair) {
    }

    private final List<Diagnostic> diagnostics = new ArrayList<>();

    private TsonObjectReader reader(Map<String, Class<?>> bindings) {
        SchemaSource source = uri -> {
            if (uri.startsWith(ID)) {
                return SCHEMA;
            }
            throw new SchemaFetchException(uri, SchemaFetchException.Reason.NOT_FOUND, "only " + ID, null);
        };
        DataBindContext context = DataBindContext.builder().nameBinder(DataNameBinder.ofMap(bindings))
                .registerAtoms(AtomContext.hostTypes()).build();
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source))
                .withDataBindContext(context)).objectReader().withSchema(ID).withDiagnostics(diagnostics::add);
    }

    @Test
    void anInlineTupleBindsToItsComponentsTupleClass() {
        assertEquals(new Holder(new Pair("a", 1)),
                reader(Map.of("holder", Holder.class)).readAs("{ pair: [a 1] }", "holder", Holder.class));
        assertEquals(List.of(), diagnostics);
    }

    @Test
    void aVoidablePositionBindsToABoxedComponent() {
        assertEquals(new LooseHolder(new LoosePair("a", null)),
                reader(Map.of("loose", LooseHolder.class)).readAs("{ pair: [a _] }", "loose", LooseHolder.class));
        assertEquals(List.of(), diagnostics);
    }

    /** Refused when the schema is compiled, so a document that never writes the void value is refused too. */
    @Test
    void aVoidablePositionBoundToAPrimitiveIsRefused() {
        assertNull(reader(Map.of("loose", Holder.class)).readAs("{ pair: [a 1] }", "loose", Holder.class));
        assertEquals(List.of(Diagnostic.Code.BIND_MISMATCH), diagnostics.stream().map(Diagnostic::code).toList());
        assertTrue(diagnostics.getFirst().message().contains("field 'pair' is a tuple, and component 'pair' binds "
                + Pair.class.getName() + ": position 1 is voidable, and int has no null to hold a void value"),
                diagnostics.toString());
    }

    @Test
    void aTupleClassOfAnotherArityIsRefused() {
        assertNull(reader(Map.of("triple", Holder.class)).readAs("{ pair: [a 1 true] }", "triple", Holder.class));
        assertEquals(List.of(Diagnostic.Code.BIND_MISMATCH), diagnostics.stream().map(Diagnostic::code).toList());
        assertTrue(diagnostics.getFirst().message().contains("it has 3 positions, and Pair has 2"),
                diagnostics.toString());
    }

    /** Read directly, a tuple with no class bound still says so, as it did before it could be rebuilt. */
    @Test
    void aTupleNothingBindsStillRaisesItsMissingBinding() {
        TsonObjectReader reader = reader(Map.of("holder", Holder.class));
        assertThrows(MissingBindingException.class, () -> reader.readAs("[a 1]", "unbound", Pair.class));
    }
}
