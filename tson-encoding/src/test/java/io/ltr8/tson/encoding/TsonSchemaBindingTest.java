package io.ltr8.tson.encoding;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.EntryKey;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.RecordBody;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonSchemaBindingTest {

    private static final String HOST = "https://example.test/host.tn";

    private static final String LIB = "https://example.test/lib.tn";

    private static final String IDENTITY = "example.test/host.tn";

    private static final Map<String, String> SOURCES = Map.of(HOST, """
            !!id:"https://example.test/host.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              t    => { n: int32 }
              twin => { n: int32 }
              near => t
              u    => { url: uri }
              y    => !meta.extern { schema: "https://example.test/lib.tn"  type: x }
              box  => <V> { item: V }
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

    /** The class a code generator emits for a parametric record: its component erases to {@code Object}. */
    public record Box<V>(V item) {
    }

    private static final TsonLinkedSchema HOST_SCHEMA = Tson.of(ProcessorConfig.defaults()
            .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(SOURCES)))).resolve(SOURCES.get(HOST));

    private static DataBindContext context(DataNameBinder binder) {
        return DataBindContext.builder().nameBinder(binder).registerAtoms(AtomContext.hostTypes()).build();
    }

    private static TsonSchemaBinding load(Map<String, Class<?>> bound) {
        return TsonSchemaBinding.load(HOST_SCHEMA, context(DataNameBinder.ofMap(bound)));
    }

    private static EntryKey key(String name) {
        return new EntryKey(IDENTITY, name);
    }

    @Test
    void aBoundEntryIsFoundByItsKeyByItsNameAndByItsClass() {
        TsonSchemaBinding binding = load(Map.of("t", T.class));

        TsonTypeBinding t = binding.require(key("t"));
        assertEquals(key("t"), t.key());
        assertEquals(key("t"), t.boundAs());
        assertEquals(T.class, t.dataClass().typeClass());
        assertInstanceOf(RecordBody.class, t.definition());
        assertEquals(Optional.of(t), binding.get(key("t")));
        assertEquals(Optional.of(t), binding.get("t"));
        assertEquals(Optional.of(t), binding.get(T.class));
        assertEquals(List.of(t), binding.bindingsOf(T.class));
    }

    @Test
    void anAliasIsAnsweredForTheEntryItReaches() {
        TsonSchemaBinding binding = load(Map.of("t", T.class));

        assertSame(binding.require(key("t")), binding.get("near").orElseThrow());
    }

    @Test
    void anUnboundEntryIsAbsentAndRequiringItSaysWhatTheBinderHolds() {
        TsonSchemaBinding binding = load(Map.of("near", T.class));

        assertTrue(binding.get("t").isEmpty());
        assertTrue(binding.get(T.class).isEmpty());
        MissingBindingException missing = assertThrows(MissingBindingException.class, () -> binding.require(key("t")));
        assertEquals("no bound Java class for 't': nothing in this bind context resolves that schema type name. Map "
                + "it (ProcessorConfig.withDataBindContext) or give the context a DataNameBinder that can find it -- "
                + "'t' is not bound: the binding map holds [near]", missing.getMessage());
    }

    @Test
    void aKeyNoEntryOfTheSchemaHasIsRefused() {
        TsonSchemaBinding binding = load(Map.of());

        assertTrue(binding.get("absent").isEmpty());
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> binding.require(key("absent")));
        assertEquals("'absent' of example.test/host.tn is no entry example.test/host.tn reaches",
                refused.getMessage());
    }

    @Test
    void aClassTheContextCannotDescribeFailsTheLoad() {
        BindMismatchException refused = assertThrows(BindMismatchException.class, () -> load(Map.of("u", U.class)));

        assertEquals("'u' of example.test/host.tn binds " + U.class.getName() + ", which cannot be analysed: "
                + "Unable to find a valid data conversion for class: class java.net.URL. Bind 'u' to a class this bind "
                + "context can describe, or leave it out of the binding map", refused.getMessage());
        assertInstanceOf(DataBindException.class, refused.getCause());
    }

    @Test
    void aBinderThatFailsFailsTheLoad() {
        IllegalStateException broken = new IllegalStateException("no binder here");
        BindMismatchException refused = assertThrows(BindMismatchException.class,
                () -> TsonSchemaBinding.load(HOST_SCHEMA, context(name -> {
                    if (name.equals("t")) {
                        throw broken;
                    }
                    throw new DataBindException("'" + name + "' is not bound");
                })));

        assertEquals("'t' of example.test/host.tn could not be asked of the binder, which failed: "
                + "java.lang.IllegalStateException: no binder here. A DataNameBinder answers a name it binds no class "
                + "for by throwing DataBindException", refused.getMessage());
        assertSame(broken, refused.getCause());
    }

    /** No position names a parametric record bare ([TSON-SCHEMA] §5.10), so its class is never described. */
    @Test
    void aParametricRecordIsNotAskedOfTheBinder() {
        TsonSchemaBinding binding = load(Map.of("box", Box.class));

        assertTrue(binding.get("box").isEmpty());
        MissingBindingException missing = assertThrows(MissingBindingException.class,
                () -> binding.require(key("box")));
        assertEquals("'box' is a parametric record, which nothing reads bare ([TSON-SCHEMA] §5.10): a value is read "
                + "through an application of it, bound under the name the application is declared by",
                missing.getMessage());
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

        TsonTypeBinding y = binding.get("y").orElseThrow();
        assertEquals(new EntryKey("example.test/lib.tn", "x"), y.key());
        assertEquals(X.class, y.dataClass().typeClass());
        assertSame(y, binding.require(new EntryKey("example.test/lib.tn", "x")));
    }

    /**
     * Several entries may bind one class -- reading needs only the entry -- so the load accepts it, and the question
     * that cannot be answered, which entry a value of the class is, is refused when asked.
     */
    @Test
    void aClassBoundUnderSeveralNamesIsRefusedWhenAskedNotAtLoad() {
        TsonSchemaBinding binding = load(Map.of("t", T.class, "twin", T.class));

        assertEquals(List.of(key("t"), key("twin")), binding.bindingsOf(T.class).stream()
                .map(TsonTypeBinding::key).toList());
        BindMismatchException ambiguous = assertThrows(BindMismatchException.class, () -> binding.get(T.class));
        assertEquals(T.class.getName() + " is bound as 2 entries ('t' of example.test/host.tn, 'twin' of "
                + "example.test/host.tn), so which of them a value of it is cannot be told from its class: bind each to "
                + "a class of its own, or ask bindingsOf(T) and choose among them", ambiguous.getMessage());
    }

    /**
     * A constructor composed over another with no field added binds as its parent ([TSON-SCHEMA] §5.8), so the two
     * entries share a class and one name it is bound as: that class's entry is the parent.
     */
    @Test
    void aConstructorComposedOverItsParentSharesItsClassAndAnswersAsIt() {
        DataBindContext context = SchemaModelNameBinder.defaultContext();
        TsonLinkedSchema composed = Tson.of(ProcessorConfig.defaults().withDataBindContext(context)).resolve("""
                !!id:"https://example.test/composed.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  my_list => meta.array & { min_items?: = 1 }
                }
                """);
        TsonSchemaBinding binding = TsonSchemaBinding.load(composed, context);
        EntryKey array = new EntryKey("tson.io/2026/40/m/meta.tn", "meta.array");

        TsonTypeBinding myList = binding.get("my_list").orElseThrow();
        assertEquals(array, myList.boundAs());
        assertEquals(List.of(array, new EntryKey("example.test/composed.tn", "my_list")),
                binding.bindingsOf(ArrayBody.class).stream().map(TsonTypeBinding::key).toList());
        assertEquals(array, binding.get(ArrayBody.class).orElseThrow().key());
    }

    @Test
    void theContextAndTheSchemaAreTheOnesLoadedUnder() {
        DataBindContext context = context(DataNameBinder.ofMap(Map.of()));
        TsonSchemaBinding binding = TsonSchemaBinding.load(HOST_SCHEMA, context);

        assertSame(context, binding.context());
        assertSame(HOST_SCHEMA, binding.schema());
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

        binding.get("t");
        binding.get("near");
        binding.get(T.class);
        assertThrows(MissingBindingException.class, () -> binding.require(key("u")));

        assertEquals(atLoad, asks.get());
        assertTrue(atLoad <= HOST_SCHEMA.schema().entries().size(), atLoad + " asks");
    }
}
