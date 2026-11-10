package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.compiler.resolver.KindMembers;
import io.ltr8.tson.compiler.resolver.AliasChain;
import io.ltr8.tson.schema.meta.ReferenceBody;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §4.2's {@code reference} over a constructor, a kind: the atom whose values are the names of the
 * entries whose value is of its {@code of}, its members computed at link by {@link KindMembers}' walk.
 *
 * <p>Each walk case the kernel's {@code reference} doc names is pinned on its own: a construction is a member, a
 * refinement of a member is one, a type built with another constructor is not, an ABSTRACT record base is a member of
 * {@code meta.ref<record>} with no values of its own, an instantiation is a member of its constructor's kind where the
 * template it closes is not, and a constructor, whose declaration is a record, is a member of {@code meta.ref<record>}
 * and {@code meta.type_ref}. A kind's members are computed over the whole closure, so the kernel's and numeric.tn's own
 * types join them wherever the schema reaches core. Then {@code of}: any type or constructor of the schema's one
 * namespace, a type that is no constructor making the reference one that names values. Last, the kernel's {@code
 * name_type} slots, typed {@code meta.identifier_ref}, judged by membership.
 */
class KindMembersTest {

    private static final String ID = "https://example.test/kinds.tn";

    private static final String IMPORT_ID = "https://example.test/kind-ids.tn";

    private static final String HEADER = """
            !!id:"https://example.test/kinds.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema
            """;

    private static TsonLinkedSchema link(String body) {
        return link(HEADER + body, "");
    }

    private static TsonLinkedSchema link(String schema, String imported) {
        return TsonCompiledVocabularyRegistry.withStandardLibrary(SchemaModelNameBinder.defaultContext(), uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            if (CanonicalIdentity.sameIdentity(uri, IMPORT_ID)) {
                return imported;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        }).resolveLinked(ID);
    }

    private static ReferenceBody kind(TsonLinkedSchema linked, String name) {
        return assertInstanceOf(ReferenceBody.class, linked.schema().entries().get(name));
    }

    private static final String IDS = """
            {
              stock_id    => !net.uuid_type {}
              customer_id => !net.uuid_type {}
              v4_stock_id => !stock_id ^ { version: 4 }
              sku         => text
              any_id      => !meta.reference_type { of: net.uuid_type }
            }
            """;

    @Test
    void aConstructionAndARefinementOfOneAreMembersAndTheConstructorAndAnotherFamilyAreNot() {
        ReferenceBody ids = kind(link(IDS), "any_id");

        // core's own `uuid` is a member too: the closure is the schema's, imports included.
        assertEquals(List.of("customer_id", "stock_id", "uuid", "v4_stock_id"), ids.memberNames());
        assertEquals("net.uuid_type", ids.of().text());
    }

    @Test
    void theInlineSpellingMaterialisesTheSameInstance() {
        TsonLinkedSchema linked = link("""
                {
                  stock_id => !net.uuid_type {}
                  order    => { id: meta.ref<net.uuid_type> }
                }
                """);
        RecordBody order = assertInstanceOf(RecordBody.class, linked.schema().entries().get("order"));
        ReferenceBody inline = kind(linked, AliasChain.terminal(order.fields().get("id").typeRef().name(),
                linked.schema().entries()::get));

        assertEquals(ReferenceBody.of("net.uuid_type"), inline);
        assertEquals(List.of("stock_id", "uuid"), inline.memberNames());
    }

    @Test
    void aBraceRecordIsBuiltWithRecordAndAnAbstractBaseIsAMember() {
        ReferenceBody records = kind(link("""
                {
                  pet     => abstract { name: text }
                  dog     => pet & { breed: text }
                  records => !meta.reference_type { of: meta.record }
                }
                """), "records");

        assertTrue(records.memberNames().containsAll(List.of("dog", "pet", "meta.record_field")),
                records.memberNames().toString());
    }

    /**
     * A template is no type and no member (§5.10): it is an open record, built with {@code record}, and a kind of
     * {@code record} holds its applications and not it. The root's {@code set} is an alias of the constructor {@code
     * meta.set_type} itself, no instance of it, so no member of a kind over it.
     */
    @Test
    void anInstantiationIsAMemberOfItsConstructorsKindAndATemplateOfNone() {
        TsonLinkedSchema linked = link("""
                {
                  tags  => set<text>
                  sets  => !meta.reference_type { of: meta.set_type }
                  boxed => <T> { v: T }
                  box_t => boxed<text>
                  recs  => !meta.reference_type { of: meta.record }
                }
                """);

        List<String> sets = kind(linked, "sets").memberNames();
        assertTrue(sets.containsAll(List.of("tags", "meta.enum_set")) && !sets.contains("set"), sets.toString());
        List<String> recs = kind(linked, "recs").memberNames();
        assertTrue(recs.contains("box_t") && !recs.contains("boxed"), recs.toString());
    }

