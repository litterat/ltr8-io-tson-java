package io.ltr8.tson;

import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.consumer.Endpoint;
import io.ltr8.tson.consumer.Pick;
import io.ltr8.tson.consumer.Route;
import io.ltr8.tson.consumer.Service;
import io.ltr8.tson.consumer.Shape;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A value by name reached through a record value's field: a namespace type declares only its facets, so a construct
 * with fields of its own and a map of members is a record holding both under a namespace key, and a {@code ref<T>}
 * names a member through the record's field ({@code shop.orders.endpoints.place_order}). The walk reads the field of
 * the host object the read bound, through the binding's own descriptor, and delivers the value it reaches into the
 * {@code Ref}; a field the record does not declare, a key the map lacks, a member no value has, and an optional
 * field the value does not hold are each refused at their step.
 */
class ValueThroughFieldTest {

    private static final String META = "https://example.test/meta-shop.tn";

    private static final String META_TEXT = """
            !!id:"https://example.test/meta-shop.tn"
            !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
            !!import:"https://tson.io/2026/39/m/meta.tn"
            !schema {
              endpoint     => { request?: kind<record>  response?: type_kind }
              endpoint_map => !namespace { key_type: identifier  value_type: endpoint }
              service      => { extends?: [identifier]  fallback?: endpoint  endpoints: endpoint_map }
              services     => !namespace { key_type: identifier  value_type: service }
              route        => { endpoint: ref<endpoint>  verb: text }
              routes       => !namespace { key_type: identifier  value_type: route }
              shape        => { fields: field_map }
              shapes       => !namespace { key_type: identifier  value_type: shape }
              pick         => { field: ref<record_field> }
              picks        => !namespace { key_type: identifier  value_type: pick }
              unbound      => { endpoints: endpoint_map }
              unbounds     => !namespace { key_type: identifier  value_type: unbound }
            }
            """;

    /** The consumer's classes, found by the name binder's package convention. */
    private static final DataNameBinder CONSUMER = new DataNameBinder.DefaultDataNameBinder(
            Set.of("io.ltr8.tson.consumer"), Map.of());

