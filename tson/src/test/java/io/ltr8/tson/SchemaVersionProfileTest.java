package io.ltr8.tson;

import io.ltr8.annotation.Profile;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.TsonBindMismatchException;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.schema.TsonCanonicalIdentity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two versions of one schema, read into one Java class, in one JVM at once -- the case binding profiles
 * exist for, driven end to end through {@link Tson} rather than through the binder alone.
 *
 * <p>{@code v2} both <b>adds</b> a field and <b>removes</b> one, which is what makes it a real version
 * change rather than a widening: neither shape is a subset of the other, so no single constructor can serve
 * both and each supplies its own default for the field its version does not carry.
 *
 * <p><b>The two mechanisms have to hold together here.</b> The profile picks the constructor; the strict
 * binding check ({@code TsonBindMismatchException}) then verifies that constructor against that version's
 * schema. Either alone would be unsafe -- selection without checking binds whatever it picked, and checking
 * without selection has only one shape to check.
 */
class SchemaVersionProfileTest {

    private static final String V1 = "https://example.test/order-1.tn";
    private static final String V2 = "https://example.test/order-2.tn";

    /** v1 carries {@code code}; v2 drops it and adds {@code currency}. */
    private static final String V1_SCHEMA = """
            !!id:"https://example.test/order-1.tn"
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
            {
              order => { sku: text  quantity: int32  code: int32 }
            }
            """;

    private static final String V2_SCHEMA = """
            !!id:"https://example.test/order-2.tn"
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
            {
              order => { sku: text  quantity: int32  currency: text }
            }
            """;

    /**
     * One class holding the union of both versions' fields, with a constructor per version. Each supplies
     * the default for the field its own version does not carry -- {@code currency} did not exist in v1, and
     * {@code code} is gone in v2 -- so the class is complete whichever document it was built from.
     *
     * <p>The canonical constructor is deliberately left unprofiled: it takes the union, which no version's
     * schema declares, so it is the fallback for a context that names no profile and is never what a
     * versioned read binds through.
     */
    public record Order(String sku, int quantity, int code, String currency) {

        @Profile(value = "api-1", fields = {"sku", "quantity", "code"})
        public Order(String sku, int quantity, int code) {
            this(sku, quantity, code, "AUD");
        }

        @Profile(value = "api-2", fields = {"sku", "quantity", "currency"})
        public Order(String sku, int quantity, String currency) {
            this(sku, quantity, 0, currency);
        }
    }

    private static Tson tson(String profile, String... schemas) {
        TsonSchemaSource source = uri -> {
            for (String schema : schemas) {
                if (TsonCanonicalIdentity.sameIdentity(uri, schema.contains(V1) ? V1 : V2)) {
                    return schema;
                }
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        DataNameBinder binder = name -> "order".equals(name) ? Order.class : SchemaMetaNameBinder.INSTANCE.resolve(name);
        DataBindContext context = TsonAtomContext.registerDefaults(
                DataBindContext.builder().nameBinder(binder).profile(profile).build());
        return Tson.builder().schemaSource(source).dataBindContext(context).build();
    }

    private static final String V1_DOC = """
            !!schema:"https://example.test/order-1.tn"
            !order { sku: "A"  quantity: 1  code: 7 }""";

    private static final String V2_DOC = """
            !!schema:"https://example.test/order-2.tn"
            !order { sku: "B"  quantity: 2  currency: "NZD" }""";

    /** The v1 document, bound through v1's constructor: its own three fields, and v2's default filled in. */
    @Test
    void theOlderDocumentBindsThroughTheOlderConstructor() {
        Order order = tson("api-1", V1_SCHEMA).objectReader().read(V1_DOC, Order.class);

        assertEquals(new Order("A", 1, 7, "AUD"), order);
    }

    /** And the newer one through the newer constructor, defaulting the field its version dropped. */
    @Test
    void theNewerDocumentBindsThroughTheNewerConstructor() {
        Order order = tson("api-2", V2_SCHEMA).objectReader().read(V2_DOC, Order.class);

        assertEquals(new Order("B", 2, 0, "NZD"), order);
    }

    /**
     * Both at once, in one JVM, into one class -- the deployment this is for. Each {@link Tson} holds its own
     * context, so the descriptors never meet.
     */
    @Test
    void bothVersionsAreServedSimultaneouslyByOneClass() {
        Tson older = tson("api-1", V1_SCHEMA);
        Tson newer = tson("api-2", V2_SCHEMA);

        assertEquals(new Order("A", 1, 7, "AUD"), older.objectReader().read(V1_DOC, Order.class));
        assertEquals(new Order("B", 2, 0, "NZD"), newer.objectReader().read(V2_DOC, Order.class));
        assertEquals(new Order("A", 1, 7, "AUD"), older.objectReader().read(V1_DOC, Order.class),
                "the older reader is unchanged by the newer one having run");
    }

    /**
     * <b>A constructor built for another profile is never a candidate.</b> Point the v2 profile at the v1
     * schema and nothing quietly binds: {@code api-2}'s constructor takes {@code currency}, which v1 does not
     * declare, and v1's {@code code} has nowhere to go -- so the two mechanisms catch it together, at
     * startup, naming both sides. Were another profile's constructor eligible, this would have found v1's,
     * matched, and bound a v1 shape under a v2 profile with nothing to say about it.
     */
    @Test
    void aProfilePointedAtTheWrongVersionFailsRatherThanBindingTheOtherOnesConstructor() {
        Tson mismatched = tson("api-2", V1_SCHEMA);

        TsonBindMismatchException thrown = assertThrows(TsonBindMismatchException.class,
                () -> mismatched.bindRegistry().get(V1));

        assertTrue(thrown.getMessage().contains("code"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("currency"), thrown.getMessage());
    }

    /**
     * A context naming no profile falls back to the canonical constructor, which takes the union of both
     * versions -- a shape no version's schema declares, so it is refused rather than half-filled. The
     * fallback is real; it is just not what a versioned read wants.
     */
    @Test
    void anUnprofiledContextFallsBackToTheCanonicalConstructorAndIsRefused() {
        Tson unprofiled = tson(null, V2_SCHEMA);

        TsonBindMismatchException thrown = assertThrows(TsonBindMismatchException.class,
                () -> unprofiled.bindRegistry().get(V2));

        assertTrue(thrown.getMessage().contains("code"), "the union field v2 does not declare: " + thrown.getMessage());
    }
}