    @Test
    void aBaseKindTakesEveryConstructionBelowIt() {
        ReferenceBody atoms = kind(link("""
                {
                  stock_id => !net.uuid_type {}
                  atoms    => !meta.reference_type { of: meta.atom }
                }
                """), "atoms");

        assertTrue(atoms.memberNames().containsAll(List.of("stock_id", "uuid", "text", "int32", "boolean")),
                atoms.memberNames().toString());
        assertTrue(!atoms.memberNames().contains("set"), atoms.memberNames().toString());
    }

    @Test
    void anAliasOfAMemberIsAMemberThroughItsHop() {
        ReferenceBody ids = kind(link("""
                {
                  stock_id => !net.uuid_type {}
                  sku_id   => stock_id
                  ids      => !meta.reference_type { of: net.uuid_type }
                }
                """), "ids");

        assertEquals(List.of("sku_id", "stock_id", "uuid"), ids.memberNames());
    }

    /**
     * The walk reads construction and never a kind's own members, so a kind built with a constructor at or below
     * its {@code of} is one of its own names, and nothing loops: a kind is an atom, and so is a member of {@code
     * meta.ref<atom>}; a kind of kinds lists every kind, itself among them.
     */
    @Test
    void aKindIsAMemberOfItselfWhereTheWalkSaysSo() {
        TsonLinkedSchema linked = link("""
                {
                  shape => (text | int32)
                  atoms => !meta.reference_type { of: meta.atom }
                  kinds => !meta.reference_type { of: meta.reference_type }
                  ids   => meta.ref<net.uuid_type>
                }
                """);

        ReferenceBody atoms = kind(linked, "atoms");
        assertTrue(atoms.memberNames().containsAll(List.of("atoms", "kinds", "ids")), atoms.memberNames().toString());
        assertTrue(!atoms.memberNames().contains("shape"), atoms.memberNames().toString());
        assertTrue(kind(linked, "kinds").memberNames().containsAll(List.of("atoms", "ids", "kinds", "meta.type_ref")),
                kind(linked, "kinds").memberNames().toString());
    }

    @Test
    void aMemberDeclaredInAnImportIsAMember() {
        TsonLinkedSchema linked = link("""
                !!id:"https://example.test/kinds.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://example.test/kind-ids.tn"
                !schema {
                  customer_id => !net.uuid_type {}
                  any_id      => meta.ref<net.uuid_type>
                }
                """, """
                !!id:"https://example.test/kind-ids.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  stock_id => !net.uuid_type {}
                  some_id  => meta.ref<net.uuid_type>
                }
                """);

        assertEquals(List.of("customer_id", "stock_id", "uuid"), kind(linked, "any_id").memberNames());
        // The import's own kind is recomputed over the importer's closure, not carried in from its own.
        assertEquals(List.of("customer_id", "stock_id", "uuid"), kind(linked, "some_id").memberNames());
    }

    /** The admitted names are the linker's judgement and never a field: an author cannot write them. */
    @Test
    void membersAreNoFieldAnAuthorWrites() {
        SchemaValidationException e = assertThrows(SchemaValidationException.class, () -> link("""
                {
                  stock_id => !net.uuid_type {}
                  any_id   => !meta.reference_type { of: net.uuid_type  members: [sku] }
                  sku      => text
                }
                """));

        assertTrue(e.getMessage().contains("unknown field 'members'"), e.getMessage());
    }

    /**
     * A map type and a namespace type are types: the kernel's {@code meta.type_ref} holds {@code field_map}, built with
     * {@code map}, and {@code schema}, built with {@code namespace}. A constructor is a member too, its value being a
     * record whose instances are declarations: {@code record}, {@code namespace} and {@code set_type} alike. A held
     * template is not.
     */
    @Test
    void typeRefHoldsTheNamespaceTypesAndTheConstructors() {
        TsonLinkedSchema kernel = TsonCompiledVocabularyRegistry.withStandardLibrary(SchemaModelNameBinder.defaultContext(),
                uri -> {
                    throw new IllegalStateException("unexpected fetch: " + uri);
                }).resolveLinked(TsonBundledSchemas.META_ID);
        List<String> members = kind(kernel, "meta.type_ref").memberNames();

        assertTrue(members.containsAll(List.of("meta.field_map", "meta.schema", "meta.declared", "meta.set_type",
                "meta.record", "meta.alias", "meta.namespace", "meta.reference_type", "meta.ref", "meta.top")),
                members::toString);
        assertFalse(members.contains("meta.kind"), members::toString);
    }

