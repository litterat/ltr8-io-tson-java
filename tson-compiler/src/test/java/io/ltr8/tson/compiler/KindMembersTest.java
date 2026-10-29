package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.compiler.resolver.KindMembers;
import io.ltr8.tson.compiler.resolver.ReferenceChain;
import io.ltr8.tson.schema.meta.KindBody;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §4.2's {@code kind_type}: the atom whose values are the names of the types built with a
 * constructor, its members computed at link by {@link KindMembers}' construction walk.
 *
 * <p>Each walk case the kernel's {@code kind_type} doc names is pinned on its own: a construction is a member, a
 * refinement of a member is one, the constructor itself is not, a type built with another constructor is not, an
 * ABSTRACT record base is a member of {@code kind<record>} with no values of its own, and an instantiation is a
 * member of its constructor's kind where the template it closes is not. Then {@code of}: resolved in the structure
 * namespace, so a name only the schema's own namespace holds is refused, and refused unless it is {@code type} or
 * below it. Last, the kernel's {@code name_type} slots, typed {@code identifier_kind}, judged by membership.
 */
class KindMembersTest {

    private static final String ID = "https://example.test/kinds.tn";

    private static final String IMPORT_ID = "https://example.test/kind-ids.tn";

    private static final String HEADER = """
            !!id:"https://example.test/kinds.tn"
            !!meta:"https://tson.io/2026/39/m/meta.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
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

    private static KindBody kind(TsonLinkedSchema linked, String name) {
        return assertInstanceOf(KindBody.class, linked.schema().entries().get(name));
    }

    private static final String IDS = """
            {
              stock_id    => !uuid_type {}
              customer_id => !uuid_type {}
              v4_stock_id => !stock_id ^ { version: 4 }
              sku         => text
              any_id      => !kind_type { of: uuid_type }
            }
            """;

    @Test
    void aConstructionAndARefinementOfOneAreMembersAndTheConstructorAndAnotherFamilyAreNot() {
        KindBody ids = kind(link(IDS), "any_id");

        // core's own `uuid` is a member too: the closure is the schema's, imports included.
        assertEquals(Optional.of(List.of("customer_id", "stock_id", "uuid", "v4_stock_id")), ids.members());
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
        KindBody inline = kind(linked, ReferenceChain.terminal(order.fields().get("id").type().name(),
                linked.schema().entries()::get));

        assertEquals(new KindBody("uuid_type"), inline);
        assertEquals(Optional.of(List.of("stock_id", "uuid")), inline.members());
    }

    @Test
    void aBraceRecordIsBuiltWithRecordAndAnAbstractBaseIsAMember() {
        KindBody records = kind(link("""
                {
                  pet     => abstract { name: text }
                  dog     => pet & { breed: text }
                  records => !kind_type { of: record }
                }
                """), "records");

        assertEquals(Optional.of(List.of("dog", "pet")), records.members());
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
                  sets  => !kind_type { of: set_type }
                  boxed => <T> { v: T }
                  box_t => boxed<text>
                  recs  => !kind_type { of: record }
                }
                """);

        // `set` is core's template, a `template` body: no type, so no member.
        assertEquals(Optional.of(List.of("tags")), kind(linked, "sets").members());
        assertEquals(Optional.of(List.of("box_t", "boxed")), kind(linked, "recs").members());
    }

    @Test
    void aBaseKindTakesEveryConstructionBelowIt() {
        KindBody atoms = kind(link("""
                {
                  stock_id => !uuid_type {}
                  atoms    => !kind_type { of: atom }
                }
                """), "atoms");

        assertTrue(atoms.memberNames().containsAll(List.of("stock_id", "uuid", "text", "int32", "boolean")),
                atoms.memberNames().toString());
        assertTrue(!atoms.memberNames().contains("set"), atoms.memberNames().toString());
    }

    @Test
    void anAliasOfAMemberIsAMemberThroughItsHop() {
        KindBody ids = kind(link("""
                {
                  stock_id => !uuid_type {}
                  sku_id   => stock_id
                  ids      => !kind_type { of: uuid_type }
                }
                """), "ids");

        assertEquals(Optional.of(List.of("sku_id", "stock_id", "uuid")), ids.members());
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
                  atoms => !kind_type { of: atom }
                  kinds => !kind_type { of: kind_type }
                  ids   => kind<uuid_type>
                }
                """);

        KindBody atoms = kind(linked, "atoms");
        assertTrue(atoms.memberNames().containsAll(List.of("atoms", "kinds", "ids")), atoms.memberNames().toString());
        assertTrue(!atoms.memberNames().contains("shape"), atoms.memberNames().toString());
        assertEquals(Optional.of(List.of("atoms", "ids", "kinds")), kind(linked, "kinds").members());
    }

    @Test
    void aMemberDeclaredInAnImportIsAMember() {
        TsonLinkedSchema linked = link("""
                !!id:"https://example.test/kinds.tn"
                !!meta:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://example.test/kind-ids.tn"
                {
                  customer_id => !uuid_type {}
                  any_id      => kind<uuid_type>
                }
                """, """
                !!id:"https://example.test/kind-ids.tn"
                !!meta:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                {
                  stock_id => !uuid_type {}
                  some_id  => kind<uuid_type>
                }
                """);

        assertEquals(Optional.of(List.of("customer_id", "stock_id", "uuid")), kind(linked, "any_id").members());
        // The import's own kind is recomputed over the importer's closure, not carried in from its own.
        assertEquals(Optional.of(List.of("customer_id", "stock_id", "uuid")), kind(linked, "some_id").members());
    }

    @Test
    void derivedIndexesWrittenByHandAreDiscardedAndRecomputed() {
        KindBody ids = kind(link("""
                {
                  stock_id => !uuid_type {}
                  any_id   => !kind_type { of: uuid_type  members: [sku] }
                  sku      => text
                }
                """), "any_id");

        assertEquals(Optional.of(List.of("stock_id", "uuid")), ids.members());
    }

    /**
     * A namespace constructor is a type, its instances being values: the kernel's {@code type_kind} holds {@code
     * namespace}, {@code field_map} and {@code schema}, and no constructor IS-A {@code type}, whose instances are
     * types.
     */
    @Test
    void typeKindHoldsTheNamespaceConstructorsAndNoTypeConstructor() {
        TsonLinkedSchema kernel = TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(),
                uri -> {
                    throw new IllegalStateException("unexpected fetch: " + uri);
                }).resolveLinked(TsonBundledSchemas.META_KERNEL_ID);
        List<String> members = kind(kernel, "type_kind").members().orElseThrow();

        assertTrue(members.containsAll(List.of("field_map", "namespace", "schema")), members::toString);
        assertFalse(members.contains("set_type") || members.contains("record") || members.contains("reference"),
                members::toString);
    }

    /** {@code kind<namespace>} is a kind: it holds the kernel's namespace constructors and a meta layer's own. */
    @Test
    void aKindOfNamespaceHoldsEveryNamespaceConstructor() {
        KindBody namespaces = kind(link("""
                !!id:"https://example.test/kinds.tn"
                !!meta:"https://tson.io/2026/39/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                {
                  api        => namespace & { key_type: = identifier  value_type: = text }
                  namespaces => !kind_type { of: namespace }
                }
                """, ""), "namespaces");

        assertEquals(Optional.of(List.of("api", "field_map", "namespace", "schema")), namespaces.members());
    }

    /**
     * A value of a namespace type is a map, not a record, and nothing builds it with {@code map}: it is a member of
     * {@code type_kind} and {@code kind<namespace>}, and of no kind of {@code record}, {@code product} or {@code map}.
     */
    @Test
    void aNamespaceTypeIsNoRecordAndNoMap() {
        TsonLinkedSchema linked = link("""
                !!id:"https://example.test/kinds.tn"
                !!meta:"https://tson.io/2026/39/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                {
                  api        => namespace & { key_type: = identifier  value_type: = text }
                  types      => !kind_type { of: type }
                  namespaces => !kind_type { of: namespace }
                  records    => !kind_type { of: record }
                  products   => !kind_type { of: product }
                  maps       => !kind_type { of: map }
                }
                """, "");

        assertTrue(kind(linked, "types").members().orElseThrow().contains("api"));
        assertTrue(kind(linked, "namespaces").members().orElseThrow().contains("api"));
        for (String kind : List.of("records", "products", "maps")) {
            List<String> members = kind(linked, kind).members().orElseThrow();
            assertFalse(members.contains("api") || members.contains("field_map") || members.contains("schema"),
                    () -> kind + ": " + members);
        }
    }

    @Test
    void aKindOfAConstructorBesideTypeIsRefusedAtTheDeclaration() {
        SchemaValidationException e = assertThrows(SchemaValidationException.class, () -> link("""
                {
                  order => { id: kind<reference> }
                }
                """));

        assertTrue(e.getMessage().contains("'reference'")
                        && e.getMessage().contains("not 'type', 'namespace' or a constructor"), e.getMessage());
    }

    @Test
    void aConstructorAboveTypeIsRefused() {
        SchemaValidationException e = assertThrows(SchemaValidationException.class,
                () -> link("{ tops => !kind_type { of: top } }"));

        assertTrue(e.getMessage().contains("'top'"), e.getMessage());
    }

    @Test
    void ofResolvesInTheStructureNamespaceAndNeverTheSchemasOwn() {
        // `stock_id` is this schema's type, and a kind is not a bound: it names a constructor of the governing meta.
        SchemaValidationException e = assertThrows(SchemaValidationException.class, () -> link("""
                {
                  stock_id => !uuid_type {}
                  ids      => !kind_type { of: stock_id }
                }
                """));

        assertTrue(e.getMessage().contains("structure namespace does not declare"), e.getMessage());
    }

    @Test
    void aSchemaEntryUnderAConstructorsNameDoesNotCaptureOf() {
        // `uuid_type` here is this schema's own text alias; `of` still names meta's constructor.
        KindBody ids = kind(link("""
                !!id:"https://example.test/kinds.tn"
                !!meta:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                {
                  uuid_type => text
                  stock_id  => !uuid_type {}
                  ids       => !kind_type { of: uuid_type }
                }
                """, ""), "ids");

        assertEquals(Optional.of(List.of("stock_id", "uuid")), ids.members());
    }

    @Test
    void anEmptyKindAdmitsNoValueAndIsRefusedAsUninhabited() {
        SchemaValidationException e = assertThrows(SchemaValidationException.class, () -> link("""
                !!id:"https://example.test/kinds.tn"
                !!meta:"https://tson.io/2026/39/m/meta.tn"
                {
                  ids => !kind_type { of: uuid_type }
                }
                """, ""));

        assertTrue(e.getMessage().contains("can never be satisfied"), e.getMessage());
    }

    @Test
    void theResolvedFormWritesTheMembers() {
        Namespace<Top> entries = link(IDS).schema().entries();

        assertEquals(Optional.of(List.of("customer_id", "stock_id", "uuid", "v4_stock_id")),
                ((KindBody) entries.get("any_id")).members());
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

        assertEquals(Optional.of(List.of("field_name", "identifier", "param_name", "scheme_name", "type_name")),
                kind(meta, "identifier_kind").members());
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
                !!meta:"https://tson.io/2026/39/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
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
