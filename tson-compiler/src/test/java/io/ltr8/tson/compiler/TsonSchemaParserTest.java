package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.ast.schema.ArrayContainerDef;
import io.ltr8.tson.compiler.ast.schema.AtomRefinement;
import io.ltr8.tson.compiler.ast.schema.ChoiceRef;
import io.ltr8.tson.compiler.ast.schema.ConstructionDef;
import io.ltr8.tson.compiler.ast.schema.ContainerTypeDef;
import io.ltr8.tson.compiler.ast.schema.FieldDef;
import io.ltr8.tson.compiler.ast.schema.GenericRef;
import io.ltr8.tson.compiler.ast.schema.GroupDef;
import io.ltr8.tson.compiler.ast.schema.ElementType;
import io.ltr8.tson.compiler.ast.schema.InlineArrayRef;
import io.ltr8.tson.compiler.ast.schema.InlineMapRef;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.compiler.ast.schema.MapContainerDef;
import io.ltr8.tson.compiler.ast.schema.RecordDef;
import io.ltr8.tson.compiler.ast.schema.RefinedDef;
import io.ltr8.tson.compiler.ast.schema.ReferenceTypeDef;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.ast.schema.SimpleRef;
import io.ltr8.tson.compiler.ast.schema.SizeSpec;
import io.ltr8.tson.compiler.ast.schema.StructuralTypeDef;
import io.ltr8.tson.compiler.ast.schema.TupleContainerDef;
import io.ltr8.tson.compiler.ast.schema.TypeArg;
import io.ltr8.tson.compiler.ast.schema.TypeDef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** The {@link MapContainerDef} one declaration's body is, for the map-grammar fixtures. */
    private static MapContainerDef mapOf(String declaration) {
        ContainerTypeDef container = assertInstanceOf(ContainerTypeDef.class, declOf(declaration).typeDef());
        return assertInstanceOf(MapContainerDef.class, container.container());
    }

    // ── Header (§2.1, §2.2) ──────────────────────────────────────────────

    @Test
    void parsesIdMetaAndImports() {
        SchemaDocument doc = parse("""
                !!id:"https://example.com/x.tn1"
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                !!import:"https://tson.io/2026/32/m/core.tn1"
                { a => text }""");
        assertEquals("https://example.com/x.tn1", doc.id().orElseThrow());
        assertEquals("https://tson.io/2026/32/m/meta.tn1", doc.meta());
        assertEquals(List.of("https://tson.io/2026/32/m/core.tn1"), doc.imports());
    }

    @Test
    void idIsOptional() {
        SchemaDocument doc = parse("""
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                { a => text }""");
        assertTrue(doc.id().isEmpty());
    }

    @Test
    void multipleImportsPreserveOrder() {
        SchemaDocument doc = parse("""
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
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
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                !!schema:"https://example.com/x.tn1"
                { a => text }"""));
    }

    // ── Schema map (§2.1) ─────────────────────────────────────────────────

    @Test
    void emptySchemaMapIsAParseError() {
        assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                {}"""));
    }

    @Test
    void schemaLevelAnnotationBindsToTheMap() {
        SchemaDocument doc = parse("""
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                @doc:"a schema"
                { a => text }""");
        assertEquals(1, doc.body().annotations().size());
        assertEquals("doc", doc.body().annotations().get(0).name());
    }

    @Test
    void declarationNameAndTypeDefAnnotationsBindSeparately() {
        SchemaDocument doc = parse("""
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
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
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
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
        ContainerTypeDef container = assertInstanceOf(ContainerTypeDef.class, def);
        ArrayContainerDef array = assertInstanceOf(ArrayContainerDef.class, container.container());
        assertEquals(new SimpleRef("integer"),
                ((ElementType.Expr.Plain) array.elementType().expr()).typeRef());
        assertEquals(new SizeSpec.Min("1"), array.size().orElseThrow());
    }

    @Test
    void declarationLevelTuple() {
        TypeDef def = declOf("point => [number, number]").typeDef();
        ContainerTypeDef container = assertInstanceOf(ContainerTypeDef.class, def);
        TupleContainerDef tuple = assertInstanceOf(TupleContainerDef.class, container.container());
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
        MapContainerDef map = mapOf("translations => {text => text}");
        assertEquals(new SimpleRef("text"), map.keyType());
        assertEquals(new SimpleRef("text"), ((ElementType.Expr.Plain) map.valueType()).typeRef());
        assertTrue(map.size().isEmpty());
    }

    @Test
    void declarationLevelMapWithSize() {
        MapContainerDef map = mapOf("index => {text => order; 1..5}");
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
        MapContainerDef map = mapOf("index => { pair<text> => integer }");
        assertEquals(new GenericRef("pair", List.of(new TypeArg.Ref(new SimpleRef("text")))), map.keyType());
    }

    /** {@code map-value = container-def / type-ref}, so the declaration-level tier nests inside a map value. */
    @Test
    void aMapValueMayNestADeclarationLevelForm() {
        MapContainerDef map = mapOf("index => {text => [order; 1..]}");
        ArrayContainerDef nested = assertInstanceOf(ArrayContainerDef.class,
                ((ElementType.Expr.Nested) map.valueType()).container());
        assertEquals(new SizeSpec.Min("1"), nested.size().orElseThrow());
    }

    @Test
    void aMapValueMayNestAnotherMap() {
        MapContainerDef map = mapOf("index => {text => {text => integer}}");
        assertInstanceOf(MapContainerDef.class, ((ElementType.Expr.Nested) map.valueType()).container());
    }

    @Test
    void anInlineMapAtAFieldPosition() {
        FieldDef field = (FieldDef) ((RecordDef) ((StructuralTypeDef)
                declOf("holder => { entries: {text => integer} }").typeDef()).body()).entries().get(0);
        InlineMapRef map = assertInstanceOf(InlineMapRef.class, field.type().orElseThrow().typeRef());
        assertEquals(new SimpleRef("text"), map.keyType());
        assertEquals(new SimpleRef("integer"), map.valueType());
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
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                { config => base ^ {text => text} }"""));
        assertTrue(thrown.getMessage().contains("'=>' begins a map type only where a type is expected"),
                thrown.getMessage());
    }

    /** A map <em>type</em> has one key type and one value type; the data grammar's multi-entry habit is named. */
    @Test
    void aSecondMapEntryIsNamedAsTheSingleEntryRuleRatherThanAnUnexpectedToken() {
        TsonParseException thrown = assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                { m => {text => integer  integer => text} }"""));
        assertTrue(thrown.getMessage().contains("a map type is a single 'key => value' entry"),
                thrown.getMessage());
    }

    /** At a type-ref position the two brace meanings are distinguished by name: a bare record must be declared. */
    @Test
    void aBareRecordAtATypeRefPositionDistinguishesTheTwoBraceMeanings() {
        TsonParseException thrown = assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/32/m/meta.tn"
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
                    !!meta:"https://tson.io/2026/32/m/meta.tn"
                    { m => %s }""".formatted(body)), body);
            assertTrue(thrown.getMessage().contains("not permitted on a map type's"), thrown.getMessage());
        }
    }

    /**
     * {@code {text? => integer}} is rejected, but as a <b>record</b>: {@code text} followed by anything that
     * is not {@code =>} or {@code <} commits the brace to a record body, and the {@code ?} is then a field
     * name missing its {@code :}. Pinned because it is the one place the dispatch's answer and the author's
     * intent visibly diverge, and closing it would cost a third token of lookahead ({@code SPEC-FEEDBACK.md}
     * #52).
     */
    @Test
    void aQuestionMarkOnAPlainMapKeyIsAnsweredByTheBraceDispatch() {
        TsonParseException thrown = assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/32/m/meta.tn"
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
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
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
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                { a => (text) }"""));
    }

    @Test
    void bareTypeRefFollowedByBraceIsAParseError() {
        assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                { a => text { x: text } }"""));
    }

    // ── Inline vs declaration-level sugar (§5.3) ─────────────────────────

    @Test
    void inlineSizeSpecifierIsAParseError() {
        assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                { a => { x: [text; 1] } }"""));
    }

    @Test
    void anInlineMapSizeSpecifierIsAParseError() {
        assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                { a => { x: {text => integer; 1} } }"""));
    }

    @Test
    void inlineElementOptionalityIsAParseError() {
        assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                { a => { x: [text?] } }"""));
    }

    @Test
    void trailingCommaInTupleIsAParseError() {
        assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                { a => [text, integer,] }"""));
    }

    // ── Declaration names ────────────────────────────────────────────────

    @Test
    void numericDeclarationNameIsAParseError() {
        assertThrows(TsonParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                { 42 => text }"""));
    }

    // ── §1.6's full worked example ────────────────────────────────────────

    @Test
    void section1Point6WorkedExample() {
        SchemaDocument doc = parse("""
                !!id:"https://example.com/task.tn1"
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                !!import:"https://tson.io/2026/32/m/core.tn1"
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
        InlineArrayRef historyArray = assertInstanceOf(InlineArrayRef.class, history.type().get().typeRef());
        GenericRef flaggedApplication = assertInstanceOf(GenericRef.class, historyArray.elementType());
        assertEquals("flagged", flaggedApplication.name());
        assertEquals(2, flaggedApplication.args().size());
    }

    // ── Real spec fixtures parse end-to-end (grammar layer only) ─────────

    @Test
    void metaKernelParses() throws IOException {
        SchemaDocument doc = parse(readFixture("meta-kernel.tn"));
        assertEquals(47, doc.body().declarations().size());
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
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
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
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
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
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
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
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
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
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
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
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
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
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
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
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
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
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
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
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
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