    /**
     * {@code meta.ref<namespace>} is a kind: it holds the kernel's namespace type and a schema's own, and the kernel
     * root's {@code schema}, an alias of {@code meta.schema}, beside its target, as a kind holds every alias of a
     * member. A record's {@code field_map} is a map, and no member.
     */
    @Test
    void aKindOfNamespaceHoldsEveryNamespaceType() {
        ReferenceBody namespaces = kind(link("""
                !!id:"https://example.test/kinds.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema {
                  api        => !meta.namespace { key_type: meta.identifier  value_type: text }
                  namespaces => !meta.reference_type { of: meta.namespace }
                }
                """, ""), "namespaces");

        assertEquals(List.of("api", "meta.schema", "schema"), namespaces.memberNames());
    }

    /**
     * A namespace type is built with {@code namespace}, a refinement of {@code map} and so IS-A {@code map} and {@code
     * product}: it is a member of {@code meta.type_ref}, {@code meta.ref<namespace>}, {@code meta.ref<map>} and
     * {@code meta.ref<product>}, and of no kind of {@code record}. A record's {@code field_map} is a map type, a
     * member of {@code meta.ref<map>} and of no {@code meta.ref<namespace>}.
     */
    @Test
    void aNamespaceTypeIsAMapAndNoRecord() {
        TsonLinkedSchema linked = link("""
                !!id:"https://example.test/kinds.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema {
                  api        => !meta.namespace { key_type: meta.identifier  value_type: text }
                  labels     => !meta.map { key_type: meta.identifier  value_type: text }
                  types      => !meta.reference_type { of: meta.type }
                  namespaces => !meta.reference_type { of: meta.namespace }
                  records    => !meta.reference_type { of: meta.record }
                  products   => !meta.reference_type { of: meta.product }
                  maps       => !meta.reference_type { of: meta.map }
                }
                """, "");

        for (String kind : List.of("types", "namespaces", "products", "maps")) {
            List<String> members = kind(linked, kind).memberNames();
            assertTrue(members.containsAll(List.of("api", "meta.schema")), () -> kind + ": " + members);
        }
        List<String> records = kind(linked, "records").memberNames();
        assertFalse(records.contains("api") || records.contains("meta.schema") || records.contains("meta.field_map"),
                () -> "records: " + records);
        assertTrue(kind(linked, "maps").memberNames().containsAll(List.of("labels", "meta.field_map")));
        assertFalse(kind(linked, "namespaces").memberNames().contains("labels"));
        assertFalse(kind(linked, "namespaces").memberNames().contains("meta.field_map"));
    }

    /**
     * A refinement of a reference narrows {@code of} to the source's or a type IS-A it ([TSON-SCHEMA] §5.7), and is
     * IS-A its source: {@code meta.map} IS-A {@code meta.product}, and {@code meta.namespace}, a refinement of {@code
     * meta.map}, IS-A {@code meta.map}. The kernel's own {@code meta.identifier_ref} is such a refinement of {@code
     * meta.type_ref}.
     */
    @Test
    void aReferenceRefinementNarrowsOfAndIsAItsSource() {
        TsonLinkedSchema linked = link("""
                {
                  api        => !meta.namespace { key_type: meta.identifier  value_type: text }
                  products   => !meta.reference_type { of: meta.product }
                  maps       => !products ^ { of: meta.map }
                  namespaces => !maps ^ { of: meta.namespace }
                  records    => !meta.type_ref ^ { of: meta.record }
                  same       => !products ^ {}
                  order      => { id: text }
                  big_order  => order ^ {}
                  orders     => !meta.reference_type { of: order }
                  big_orders => !orders ^ { of: big_order }
                }
                """);

        assertEquals(List.of("products"), TypeChain.supertypesOf(kind(linked, "maps")));
        assertEquals(List.of("maps", "products"), TypeChain.supertypesOf(kind(linked, "namespaces")));
        assertEquals(List.of("meta.field_type_ref", "meta.type_ref"),
                TypeChain.supertypesOf(kind(linked, "meta.identifier_ref")));
        assertTrue(TypeChain.subtypesOf(kind(linked, "meta.type_ref")).contains("meta.identifier_ref"));
        assertEquals("meta.namespace", kind(linked, "namespaces").of().text());
        assertEquals("big_order", kind(linked, "big_orders").of().text());
        List<String> namespaces = kind(linked, "namespaces").memberNames();
        assertTrue(namespaces.contains("api") && !namespaces.contains("meta.field_map"), namespaces::toString);
        assertTrue(kind(linked, "products").memberNames().containsAll(kind(linked, "maps").memberNames()));
    }

