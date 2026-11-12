package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.TsonSchemaLinker;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.ParamRef;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.TypeSlots;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A parameter is a leaf at a type slot of any type written in place inside an open record, and is replaced when the
 * record is applied ([TSON-SCHEMA] §5.10): a container, a tuple, a choice and a record in place over a parameter are
 * each the construction the sugar denotes anywhere, nested to any depth, and the application closes to the same
 * structure the hand-written type has.
 */
class TypeInPlaceSubstitutionTest {

    private static final String ID = "https://example.test/type-in-place-substitution.tn";

    private static final String SCHEMA = """
            !!id:"%s"
            !!import:"%s"
            !!import:"%s"
            !schema {
              order => { id: text }
              page => <T> {
                items:  [T]
                some:   [T; 1..]
                maybe?: [T?]
                pair?:  [T, text]
                either?: ( T | int32 )
                byKey?: {text => T}
                keyed?: {T => int32}
                meta:   { cursor: T  total: int32 }
                deep?:  [{ at: T  tags: {text => T} }]
              }
              order_page => page<order>
              holder => { f: page<order>  g?: page<order> }
              slim   => <T> { items: [T]  some: [T; 1..]  meta: { cursor: T  total: int32 } }
              bound  => { s: slim<order> }
              by_hand => {
                f: {
                  items:  [order]
                  some:   [order; 1..]
                  maybe?: [order?]
                  pair?:  [order, text]
                  either?: ( order | int32 )
                  byKey?: {text => order}
                  keyed?: {order => int32}
                  meta:   { cursor: order  total: int32 }
                  deep?:  [{ at: order  tags: {text => order} }]
                }
              }
              tree      => <T> { v: T  kids?: [tree<T>]  meta?: { first?: tree<T> } }
              tree_text => tree<text>
            }""".formatted(ID, TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    public record Order(String id) {
    }

    public record Cursor(Order cursor, int total) {
    }

    public record Page(List<Order> items, List<Order> some, Cursor meta) {
    }

    public record Bound(Page s) {
    }

    private static Tson tson() {
        SchemaSource source = uri -> SCHEMA;
        DataBindContext context = DataBindContext.builder()
                .nameBinder(DataNameBinder.ofMap(Map.of("order", Order.class, "bound", Bound.class))
                        .orElse(SchemaModelNameBinder.INSTANCE))
                .registerAtoms(AtomContext.hostTypes())
                .build();
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source))
                .withDataBindContext(context));
    }

    private static io.ltr8.tson.schema.meta.Namespace<Top> entries() {
        return tson().resolve(SCHEMA).schema().entries();
    }

    private static String document(String root, String body) {
        return "!!import:\"" + ID + "\"\n!" + root + " " + body;
    }

    private static final String PAGE = "{ items: [ { id: a } ]  some: [ { id: b } ]  maybe: [ _ { id: c } ]  "
            + "pair: [ { id: d } t ]  either: 3  byKey: { k => { id: e } }  keyed: { o => 1 }  "
            + "meta: { cursor: { id: f }  total: 2 }  deep: [ { at: { id: g }  tags: { t => { id: h } } } ] }";

    /** Inside the open record each form is its construction in place, the parameter a leaf at its slot. */
    @Test
    void aFormOverAParameterIsItsConstructionInPlace() {
        RecordBody page = (RecordBody) entries().get("page");
        TypeInline items = assertInstanceOf(TypeInline.class, page.fields().get("items").type());
        assertEquals(new TypeInline(ArrayBody.of(ParamRef.of("T"))), items);
        TypeInline meta = assertInstanceOf(TypeInline.class, page.fields().get("meta").type());
        assertEquals(ParamRef.of("T"), ((RecordBody) meta.of()).fields().get("cursor").type());
    }

    @Test
    void aValueReadsThroughTheDeclaredApplicationAndTheApplicationAtASlot() {
        assertEquals(List.of(), tson().validate(document("order_page", PAGE)));
        assertEquals(List.of(), tson().validate(document("holder", "{ f: " + PAGE + " }")));
        assertEquals(List.of(), tson().validate(document("by_hand", "{ f: " + PAGE + " }")));
        TsonValue read = tson().treeReader().read(document("holder", "{ f: " + PAGE + " }"));
        assertEquals(Optional.of("h"), read.get("f").get("deep").get(0).get("tags").get("t").get("id").asString());
    }

    /** What each form checks is checked at the substituted type: a size, an element, a key, a nested record. */
    @Test
    void aValueIsJudgedAgainstTheSubstitutedType() {
        String base = "items: []  meta: { cursor: { id: f }  total: 2 }";
        assertEquals(Optional.of("/some"), only(tson().validate(document("order_page", "{ " + base + "  some: [] }")))
                .path());
        assertEquals(Optional.of("/meta/cursor/id"), only(tson().validate(document("order_page",
                "{ items: []  some: [ { id: b } ]  meta: { cursor: { id: [] }  total: 2 } }"))).path());
        assertEquals(Optional.of("/deep/0/tags/t/colour"), only(tson().validate(document("order_page",
                "{ " + base + "  some: [ { id: b } ]  deep: [ { at: { id: g }  tags: { t => { id: h  colour: r } } } ] }")))
                .path());
    }

    /**
     * Two applications closing to equal structures are one type, and the record in place reached through substitution
     * equals the same record written by hand.
     */
    @Test
    void theSubstitutedTypeIsTheTypeWrittenByHand() {
        var entries = entries();
        RecordBody holder = (RecordBody) entries.get("holder");
        TypeInline f = assertInstanceOf(TypeInline.class, holder.fields().get("f").type());
        TypeInline g = assertInstanceOf(TypeInline.class, holder.fields().get("g").type());
        assertEquals(f, g);
        TypeInline byHand = assertInstanceOf(TypeInline.class, ((RecordBody) entries.get("by_hand")).fields().get("f")
                .type());
        assertEquals(byHand, f);
        assertEquals(byHand.of(), entries.get("order_page"));
    }

    /** A recursion held inside a type in place ties to the declaration closing it, at any depth. */
    @Test
    void aRegularRecursionInsideATypeInPlaceTiesToItsDeclaration() {
        RecordBody tree = (RecordBody) entries().get("tree_text");
        ArrayBody kids = assertInstanceOf(ArrayBody.class, ((TypeInline) tree.fields().get("kids").type()).of());
        assertEquals(TypeRef.of("tree_text"), kids.elementType());
        RecordBody meta = (RecordBody) ((TypeInline) tree.fields().get("meta").type()).of();
        assertEquals(TypeRef.of("tree_text"), meta.fields().get("first").type());
        assertEquals(List.of(), tson().validate(document("tree_text",
                "{ v: a  kids: [ { v: b  meta: { first: { v: c } } } ] }")));
    }

    @Test
    void aValueBindsThroughTheSubstitutedRecordInPlace() {
        Bound bound = tson().objectReader().read(document("bound",
                "{ s: { items: [ { id: a } ]  some: [ { id: b } ]  meta: { cursor: { id: f }  total: 2 } } }"),
                Bound.class);
        assertEquals(new Bound(new Page(List.of(new Order("a")), List.of(new Order("b")),
                new Cursor(new Order("f"), 2))), bound);
    }

    // ── A constructor's application over a parameter (§5.10) ─────────────────────────────────────

    private static final String SPELLINGS = """
            !!id:"https://example.test/spellings.tn"
            !!import:"%s"
            !!import:"%s"
            !schema {
              order    => { id: text }
              box      => <T> { v: T }
              explicit => <T> { a: meta.array<T>  m: meta.map<text meta.array<T>>  s: set<T>  r: meta.ref<T>
                                b: meta.array<box<T>> }
              sugar    => <T> { a: [T]  m: {text => [T]}  s: set<T>  r: meta.ref<T>  b: [box<T>] }
              e        => explicit<order>
              g        => sugar<order>
            }""".formatted(TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    /**
     * Written explicitly or as sugar, a constructor's application over a parameter is its construction in place in
     * the open record, so the two spellings are one resolved form, and their applications one type.
     */
    @Test
    void theExplicitSpellingAndTheSugarAreOneResolvedForm() {
        var entries = Tson.standard().resolve(SPELLINGS).schema().entries();
        RecordBody explicit = (RecordBody) entries.get("explicit");
        RecordBody sugar = (RecordBody) entries.get("sugar");
        for (String field : List.of("a", "m", "s", "r", "b")) {
            assertInstanceOf(TypeInline.class, explicit.fields().get(field).type(), field);
            assertEquals(sugar.fields().get(field).type(), explicit.fields().get(field).type(), field);
        }
        assertEquals(entries.get("g"), entries.get("e"));
    }

    /** In resolved output an application stands only where its head is an open record, inside another (§8.1). */
    @Test
    void noConstructorsApplicationStandsInResolvedOutput() {
        var entries = Tson.standard().resolve(SPELLINGS).schema().entries();
        for (String name : entries.keySet()) {
            Top value = entries.get(name);
            List<TypeRef> held = new ArrayList<>();
            TypeSlots.forEachIn(value, held::add);
            for (TypeRef reference : held) {
                if (!reference.arguments().isEmpty()) {
                    assertTrue(OpenEntry.isOpen(entries.get(reference.name()))
                            && OpenEntry.isOpen(value), name + " holds " + reference.text());
                }
            }
        }
    }

    // ── What the declaration still judges, now that the forms are written in place ─────────────

    private static List<Diagnostic> problems(String declarations) {
        return Tson.standard().validateSchema("""
                !!id:"https://example.test/type-in-place-rules.tn"
                !!import:"%s"
                !!import:"%s"
                !schema {
                  box  => <T> { v: T }
                  node => <T> { value: T  next?: node<T> }
                %s
                }""".formatted(TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID, declarations));
    }

    private static void refused(String declarations, String fragment) {
        Diagnostic refusal = only(problems(declarations));
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, refusal.code(), refusal::toString);
        assertTrue(refusal.message().contains(fragment), refusal.message());
    }

    /** A recursion growing its argument is irregular wherever it is held, inside a type in place included. */
    @Test
    void anIrregularRecursionInsideATypeInPlaceIsRefused() {
        refused("  weird => <T> { next?: [weird<box<T>>] }", "does not pass 'T' through unchanged");
        refused("  weird => <T> { m: { next?: weird<box<T>> } }", "does not pass 'T' through unchanged");
    }

    /** A recursive open record held in place with no declaration to tie to is refused where the slot closes it. */
    @Test
    void aRecursionClosedInPlaceWithNoDeclarationIsRefused() {
        refused("  f => { ns: [node<text>] }", "node<text>");
        refused("  index => <T> { m: { nodes: [node<T>] } }", "'index' holds 'node<T>'");
    }

    /** A parameter passed on from inside a type in place is judged against the bound its callee wrote. */
    @Test
    void aParameterPassedOnFromATypeInPlaceMeetsTheCalleesBound() {
        refused("  boxed => <T: text> { v: T }\n  rebox => <T> { m: { b: [boxed<T>] } }", "is written with no bound");
    }

    /** A literal needs a closed type inside a record in place as at the open record's own field. */
    @Test
    void aLiteralOnAParameterTypedFieldInPlaceIsRefused() {
        refused("  p => <T> { m: { w?: T ~ 5 } }", "literal");
    }

    /** Where a name is required inside a type in place an application is refused at the declaration. */
    @Test
    void anApplicationAtAMapKeyInPlaceIsRefused() {
        refused("  k => <T> { m: {box<T> => text} }", "only a name may stand");
    }

    /** A choice over a parameter is judged disjoint at each application, where its variants are known. */
    @Test
    void aChoiceOverAParameterIsJudgedWhereItIsApplied() {
        assertEquals(List.of(), problems("  pick => <T> { v: ( T | int32 ) }\n  p => pick<text>"));
        refused("  pick => <T> { v: ( T | int32 ) }\n  p => pick<int32>", "int32");
    }

    // ── A closed body holds no parameter (§8.1) ─────────────────────────────────────────────────

    /** In source a parameter outside any open record is a name, and one naming nothing is unresolved. */
    @Test
    void aParameterOutsideAnOpenRecordIsAnUnresolvedReferenceInSource() {
        refused("  r => { m: { cursor: T }  items: [T] }", "unresolved reference 'T'");
    }

    /**
     * In a resolved form read as output, a closed record's {@code T} is a name, at any depth of types in place, never a
     * parameter; what it names is the schema's link to judge (§8.1), which refuses it as an unresolved reference.
     */
    @Test
    void aParameterOutsideAnOpenRecordIsRefusedInAResolvedForm() {
        Map<String, Object> read = ResolvedForm.readResolved(tson(), """
                !!import:"%s"
                !schema {
                  r => !meta.record { fields: {
                    m => !meta.record_field { type: { of: !meta.record { fields: {
                      cursor => !meta.record_field { type: T }
                    } } } }
                  } }
                }
                """.formatted(TsonBundledSchemas.META_ID));
        RecordBody r = assertInstanceOf(RecordBody.class, read.get("r"));
        RecordBody m = (RecordBody) ((TypeInline) r.fields().get("m").type()).of();
        assertEquals(TypeRef.of("T"), m.fields().get("cursor").type());

        SchemaValidationException refused = assertThrows(SchemaValidationException.class,
                () -> TsonSchemaLinker.link(new TsonSchema("https://example.test/read.tn", List.of(),
                        Map.<String, Top>of("r", r)), null));
        assertTrue(refused.getMessage().contains("unresolved reference 'T'"), refused.getMessage());
    }

    private static Diagnostic only(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), problems::toString);
        return problems.getFirst();
    }
}
