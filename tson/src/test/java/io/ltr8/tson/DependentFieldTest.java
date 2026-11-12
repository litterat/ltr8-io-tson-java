package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dependent field, {@code v?: kind!} ([TSON-SCHEMA] §5.2): the sibling's rules, checked at the declaration against
 * the flattened record, and the reading, a value read per instance against the type its sibling's value names -- a
 * pinned sibling's, a discriminator member's, or a free sibling's, which is then written first.
 */
class DependentFieldTest {

    private static final String ID = "https://example.test/dependent.tn";

    private static String schema(String declarations) {
        return """
                !!id:"%s"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                %s
                }""".formatted(ID, declarations);
    }

    private static final String CELLS = schema("""
              point     => { x: int32  y: int32 }
              cell      => { kind: meta.type_ref  v?: kind! }
              int_cell  => cell ^ { kind?: = int32 }
              defaulted => { kind?: meta.type_ref ~ int32  v?: kind! }
              box       => <T> { item: T }
              slot      => abstract { kind: meta.type_ref =?  v?: kind! }
              int_slot  => slot & { kind?: = int32 }
              text_slot => slot & { kind?: = text }
              rack      => { s: slot }
              grouped   => { ( kind: meta.type_ref | name: text )  v?: kind! }
              labelled  => { ( kind: meta.type_ref | name: text )  ( v: kind! | w: text )? }""");

