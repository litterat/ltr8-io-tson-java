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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules a type written in place is held to beside a name ([TSON-SCHEMA] §5.2, §5.4, §5.7, §8.2): a value modifier
 * on an inline reference, a name never one type with a construction, a choice that is not disjoint naming every
 * variant, and an application of an open record absorbed at a refinement source.
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
}
