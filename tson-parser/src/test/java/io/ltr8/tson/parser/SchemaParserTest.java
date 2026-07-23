package io.ltr8.tson.parser;

import io.ltr8.tson.parser.ast.schema.ArrayContainerDef;
import io.ltr8.tson.parser.ast.schema.AtomRefinement;
import io.ltr8.tson.parser.ast.schema.ChoiceRef;
import io.ltr8.tson.parser.ast.schema.ConstructionDef;
import io.ltr8.tson.parser.ast.schema.ContainerTypeDef;
import io.ltr8.tson.parser.ast.schema.FieldDef;
import io.ltr8.tson.parser.ast.schema.GenericRef;
import io.ltr8.tson.parser.ast.schema.GroupDef;
import io.ltr8.tson.parser.ast.schema.InlineArrayRef;
import io.ltr8.tson.parser.ast.schema.Instance;
import io.ltr8.tson.parser.ast.schema.RecordDef;
import io.ltr8.tson.parser.ast.schema.RefinedDef;
import io.ltr8.tson.parser.ast.schema.ReferenceTypeDef;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.ast.schema.SchemaMap;
import io.ltr8.tson.parser.ast.schema.SimpleRef;
import io.ltr8.tson.parser.ast.schema.SizeSpec;
import io.ltr8.tson.parser.ast.schema.StructuralTypeDef;
import io.ltr8.tson.parser.ast.schema.TupleContainerDef;
import io.ltr8.tson.parser.ast.schema.TypeArg;
import io.ltr8.tson.parser.ast.schema.TypeDef;
import io.ltr8.tson.parser.ast.schema.TypeRef;
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

class SchemaParserTest {

    private static SchemaDocument parse(String source) {
        return new SchemaParser(source).parseSchemaDocument();
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
        assertThrows(ParseException.class, () -> parse("{ a => text }"));
    }

    @Test
    void schemaDirectiveInHeaderIsAParseError() {
        // !!schema belongs to data documents, not schema documents (§2.2).
        assertThrows(ParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                !!schema:"https://example.com/x.tn1"
                { a => text }"""));
    }

    // ── Schema map (§2.1) ─────────────────────────────────────────────────

    @Test
    void emptySchemaMapIsAParseError() {
        assertThrows(ParseException.class, () -> parse("""
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
                ((io.ltr8.tson.parser.ast.schema.ElementType.Expr.Plain) array.elementType().expr()).typeRef());
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
        assertThrows(ParseException.class, () -> parse("""
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
        assertThrows(ParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                { a => (text) }"""));
    }

    @Test
    void bareTypeRefFollowedByBraceIsAParseError() {
        assertThrows(ParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                { a => text { x: text } }"""));
    }

    // ── Inline vs declaration-level sugar (§5.3) ─────────────────────────

    @Test
    void inlineSizeSpecifierIsAParseError() {
        assertThrows(ParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                { a => { x: [text; 1] } }"""));
    }

    @Test
    void inlineElementOptionalityIsAParseError() {
        assertThrows(ParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                { a => { x: [text?] } }"""));
    }

    @Test
    void trailingCommaInTupleIsAParseError() {
        assertThrows(ParseException.class, () -> parse("""
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                { a => [text, integer,] }"""));
    }

    // ── Declaration names ────────────────────────────────────────────────

    @Test
    void numericDeclarationNameIsAParseError() {
        assertThrows(ParseException.class, () -> parse("""
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
        SchemaDocument doc = parse(readFixture("meta-kernel.tn1"));
        assertEquals(49, doc.body().declarations().size());
    }

    @Test
    void metaSchemaParses() throws IOException {
        SchemaDocument doc = parse(readFixture("meta.tn1"));
        assertFalse(doc.body().declarations().isEmpty());
    }

    @Test
    void coreTypeLibraryParses() throws IOException {
        SchemaDocument doc = parse(readFixture("core.tn1"));
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
}