    private static Tson tson(String schema) {
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(
                Map.of(ID, schema)))));
    }

    /** The one problem {@code declarations} reports, as its message. */
    private static String refusal(String declarations) {
        List<Diagnostic> problems = Tson.standard().validateSchema(schema(declarations));
        assertEquals(1, problems.size(), () -> declarations + " -> " + problems);
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code(), problems::toString);
        return problems.getFirst().message();
    }

    private static List<Diagnostic> validate(String document) {
        return tson(CELLS).validate("!!import:\"" + ID + "\"\n" + document);
    }

    // ── The sibling's rules ───────────────────────────────────────────────

    @Test
    void aDependentFieldOverEachReadableSiblingResolves() {
        for (String declarations : List.of(
                "cell => { kind: meta.type_ref  v?: kind!  w: kind! }",
                "cell => { kind?: meta.type_ref ~ int32  v?: kind! }",
                "cell => { kind: meta.type_ref  v?: kind! }\n  pinned => cell ^ { kind?: = int32 }",
                "cell => { kind: meta.type_ref  v?: kind! }\n  strict => cell ^ { v: kind! }",
                "records => meta.ref<meta.record>\n  shape => { kind: records  v?: kind! }",
                "base => { kind: meta.type_ref }\n  cell => base & { v?: kind! }",
                "pair => <T> { kind: meta.type_ref  v?: kind!  w: T }\n  p => pair<text>",
                "cell => !meta.record { fields: { kind => meta.type_ref  v => !meta.record_field { "
                        + "type: { of: !meta.dependent { on: kind } } } } }",
                "cell => abstract { kind: meta.type_ref =?  v?: kind! }\n  int_cell => cell & { kind?: = int32 }")) {
            assertEquals(List.of(), Tson.standard().validateSchema(schema(declarations)), declarations);
        }
    }

    /** In sugar and in canonical form alike, the field {@code on} names precedes the dependent field. */
    @Test
    void theSiblingIsDeclaredBeforeItsDependentField() {
        assertTrue(refusal("cell => { v?: kind!  kind: meta.type_ref }").contains("is declared after it"));
        assertTrue(refusal("kind => { a: text }\n  cell => { v?: kind! }").contains("has no field 'kind'"));
        String dependent = "v => !meta.record_field { type: { of: !meta.dependent { on: kind } }  optional: true }";
        assertTrue(refusal("cell => !meta.record { fields: { " + dependent + "  kind => meta.type_ref } }")
                .contains("is declared after it"));
        assertTrue(refusal("cell => !meta.record { fields: { name => text  " + dependent + " } }")
                .contains("has no field 'kind'"));
    }

    @Test
    void theSiblingIsTypedByAReferenceToTypes() {
        assertTrue(refusal("cell => { n: int32  v?: n! }").contains("which is typed 'int32'"));
        assertTrue(refusal("method => { verb: text }\n  calls => meta.ref<method>\n  cell => { m: calls  v?: m! }")
                .contains("which is typed 'calls'"));
    }

    /** A dependent field names no types, so it is no sibling, in sugar and in canonical form alike. */
    @Test
    void theSiblingIsNoDependentField() {
        assertTrue(refusal("cell => { kind: meta.type_ref  a?: kind!  b?: a! }").contains("itself a dependent field"));
        assertTrue(refusal("cell => !meta.record { fields: { kind => meta.type_ref  a => !meta.record_field { type: "
                + "{ of: !meta.dependent { on: kind } }  optional: true }  b => !meta.record_field { type: "
                + "{ of: !meta.dependent { on: a } }  optional: true } } }").contains("itself a dependent field"));
    }

    @Test
    void theSiblingIsThereToReadWheneverItsDependentFieldIs() {
        assertTrue(refusal("cell => { kind?: meta.type_ref  v?: kind! }").contains("optional with no default"));
        assertTrue(refusal("cell => { kind: meta.type_ref?  v?: kind! }").contains("which is voidable"));
    }

    /** A group member beside an optional dependent field, a dependent group member being optional as declared. */
    @Test
    void aSiblingMayBeAFieldGroupMemberBesideAnOptionalDependentField() {
        for (String declarations : List.of(
                "cell => { ( kind: meta.type_ref | name: text )  v?: kind! }",
                "cell => { ( kind: meta.type_ref | name: text )?  v?: kind! }",
                "cell => { ( kind: meta.type_ref | name: text )  ( v: kind! | w: text )? }",
                "cell => { ( kind: meta.type_ref | name: text )  ( v: kind! | w: text ) }")) {
            assertEquals(List.of(), Tson.standard().validateSchema(schema(declarations)), declarations);
        }
    }

    @Test
    void aFieldGroupMemberIsNoSiblingOfARequiredDependentField() {
        assertTrue(refusal("cell => { ( kind: meta.type_ref | name: text )  v: kind! }")
                .contains("a member of a field group, and 'v' is required"));
        assertTrue(refusal("cell => { ( kind: meta.type_ref | name: text )  v?: kind! }\n  must => cell ^ { v: kind! }")
                .contains("a member of a field group, and 'v' is required"));
    }

    @Test
    void aSiblingsPinOrDefaultNamesNoTemplate() {
        assertTrue(refusal("box => <T> { item: T }\n  cell => { kind?: meta.type_ref = box  v?: kind! }")
                .contains("'box' is a template"));
        assertTrue(refusal("box => <T> { item: T }\n  cell => { kind?: meta.type_ref ~ box  v?: kind! }")
                .contains("'box' is a template"));
    }

    @Test
    void aDependentFieldIsNeverVoidableAndCarriesNoValue() {
        String defaulted = refusal("cell => { kind: meta.type_ref  v?: kind! ~ 5 }");
        assertTrue(defaulted.contains("cannot have a default"), defaulted);
        String pin = refusal("cell => { kind: meta.type_ref  v?: kind! = 5 }");
        assertTrue(pin.contains("cannot have a fixed value") || pin.contains("cannot have a"), pin);
        assertTrue(refusal("cell => !meta.record { fields: { kind => meta.type_ref  v => !meta.record_field { "
                + "type: { of: !meta.dependent { on: kind } }  optional: true  voidable: true } } }")
                .contains("is voidable"));
        // In canonical form the grammar and the resolver's refusal of a value on a type in place are not reached, so
        // the dependent field's own rule is.
        for (String role : List.of("DEFAULT", "FIXED")) {
            String canonical = refusal("cell => !meta.record { fields: { kind => meta.type_ref  v => !meta.record_field "
                    + "{ type: { of: !meta.dependent { on: kind } }  optional: true  role: " + role + "  value: 5 } } }");
            assertTrue(canonical.contains("a dependent field, and carries"), canonical);
        }
    }

    @Test
    void aDependentFieldIsRestatedOnlyAsItself() {
        assertTrue(refusal("cell => { kind: meta.type_ref  v?: kind! }\n  int_cell => cell ^ { kind?: = int32  "
                + "v?: int32 }").contains("a dependent field is restated only as itself"));
    }

    // ── Reading, tree mode ────────────────────────────────────────────────

    @Test
    void eachInstanceIsReadAgainstTheTypeItsSiblingNames() {
        assertEquals(List.of(), validate("!cell { kind: int32  v: 5 }"));
        assertEquals(List.of(), validate("!cell { kind: point  v: { x: 1  y: 2 } }"));
        assertEquals(Optional.of("/v"), only(validate("!cell { kind: point  v: 5 }")).path());
        TsonValue read = tson(CELLS).treeReader().read("!!import:\"" + ID + "\"\n!cell { kind: text  v: hello }");
        assertEquals(Optional.of("hello"), read.get("v").asString());
    }

    @Test
    void aDefaultedOrPinnedSiblingTypesAnOmittedOne() {
        assertEquals(List.of(), validate("!defaulted { v: 7 }"));
        assertEquals(Optional.of("/v"), only(validate("!defaulted { v: hello }")).path());
        assertEquals(List.of(), validate("!int_cell { v: 7 }"));
        assertEquals(Optional.of("/v"), only(validate("!int_cell { v: hello }")).path());
    }

    /** Only a free sibling orders the fields: a pinned one or a discriminator may follow its dependent field. */
    @Test
    void aFreeSiblingIsWrittenBeforeItsDependentField() {
        Diagnostic early = only(validate("!cell { v: 5  kind: int32 }"));
        assertEquals(Optional.of("/v"), early.path());
        assertTrue(early.message().contains("written before 'kind'"), early.message());
        Diagnostic defaulted = only(validate("!defaulted { v: 5  kind: int32 }"));
        assertEquals(Optional.of("/v"), defaulted.path());
        assertEquals(List.of(), validate("!int_cell { v: 7  kind: int32 }"));
        assertEquals(List.of(), validate("!rack { s: { v: 5  kind: int32 } }"));
        assertEquals(Optional.of("/s/v"), only(validate("!rack { s: { v: hello  kind: int32 } }")).path());
    }

    /** A sibling that never arrives is no order broken: the refusal says it is absent, or is the missing field's. */
    @Test
    void aDependentFieldWrittenWithoutItsGroupMemberSiblingIsRefused() {
        Diagnostic absent = only(validate("!grouped { name: x  v: 5 }"));
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, absent.code());
        assertEquals(Optional.of("/v"), absent.path());
        assertTrue(absent.message().contains("is written without 'kind'"), absent.message());
        assertFalse(absent.message().contains("before"), absent.message());
        assertEquals("!grouped { name: x  ".length() + 1, absent.dataPosition().orElseThrow().column(), "at the field");
        Diagnostic member = only(validate("!labelled { name: x  v: 5 }"));
        assertEquals(Optional.of("/v"), member.path());
        assertTrue(member.message().contains("is written without 'kind'"), member.message());
        Diagnostic required = only(validate("!cell { v: 5 }"));
        assertEquals(Diagnostic.Code.FIELD_REQUIRED, required.code());
        assertEquals(Optional.of("/kind"), required.path());
    }

    @Test
    void aGroupMemberSiblingReadsAsAnyFreeSiblingDoes() {
        assertEquals(List.of(), validate("!grouped { kind: int32  v: 5 }"));
        assertEquals(List.of(), validate("!grouped { name: x }"));
        assertEquals(List.of(), validate("!labelled { kind: text  v: hello }"));
        assertEquals(List.of(), validate("!labelled { name: x  w: y }"));
        assertEquals(Optional.of("/v"), only(validate("!grouped { kind: int32  v: hello }")).path());
        Diagnostic early = only(validate("!grouped { v: 5  kind: int32 }"));
        assertEquals(Optional.of("/v"), early.path());
        assertTrue(early.message().contains("written before 'kind'"), early.message());
        assertTrue(early.dataPosition().orElseThrow().column() < "!grouped { v: 5  kind".length(), "at the field");
    }

    @Test
    void aValueEntryWritingADependentFieldWithoutItsSiblingDoesNotLoad() {
        assertTrue(refusal("cell => { ( kind: meta.type_ref | name: text )  v?: kind! }\n  a => !cell { name: x  v: 5 }")
                .contains("is written without 'kind'"));
    }

    /**
     * A sibling naming no readable type is refused at the sibling. A template is no type (§5.10), so a reference
     * over types holds none, and naming one is refused whether or not the dependent field is written.
     */
    @Test
    void aSiblingNamingNoReadableTypeIsRefusedAtTheSibling() {
        assertEquals(Optional.of("/kind"), only(validate("!cell { kind: box  v: { item: 5 } }")).path());
        assertEquals(Optional.of("/kind"), only(validate("!cell { kind: { of: !meta.dependent { on: kind } }  "
                + "v: 5 }")).path());
        assertEquals(Optional.of("/kind"), only(validate("!cell { kind: box }")).path());
    }

    // ── The kernel's own dependent field, and the facet ───────────────────

    @Test
    void theKernelsRecordFieldValueIsReadAgainstItsType() {
        Tson tson = Tson.standard();
        String meta = "!!import:\"https://tson.io/2026/40/m/meta.tn\"\n";
        assertEquals(List.of(), tson.validate(meta + "!meta.record_field { type: integer  optional: true  "
                + "role: DEFAULT  value: 5 }"));
        assertEquals(Optional.of("/value"), only(tson.validate(meta + "!meta.record_field { type: boolean  "
                + "optional: true  role: DEFAULT  value: 5 }")).path());
        assertTrue(refusal("config => !meta.record { fields: { port => !meta.record_field { type: int32  "
                + "optional: true  role: DEFAULT  value: \"nope\" } } }").contains("is not a value of that type"));
    }

    /** What a template's literal is judged by, and its refusals on an open type, are §5.10's as written. */
    @Test
    void aTemplateLiteralNeedsAClosedType() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema(
                "box => <T> { n?: int32 ~ 5  w: T }\n  b => box<text>")));
        assertTrue(refusal("box => <T> { n?: int32 ~ nope  w: T }\n  b => box<text>")
                .contains("is not a value of that type"));
        assertTrue(refusal("box => <T> { v?: T ~ 5 }")
                .contains("typed by the parameter 'T' and carries the literal value '5'"));
        assertTrue(refusal("box => <T> { v: T }\n  boxy => <T> { w?: box<T> = x }")
                .contains("typed by the parameter 'T' and carries the literal value 'x'"));
    }

    /**
     * A dependent type stands directly at a record field's type, written in place: declared, or at any other slot, it
     * is refused at link, since nothing else binds its field.
     */
    @Test
    void aDependentTypeStandsDirectlyAtAFieldsTypeAlone() {
        Tson tson = Tson.standard();
        String meta = "!!import:\"https://tson.io/2026/40/m/meta.tn\"\n";
        assertEquals(List.of(), tson.validate(meta + "!meta.record { fields: { kind => { type: meta.type_ref }  "
                + "value => { type: { of: !meta.dependent { on: kind } }  optional: true } } }"));
        String dependent = "{ of: !meta.dependent { on: kind } }";
        for (String declaration : List.of("x => !meta.dependent { on: kind }",
                "x => !meta.array { element_type: " + dependent + " }")) {
            assertTrue(refusal(declaration).contains("stands only directly at a record field's"), declaration);
        }
        for (String type : List.of("!meta.array { element_type: " + dependent + " }",
                "!meta.map { key_type: text  value_type: " + dependent + " }",
                "!meta.tuple { elements: [{ element_type: " + dependent + " } { element_type: text }] }",
                "!meta.choice { variants: [" + dependent + " text] }")) {
            String declaration = "cell => !meta.record { fields: { kind => !meta.record_field { type: meta.type_ref }  "
                    + "v => !meta.record_field { type: { of: " + type + " } } } }";
            // A choice holding one is also refused as no disjoint choice, a dependent type having no form class.
            List<Diagnostic> problems = Tson.standard().validateSchema(schema(declaration));
            assertTrue(problems.stream().anyMatch(problem -> problem.message().contains(
                    "stands only directly at a record field's")), () -> declaration + " -> " + problems);
        }
        assertTrue(refusal("wide => !meta.type_ref ^ { allow_sibling: true }").contains("allow_sibling"));
    }

    // ── Where kind! is written ────────────────────────────────────────────

    /**
     * {@code kind!} is schema-grammar sugar at a record field's type alone (a group member's included), so anywhere
     * else in a schema it is a syntax error naming where it may stand.
     */
    @Test
    void theMarkIsASyntaxErrorOffAFieldsType() {
        for (String declaration : List.of("cell => { kind: meta.type_ref  v: [ kind! ] }", "x => kind!",
                "cell => { kind: meta.type_ref  v: ( kind! | text ) }")) {
            Diagnostic problem = only(Tson.standard().validateSchema(schema(declaration)));
            assertTrue(problem.message().contains("'kind!' is a dependent field's type, and stands only as a record "
                    + "field's type"), problem.message());
        }
    }

    /**
     * Data has no {@code kind!}: a construction's payload and a data document are read by the data grammar, which
     * gives the mark its own syntax error. Its canonical form, {@code { of: !meta.dependent { on: kind } }}, is what a
     * payload writes; quoted, {@code "kind!"} is text, which names no type.
     */
    @Test
    void theMarkIsNoDataForm() {
        String separator = "adjacent values must be separated by whitespace, a comma, or both";
        for (String declaration : List.of(
                "cell => !meta.record { fields: { kind => meta.type_ref  v => !meta.record_field { type: kind! } } }",
                "x => !meta.array { element_type: kind! }")) {
            List<Diagnostic> problems = Tson.standard().validateSchema(schema(declaration));
            assertTrue(problems.stream().anyMatch(problem -> problem.message().equals(separator)),
                    () -> declaration + " -> " + problems);
        }
        Tson typed = tson(schema("typed => { t: meta.type_ref }"));
        List<Diagnostic> problems = typed.validate("!!import:\"" + ID + "\"\n!typed { t: kind! }");
        assertTrue(problems.stream().anyMatch(problem -> problem.message().equals(separator)), problems::toString);
        assertEquals(Diagnostic.Code.TYPE_MISMATCH,
                only(typed.validate("!!import:\"" + ID + "\"\n!typed { t: \"kind!\" }")).code());
    }

    // ── Reading, bind mode ────────────────────────────────────────────────

    /**
     * A bind target for {@code cell}: the dependent field binds to {@code Object}, what the named type reads to, which
     * the context registers as an atom so the binder takes it whole.
     */
    public record Cell(TypeRef kind, Object v) {
    }

    /** A bind target that narrows the dependent field's component, which no named type can promise to fill. */
    public record NarrowCell(TypeRef kind, Integer v) {
    }

    private static Tson bound(Class<?> cell) {
        DataNameBinder binder = name -> "cell".equals(name) ? cell : SchemaModelNameBinder.INSTANCE.resolve(name);
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(ID, CELLS))))
                .withDataBindContext(DataBindContext.builder().nameBinder(binder)
                        .registerAtoms(AtomContext.hostTypes()).registerAtom(Object.class).build()));
    }

    @Test
    void aBoundDependentFieldReceivesWhatTheNamedTypeReadsTo() {
        Tson tson = bound(Cell.class);
        Cell cell = tson.objectReader().read("!!import:\"" + ID + "\"\n!cell { kind: int32  v: 5 }", Cell.class);
        assertEquals("int32", cell.kind().name());
        assertEquals(5, ((Number) cell.v()).intValue());
        io.ltr8.tson.base.DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
        tson.objectReader().withDiagnostics(problems)
                .read("!!import:\"" + ID + "\"\n!cell { v: 5  kind: int32 }", Cell.class);
        assertEquals(Optional.of("/v"), only(problems.diagnostics()).path());
    }

    /** A bind target for {@code grouped}: each member of the group a component of its own. */
    public record Grouped(Optional<TypeRef> kind, Optional<String> name, Object v) {
    }

    @Test
    void aBoundDependentFieldWrittenWithoutItsGroupMemberSiblingIsRefused() {
        DataNameBinder binder = name -> "grouped".equals(name) ? Grouped.class
                : SchemaModelNameBinder.INSTANCE.resolve(name);
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(ID, CELLS))))
                .withDataBindContext(DataBindContext.builder().nameBinder(binder)
                        .registerAtoms(AtomContext.hostTypes()).registerAtom(Object.class).build()));
        String header = "!!import:\"" + ID + "\"\n";
        Grouped grouped = tson.objectReader().read(header + "!grouped { kind: int32  v: 5 }", Grouped.class);
        assertEquals(5, ((Number) grouped.v()).intValue());
        io.ltr8.tson.base.DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
        assertNull(tson.objectReader().withDiagnostics(problems)
                .read(header + "!grouped { name: x  v: 5 }", Grouped.class));
        Diagnostic absent = only(problems.diagnostics());
        assertEquals(Optional.of("/v"), absent.path());
        assertTrue(absent.message().contains("is written without 'kind'"), absent.message());
    }

    @Test
    void aBoundDependentFieldNarrowerThanObjectIsAMismatch() {
        Tson tson = bound(NarrowCell.class);
        Exception thrown = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> tson.bindRegistry().get(ID));
        assertTrue(thrown.getMessage().contains("is a dependent field"), thrown.getMessage());
    }

    private static Diagnostic only(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), problems::toString);
        return problems.getFirst();
    }
}
