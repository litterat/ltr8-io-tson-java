package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.meta.Ref;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A value at a position typed by a reference that names values ([TSON-SCHEMA] §4.2, §5.12), {@code meta.ref<order>}
 * over the record type {@code order}: a data document writes the qualified name of a value of the governing schema,
 * judged by what it reaches as the linker judges a value body's names, and a default or pin a schema writes there is
 * judged the same way when the schema links.
 */
class ValueRefReadTest {

    private static final String SHOP = "https://example.test/shop.tn";

    private static final String SHOP_SCHEMA = """
            !!id:"https://example.test/shop.tn"
            !!import:"https://tson.io/2026/39/m/meta.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            !schema {
              order      => { id: text  qty: int32 }
              rush_order => order & { by: text }
              customer   => { name: text }
              orders     => !meta.namespace { key_type: meta.identifier  value_type: order }
              order_ref  => meta.ref<order>
              pick       => { which: order_ref }
              rpick      => { which?: meta.ref<order> ~ o1 }
              o1         => !order { id: "A-1"  qty: 2 }
              r1         => !rush_order { id: "R-1"  qty: 1  by: noon }
              c1         => !customer { name: Ada }
              book       => !orders { first => { id: "B-1"  qty: 3 } }
            }""";

    private static Tson tson(String schema) {
        SchemaSource source = uri -> schema;
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source)));
    }

    private static TsonValue read(String data) {
        return tson(SHOP_SCHEMA).treeReader().withSchema(SHOP).read("!!import:\"" + SHOP + "\"\n" + data);
    }

    private static List<Diagnostic> problems(String data) {
        return tson(SHOP_SCHEMA).validate("!!import:\"" + SHOP + "\"\n" + data);
    }

    private static TypeRef reference(TsonValue node) {
        return node.as(TypeRef.class).orElseThrow(() -> new AssertionError("not a reference: " + node));
    }

    /** A tree read delivers the reference, as at a kind, and the reference finds the value its name reaches. */
    @Test
    void aTreeReadDeliversTheReferenceAndItsReferent() {
        TypeRef which = reference(read("!pick { which: o1 }").get("which"));

        assertEquals("o1", which.text());
        TsonValue referent = (TsonValue) which.referent().orElseThrow();
        assertEquals("A-1", referent.get("id").asString().orElseThrow());
    }

    @Test
    void aValueOfASubtypeAndAKeyOfANamespaceValueAreNamed() {
        assertEquals(List.of(), problems("!pick { which: r1 }"));
        TypeRef first = reference(read("!pick { which: book.first }").get("which"));
        assertEquals("B-1", ((TsonValue) first.referent().orElseThrow()).get("id").asString().orElseThrow());
    }

    @Test
    void aNameReachingNoValueOfTheTypeIsRefused() {
        for (String refused : List.of("nope", "c1", "order", "book.second", "o1.id")) {
            List<Diagnostic> problems = problems("!pick { which: " + refused + " }");
            assertEquals(1, problems.size(), refused + ": " + problems);
            assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, problems.getFirst().code(), refused);
            assertEquals("/which", problems.getFirst().path().orElseThrow(), refused);
        }
        assertTrue(problems("!pick { which: c1 }").getFirst().message().contains("'customer', which is no 'order'"));
    }

    /** A default written inline at the field is a value the data may omit, and finds what it names. */
    @Test
    void anInlineDefaultIsReadWhereTheDataOmitsTheField() {
        TypeRef which = reference(read("!rpick { }").get("which"));

        assertEquals("o1", which.text());
        assertEquals("A-1", ((TsonValue) which.referent().orElseThrow()).get("id").asString().orElseThrow());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, problems("!rpick { which: c1 }").getFirst().code());
    }

    public record Order(String id, long qty) {
    }

    public record RushOrder(String id, long qty, String by) {
    }

    public record Customer(String name) {
    }

    public record Pick(Ref<Order> which) {
    }

    public record NamedPick(TypeRef which) {
    }

    public record TextPick(String which) {
    }

    private static Tson binding(Class<?> pick) {
        SchemaSource source = uri -> SHOP_SCHEMA;
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source))
                .withDataBindContext(DataBindContext.builder()
                        .nameBinder(DataNameBinder.ofMap(Map.of("order", Order.class, "rush_order", RushOrder.class,
                                        "customer", Customer.class, "pick", pick))
                                .orElse(SchemaModelNameBinder.INSTANCE))
                        .registerAtoms(AtomContext.hostTypes()).build()));
    }

    private static <T> T bind(Class<T> pick, String data) {
        return binding(pick).objectReader().read("!!import:\"" + SHOP + "\"\n" + data, pick);
    }

    /** A {@code Ref} host receives the value the name reaches, bound as the schema's value entry is. */
    @Test
    void aRefHostReceivesTheValueTheNameReaches() {
        Pick pick = bind(Pick.class, "!pick { which: o1 }");

        assertEquals("o1", pick.which().name());
        assertEquals(new Order("A-1", 2), pick.which().get());
    }

    @Test
    void aTypeRefHostReceivesTheNameCarryingItsReferent() {
        NamedPick pick = bind(NamedPick.class, "!pick { which: book.first }");

        assertEquals(TypeRef.of("book.first"), pick.which());
        assertEquals(new Order("B-1", 3), pick.which().referent().orElseThrow());
    }

    @Test
    void aTextHostReceivesTheName() {
        assertEquals(new TextPick("r1"), bind(TextPick.class, "!pick { which: r1 }"));
    }

    @Test
    void aBoundReadRefusesANameReachingNoValueOfTheType() {
        List<Diagnostic> problems = binding(Pick.class).validate("!!import:\"" + SHOP + "\"\n!pick { which: c1 }");
        assertEquals(List.of(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION),
                problems.stream().map(Diagnostic::code).toList());
    }

    private static List<Diagnostic> schemaProblems(String body) {
        String schema = """
                !!id:"https://example.test/picks.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  order     => { id: text }
                  customer  => { name: text }
                  o1        => !order { id: A-1 }
                  c1        => !customer { name: Ada }
                  order_ref => meta.ref<order>
                %s}""".formatted(body);
        return tson(schema).validateSchema(schema);
    }

    /** A default or pin at a reference is a value's name, judged at link against the field that wrote it. */
    @Test
    void aDefaultOrPinIsJudgedByWhatItsNameReaches() {
        assertEquals(List.of(), schemaProblems("  rpick => { which?: order_ref ~ o1 }\n"));
        assertEquals(List.of(), schemaProblems("  rpick => { which: order_ref = o1 }\n"));
        for (String refused : List.of("{ which?: order_ref ~ nope }", "{ which: order_ref = c1 }",
                "{ which?: order_ref ~ order }", "{ which?: meta.ref<order> ~ nope }")) {
            List<Diagnostic> problems = schemaProblems("  rpick => " + refused + "\n");
            assertEquals(1, problems.size(), refused + ": " + problems);
            assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code(), refused);
            assertEquals("/rpick", problems.getFirst().schemaPointer().orElseThrow(), refused);
        }
    }

    /** A value entry's name at a reference is judged where the entry writes it, never at the value it names. */
    @Test
    void aValueEntrysNameIsRefusedAtTheEntryThatWritesIt() {
        List<Diagnostic> problems = schemaProblems("  rpick => { which?: order_ref ~ o1 }\n  p => !rpick { which: nope }\n");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals("/p", problems.getFirst().schemaPointer().orElseThrow());
    }

    /**
     * A sealed family discriminated by a reference naming values: each member pins a value's name, the pins are judged
     * as any pin is, and a value is dispatched by the name it writes.
     */
    @Test
    void aFamilyIsDiscriminatedByAValuesName() {
        String schema = """
                !!id:"https://example.test/family.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  order     => { id: text }
                  o1        => !order { id: A-1 }
                  o2        => !order { id: A-2 }
                  order_ref => meta.ref<order>
                  fam       => { which: order_ref =? }
                  m1        => fam & { which?: = o1  x: text }
                  m2        => fam & { which?: = o2  y: text }
                  holder    => { f: fam }
                }""";
        Tson tson = tson(schema);
        assertEquals(List.of(), tson.validateSchema(schema));
        String head = "!!import:\"https://example.test/family.tn\"\n";
        assertEquals(List.of(), tson.validate(head + "!holder { f: { which: o2  y: b } }"));
        List<Diagnostic> problems = tson.validate(head + "!holder { f: { which: o1  y: b } }");
        assertEquals(List.of("/f/y", "/f/x"), problems.stream().map(d -> d.path().orElseThrow()).toList());
        Diagnostic unmatched = tson.validate(head + "!holder { f: { which: nope  x: a } }").getFirst();
        assertTrue(unmatched.message().matches(".*one of \\((o1 \\| o2|o2 \\| o1)\\).*"), unmatched.message());
        assertEquals(Diagnostic.Code.SCHEMA_ERROR,
                tson(schema.replace("= o2", "= nope")).validateSchema(schema.replace("= o2", "= nope")).getFirst().code());
    }

    /**
     * A field typed by an application written inline, with a default, beside a value entry: a value entry's read
     * compiles the entries ready so far, where the field is still typed by the application, and its default is
     * decoded there as written rather than through the template's reader, which refuses every value.
     */
    @Test
    void anInlineApplicationsDefaultBesideAValueEntryCompiles() {
        assertEquals(List.of(), schemaProblems("  rpick => { which?: meta.ref<order> ~ o1 }\n  p => !rpick { }\n"));
        assertEquals(List.of(), schemaProblems("  kpick => { which?: meta.ref<meta.record> ~ order }\n"
                + "  k => !kpick { }\n"));
    }
}
