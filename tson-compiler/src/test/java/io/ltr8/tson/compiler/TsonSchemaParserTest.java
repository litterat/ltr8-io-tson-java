package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.schema.ArrayRef;
import io.ltr8.tson.compiler.ast.schema.AtomRefinement;
import io.ltr8.tson.compiler.ast.schema.ChoiceRef;
import io.ltr8.tson.compiler.ast.schema.ConstructionDef;
import io.ltr8.tson.compiler.ast.schema.FieldDef;
import io.ltr8.tson.compiler.ast.schema.GenericRef;
import io.ltr8.tson.compiler.ast.schema.GroupDef;
import io.ltr8.tson.compiler.ast.schema.ElementType;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.compiler.ast.schema.MapRef;
import io.ltr8.tson.compiler.ast.schema.RecordDef;
import io.ltr8.tson.compiler.ast.schema.RefinedDef;
import io.ltr8.tson.compiler.ast.schema.ReferenceTypeDef;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.ast.schema.SimpleRef;
import io.ltr8.tson.compiler.ast.schema.SizeSpec;
import io.ltr8.tson.compiler.ast.schema.StructuralTypeDef;
import io.ltr8.tson.compiler.ast.schema.TupleRef;
import io.ltr8.tson.compiler.ast.schema.TypeArg;
import io.ltr8.tson.compiler.ast.schema.TypeDef;
import io.ltr8.tson.compiler.ast.schema.TypeRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonSchemaParserTest {

    private static SchemaDocument parse(String source) {
        return new TsonSchemaParser(source).parseSchemaDocument();
    }

    /** One field of a declaration, for the fixtures that assert what a field's type parsed to. */
    private static FieldDef fieldOf(String declaration, String field) {
        RecordDef record = (RecordDef) ((StructuralTypeDef) declOf(declaration).typeDef()).body();
        return record.entries().stream().map(FieldDef.class::cast)
                .filter(f -> f.name().equals(field)).findFirst().orElseThrow();
    }

    private static TypeRef fieldTypeOf(String declaration, String field) {
        return fieldOf(declaration, field).type().orElseThrow().typeRef();
    }

    /** The {@link MapRef} one declaration's body is, for the map-grammar fixtures. */
    private static MapRef mapOf(String declaration) {
        ReferenceTypeDef ref = assertInstanceOf(ReferenceTypeDef.class, declOf(declaration).typeDef());
        return assertInstanceOf(MapRef.class, ref.ref());
    }

    // ── Header (§2.1, §2.2) ──────────────────────────────────────────────

    @Test
    void parsesIdMetaAndImports() {
        SchemaDocument doc = parse("""
                !!id:"https://example.com/x.tn1"
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                !!import:"https://tson.io/2026/33/m/core.tn1"
                { a => text }""");
        assertEquals("https://example.com/x.tn1", doc.id().orElseThrow());
        assertEquals("https://tson.io/2026/33/m/meta.tn1", doc.meta());
        assertEquals(List.of("https://tson.io/2026/33/m/core.tn1"), doc.imports());
    }

    @Test
    void idIsOptional() {
        SchemaDocument doc = parse("""
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                { a => text }""");
        assertTrue(doc.id().isEmpty());
    }

    @Test
    void multipleImportsPreserveOrder() {
        SchemaDocument doc = parse("""
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                !!import:"https://example.com/one.tn1"
                !!import:"https://example.com/two.tn1"
                { a => text }""");
        assertEquals(List.of("https://example.com/one.tn1", "https://example.com/two.tn1"), doc.imports());
    }

    @Test
    void missingMetaIsAParseError() {
        assertThrows(TsonParseException.class, () -> parse("{ a => text }"));
    }

    @Test
    void schemaDirectiveInHeaderIsAParseError() {
        // !!schema belongs to data documents, not schema documents (§2.2).
        assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                !!schema:"https://example.com/x.tn1"
                { a => text }"""));
    }

    // ── Schema map (§2.1) ─────────────────────────────────────────────────

    @Test
    void emptySchemaMapIsAParseError() {
        assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                {}"""));
    }

    @Test
    void schemaLevelAnnotationBindsToTheMap() {
        SchemaDocument doc = parse("""
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                @doc:"a schema"
                { a => text }""");
        assertEquals(1, doc.body().annotations().size());
        assertEquals("doc", doc.body().annotations().get(0).name());
    }

    @Test
    void declarationNameAndTypeDefAnnotationsBindSeparately() {
        SchemaDocument doc = parse("""
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                { @since:2025 a => @doc:"a field" text }""");
        SchemaMap.Declaration decl = doc.body().declarations().get("a");
        assertEquals("since", decl.nameAnnotations().get(0).name());
        assertEquals("doc", decl.typeDefAnnotations().get(0).name());
    }

    @Test
    void declarationsIsKeyedByNameInSourceOrderWithLastDuplicateWinning() {
        // Genuine duplicate-name detection is deferred to schema resolution's Pass 1 (§3.4.1),
        // the same "grammar layer doesn't dedupe" treatment as ordinary data maps/records.
        SchemaDocument doc = parse("""
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                { a => text  b => integer  a => uuid }""");
        assertEquals(List.of("a", "b"), List.copyOf(doc.body().declarations().keySet()));
        assertEquals(new SimpleRef("uuid"),
                ((ReferenceTypeDef) doc.body().declarations().get("a").typeDef()).ref());
    }

    // ── §5.1's own worked examples ────────────────────────────────────────

    @Test
    void recordConstruction() {
        TypeDef def = declOf("person => { name: text  age: integer }").typeDef();
        StructuralTypeDef structural = assertInstanceOf(StructuralTypeDef.class, def);
        assertFalse(structural.constructor());
        RecordDef record = assertInstanceOf(RecordDef.class, structural.body());
        assertEquals(2, record.entries().size());
        FieldDef name = assertInstanceOf(FieldDef.class, record.entries().get(0));
        assertEquals("name", name.name());
        assertEquals(new SimpleRef("text"), name.type().orElseThrow().typeRef());
    }

    @Test
    void supertypeComposition() {
        TypeDef def = declOf("employee => person & contact & { department: text }").typeDef();
        StructuralTypeDef structural = assertInstanceOf(StructuralTypeDef.class, def);
        ConstructionDef construction = assertInstanceOf(ConstructionDef.class, structural.body());
        assertEquals(List.of(new SimpleRef("person"), new SimpleRef("contact")), construction.supertypes());
        assertTrue(construction.body().isPresent());
        assertEquals(1, construction.body().get().entries().size());
        assertTrue(construction.removal().isEmpty());
    }

    @Test
    void subtraction() {
        TypeDef def = declOf("account_public => account - { password }").typeDef();
        StructuralTypeDef structural = assertInstanceOf(StructuralTypeDef.class, def);
        ConstructionDef construction = assertInstanceOf(ConstructionDef.class, structural.body());
        assertEquals(List.of(new SimpleRef("account")), construction.supertypes());
        assertTrue(construction.body().isEmpty());
        assertEquals(List.of("password"), construction.removal().orElseThrow().fieldNames());
    }

    @Test
    void compositionWithTrailingBodyAndRemoval() {
        // staff_public => account & user & { badge: text } - { password  ssn }
        TypeDef def = declOf("staff_public => account & user & { badge: text } - { password  ssn }").typeDef();
        StructuralTypeDef structural = assertInstanceOf(StructuralTypeDef.class, def);
        ConstructionDef construction = assertInstanceOf(ConstructionDef.class, structural.body());
        assertEquals(List.of(new SimpleRef("account"), new SimpleRef("user")), construction.supertypes());
        assertEquals(1, construction.body().orElseThrow().entries().size());
        assertEquals(List.of("password", "ssn"), construction.removal().orElseThrow().fieldNames());
    }

    @Test
    void recordRefinement() {
        TypeDef def = declOf("production => config ^ { host: = \"prod.example.com\" }").typeDef();
        StructuralTypeDef structural = assertInstanceOf(StructuralTypeDef.class, def);
        RefinedDef refined = assertInstanceOf(RefinedDef.class, structural.body());
        assertEquals(new SimpleRef("config"), refined.target());
        assertEquals(1, refined.body().entries().size());
    }

    @Test
    void constructorApplication() {
        TypeDef def = declOf("status => !enum [ACTIVE INACTIVE SUSPENDED]").typeDef();
        Instance instance = assertInstanceOf(Instance.class, def);
        assertEquals("enum", instance.target());
    }

    @Test
    void atomRefinement() {
        TypeDef def = declOf("age => !integer ^ { min: 0  max: 150 }").typeDef();
        AtomRefinement refinement = assertInstanceOf(AtomRefinement.class, def);
        assertEquals("integer", refinement.target());
    }

    @Test
    void constructorDefinitionWithTypeParamAndRefinementHead() {
        TypeDef def = declOf("set => <T> ~array<T> ^ { unordered: = true }").typeDef();
        StructuralTypeDef structural = assertInstanceOf(StructuralTypeDef.class, def);
        assertEquals(List.of("T"), structural.typeParams());
        assertTrue(structural.constructor());
        RefinedDef refined = assertInstanceOf(RefinedDef.class, structural.body());
        assertEquals(new GenericRef("array", List.of(new TypeArg.Ref(new SimpleRef("T")))), refined.target());
    }

    @Test
    void plainTypeReference() {
        TypeDef def = declOf("id => uuid").typeDef();
        ReferenceTypeDef ref = assertInstanceOf(ReferenceTypeDef.class, def);
        assertEquals(new SimpleRef("uuid"), ref.ref());
    }

    @Test
    void declarationLevelArrayWithSize() {
        TypeDef def = declOf("scores => [integer; 1..]").typeDef();
        ReferenceTypeDef ref = assertInstanceOf(ReferenceTypeDef.class, def);
        ArrayRef array = assertInstanceOf(ArrayRef.class, ref.ref());
        assertEquals(new SimpleRef("integer"),
                array.elementType().typeRef());
        assertEquals(new SizeSpec.Min("1"), array.size().orElseThrow());
    }

    @Test
    void declarationLevelTuple() {
        TypeDef def = declOf("point => [number, number]").typeDef();
        ReferenceTypeDef ref = assertInstanceOf(ReferenceTypeDef.class, def);
        TupleRef tuple = assertInstanceOf(TupleRef.class, ref.ref());
        assertEquals(2, tuple.elementTypes().size());
    }

    @Test
    void choiceType() {
        TypeDef def = declOf("contact_method => (email | phone | address)").typeDef();
        ReferenceTypeDef ref = assertInstanceOf(ReferenceTypeDef.class, def);
        ChoiceRef choice = assertInstanceOf(ChoiceRef.class, ref.ref());
        assertEquals(List.of(new SimpleRef("email"), new SimpleRef("phone"), new SimpleRef("address")), choice.variants());
    }

    // ── The map sugar and §12.2's brace dispatch (§5.3, D1/D2) ───────────
    //    A '{' at a type position opens either a record body or a map type, decided by consuming it and
    //    inspecting one more token. Every row of §12.2's dispatch matrix is here.

    @Test
    void declarationLevelMap() {
        MapRef map = mapOf("translations => {text => text}");
        assertEquals(new SimpleRef("text"), map.keyType());
        assertEquals(new SimpleRef("text"), map.valueType().typeRef());
        assertTrue(map.size().isEmpty());
    }

    @Test
    void declarationLevelMapWithSize() {
        MapRef map = mapOf("index => {text => order; 1..5}");
        assertEquals(new SizeSpec.Ranged("1", "5"), map.size().orElseThrow());
    }

    /** The open-ended {@code N..} form runs up against the map's own closing brace, not a bracket. */
    @Test
    void anOpenEndedMapSizeBoundClosesOnTheBrace() {
        assertEquals(new SizeSpec.Min("1"), mapOf("index => {text => order; 1..}").size().orElseThrow());
    }

    /** A generic key consumes its own argument list before the {@code =>} the dispatch committed on. */
    @Test
    void aGenericKeyIsStillAMap() {
        MapRef map = mapOf("index => { pair<text> => integer }");
        assertEquals(new GenericRef("pair", List.of(new TypeArg.Ref(new SimpleRef("text")))), map.keyType());
    }

    /** {@code map-value = container-def / type-ref}, so the declaration-level tier nests inside a map value. */
    @Test
    void aMapValueMayNestADeclarationLevelForm() {
        MapRef map = mapOf("index => {text => [order; 1..]}");
        ArrayRef nested = assertInstanceOf(ArrayRef.class,
                map.valueType().typeRef());
        assertEquals(new SizeSpec.Min("1"), nested.size().orElseThrow());
    }

    @Test
    void aMapValueMayNestAnotherMap() {
        MapRef map = mapOf("index => {text => {text => integer}}");
        assertInstanceOf(MapRef.class, map.valueType().typeRef());
    }

    @Test
    void anInlineMapAtAFieldPosition() {
        FieldDef field = (FieldDef) ((RecordDef) ((StructuralTypeDef)
                declOf("holder => { entries: {text => integer} }").typeDef()).body()).entries().get(0);
        MapRef map = assertInstanceOf(MapRef.class, field.type().orElseThrow().typeRef());
        assertEquals(new SimpleRef("text"), map.keyType());
        assertEquals(new SimpleRef("integer"), map.valueType().typeRef());
    }

    /** Every brace that is not {@code name "=>"} or {@code name "<"} commits to a record, {@code {}} included. */
    @Test
    void everyOtherBraceShapeCommitsToARecord() {
        assertInstanceOf(StructuralTypeDef.class, declOf("empty => {}").typeDef());
        assertInstanceOf(StructuralTypeDef.class, declOf("plain => { a: text }").typeDef());
        assertInstanceOf(StructuralTypeDef.class, declOf("grouped => { ( a: text | b: integer ) }").typeDef());
        assertInstanceOf(StructuralTypeDef.class, declOf("annotated => { @doc:\"x\" a: text }").typeDef());
    }

    /**
     * A record body stays a record body wherever the grammar already fixed one -- a refinement body, a
     * composition tail, a constructor vocabulary -- so {@code =>} there is an error rather than a map, and
     * the diagnostic says which of the two constructs {@code =>} belongs to.
     */
    @Test
    void aMapArrowInARecordBodyNamesTheConstructRatherThanTheToken() {
        TsonParseException thrown = assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/33/m/meta.tn"
                { config => base ^ {text => text} }"""));
        assertTrue(thrown.getMessage().contains("'=>' begins a map type only where a type is expected"),
                thrown.getMessage());
    }

    /** A map <em>type</em> has one key type and one value type; the data grammar's multi-entry habit is named. */
    @Test
    void aSecondMapEntryIsNamedAsTheSingleEntryRuleRatherThanAnUnexpectedToken() {
        TsonParseException thrown = assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/33/m/meta.tn"
                { m => {text => integer  integer => text} }"""));
        assertTrue(thrown.getMessage().contains("a map type is a single 'key => value' entry"),
                thrown.getMessage());
    }

    /** At a type-ref position the two brace meanings are distinguished by name: a bare record must be declared. */
    @Test
    void aBareRecordAtATypeRefPositionDistinguishesTheTwoBraceMeanings() {
        TsonParseException thrown = assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/33/m/meta.tn"
                { holder => { inner: {name: text} } }"""));
        assertTrue(thrown.getMessage().contains("opens the map sugar"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("record body is not permitted"), thrown.getMessage());
    }

    /**
     * Neither side of the {@code =>} admits {@code ?}: a map declares no element state (§5.3). The value
     * side, and a <em>generic</em> key, reach that rule; a plain-name key marked {@code ?} never does, because
     * §12.2's dispatch has already committed the brace to a record by the time the {@code ?} is read -- see
     * {@link #aQuestionMarkOnAPlainMapKeyIsAnsweredByTheBraceDispatch}.
     */
    @Test
    void aQuestionMarkOnAMapTypeIsAParseError() {
        for (String body : List.of("{text => integer?}", "{pair<text>? => integer}")) {
            TsonParseException thrown = assertThrows(TsonParseException.class, () -> parse("""
                    !!meta:"https://tson.io/2026/33/m/meta.tn"
                    { m => %s }""".formatted(body)), body);
            assertTrue(thrown.getMessage().contains("not permitted on a map type's"), thrown.getMessage());
        }
    }

    /**
     * {@code {text? => integer}} is rejected, but as a <b>record</b>: {@code text} followed by anything that
     * is not {@code =>} or {@code <} commits the brace to a record body, and the {@code ?} is then a field
     * name missing its {@code :}. Pinned because it is the one place the dispatch's answer and the author's
     * intent visibly diverge, and closing it would cost a third token of lookahead -- more than §12.2's
     * stated budget of one consumed token plus one of lookahead.
     */
    @Test
    void aQuestionMarkOnAPlainMapKeyIsAnsweredByTheBraceDispatch() {
        TsonParseException thrown = assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/33/m/meta.tn"
                { m => {text? => integer} }"""));
        assertTrue(thrown.getMessage().contains("a record field's ':'"), thrown.getMessage());
    }

    @Test
    void genericApplication() {
        TypeDef def = declOf("translations => map<text, text>").typeDef();
        ReferenceTypeDef ref = assertInstanceOf(ReferenceTypeDef.class, def);
        assertEquals(new GenericRef("map", List.of(
                new TypeArg.Ref(new SimpleRef("text")), new TypeArg.Ref(new SimpleRef("text")))), ref.ref());
    }

    // ── Field states (§5.2) ───────────────────────────────────────────────

    @Test
    void allSixFieldStateSpellings() {
        RecordDef record = (RecordDef) ((StructuralTypeDef) declOf("""
                config => {
                  host:   text
                  port:   integer ~ 8080
                  debug:  boolean = false
                  label:  text?
                  format: text? = json
                  hidden: text? = _
                }""").typeDef()).body();

        FieldDef host = (FieldDef) record.entries().get(0);
        assertTrue(host.type().isPresent());
        assertTrue(host.modifier().isEmpty());
        assertFalse(host.type().get().optional());

        FieldDef port = (FieldDef) record.entries().get(1);
        assertEquals(FieldDef.Modifier.Kind.DEFAULT, port.modifier().orElseThrow().kind());
        assertEquals("8080", ((FieldDef.Modifier.Value.Literal) port.modifier().get().value()).token().text());

        FieldDef debug = (FieldDef) record.entries().get(2);
        assertEquals(FieldDef.Modifier.Kind.FIXED, debug.modifier().orElseThrow().kind());

        FieldDef label = (FieldDef) record.entries().get(3);
        assertTrue(label.type().orElseThrow().optional());
        assertTrue(label.modifier().isEmpty());

        FieldDef format = (FieldDef) record.entries().get(4);
        assertTrue(format.type().orElseThrow().optional());
        assertEquals(FieldDef.Modifier.Kind.FIXED, format.modifier().orElseThrow().kind());
        assertEquals("json", ((FieldDef.Modifier.Value.Literal) format.modifier().get().value()).token().text());

        FieldDef hidden = (FieldDef) record.entries().get(5);
        assertInstanceOf(FieldDef.Modifier.Value.Absent.class, hidden.modifier().orElseThrow().value());
    }

    @Test
    void elidedTypeRefInARefinementBody() {
        // Only a modifier, no type-ref -- legal in a refinement/composition tightening body (§5.7).
        TypeDef def = declOf("production => config ^ { port: = 9090 }").typeDef();
        RefinedDef refined = (RefinedDef) ((StructuralTypeDef) def).body();
        FieldDef port = (FieldDef) refined.body().entries().get(0);
        assertTrue(port.type().isEmpty());
        assertEquals(FieldDef.Modifier.Kind.FIXED, port.modifier().orElseThrow().kind());
    }

    // ── Field groups (§5.11) ──────────────────────────────────────────────

    @Test
    void fieldGroupRequiredAndOptional() {
        StructuralTypeDef structural = (StructuralTypeDef) declOf("""
                integer_type => ~atom & {
                  size:  integer_size?
                  ( min: integer | exclusive_min: integer )?
                  multiple_of: integer?
                }""").typeDef();
        ConstructionDef construction = (ConstructionDef) structural.body();
        RecordDef record = construction.body().orElseThrow();

        GroupDef group = (GroupDef) record.entries().get(1);
        assertTrue(group.optional());
        assertEquals(2, group.members().size());
        assertEquals("min", group.members().get(0).name());
        assertEquals("exclusive_min", group.members().get(1).name());
    }

    @Test
    void groupWithOneMemberIsAParseError() {
        assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                { a => { ( x: text ) } }"""));
    }

    // ── Templates and parameters (§5.10) ──────────────────────────────────

    @Test
    void templateWithMultipleParameters() {
        TypeDef def = declOf("pair => <T, U> { first: T  second: U }").typeDef();
        StructuralTypeDef structural = (StructuralTypeDef) def;
        assertEquals(List.of("T", "U"), structural.typeParams());
    }

    // ── Choice and tuple minimum-arity errors ────────────────────────────

    @Test
    void choiceWithOneVariantIsAParseError() {
        assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                { a => (text) }"""));
    }

    @Test
    void bareTypeRefFollowedByBraceIsAParseError() {
        assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                { a => text { x: text } }"""));
    }

    // ── One tier, not two (§5.3) ─────────────────────────────────────────
    //    A size specifier and an element `?` used to be declaration-level-only, enforced by there being
    //    two productions. There is one now, and no position refuses either: the split existed because a
    //    sized form had no inline representation to carry it, and every form lifts to an entry.

    @Test
    void aSizeSpecifierIsLegalAtAFieldPosition() {
        ArrayRef array = (ArrayRef) fieldTypeOf("a => { x: [text; 1] }", "x");
        assertEquals(new SizeSpec.Exact("1"), array.size().orElseThrow());
    }

    @Test
    void aMapSizeSpecifierIsLegalAtAFieldPosition() {
        MapRef map = (MapRef) fieldTypeOf("a => { x: {text => integer; 1..} }", "x");
        assertEquals(new SizeSpec.Min("1"), map.size().orElseThrow());
    }

    @Test
    void anElementQuestionMarkIsLegalAtAFieldPosition() {
        ArrayRef array = (ArrayRef) fieldTypeOf("a => { x: [text?] }", "x");
        assertTrue(array.elementType().optional());
    }

    /** The one place the two {@code ?} positions meet: the inner is the element's, the outer the field's. */
    @Test
    void anElementQuestionMarkAndAFieldQuestionMarkDoNotCollide() {
        FieldDef field = fieldOf("a => { x: [text?]? }", "x");
        assertTrue(field.type().orElseThrow().optional(), "the field's own '?'");
        assertTrue(((ArrayRef) field.type().orElseThrow().typeRef()).elementType().optional(), "the element's");
    }

    /** Nesting is the recursion in {@code element-type}, so a sized form nests at a field like anywhere else. */
    @Test
    void aNestedSizedFormIsLegalAtAFieldPosition() {
        ArrayRef outer = (ArrayRef) fieldTypeOf("a => { x: [[text; 2]; 3] }", "x");
        assertEquals(new SizeSpec.Exact("3"), outer.size().orElseThrow());
        assertEquals(new SizeSpec.Exact("2"),
                ((ArrayRef) outer.elementType().typeRef()).size().orElseThrow());
    }

    @Test
    void trailingCommaInTupleIsAParseError() {
        assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                { a => [text, integer,] }"""));
    }

    // ── Instance templates (§12.1) ────────────────────────────────────────
    //    `[type-params] "!" type-name ws core-value` -- the same production as a closed instance, with a
    //    parameter list in front. There is no narrower payload grammar, because an open entry's body is held
    //    rather than read against the constructor's vocabulary until materialisation substitutes.

    @Test
    void anInstanceTemplateCarriesItsParametersAndItsPayloadWhole() {
        Instance template = assertInstanceOf(Instance.class,
                declOf("vector => <T, N> !array { element_type: T  min_items: N  max_items: N }").typeDef());

        assertEquals(List.of("T", "N"), template.typeParams());
        assertEquals("array", template.target());
        RecordValue payload = assertInstanceOf(RecordValue.class, template.value().coreValue());
        assertEquals(List.of("element_type", "min_items", "max_items"),
                payload.fields().stream().map(RecordValue.Field::name).toList());
    }

    /** No parameter list means a closed {@link Instance}: the list is the only thing that distinguishes them. */
    @Test
    void theParameterListIsWhatDistinguishesATemplateFromAnInstance() {
        assertEquals(List.of(), assertInstanceOf(Instance.class,
                declOf("x => !array { element_type: text }").typeDef()).typeParams());
        assertEquals(List.of("T"), assertInstanceOf(Instance.class,
                declOf("x => <T> !array { element_type: T }").typeDef()).typeParams());
    }

    /**
     * A collection payload parses, which is the whole point of the production being {@code core-value}: a
     * parameter inside {@code variants} or {@code elements} is a token in an array, and the phase that would
     * have had to classify it does not run until the parameters are gone ({@code SPEC-FEEDBACK.md} #5).
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "t => <T> !choice { variants: [T error] }",
            "t => <T> !tuple { elements: [{ element_type: T } { element_type: text }] }",
            "t => <T> !array { element_type: T  min_items: 2 }"})
    void aCollectionPayloadIsOrdinaryInATemplate(String declaration) {
        assertInstanceOf(Instance.class, declOf(declaration).typeDef());
    }

    /** An empty payload is a legal instance and stays one behind a parameter list -- the same production. */
    @Test
    void anEmptyPayloadIsTheConstructorsOwnDefaults() {
        assertInstanceOf(Instance.class, declOf("t => <T> !array { }").typeDef());
    }

    /**
     * {@code atom-refinement = "!" type-name ws "^" ws record-def} (§12.1) -- a braced record of constraint
     * bindings and nothing else. The grammar does not backtrack, so {@code !integer ^} has already committed
     * to this production and anything but a brace is a parse error here; {@code instance} cannot rescue it
     * either, {@code ^} being no {@code core-value}. Revision 32 spelled the payload {@code data-value},
     * which is how a bare token, a second type-ref and a leading annotation all used to parse.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "t => !integer ^ 5",                      // a bare token where a record-def is required
            "t => !integer ^ \"5\"",                   // ... quoted, in case form were mistaken for shape
            "t => !integer ^ _",                      // ... the absent sentinel
            "t => !integer ^ [1 2]",                  // ... an array: a core-value, still not a record-def
            "t => !integer ^ !integer_type { min: 1 }",   // a second, competing type-ref on the payload
            "t => !integer ^ @doc:\"d\" { min: 1 }"})     // an annotation layer on the payload
    void anAtomRefinementBodyMustBeABracedRecord(String declaration) {
        TsonParseException thrown = assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/33/m/meta.tn"
                { %s }""".formatted(declaration)));
        assertTrue(thrown.getMessage().contains("'{'"), thrown.getMessage());
    }

    /** The two spellings that are record-defs: bindings, and the empty body a fresh instance takes. */
    @ParameterizedTest
    @ValueSource(strings = {"age => !integer ^ { min: 0 }", "age => !integer ^ {}"})
    void anAtomRefinementBodyMayBeEmptyOrBound(String declaration) {
        assertInstanceOf(AtomRefinement.class, declOf(declaration).typeDef());
    }

    /**
     * And it resyncs like any other declaration-level syntax error, rather than taking the whole document
     * with it -- the recovering parse is what {@code Tson.validateSchema} runs.
     */
    @Test
    void aRefinementBodyErrorIsReportedPerDeclarationAndTheParseContinues() {
        List<Diagnostic> problems = new ArrayList<>();
        new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/33/m/meta.tn"
                {
                  bad  => !integer ^ 5
                  good => { n: int32 }
                }""").parseSchemaDocument(problems::add);

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, problems.getFirst().code(), problems::toString);
        assertTrue(problems.getFirst().schemaPointer().orElseThrow().contains("bad"), problems::toString);
    }

    /** {@code atom-refinement} keeps its unparameterised form -- refining an atom instance binds nothing. */
    @Test
    void aParameterizedAtomRefinementIsAParseError() {
        TsonParseException thrown = assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/33/m/meta.tn"
                { t => <N> !integer ^ { min: N } }"""));
        assertTrue(thrown.getMessage().contains("'^' takes no type parameters"), thrown.getMessage());
    }

    // ── Declaration names ────────────────────────────────────────────────

    @Test
    void numericDeclarationNameIsAParseError() {
        assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                { 42 => text }"""));
    }

    // ── §1.6's full worked example ────────────────────────────────────────

    @Test
    void section1Point6WorkedExample() {
        SchemaDocument doc = parse("""
                !!id:"https://example.com/task.tn1"
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                !!import:"https://tson.io/2026/33/m/core.tn1"
                @doc:"Task-tracking example schema."
                {
                  priority => !integer ^ { min: 1  max: 5 }
                  status   => !enum [OPEN ACTIVE DONE]
                  flagged  => <T, N> { entry: T  priority: priority ~ N }
                  task => {
                    id:       uuid
                    title:    non_empty_text
                    priority: priority ~ 3
                    status:   status ~ OPEN
                    due:      date?
                    tags:     [text]?
                    history:  [flagged<status, 2>]?
                  }
                }""");
        Map<String, SchemaMap.Declaration> decls = doc.body().declarations();
        assertEquals(4, decls.size());
        assertInstanceOf(AtomRefinement.class, decls.get("priority").typeDef());
        assertInstanceOf(Instance.class, decls.get("status").typeDef());

        StructuralTypeDef flagged = assertInstanceOf(StructuralTypeDef.class, decls.get("flagged").typeDef());
        assertEquals(List.of("T", "N"), flagged.typeParams());

        StructuralTypeDef task = assertInstanceOf(StructuralTypeDef.class, decls.get("task").typeDef());
        RecordDef taskBody = assertInstanceOf(RecordDef.class, task.body());
        assertEquals(7, taskBody.entries().size());

        FieldDef history = (FieldDef) taskBody.entries().get(6);
        assertTrue(history.type().orElseThrow().optional());
        ArrayRef historyArray = assertInstanceOf(ArrayRef.class, history.type().get().typeRef());
        GenericRef flaggedApplication = assertInstanceOf(GenericRef.class, historyArray.elementType().typeRef());
        assertEquals("flagged", flaggedApplication.name());
        assertEquals(2, flaggedApplication.args().size());
    }

    // ── Real spec fixtures parse end-to-end (grammar layer only) ─────────

    @Test
    void metaKernelParses() throws IOException {
        SchemaDocument doc = parse(readFixture("meta-kernel.tn"));
        assertEquals(49, doc.body().declarations().size());
    }

    @Test
    void metaSchemaParses() throws IOException {
        SchemaDocument doc = parse(readFixture("meta.tn"));
        assertFalse(doc.body().declarations().isEmpty());
    }

    @Test
    void coreTypeLibraryParses() throws IOException {
        SchemaDocument doc = parse(readFixture("core.tn"));
        assertFalse(doc.body().declarations().isEmpty());
    }

    private static String readFixture(String name) throws IOException {
        return Files.readString(Path.of("").toAbsolutePath().resolve("../spec/m/" + name).normalize());
    }

    private static SchemaMap.Declaration declOf(String declaration) {
        SchemaDocument doc = parse("""
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                { %s }""".formatted(declaration));
        return doc.body().declarations().values().iterator().next();
    }

    // ── Message level: the construct, not the token class (#29) ──────────

    @Test
    void aMismatchNamesTheConstructThePositionAdmitsAndNotTheTokenClass() {
        TsonParseException e = assertThrows(TsonParseException.class, () -> declOf("a => { x: }"));
        assertEquals("expected a type reference, found '}'", e.getMessage());
        assertEquals("a type reference", e.expected());
        assertEquals("'}'", e.actual());
    }

    @Test
    void aQuotedTokenIsDescribedAsOneSinceItsTextAloneDoesNotSaySo() {
        TsonParseException e = assertThrows(TsonParseException.class, () -> declOf("a => \"text\""));
        assertEquals("expected a type reference, found the quoted token 'text'", e.getMessage());
    }

    @Test
    void anInlineAtomRefinementNamesTheFixRatherThanTheTokenItTrippedOn() {
        TsonParseException e = assertThrows(TsonParseException.class,
                () -> declOf("order => { quantity: !integer ^ { min: 1 } }"));
        assertTrue(e.getMessage().startsWith("an atom refinement or constructor application is not permitted "
                + "at a type-ref position (§5.3)"), e::getMessage);
        assertTrue(e.getMessage().contains("declare a named type instead"), e::getMessage);
    }

    @Test
    void aRuleViolationCarriesNoExpectedActualPairToInvent() {
        TsonParseException e = assertThrows(TsonParseException.class, () -> declOf("a => { x: text ? }"));
        assertEquals("", e.expected());
        assertEquals("", e.actual());
    }

    // ── Declaration-level recovery (#29) ─────────────────────────────────

    private static List<Diagnostic> parseCollecting(String source) {
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        new TsonSchemaParser(source).parseSchemaDocument(problems);
        return problems.diagnostics();
    }

    @Test
    void everyBrokenDeclarationIsReportedInOnePass() {
        List<Diagnostic> problems = parseCollecting("""
                !!id:"https://example.com/x.tn"
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                {
                  first => { x: }
                  second => { y: text }
                  third => { z: !integer ^ { min: 1 } }
                }
                """);
        assertEquals(2, problems.size(), problems::toString);
        assertEquals(List.of("/first", "/third"),
                problems.stream().map(d -> d.schemaPointer().orElseThrow()).toList());
    }

    @Test
    void aParseThatReportedAnythingHandsBackNoDocumentEvenThoughSomeDeclarationsParsed() {
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        TsonSchemaParser parser = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                {
                  broken => { x: }
                  sound => { y: text }
                }
                """);
        assertTrue(parser.parseSchemaDocument(problems).isEmpty());
        assertEquals(1, parser.reported());
    }

    @Test
    void aCleanParseThroughTheRecoveringEntryPointHandsBackTheDocument() {
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        TsonSchemaParser parser = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                { sound => { y: text } }
                """);
        SchemaDocument doc = parser.parseSchemaDocument(problems).orElseThrow();
        assertEquals(0, parser.reported());
        assertEquals(List.of("sound"), List.copyOf(doc.body().declarations().keySet()));
    }

    @Test
    void everyDeclarationFailingLeavesNoSchemaMapToBuildRatherThanAnEmptyOne() {
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        TsonSchemaParser parser = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                {
                  first => { x: }
                  second => { y: }
                }
                """);
        assertTrue(parser.parseSchemaDocument(problems).isEmpty());
        assertEquals(2, parser.reported());
    }

    @Test
    void recoveryResynchronisesPastNestedBracketsRatherThanStoppingAtTheirClosers() {
        List<Diagnostic> problems = parseCollecting("""
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                {
                  broken => { a: [ text, (b | c) ] x: }
                  next => { y: }
                }
                """);
        assertEquals(List.of("/broken", "/next"),
                problems.stream().map(d -> d.schemaPointer().orElseThrow()).toList());
    }

    @Test
    void aDeclarationFailingBeforeItsOwnNameIsPointedAtTheDocumentRoot() {
        List<Diagnostic> problems = parseCollecting("""
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                {
                  "quoted" => text
                  next => text
                }
                """);
        assertEquals("", problems.get(0).schemaPointer().orElseThrow());
    }

    @Test
    void aSchemaSyntaxDiagnosticLocatesItselfAtTheSchemaEndAndNotTheDataEnd() {
        Diagnostic d = parseCollecting("""
                !!id:"https://example.com/x.tn"
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                {
                  broken => { x: }
                  next => text
                }
                """).get(0);
        // Canonicalized (§2.2.1, scheme stripped), so it matches the id every later phase reports under.
        assertEquals("example.com/x.tn", d.schemaId());
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, d.code());
        assertTrue(d.path().isEmpty());
        assertTrue(d.dataPosition().isEmpty());
        assertEquals(4, d.schemaPosition().orElseThrow().line());
        assertEquals("a type reference", d.expected());
    }

    @Test
    void aMissingReceiverIsStillFailFast() {
        assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                {
                  broken => { x: }
                  next => text
                }
                """));
    }

    @Test
    void aMalformedHeaderStillThrowsBecauseThereIsNothingToResynchroniseOn() {
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        assertThrows(TsonParseException.class,
                () -> new TsonSchemaParser("{ a => text }").parseSchemaDocument(problems));
    }

    // ── Declaration position side-table ──────────────────────────────────

    @Test
    void declarationPositionsRecordsEachDeclarationsOwnNameTokenPosition() {
        String source = """
                !!meta:"https://tson.io/2026/33/m/meta.tn1"
                {
                  first => {}

                  second => {}
                }
                """;
        TsonSchemaParser parser = new TsonSchemaParser(source);
        SchemaDocument doc = parser.parseSchemaDocument();

        SchemaMap.Declaration first = doc.body().declarations().get("first");
        SchemaMap.Declaration second = doc.body().declarations().get("second");

        Map<SchemaMap.Declaration, Position> declarationPositions = parser.declarationPositions();
        assertEquals(new Position(3, 3, source.indexOf("first")), declarationPositions.get(first));
        assertEquals(5, declarationPositions.get(second).line());
        assertTrue(declarationPositions.get(second).line() > declarationPositions.get(first).line());
    }
}