    /** A refinement whose {@code of} widens the source's is refused: it would admit names the source refuses. */
    @Test
    void aReferenceRefinementThatWidensOfIsRefused() {
        SchemaValidationException e = assertThrows(SchemaValidationException.class, () -> link("""
                {
                  maps => !meta.reference_type { of: meta.map }
                  wide => !maps ^ { of: meta.product }
                }
                """));

        assertTrue(e.getMessage().contains("not a valid refinement of '!maps'"), e.getMessage());
        assertTrue(e.getMessage().contains("of 'meta.product' is not 'meta.map' or IS-A it"), e.getMessage());
    }

    /** And so is one whose {@code of} is unrelated to the source's, over constructors and over ordinary types. */
    @Test
    void aReferenceRefinementToAnUnrelatedOfIsRefused() {
        SchemaValidationException constructors = assertThrows(SchemaValidationException.class, () -> link("""
                {
                  maps => !meta.reference_type { of: meta.map }
                  odd  => !maps ^ { of: meta.record }
                }
                """));
        assertTrue(constructors.getMessage().contains("of 'meta.record' is not 'meta.map' or IS-A it"),
                constructors.getMessage());

        SchemaValidationException types = assertThrows(SchemaValidationException.class, () -> link("""
                {
                  order  => { id: text }
                  orders => !meta.reference_type { of: order }
                  texts  => !orders ^ { of: text }
                }
                """));
        assertTrue(types.getMessage().contains("of 'text' is not 'order' or IS-A it"), types.getMessage());
    }

    /**
     * {@code of} names any type or constructor, and the predicate decides what it admits: past its alias hops no
     * entry's value is an alias, so {@code meta.ref<alias>} admits nothing, and a required field typed by it can hold
     * no value -- the inhabitance check's verdict, not a refusal of the declaration.
     */
    @Test
    void aKindOfAliasAdmitsNothing() {
        SchemaValidationException e = assertThrows(SchemaValidationException.class, () -> link("""
                {
                  order => { id: meta.ref<meta.alias> }
                }
                """));

        assertTrue(e.getMessage().contains("can never be satisfied"), e.getMessage());
    }

    /** {@code top} is a constructor, so a reference over it names types: every entry whose value IS-A top. */
    @Test
    void aReferenceOverTopNamesEveryType() {
        List<String> members = kind(link("""
                {
                  order => { id: text }
                  tops  => !meta.reference_type { of: meta.top }
                }
                """), "tops").memberNames();

        assertTrue(members.containsAll(List.of("order", "text", "meta.record", "tops")), members::toString);
    }

    /**
     * A reference whose {@code of} is no constructor names values: {@code !meta.reference_type { of: stock_id }}, which
     * {@code meta.ref<stock_id>} spells, holds no type's name.
     */
    @Test
    void ofNamingATypeThatIsNoConstructorNamesValues() {
        ReferenceBody ids = kind(link("""
                {
                  stock_id => !net.uuid_type {}
                  ids      => !meta.reference_type { of: stock_id }
                }
                """), "ids");

        assertFalse(ids.namesTypes());
        assertEquals(List.of(), ids.memberNames());
    }

    @Test
    void aSchemaEntryUnderTheKernelsNameCollidesWithIt() {
        // One namespace per schema: the kernel's constructors sit under `meta`, so a schema importing core may
        // declare its own `uuid_type`, and `meta` itself is the name it cannot declare.
        assertTrue(link("""
                !!id:"https://example.test/kinds.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  uuid_type => text
                }
                """, "").schema().entries().containsKey("uuid_type"));
        SchemaValidationException e = assertThrows(SchemaValidationException.class, () -> link("""
                !!id:"https://example.test/kinds.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  meta => text
                }
                """, ""));

        assertTrue(e.getMessage().contains("'meta' collides"), e.getMessage());
    }

