package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules a type written in place is held to beside a name ([TSON-SCHEMA] §5.2, §5.4, §5.7, §8.2): a value modifier
 * on an inline reference, a name never one type with a construction, a choice that is not disjoint naming every
 * variant, an application of an open record absorbed at a refinement source, what a type in place may carry, and an
 * abstract record in place empty.
 */
class InlineTypeRulesTest {

    private static final String ID = "https://example.test/inline-type-rules.tn";

    private static final String PRELUDE = """
              method     => { name: text }
              orders     => !meta.namespace { key_type: meta.identifier  value_type: method }
              api        => !orders { place_order => { name: "p" } }
              method_ref => meta.ref<method>
              text_list  => [text]
              box        => <T> { v: T  w?: text }
              animal     => { legs?: int32 }
              pet_box    => <T> animal & { v: T }
            """;

    private static String schema(String declarations) {
        return """
                !!id:"%s"
                !!import:"%s"
                !!import:"%s"
                !schema {
                %s%s
                }""".formatted(ID, TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID, PRELUDE, declarations);
    }

    private static List<Diagnostic> problems(String declarations) {
        return Tson.standard().validateSchema(schema(declarations));
    }

    private static Tson tson(String declarations) {
        String text = schema(declarations);
        SchemaSource source = uri -> text;
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source)));
    }

    private static void refused(String declarations, String fragment) {
        List<Diagnostic> found = problems(declarations);
        assertEquals(1, found.size(), found::toString);
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, found.getFirst().code(), found::toString);
        assertTrue(found.getFirst().message().contains(fragment), found.getFirst().message());
    }

    // ── A value modifier on an inline reference (§5.2) ──────────────────────────────────────────

    /** A reference written in place is an atom, so it carries a default, judged at link as a declared one is. */
    @Test
    void aDefaultOnAnInlineReferenceIsAdmittedAndJudged() {
        assertEquals(List.of(), problems("  r => { handler?: meta.ref<method> ~ api.place_order }"));
        assertEquals(List.of(), problems("  r => { handler?: method_ref ~ api.place_order }"));
        refused("  r => { handler?: meta.ref<method> ~ api.nope }", "'api' has no key 'nope'");
    }

    /** The default is injected into a document that omits the field, as a declared reference's is. */
    @Test
    void theDefaultOfAnInlineReferenceIsInjected() {
        TsonValue read = tson("  r => { handler?: meta.ref<method> ~ api.place_order  n: text }")
                .treeReader().read("!!import:\"" + ID + "\"\n!r { n: x }");
        assertFalse(read.get("handler").isMissing(), read::toString);
    }

    /** Any other type written in place carries no value, as a declared container carries none. */
    @Test
    void aValueOnAnyOtherInlineTypeIsRefused() {
        refused("  r => { tags?: [text] ~ a }", "a type written in place that is no reference");
        refused("  r => { pair?: [text, int32] = a }", "a type written in place that is no reference");
        refused("  r => { pick?: (text | int32) ~ a }", "a type written in place that is no reference");
        refused("  r => { tags?: text_list ~ a }", "which is an array, so it cannot have a default");
    }

    /**
     * The kernel's own dependent field: a record field's {@code value}, typed {@code type!}, is judged against an
     * inline reference its {@code type} holds.
     */
    @Test
    void aRecordFieldsValueIsJudgedAgainstAnInlineReference() {
        assertEquals(List.of(), problems("  s => !meta.record { fields: { h => { type: { of: !meta.ref { of: method } }"
                + "  optional: true  role: DEFAULT  value: api.place_order } } }"));
        refused("  s => !meta.record { fields: { h => { type: { of: !meta.ref { of: method } }  optional: true  "
                + "role: DEFAULT  value: api.nope } } }", "'api' has no key 'nope'");
    }

    public record Setting(io.ltr8.tson.schema.meta.TypeExpr kind, Object v) {
    }

    /**
     * In data, a dependent value whose sibling holds a type written in place is read against it, not dropped: a bind
     * read, whose sibling is the type, reads it; a tree read, whose sibling is the record it is written as, reports
     * that it does not yet.
     */
    @Test
    void aDependentValueIsReadAgainstASiblingWrittenInPlace() {
        String text = """
                !!id:"%s"
                !!import:"%s"
                !!import:"%s"
                !schema { setting => { kind: meta.type_expr  v?: kind! } }"""
                .formatted(ID, TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);
        SchemaSource source = uri -> text;
        io.ltr8.bind.DataBindContext context = io.ltr8.bind.DataBindContext.builder()
                .nameBinder(io.ltr8.bind.DataNameBinder.ofMap(java.util.Map.of("setting", Setting.class))
                        .orElse(io.ltr8.tson.compiler.config.SchemaModelNameBinder.INSTANCE))
                .registerAtoms(io.ltr8.tson.base.bind.AtomContext.hostTypes()).registerAtom(Object.class)
                .build();
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source))
                .withDataBindContext(context));
        String data = "!!import:\"" + ID + "\"\n!setting { kind: { of: !meta.array { element_type: int32 } }  v: %s }";

        Setting bound = tson.objectReader().read(data.formatted("[1 2]"), Setting.class);
        assertEquals(List.of(1, 2), bound.v());
        assertThrows(io.ltr8.tson.base.ReadException.class,
                () -> tson.objectReader().read(data.formatted("[a]"), Setting.class));

        List<Diagnostic> found = new ArrayList<>();
        tson.treeReader().withDiagnostics(found::add).read(data.formatted("[1 2]"));
        assertEquals(List.of(Diagnostic.Code.NOT_IMPLEMENTED), found.stream().map(Diagnostic::code).toList(),
                found::toString);
    }

    // ── A name and a construction are never one type (§8.2) ─────────────────────────────────────

    @Test
    void aRestatedFieldMayNotSwapANameForItsConstruction() {
        refused("  base => { tags: text_list }\n  sub => base ^ { tags: [text] }",
                "a name and a type written in place are never one type");
        refused("  base => { tags: [text] }\n  sub => base ^ { tags: text_list }",
                "a name and a type written in place are never one type");
        assertEquals(List.of(), problems("  base => { tags: [text] }\n  sub => base ^ { tags: [text] }"));
        assertEquals(List.of(), problems("  base => { tags: text_list }\n  sub => base ^ { tags: text_list }"));
    }

    /**
     * A name and the construction it names are two variants and no repeat: the choice is refused for not being
     * disjoint, as any two bracket-class variants are, never as a duplicate; two equal constructions are a duplicate.
     */
    @Test
    void aNameAndItsConstructionAreTwoVariants() {
        refused("  c => ( text_list | [text] )", "is not disjoint");
        refused("  c => ( [text] | [text] )", "twice");
    }

    // ── A choice that is not disjoint names every variant (§5.4) ────────────────────────────────

    @Test
    void aChoiceThatIsNotDisjointHasEveryVariantAName() {
        refused("  c => ( [text] | [int32] )", "[[text], [int32]] are variants written in place");
        refused("  r => { f: ( [text] | [int32] ) }", "is not disjoint");
        assertEquals(List.of(), problems("  c => ( [text] | text )"));
        assertEquals(List.of(), problems("  a1 => [text]\n  a2 => [int32]\n  c => ( a1 | a2 )"));
    }

    /** An explicit assertion that fails keeps its own diagnostic, and only that one. */
    @Test
    void aFailedDisjointAssertionKeepsItsOwnDiagnostic() {
        refused("  c => @meta.disjoint ( [text] | [int32] )", "asserts @meta.disjoint");
    }

    // ── An application at a refinement source is absorbed (§5.7, §5.8) ──────────────────────────

    @Test
    void anApplicationAtARefinementSourceIsAbsorbed() {
        assertEquals(List.of(), problems("  x => box<text> ^ { w: text }"));
        RecordBody x = assertInstanceOf(RecordBody.class, Tson.standard().resolve(schema("  x => box<text> ^ { w: text }"))
                .schema().entries().get("x"));
        assertEquals(List.of("v", "w"), List.copyOf(x.fields().keySet()));
        assertFalse(x.fields().get("w").optional(), "the refinement made 'w' required");
        assertFalse(TypeChain.supertypesOf(x).contains("box"), "nothing is IS-A a template");
    }

    /** It is IS-A its template's written parents, and the entry declared for the application where one is. */
    @Test
    void anAbsorbedRefinementSourceReachesTheDeclaredApplication() {
        String declarations = "  tb => pet_box<text>\n  x => pet_box<text> ^ { legs: int32 }";
        assertEquals(List.of(), problems(declarations));
        Top x = Tson.standard().resolve(schema(declarations)).schema().entries().get("x");
        List<String> supertypes = TypeChain.supertypesOf(x);
        assertTrue(supertypes.contains("animal"), supertypes::toString);
        assertTrue(supertypes.contains("tb"), supertypes::toString);
        assertFalse(supertypes.contains("pet_box"), supertypes::toString);
    }

    /**
     * An application whose head is an alias of an open record is the open record's application ([TSON-SCHEMA] §8.3),
     * absorbed at a refinement source and at a composition operand alike, its edge naming the entry declared for the
     * application, and so is one an open record composes over its own parameter.
     */
    @Test
    void anApplicationThroughAnAliasIsAbsorbedAtAnOperand() {
        String declarations = """
                  pet_of => pet_box
                  tb     => pet_box<text>
                  x      => pet_of<text> ^ { legs: int32 }
                  y      => pet_of<text> & { name: text }
                  vip    => <T> pet_of<T> & { rank: int32 }
                  vt     => vip<text>
                """;
        assertEquals(List.of(), problems(declarations));
        var entries = Tson.standard().resolve(schema(declarations)).schema().entries();
        RecordBody x = assertInstanceOf(RecordBody.class, entries.get("x"));
        assertEquals(List.of("legs", "v"), List.copyOf(x.fields().keySet()));
        assertFalse(x.fields().get("legs").optional(), "the refinement made 'legs' required");
        RecordBody y = assertInstanceOf(RecordBody.class, entries.get("y"));
        assertEquals(List.of("legs", "v", "name"), List.copyOf(y.fields().keySet()));
        for (String name : List.of("x", "y", "vt")) {
            List<String> supertypes = TypeChain.supertypesOf(entries.get(name));
            assertTrue(supertypes.contains("animal"), name + " " + supertypes);
            assertTrue(supertypes.contains("tb"), name + " " + supertypes);
            assertFalse(supertypes.contains("pet_box") || supertypes.contains("pet_of"), name + " " + supertypes);
        }
    }

    // ── A namespace type is declared (§4.2, §5.2) ───────────────────────────────────────────────

    /**
     * A namespace value carries its type's name as its tag, and a type written in place has none, so every route to a
     * namespace written in place is refused at the declaration that wrote it, naming the declaration to write.
     */
    @Test
    void aNamespaceWrittenInPlaceIsRefused() {
        refused("  r => { f: meta.namespace<meta.identifier method> }",
                "'meta.namespace<meta.identifier method>' constructs a namespace type where it is used");
        refused("  r => { f: meta.namespace<meta.identifier method> }",
                "('my_namespace => !meta.namespace { key_type: meta.identifier  value_type: method }')");
        refused("  r => { f: [meta.namespace<meta.identifier method>] }", "constructs a namespace type");
        refused("  ns => meta.namespace\n  r => { f: ns<meta.identifier method> }", "constructs a namespace type");
        refused("  o => <K> { m: meta.namespace<K text> }", "'meta.namespace<K text>' constructs a namespace type");
        refused("  o => <T> { v: T  m: {text => meta.namespace<meta.identifier T>} }", "constructs a namespace type");
    }

    /** A declared namespace type is unchanged, a type written in place at its value type included. */
    @Test
    void aDeclaredNamespaceIsUnchanged() {
        assertEquals(List.of(),
                problems("  tagged => !meta.namespace { key_type: meta.identifier  value_type: box<text> }"));
        assertEquals(List.of(), problems("  ns => meta.namespace<meta.identifier method>\n  r => { f: ns }"));
    }

    // ── What a type written in place may carry (§4.2) ───────────────────────────────────────────

    private static String atInPlace(String of) {
        return atInPlace("", of);
    }

    /** {@code r}, whose field {@code at} states {@code facts} and holds {@code of} in place. */
    private static String atInPlace(String facts, String of) {
        return "  r => !meta.record { fields: { at => !meta.record_field { " + facts + " type: { of: " + of
                + " } } } }";
    }

    private static final String X = "fields: { x => !meta.record_field { type: int32 } }";

    /** What the kernel admits at {@code of} and a type in place may not carry is refused by the rule. */
    @Test
    void aChainParametersOrANominalTypeInPlaceIsRefusedByTheRule() {
        refused(atInPlace("!meta.record { " + X + "  chain: { supertypes: [animal] } }"),
                "the record written in place '{ x: int32 }' states a chain");
        refused(atInPlace("!meta.record { parameters: [ !meta.param { name: T } ]  "
                + "fields: { x => !meta.record_field { type: T } } }"), "'{ x: T }' takes parameters");
        refused(atInPlace("!meta.array { element_type: text  chain: { supertypes: [animal] } }"),
                "the type written in place '[text]' states a chain");
        refused(atInPlace("!numeric.integer_type { min: 0 }"), "'!numeric.integer_type { ... }' is written in place");
        for (String body : List.of("!meta.record { " + X + "  chain: { supertypes: [animal] } }",
                "!numeric.integer_type { min: 0 }")) {
            List<Diagnostic> found = problems(atInPlace(body));
            assertFalse(found.getFirst().message().contains("rejected"), found::toString);
        }
    }

    /** What a record in place may say of itself loads: FINAL, an empty chain, a name type, groups. */
    @Test
    void whatARecordInPlaceMayStateLoads() {
        for (String body : List.of("!meta.record { " + X + "  extension: FINAL }",
                "!meta.record { " + X + "  chain: {} }", "!meta.record { " + X + "  name_type: meta.field_name }",
                "!meta.record { fields: { x => !meta.record_field { type: int32  optional: true }  "
                        + "y => !meta.record_field { type: int32  optional: true } }  "
                        + "groups: [ !meta.field_group { members: [[x] [y]] } ] }")) {
            assertEquals(List.of(), problems(atInPlace(body)), body);
        }
    }

    // ── An abstract record written in place is empty (§4.2, §5.10.1) ────────────────────────────

    private static final String SHAPE = "  shape => <T> { kind: text =?  data: T }\n";

    private static void unsatisfiable(String declarations, String entry) {
        refused(declarations, "'" + entry + "' can never be satisfied by any document");
    }

    /**
     * An abstract record in place has no member any schema could add, so a required use of it empties the record
     * holding it, by the productivity rule -- written in canonical form or closed from an application alike, and
     * through a container exactly where the container cannot be empty.
     */
    @Test
    void aRequiredUseOfAnAbstractRecordInPlaceIsUnsatisfiable() {
        unsatisfiable(atInPlace("!meta.record { " + X + "  extension: ABSTRACT }"), "r");
        unsatisfiable(SHAPE + "  r => { s: shape<method> }", "r");
        unsatisfiable(SHAPE + "  r => { s: [shape<method>; 1..] }", "r");
        unsatisfiable(SHAPE + "  r => { s: [shape<method>, text] }", "r");
        assertEquals(List.of(), problems(SHAPE + "  r => { s: [shape<method>]  m: {text => shape<method>} }"));
        assertEquals(List.of(), problems(SHAPE + "  r => { s: ( shape<method> | text )  v: shape<method>? }"));
    }

    /** A declared abstract record stays open to members an importing schema declares, so it empties nothing. */
    @Test
    void aRequiredUseOfADeclaredAbstractRecordLoads() {
        assertEquals(List.of(), problems("  pet => abstract { kind: text =?  name: text }\n  r => { p: pet }"));
    }

    /** An optional field typed by an abstract record in place loads, and can only be missing. */
    @Test
    void anOptionalUseOfAnAbstractRecordInPlaceLoadsAndCanOnlyBeMissing() {
        for (String declarations : List.of(atInPlace("optional: true", "!meta.record { " + X
                + "  extension: ABSTRACT }"), SHAPE + "  r => { at?: shape<method> }")) {
            assertEquals(List.of(), problems(declarations), declarations);
            assertEquals(List.of(), read(declarations, "!r {}"), declarations);
            List<Diagnostic> written = read(declarations, "!r { at: { x: 1  kind: k  data: { name: n } } }");
            assertEquals(1, written.size(), written::toString);
            assertEquals(Diagnostic.Code.TYPE_MISMATCH, written.getFirst().code(), written::toString);
            assertEquals(Optional.of("/at"), written.getFirst().path(), written::toString);
        }
    }

    /** {@code discriminators} alone decide nothing: a record in place that is not abstract reads as any record. */
    @Test
    void discriminatorsWithoutAbstractDoNotEmptyARecordInPlace() {
        String declarations = atInPlace("!meta.record { " + X + "  discriminators: [x] }");
        assertEquals(List.of(), problems(declarations));
        assertEquals(List.of(), read(declarations, "!r { at: { x: 1 } }"));
    }

    // ── A record body written at a slot (§5.2) ──────────────────────────────────────────────────

    /** Two records in place are not disjoint, and neither has a name a tag could give; one beside text is. */
    @Test
    void twoRecordsInPlaceAsVariantsAreNotDisjoint() {
        refused("  r => { c: ( { x: int32 } | { y: int32 } ) }", "are variants written in place");
        refused("  c => ( { x: int32 } | { y: int32 } )", "are variants written in place");
        assertEquals(List.of(), problems("  r => { c: ( { x: int32 } | text ) }"));
    }

    /** A field typed by a record in place carries no value: a field's value is a token. */
    @Test
    void aDefaultOrPinOnAFieldTypedByARecordInPlaceIsRefused() {
        refused("  r => { at?: { x: int32 } ~ a }", "a type written in place that is no reference");
        refused("  r => { at: { x: int32 } = a }", "a type written in place that is no reference");
    }

    /** The checks a declared record's fields answer to reach one written in place. */
    @Test
    void aRecordInPlaceAnswersADeclaredRecordsChecks() {
        refused("  r => { at: { x: int32 ~ 3 } }", "gives a default to a key that is always written");
        refused("  r => { at: { x: = 3 } }", "states only a modifier and no type");
        refused("  r => { at: { x: nothing_here } }", "nothing_here");
        refused("  r => { at: { @nope x: int32 } }", "'@nope' does not name a type in this schema's namespace");
        unsatisfiable("  r => { at: { a: void } }", "r");
        assertEquals(List.of(), problems("  r => { at?: { a: void } }"));
    }

    /** {@code {}} at a slot is the empty record, which reads an empty value and refuses any field. */
    @Test
    void anEmptyBraceAtASlotIsTheEmptyRecord() {
        assertEquals(List.of(), read("  r => { at: {} }", "!r { at: {} }"));
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, read("  r => { at: {} }", "!r { at: { a: 1 } }").getFirst()
                .code());
    }

    /**
     * A selector in a record in place makes it abstract, as {@code =?} makes a declared record (§5.2), and nothing
     * can be its member, so it is empty: required it empties its record, optional it can only be missing.
     */
    @Test
    void aSelectorInARecordInPlaceMakesItAnEmptyAbstractRecord() {
        unsatisfiable("  r => { at: { kind: text =?  x: int32 } }", "r");
        String optional = "  r => { at?: { kind: text =?  x: int32 } }";
        assertEquals(List.of(), problems(optional));
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read(optional, "!r { at: { kind: a  x: 1 } }").getFirst().code());
    }

    /** A record in place over a parameter of its open record is substituted where the record is applied. */
    @Test
    void aRecordInPlaceOverAParameterReadsWhereItsRecordIsApplied() {
        String declarations = "  page => <T> { meta: { cursor: T  total: int32 } }\n  r => { p: page<int32> }";
        assertEquals(List.of(), problems(declarations));
        assertEquals(Optional.of("/p/meta/cursor"),
                read(declarations, "!r { p: { meta: { cursor: x  total: 1 } } }").getFirst().path());
    }

    /** One naming no parameter is closed, and an application of its open record reads it. */
    @Test
    void aRecordInPlaceNamingNoParameterInsideAnOpenRecordReads() {
        String declarations = "  page => <T> { items: [T]  meta: { total: int32 } }\n  r => { p: page<text> }";
        assertEquals(List.of(), problems(declarations));
        assertEquals(List.of(), read(declarations, "!r { p: { items: [a]  meta: { total: 1 } } }"));
        assertEquals(Optional.of("/p/meta/total"),
                read(declarations, "!r { p: { items: []  meta: { total: x } } }").getFirst().path());
    }

    // ── A record in place's field names (§5.2, §11.4) ───────────────────────────────────────────

    /**
     * A record written in place judges its field names as a declared record does, under its own name type, at any
     * depth and inside containers and choices, written as sugar or in canonical form, refused at its declaration.
     */
    @Test
    void aFieldNameOutsideTheNameTypeOfARecordInPlaceIsRefused() {
        refused("  r => { at: { \"a b\": int32 } }",
                "the record written in place '{ a b: int32 }' in 'r': field name 'a b' is not an identifier");
        refused("  r => { at: [{ x: ( text | { \"a b\": int32 } ) }] }", "field name 'a b' is not an identifier");
        refused("  r => [{ \"a b\": int32 }]", "field name 'a b' is not an identifier");
        refused("  r => !meta.record { fields: { at => { type: { of: !meta.record { fields: { \"a b\" => { type: int32 "
                + "} } } } } } }", "field name 'a b' is not an identifier");
        assertEquals(List.of(), problems("  jn => !unicode.identifier_type { start_add: \"@$\" }\n  r => !meta.record { "
                + "fields: { at => { type: { of: !meta.record { name_type: jn  fields: { \"@id\" => { type: int32 } } } "
                + "} } } }"));
    }

    /** The names a record an application closes to holds are its open record's, refused there and only there. */
    @Test
    void aRecordAnApplicationClosesToIsJudgedAtItsOpenRecord() {
        List<Diagnostic> found = problems("  bad => <T> !meta.record { fields: { \"a b\" => { type: T } } }\n"
                + "  r => { at: bad<int32> }");
        assertEquals(1, found.size(), found::toString);
        assertEquals(Optional.of("/bad"), found.getFirst().schemaPointer(), found::toString);
    }

    /** Two look-alike field names in one record in place are refused; its fields are a scope of their own. */
    @Test
    void aRecordInPlaceIsANameScopeOfItsOwn() {
        for (String declarations : List.of("  r => { at: { comer: int32  corner: int32 } }",
                "  r => { at: { ( comer: int32 | corner: text ) } }", "  r => { at: [{ comer: int32  corner: int32 }] }")) {
            List<Diagnostic> found = problems(declarations);
            assertEquals(List.of(Diagnostic.Code.CONFUSABLE_NAMES), found.stream().map(Diagnostic::code).toList(),
                    declarations + " " + found);
            assertTrue(found.getFirst().message().contains("the record written in place"), found::toString);
        }
        assertEquals(List.of(), problems("  r => { comer: int32  at: { corner: int32 } }"));
    }

    @Test
    void aMixedScriptFieldNameInARecordInPlaceIsRefused() {
        List<Diagnostic> found = problems("  r => { at: { p\u0430y: text } }");
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT), found.stream().map(Diagnostic::code).toList(),
                found::toString);
    }

    private static List<Diagnostic> read(String declarations, String document) {
        List<Diagnostic> found = new ArrayList<>();
        tson(declarations).treeReader().withDiagnostics(found::add).read("!!import:\"" + ID + "\"\n" + document);
        return found;
    }
}
