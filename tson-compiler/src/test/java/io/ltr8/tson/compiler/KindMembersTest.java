package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.compiler.resolver.KindMembers;
import io.ltr8.tson.compiler.resolver.AliasChain;
import io.ltr8.tson.schema.meta.ReferenceBody;
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
 * {@code kind<record>} with no values of its own, an instantiation is a member of its constructor's kind where the
 * template it closes is not, and a constructor, whose declaration is a record, is a member of {@code kind<record>}
 * and {@code type_kind}. A kind's members are computed over the whole closure, so the kernel's and meta.tn's own
 * types join them wherever the schema reaches core. Then {@code of}: any type or constructor of the schema's one
 * namespace, a type that is no constructor making the reference one that names values. Last, the kernel's {@code
 * name_type} slots, typed {@code identifier_kind}, judged by membership.
 */
class KindMembersTest {

    private static final String ID = "https://example.test/kinds.tn";

    private static final String IMPORT_ID = "https://example.test/kind-ids.tn";

    private static final String HEADER = """
            !!id:"https://example.test/kinds.tn"
            !!import:"https://tson.io/2026/39/m/meta.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            !schema
            """;

    private static TsonLinkedSchema link(String body) {
        return link(HEADER + body, "");
    }

    private static TsonLinkedSchema link(String schema, String imported) {
        return TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), uri -> {
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
              stock_id    => !uuid_type {}
              customer_id => !uuid_type {}
              v4_stock_id => !stock_id ^ { version: 4 }
              sku         => text
              any_id      => !reference { of: uuid_type }
            }
            """;

    @Test
    void aConstructionAndARefinementOfOneAreMembersAndTheConstructorAndAnotherFamilyAreNot() {
        ReferenceBody ids = kind(link(IDS), "any_id");

        // core's own `uuid` is a member too: the closure is the schema's, imports included.
        assertEquals(List.of("customer_id", "stock_id", "uuid", "v4_stock_id"), ids.memberNames());
        assertEquals("uuid_type", ids.of());
    }

    @Test
    void theInlineSpellingMaterialisesTheSameInstance() {
        TsonLinkedSchema linked = link("""
                {
                  stock_id => !uuid_type {}
                  order    => { id: kind<uuid_type> }
                }
                """);
        RecordBody order = assertInstanceOf(RecordBody.class, linked.schema().entries().get("order"));
        ReferenceBody inline = kind(linked, AliasChain.terminal(order.fields().get("id").type().name(),
                linked.schema().entries()::get));

        assertEquals(new ReferenceBody("uuid_type"), inline);
        assertEquals(List.of("stock_id", "uuid"), inline.memberNames());
    }

    @Test
    void aBraceRecordIsBuiltWithRecordAndAnAbstractBaseIsAMember() {
        ReferenceBody records = kind(link("""
                {
                  pet     => abstract { name: text }
                  dog     => pet & { breed: text }
                  records => !reference { of: record }
                }
                """), "records");

        assertTrue(records.memberNames().containsAll(List.of("dog", "pet", "record_field")),
                records.memberNames().toString());
    }

    /**
     * A template over another constructor is no type and no member; a record template is a type, the family base
     * its applications are IS-A (§5.10), so a kind of {@code record} holds it beside its applications.
     */
    @Test
    void anInstantiationIsAMemberOfItsConstructorsKindAndSoIsARecordTemplate() {
        TsonLinkedSchema linked = link("""
                {
                  tags  => set<text>
                  sets  => !reference { of: set_type }
                  boxed => <T> { v: T }
                  box_t => boxed<text>
                  recs  => !reference { of: record }
                }
                """);

        // `set` is meta's template, a `template` body: no type, so no member. The kernel's own sets are members.
        List<String> sets = kind(linked, "sets").memberNames();
        assertTrue(sets.containsAll(List.of("tags", "enum_set")) && !sets.contains("set"), sets.toString());
        List<String> recs = kind(linked, "recs").memberNames();
        assertTrue(recs.containsAll(List.of("box_t", "boxed")), recs.toString());
    }

    @Test
    void aBaseKindTakesEveryConstructionBelowIt() {
        ReferenceBody atoms = kind(link("""
                {
                  stock_id => !uuid_type {}
                  atoms    => !reference { of: atom }
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
                  stock_id => !uuid_type {}
                  sku_id   => stock_id
                  ids      => !reference { of: uuid_type }
                }
                """), "ids");

        assertEquals(List.of("sku_id", "stock_id", "uuid"), ids.memberNames());
    }

    /**
     * The walk reads construction and never a kind's own members, so a kind built with a constructor at or below
     * its {@code of} is one of its own names, and nothing loops: a kind is an atom, and so is a member of {@code
     * kind<atom>}; a kind of kinds lists every kind, itself among them.
     */
    @Test
    void aKindIsAMemberOfItselfWhereTheWalkSaysSo() {
        TsonLinkedSchema linked = link("""
                {
                  shape => (text | int32)
                  atoms => !reference { of: atom }
                  kinds => !reference { of: reference }
                  ids   => kind<uuid_type>
                }
                """);

        ReferenceBody atoms = kind(linked, "atoms");
        assertTrue(atoms.memberNames().containsAll(List.of("atoms", "kinds", "ids")), atoms.memberNames().toString());
        assertTrue(!atoms.memberNames().contains("shape"), atoms.memberNames().toString());
        assertTrue(kind(linked, "kinds").memberNames().containsAll(List.of("atoms", "ids", "kinds", "type_kind")),
                kind(linked, "kinds").memberNames().toString());
    }

    @Test
    void aMemberDeclaredInAnImportIsAMember() {
        TsonLinkedSchema linked = link("""
                !!id:"https://example.test/kinds.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://example.test/kind-ids.tn"
                !schema {
                  customer_id => !uuid_type {}
                  any_id      => kind<uuid_type>
                }
                """, """
                !!id:"https://example.test/kind-ids.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  stock_id => !uuid_type {}
                  some_id  => kind<uuid_type>
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
                  stock_id => !uuid_type {}
                  any_id   => !reference { of: uuid_type  members: [sku] }
                  sku      => text
                }
                """));

        assertTrue(e.getMessage().contains("unknown field 'members'"), e.getMessage());
    }

    /**
     * A namespace type is a type built with {@code namespace}: the kernel's {@code type_kind} holds {@code field_map}
     * and {@code schema}. A constructor is a member too, its value being a record whose instances are declarations:
     * {@code record}, {@code namespace} and {@code set_type} alike. A held template is not.
     */
    @Test
    void typeKindHoldsTheNamespaceTypesAndTheConstructors() {
        TsonLinkedSchema kernel = TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(),
                uri -> {
                    throw new IllegalStateException("unexpected fetch: " + uri);
                }).resolveLinked(TsonBundledSchemas.META_KERNEL_ID);
        List<String> members = kind(kernel, "type_kind").memberNames();

        assertTrue(members.containsAll(List.of("field_map", "schema", "declared", "set_type", "record", "alias",
                "namespace", "reference", "top")), members::toString);
        assertFalse(members.contains("kind") || members.contains("ref"), members::toString);
    }

    /** {@code kind<namespace>} is a kind: it holds the kernel's namespace types and a schema's own. */
    @Test
    void aKindOfNamespaceHoldsEveryNamespaceType() {
        ReferenceBody namespaces = kind(link("""
                !!id:"https://example.test/kinds.tn"
                !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema {
                  api        => !namespace { key_type: identifier  value_type: text }
                  namespaces => !reference { of: namespace }
                }
                """, ""), "namespaces");

        assertEquals(List.of("api", "field_map", "schema"), namespaces.memberNames());
    }

    /**
     * A namespace type is built with {@code namespace}, which IS-A {@code product}: it is a member of {@code
     * type_kind}, {@code kind<namespace>} and {@code kind<product>}, and of no kind of {@code record} or {@code map}.
     */
    @Test
    void aNamespaceTypeIsAProductAndNoRecordOrMap() {
        TsonLinkedSchema linked = link("""
                !!id:"https://example.test/kinds.tn"
                !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema {
                  api        => !namespace { key_type: identifier  value_type: text }
                  labels     => !map { key_type: identifier  value_type: text }
                  types      => !reference { of: type }
                  namespaces => !reference { of: namespace }
                  records    => !reference { of: record }
                  products   => !reference { of: product }
                  maps       => !reference { of: map }
                }
                """, "");

        assertTrue(kind(linked, "types").memberNames().contains("api"));
        assertTrue(kind(linked, "namespaces").memberNames().contains("api"));
        assertTrue(kind(linked, "products").memberNames().contains("api"));
        for (String kind : List.of("records", "maps")) {
            List<String> members = kind(linked, kind).memberNames();
            assertFalse(members.contains("api") || members.contains("field_map") || members.contains("schema"),
                    () -> kind + ": " + members);
        }
    }

    /**
     * {@code of} names any type or constructor, and the predicate decides what it admits: past its alias hops no
     * entry's value is an alias, so {@code kind<alias>} admits nothing, and a required field typed by it can hold
     * no value -- the inhabitance check's verdict, not a refusal of the declaration.
     */
    @Test
    void aKindOfAliasAdmitsNothing() {
        SchemaValidationException e = assertThrows(SchemaValidationException.class, () -> link("""
                {
                  order => { id: kind<alias> }
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
                  tops  => !reference { of: top }
                }
                """), "tops").memberNames();

        assertTrue(members.containsAll(List.of("order", "text", "record", "tops")), members::toString);
    }

    /**
     * A reference whose {@code of} is no constructor names values, whichever template spelled it: {@code
     * kind<stock_id>} is {@code ref<stock_id>}, and no type's name is one.
     */
    @Test
    void ofNamingATypeThatIsNoConstructorNamesValues() {
        ReferenceBody ids = kind(link("""
                {
                  stock_id => !uuid_type {}
                  ids      => !reference { of: stock_id }
                }
                """), "ids");

        assertFalse(ids.namesTypes());
        assertEquals(List.of(), ids.memberNames());
    }

    @Test
    void aSchemaEntryUnderAConstructorsNameCollidesWithIt() {
        // One namespace per schema: meta.tn's `uuid_type` is in the closure, so the schema cannot declare its own.
        SchemaValidationException e = assertThrows(SchemaValidationException.class, () -> link("""
                !!id:"https://example.test/kinds.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  uuid_type => text
                }
                """, ""));

        assertTrue(e.getMessage().contains("'uuid_type' collides"), e.getMessage());
    }

    @Test
    void anEmptyKindAdmitsNoValueAndIsRefusedAsUninhabited() {
        SchemaValidationException e = assertThrows(SchemaValidationException.class, () -> link("""
                !!id:"https://example.test/kinds.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema {
                  ids => !reference { of: uuid_type }
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
                  stock_id => !uuid_type {}
                  ledger   => { named?: kind<uuid_type> ~ stock_id }
                }
                """);
        SchemaValidationException e = assertThrows(SchemaValidationException.class, () -> link("""
                {
                  stock_id => !uuid_type {}
                  sku      => text
                  ledger   => { named?: kind<uuid_type> ~ sku }
                }
                """));

        assertTrue(e.getMessage().contains("'sku' is not a member of the kind"), e.getMessage());
    }

    // ── The kernel's name_type slots, typed by identifier_kind ───────────────────────────────────────────

    /**
     * The kernel's own kinds, computed when meta.tn links over its closure: {@code identifier_kind} holds the
     * kernel's identifier families and the aliases beside them, and nothing meta.tn adds is built with
     * {@code identifier_type}.
     */
    @Test
    void theKernelsIdentifierKindHoldsItsIdentifierFamilies() {
        TsonLinkedSchema meta = TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(),
                uri -> { throw new IllegalStateException("unexpected fetch: " + uri); })
                .resolveLinked("https://tson.io/2026/39/m/meta.tn");

        assertEquals(List.of("field_name", "identifier", "param_name", "scheme_name", "type_name"),
                kind(meta, "identifier_kind").memberNames());
        assertTrue(kind(meta, "atom_kind").memberNames().containsAll(
                List.of("boolean", "identifier", "identifier_kind", "atom_kind")),
                kind(meta, "atom_kind").memberNames().toString());
    }

    /**
     * {@code enum_type.name_type} is typed {@code identifier_kind}, and the name type an enum states is judged a
     * member of it: an instantiation of a template over {@code identifier_type} is built with {@code
     * identifier_type}, so it names an identifier family.
     */
    @Test
    void anInstantiationOfAnIdentifierTemplateIsANameType() {
        TsonLinkedSchema linked = link("""
                {
                  words_of => <C: text> !identifier_type { continue_add: "-"  medial: C }
                  spaced   => words_of<" ">
                  cities   => !enum_type { name_type: spaced  members: ["New York" Sydney] }
                }
                """);

        assertTrue(KindMembers.isMember("spaced", "identifier_type", linked.schema().entries()::get,
                linked.schema()::constructorOf, name -> null));
        assertEquals(List.of("New York", "Sydney"), ((io.ltr8.tson.schema.meta.EnumBody)
                linked.schema().entries().get("cities")).members());
    }

    /**
     * A constructor pinning {@code name_type} pins a value of the slot's kind, judged where the pin is written: a
     * meta layer pinning a text family fails to load, and one pinning its own identifier family loads.
     */
    @Test
    void aPinnedNameTypeIsJudgedAMemberWhereThePinIsWritten() {
        String header = """
                !!id:"https://example.test/kinds.tn"
                !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema
                """;
        link(header + """
                {
                  text_identifier => !identifier_type { continue_add: " " }
                  text_enum       => enum_type ^ { name_type?: = text_identifier }
                }
                """, "");
        SchemaValidationException e = assertThrows(SchemaValidationException.class, () -> link(header + """
                {
                  phrase      => !text_type { normalization: NFC }
                  phrase_enum => enum_type ^ { name_type?: = phrase }
                }
                """, ""));

        assertTrue(e.getMessage().contains("'phrase' is not a member of the kind"), e.getMessage());
    }

    @Test
    void aNameTypeThatIsNoMemberKeepsItsDiagnostic() {
        SchemaValidationException e = assertThrows(SchemaValidationException.class, () -> link("""
                {
                  ports => !enum_type { name_type: integer  members: ["80" "443"] }
                }
                """));

        assertTrue(e.getMessage().contains("is not an identifier family"), e.getMessage());
    }
}