    @Test
    void anEmptyKindAdmitsNoValueAndIsRefusedAsUninhabited() {
        SchemaValidationException e = assertThrows(SchemaValidationException.class, () -> link("""
                !!id:"https://example.test/kinds.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema {
                  pick_type => meta.atom & {}
                  ids => !meta.reference_type { of: pick_type }
                }
                """, ""));

        assertTrue(e.getMessage().contains("can never be satisfied"), e.getMessage());
    }

    @Test
    void theResolvedFormWritesTheMembers() {
        Namespace<Top> entries = link(IDS).schema().entries();

        assertEquals(List.of("customer_id", "stock_id", "uuid", "v4_stock_id"),
                ((ReferenceBody) entries.get("any_id")).memberNames());
    }

    /** A default at a kind-typed field is a type name, judged a member at link (§5.2's field-value rule). */
    @Test
    void aDefaultAtAKindTypedFieldMustNameAMember() {
        link("""
                {
                  stock_id => !net.uuid_type {}
                  ledger   => { named?: meta.ref<net.uuid_type> ~ stock_id }
                }
                """);
        SchemaValidationException e = assertThrows(SchemaValidationException.class, () -> link("""
                {
                  stock_id => !net.uuid_type {}
                  sku      => text
                  ledger   => { named?: meta.ref<net.uuid_type> ~ sku }
                }
                """));

        assertTrue(e.getMessage().contains("'sku' is not a member of the kind"), e.getMessage());
    }

    // ── The kernel's name_type slots, typed by meta.identifier_ref ───────────────────────────────────────────

    /**
     * The kernel's own kinds, computed when meta.tn links over its closure: {@code meta.identifier_ref} holds the
     * kernel's identifier families and the aliases beside them, and nothing meta.tn adds is built with
     * {@code identifier_type}.
     */
    @Test
    void theKernelsIdentifierRefHoldsItsIdentifierFamilies() {
        TsonLinkedSchema meta = TsonCompiledVocabularyRegistry.withStandardLibrary(SchemaModelNameBinder.defaultContext(),
                uri -> { throw new IllegalStateException("unexpected fetch: " + uri); })
                .resolveLinked("https://tson.io/2026/40/m/meta.tn");

        assertEquals(List.of("meta.field_name", "meta.identifier", "meta.param_name", "meta.type_name",
                "net.scheme_name"), kind(meta, "meta.identifier_ref").memberNames());
    }

    /**
     * {@code enum_type.name_type} is typed {@code meta.identifier_ref}, and the name type an enum states is judged a
     * member of it: an instance of {@code identifier_type} is built with {@code identifier_type}, so it names an
     * identifier family.
     */
    @Test
    void anIdentifierInstanceIsANameType() {
        TsonLinkedSchema linked = link("""
                {
                  spaced   => !unicode.identifier_type { continue_add: "-"  medial: " " }
                  cities   => !meta.enum_type { name_type: spaced  members: ["New York" Sydney] }
                }
                """);

        assertTrue(KindMembers.isMember("spaced", "unicode.identifier_type", linked.schema().entries()::get,
                linked.schema()::constructorOf, name -> null));
        assertEquals(List.of("New York", "Sydney"), ((io.ltr8.tson.schema.meta.EnumBody)
                linked.schema().entries().get("cities")).members());
    }

    /**
     * A constructor pinning {@code name_type} pins a value of the slot's kind, judged where the pin is written: a
     * schema pinning a text family fails to load, and one pinning its own identifier family loads.
     */
    @Test
    void aPinnedNameTypeIsJudgedAMemberWhereThePinIsWritten() {
        String header = """
                !!id:"https://example.test/kinds.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema
                """;
        link(header + """
                {
                  text_identifier => !unicode.identifier_type { continue_add: " " }
                  text_enum       => meta.enum_type ^ { name_type?: = text_identifier }
                }
                """, "");
        SchemaValidationException e = assertThrows(SchemaValidationException.class, () -> link(header + """
                {
                  phrase      => !unicode.text_type { normalization: NFC }
                  phrase_enum => meta.enum_type ^ { name_type?: = phrase }
                }
                """, ""));

        assertTrue(e.getMessage().contains("'phrase' is not a member of the kind"), e.getMessage());
    }

    @Test
    void aNameTypeThatIsNoMemberKeepsItsDiagnostic() {
        SchemaValidationException e = assertThrows(SchemaValidationException.class, () -> link("""
                {
                  ports => !meta.enum_type { name_type: integer  members: ["80" "443"] }
                }
                """));

        assertTrue(e.getMessage().contains("is not an identifier family"), e.getMessage());
    }
}