    private static Tson tson() {
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(META, META_TEXT))))
                .withDataBindContext(io.ltr8.bind.DataBindContext.builder().nameBinder(CONSUMER).build()));
    }

    /** A schema under the meta above holding a shop of two services, and {@code more}. */
    private static String shop(String more) {
        return """
                !!id:"https://example.test/shop.tn"
                !!import:"https://example.test/meta-shop.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  order   => { sku: text }
                  receipt => { total: int64 }
                  shop => !services {
                    reads  => { endpoints: { get_order => { response: order } } }
                    orders => { extends: [reads]  fallback: { response: receipt }
                                endpoints: { place_order => { request: order  response: receipt } } }
                  }
                  %s
                }
                """.formatted(more);
    }

    /** The shop with a route naming {@code endpoint}, resolved and linked. */
    private static Map<String, ?> linked(String endpoint) {
        return tson().resolve(shop("""
                web => !routes { r => { endpoint: %s  verb: POST } }""".formatted(endpoint))).schema().entries()
                .entries();
    }

    private static Route route(Map<String, ?> entries) {
        return assertInstanceOf(Route.class, assertInstanceOf(Namespace.class, valueOf(entries.get("web"))).get("r"));
    }

    private static Service service(Map<String, ?> entries, String key) {
        return assertInstanceOf(Service.class, assertInstanceOf(Namespace.class, valueOf(entries.get("shop"))).get(key));
    }

    /** The one diagnostic a refused name reports, and that it says {@code expected}. */
    private static void refused(String endpoint, String... expected) {
        List<Diagnostic> problems = tson().validateSchema(shop("""
                web => !routes { r => { endpoint: %s  verb: POST } }""".formatted(endpoint)));
        assertEquals(1, problems.size(), problems::toString);
        Diagnostic only = problems.getFirst();
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, only.code(), only::toString);
        for (String fragment : expected) {
            assertTrue(only.message().contains(fragment), "expected '" + fragment + "' in: " + only.message());
        }
    }

    // ── Delivered ───────────────────────────────────────────────────────────────────────────────────

    /**
     * A field step, then a key step: {@code endpoints} is read where the binding put it, a component named {@code
     * byName}, and {@code place_order} is a key of the map it holds. The {@code Ref} receives that very value.
     */
    @Test
    void aValueThroughARecordValuesFieldIsDelivered() {
        Map<String, ?> entries = linked("shop.orders.endpoints.place_order");
        Route route = route(entries);

        assertEquals("shop.orders.endpoints.place_order", route.endpoint().name());
        assertTrue(route.endpoint().isResolved(), "delivered at link");
        assertEquals(new Endpoint(TypeRef.of("order"), TypeRef.of("receipt")), route.endpoint().get());
        assertSame(service(entries, "orders").byName().get("place_order"), route.endpoint().get());
    }

    /** A field step that ends the walk: an optional field the value holds, through its {@code Optional} component. */
    @Test
    void aValueAtAnOptionalFieldTheValueHoldsIsDelivered() {
        Map<String, ?> entries = linked("shop.orders.fallback");
        Route route = route(entries);

        assertEquals(new Endpoint(null, TypeRef.of("receipt")), route.endpoint().get());
        assertSame(service(entries, "orders").fallback().orElseThrow(), route.endpoint().get());
    }

    /**
     * A value the consumer supplies no class for: a {@code field_map}'s values bind to the library's own {@link
     * RecordField} through the kernel's binder, and the walk reads the consumer's {@code shape} by its descriptor,
     * enters the {@code Namespace} its field holds, and delivers the {@code RecordField} under the key.
     */
    @Test
    void aValueTheLibrarysOwnBinderBoundIsDelivered() {
        Map<String, ?> entries = tson().resolve(shop("""
                point => !shapes { p => { fields: { x => { type: int64 } } } }
                look  => !picks { a => { field: point.p.fields.x } }""")).schema().entries().entries();

        Pick pick = assertInstanceOf(Pick.class, assertInstanceOf(Namespace.class, valueOf(entries.get("look"))).get("a"));
        Shape shape = assertInstanceOf(Shape.class,
                assertInstanceOf(Namespace.class, valueOf(entries.get("point"))).get("p"));
        RecordField x = assertInstanceOf(RecordField.class, pick.field().get());
        assertEquals(TypeRef.of("int64"), x.type());
        assertSame(shape.fields().get("x"), x);
    }

    // ── Refused ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aSegmentThatIsNoFieldOfTheRecordValueIsRefused() {
        refused("shop.orders.nope",
                "'shop.orders' is a 'service', which has no field 'nope'", "it declares extends, fallback, endpoints");
    }

    @Test
    void aKeyTheMapInTheFieldLacksIsRefused() {
        refused("shop.orders.endpoints.cancel", "'shop.orders.endpoints' has no key 'cancel'", "it holds place_order");
    }

    /** Past the key the value is an {@code endpoint}, whose {@code request} names a type and is no endpoint. */
    @Test
    void aFieldOfTheMemberReachedIsJudgedByItsType() {
        refused("shop.orders.endpoints.place_order.request",
                "wants an 'endpoint'", "'shop.orders.endpoints.place_order.request' is ", "which is no 'endpoint'");
    }

    /** An optional field the value does not hold names nothing. */
    @Test
    void anOptionalFieldTheValueDoesNotHoldIsRefusedAsNotPresent() {
        refused("shop.reads.fallback",
                "'shop.reads' is a 'service' whose field 'fallback' is not present in the value",
                "reaches no value");
    }

    /** The type is judged before the value: an absent field of the wrong type is refused for its type. */
    @Test
    void anAbsentFieldOfTheWrongTypeIsRefusedForItsType() {
        refused("shop.reads.extends", "wants an 'endpoint'", "'shop.reads.extends' is ", "which is no 'endpoint'");
    }

    // ── No host class ───────────────────────────────────────────────────────────────────────────────

    /**
     * A value entry whose records no class binds is held as a tree, and a qualified name walks it by key and field as
     * it walks a bound one: the schema validates. Obtained to bind, it is refused at the entry, {@code BIND_MISMATCH}.
     */
    @Test
    void aValueWithNoHostClassIsWalkedAsATreeAndRefusedWhereBound() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validateSchema(shop("""
                loose => !unbounds { x => { endpoints: { e => { response: order } } } }
                web   => !routes { r => { endpoint: loose.x.endpoints.e  verb: POST } }""")));

        Route route = route(tson.bindRegistry().core().resolveLinked("https://example.test/shop.tn").schema()
                .entries().entries());
        assertEquals("loose.x.endpoints.e", route.endpoint().name());
        // A tree delivered where the host's class is no tree: the schema holds it, and only a bound read refuses it.
        Object delivered = ((io.ltr8.tson.schema.meta.Ref<?>) route.endpoint()).get();
        assertEquals("order", ((io.ltr8.tson.tree.TsonValue) delivered).get("response").asString().orElseThrow());

        List<Diagnostic> problems = new java.util.ArrayList<>();
        tson.bindRegistry().get("https://example.test/shop.tn", problems::add);
        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.BIND_MISMATCH, problems.getFirst().code(), problems::toString);
        assertEquals("/loose", problems.getFirst().schemaPointer().orElseThrow());
    }

    /** The binding's own naming, which the walk follows: each wire name lands in the component it names. */
    @Test
    void theServiceBindsItsRenamedComponents() {
        Service orders = service(linked("shop.orders.fallback"), "orders");
        assertEquals(List.of("reads"), orders.bases());
        assertEquals(Optional.of(new Endpoint(null, TypeRef.of("receipt"))), orders.fallback());
        assertEquals(Set.of("place_order"), orders.byName().keySet());
    }

    /** A value entry's value. */
    private static Object valueOf(Object entry) {
        return ((ValueEntry) entry).value();
    }
}
