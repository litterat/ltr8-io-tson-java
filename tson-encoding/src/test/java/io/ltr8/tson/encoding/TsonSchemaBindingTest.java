package io.ltr8.tson.encoding;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.EntryKey;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonSchemaBindingTest {

    private static final String HOST = "https://example.test/host.tn";

    private static final String LIB = "https://example.test/lib.tn";

    private static final Map<String, String> SOURCES = Map.of(HOST, """
            !!id:"https://example.test/host.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              t    => { n: int32 }
              near => t
              u    => { url: uri }
              y    => !meta.extern { schema: "https://example.test/lib.tn"  type: x }
              flag => boolean
            }
            """, LIB, """
            !!id:"https://example.test/lib.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              x => { n: int32 }
            }
            """);

    public record T(int n) {
    }

    public record X(int n) {
    }

    /** {@code java.net.URL} has no conversion in {@code ltr8-bind}, so a record holding one cannot be described. */
    public record U(java.net.URL url) {
    }

    private static final TsonLinkedSchema HOST_SCHEMA = Tson.of(ProcessorConfig.defaults()
            .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(SOURCES)))).resolve(SOURCES.get(HOST));

    private static DataBindContext context(DataNameBinder binder) {
        return DataBindContext.builder().nameBinder(binder).registerAtoms(AtomContext.hostTypes()).build();
    }

    private static TsonSchemaBinding load(Map<String, Class<?>> bound) {
        return TsonSchemaBinding.load(HOST_SCHEMA, context(DataNameBinder.ofMap(bound)));
    }

    @Test
    void aBoundEntryAnswersItsClassAndTheCandidateThatMatched() {
        BoundEntry t = load(Map.of("t", T.class)).entry("t");

        Descriptor.Bound bound = assertInstanceOf(Descriptor.Bound.class, t.descriptor());
        assertEquals(T.class, bound.dataClass().typeClass());
        assertEquals(new EntryKey("example.test/host.tn", "t"), bound.matched());
        assertEquals(new EntryKey("example.test/host.tn", "t"), t.entry());
        assertInstanceOf(RecordBody.class, t.definition());
        assertTrue(t.atom().isEmpty());
    }

    @Test
    void anAliasIsAnsweredForTheEntryItReaches() {
        BoundEntry near = load(Map.of("t", T.class)).entry("near");

        assertEquals(new EntryKey("example.test/host.tn", "t"), near.entry());
        assertEquals(T.class, near.dataClass().orElseThrow().typeClass());
    }

    @Test
    void anUnboundEntryNamesWhatWasTriedAndWhatTheBinderHolds() {
        Descriptor.Unbound unbound = assertInstanceOf(Descriptor.Unbound.class,
                load(Map.of("near", T.class)).entry("t").descriptor());

        assertEquals(List.of(new EntryKey("example.test/host.tn", "t")), unbound.tried());
        assertTrue(unbound.refusal().getMessage().contains("the binding map holds [near]"),
                unbound.refusal().getMessage());
    }

    @Test
    void aClassTheContextCannotDescribeIsUnanalysableNotUnbound() {
        Descriptor.Unanalysable unanalysable = assertInstanceOf(Descriptor.Unanalysable.class,
                load(Map.of("u", U.class)).entry("u").descriptor());

        assertEquals(new EntryKey("example.test/host.tn", "u"), unanalysable.matched());
        assertTrue(unanalysable.cause().getMessage().contains(U.class.getName()), unanalysable.cause().getMessage());
    }

    /** An extern is the entry it lands on ([TSON-SCHEMA] §5.13), asked in the schema declaring that entry. */
    @Test
    void anExternIsAnsweredForTheEntryItLandsOn() {
        TsonSchemaBinding binding = TsonSchemaBinding.load(HOST_SCHEMA, context(new DataNameBinder() {
            @Override
            public Class<?> resolve(String name) throws DataBindException {
                throw new DataBindException("'" + name + "' is asked without its schema");
            }

            @Override
            public Class<?> resolve(String namespace, String name) throws DataBindException {
                if (namespace.equals("example.test/lib.tn") && name.equals("x")) {
                    return X.class;
                }
                throw new DataBindException("'" + name + "' of " + namespace + " is not bound");
            }
        }));

        BoundEntry y = binding.entry("y");

        assertEquals(new EntryKey("example.test/lib.tn", "x"), y.entry());
        assertEquals(X.class, y.dataClass().orElseThrow().typeClass());
    }

    @Test
    void anAtomAnswersTheTypeABindReadParsesWith() throws Exception {
        TsonSchemaBinding binding = load(Map.of());

        assertEquals(Boolean.TRUE, binding.entry("flag").atom().orElseThrow().read("true"));
        assertEquals("7", String.valueOf(binding.entry("int32").atom().orElseThrow().read("7")));
    }

    @Test
    void aNameNoEntryHoldsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> load(Map.of()).entry("absent"));
    }

    /** Every candidate is asked once, at load, and no question afterwards reaches the binder. */
    @Test
    void theBinderIsAskedAtLoadOnlyAndEachCandidateOnce() {
        AtomicInteger asks = new AtomicInteger();
        DataNameBinder map = DataNameBinder.ofMap(Map.of("t", T.class));
        DataNameBinder counting = name -> {
            asks.incrementAndGet();
            return map.resolve(name);
        };
        TsonSchemaBinding binding = TsonSchemaBinding.load(HOST_SCHEMA, context(counting));
        int atLoad = asks.get();

        binding.entry("t");
        binding.entry("near");
        binding.entry("flag");

        assertEquals(atLoad, asks.get());
        assertTrue(atLoad <= HOST_SCHEMA.schema().entries().size(), atLoad + " asks");
    }
}
