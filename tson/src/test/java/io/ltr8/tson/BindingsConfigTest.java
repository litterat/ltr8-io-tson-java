package io.ltr8.tson;

import io.ltr8.annotation.Profile;
import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.TsonMissingBindingException;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.config.TsonAtomContext;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TsonConfig#bindings} and {@link TsonConfig#profile} -- saying what an application binds in the one
 * place it configures everything else.
 *
 * <p>What it replaces was four lines with two invisible steps in them: a {@code DataNameBinder} over the
 * caller's own names, chained rather than replacing, wrapped in {@code TsonAtomContext.registerDefaults}.
 * Miss the last and atoms are unbound; every test in this repo repeated the incantation, one third of it
 * unnecessary.
 */
class BindingsConfigTest {

    private static final String ID = "https://example.test/orders.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/orders.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            {
              order => { sku: text  quantity: int32  when: datetime }
            }
            """;

    private static final String DOC = """
            !!schema:"https://example.test/orders.tn"
            !order { sku: "A"  quantity: 1  when: "2026-08-23T10:00:00Z" }""";

    /** A profiled shorter constructor, to show {@code profile} reaching the binder through the config. */
    public record Order(String sku, int quantity, java.time.OffsetDateTime when) {

        @Profile(value = "orders-1", fields = {"sku", "quantity"})
        public Order(String sku, int quantity) {
            this(sku, quantity, java.time.OffsetDateTime.parse("2000-01-01T00:00:00Z"));
        }
    }

    private static final TsonSchemaSource SOURCE = uri -> SCHEMA;

    /** The same schema without the field the profiled constructor omits. */
    private static final String SHORT_SCHEMA = """
            !!id:"https://example.test/orders.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            {
              order => { sku: text  quantity: int32 }
            }
            """;

    /** The whole configuration, in one call each. The {@code datetime} field also pins that atoms are bound. */
    @Test
    void bindingsIsTheWholeConfiguration() {
        Tson tson = Tson.builder().schemaSource(SOURCE).bindings(Map.of("order", Order.class)).build();

        Order order = tson.objectReader().read(DOC, Order.class);

        assertEquals("A", order.sku());
        assertEquals(java.time.OffsetDateTime.parse("2026-08-23T10:00:00Z"), order.when(),
                "registerDefaults ran, so the datetime atom is bound");
    }

    /** A name outside the map is reported against the map, not against whatever was consulted last. */
    @Test
    void anUnmappedNameNamesTheMap() {
        Tson tson = Tson.builder().schemaSource(uri -> SHORT_SCHEMA.replace("order =>", "invoice =>"))
                .bindings(Map.of("order", Order.class)).build();

        TsonMissingBindingException thrown = assertThrows(TsonMissingBindingException.class,
                () -> tson.objectReader().read("""
                        !!schema:"https://example.test/orders.tn"
                        !invoice { sku: "A"  quantity: 1 }""", Order.class));

        assertTrue(thrown.getMessage().contains("bindings(...) maps [order]"),
                "the caller's own configuration leads: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("invoice"), thrown.getMessage());
    }

    /** {@code profile} reaches the binder, so the shorter constructor is chosen and the schema checked against it. */
    @Test
    void profileReachesTheBinder() {
        Tson tson = Tson.builder().schemaSource(uri -> SHORT_SCHEMA)
                .bindings(Map.of("order", Order.class)).profile("orders-1").build();

        Order order = tson.objectReader().read("""
                !!schema:"https://example.test/orders.tn"
                !order { sku: "A"  quantity: 1 }""", Order.class);

        assertEquals(java.time.OffsetDateTime.parse("2000-01-01T00:00:00Z"), order.when(),
                "the profiled constructor's own default");
    }

    /** A context is built or given, never both -- a profile cannot apply to one that arrives already built. */
    @Test
    void aSuppliedContextAndBindingsAreMutuallyExclusive() {
        DataBindContext context = TsonAtomContext.defaultContext();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> Tson.builder().dataBindContext(context).bindings(Map.of("order", Order.class)).build());

        assertTrue(thrown.getMessage().contains("not both"), thrown.getMessage());
        assertTrue(assertThrows(IllegalStateException.class,
                () -> Tson.builder().dataBindContext(context).profile("orders-1").build())
                .getMessage().contains("not both"));
    }

    /**
     * A type the application never mapped is a <b>misconfiguration</b>, and says so. It used to arrive as
     * {@code UnsupportedOperationException: no usable compiled reader}, which reads as a library gap -- a
     * downstream service mapped it to a 501, for a missing line of configuration.
     *
     * <p>It surfaces at the <em>read</em> of that type rather than at compile, and deliberately: a schema
     * legitimately declares types a given consumer never binds, so failing the compile for one would make
     * bind mode unusable. What changed is the classification, not the moment.
     */
    @Test
    void aMissingBindingIsAMisconfigurationNotAGap() {
        Tson tson = Tson.builder().schemaSource(uri -> SHORT_SCHEMA).bindings(Map.of()).build();

        TsonMissingBindingException thrown = assertThrows(TsonMissingBindingException.class,
                () -> tson.objectReader().read("""
                        !!schema:"https://example.test/orders.tn"
                        !order { sku: "A"  quantity: 1 }""", Order.class));

        assertTrue(thrown.getMessage().contains("no bound Java class for 'order'"), thrown.getMessage());
    }

    /** Tree mode binds nothing, so none of this applies to it. */
    @Test
    void treeModeNeedsNoBindingsAtAll() {
        Tson tson = Tson.builder().schemaSource(SOURCE).build();

        assertEquals("A", tson.treeReader().read(DOC).get("sku").asString().orElseThrow());
    }
}
