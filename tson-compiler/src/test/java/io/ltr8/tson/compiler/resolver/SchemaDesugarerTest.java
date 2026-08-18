package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.schema.FieldDef;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.compiler.ast.schema.RecordDef;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.ast.schema.SimpleRef;
import io.ltr8.tson.compiler.ast.schema.StructuralTypeDef;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SchemaDesugarer}: the AST rewrite that hoists an application into its own declaration before
 * resolution, so {@code DefinitionResolver} only ever sees a bare reference or {@code !C value}.
 *
 * <p>The governing meta is hand-built rather than the real meta-kernel: the phase needs only a constructor's
 * {@code parameters()} and the {@code valueParam} its vocabulary fields route through, and stating that
 * directly makes the argument-to-field mapping the test is about visible in the fixture instead of buried in
 * a bundled schema.
 *
 * <p>Assertions on unchanged documents use {@link org.junit.jupiter.api.Assertions#assertSame}, not {@code
 * assertEquals}, deliberately. The nodes are records, so an equal-but-rebuilt tree would satisfy {@code
 * equals} while having silently dropped every entry in {@code TsonSchemaParser.declarationPositions()} -- an
 * {@code IdentityHashMap}, so a rebuilt {@code Declaration} no longer matches its own position. Reference
 * equality is what proves the structural sharing that keeps positions intact.
 */
class SchemaDesugarerTest {

    /** {@code array => <T> ~product & { element_type: type_ref = T  ... }} -- one parameter, one routed field. */
    private static TypeDefinition arrayConstructor() {
        RecordBody vocabulary = new RecordBody(List.of(), List.of(
                new RecordField("element_type", TypeRef.of("type_ref"), FieldState.REQUIRED,
                        Optional.empty(), Optional.of("T"))),
                List.of());
        return new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of("T"), true, List.of(), List.of(),
                Optional.empty(), vocabulary);
    }

    /** {@code map => <K, V> ~product & { key_type: type_ref = K  value_type: type_ref = V  ... }}. */
    private static TypeDefinition mapConstructor() {
        RecordBody vocabulary = new RecordBody(List.of(), List.of(
                new RecordField("key_type", TypeRef.of("type_ref"), FieldState.REQUIRED,
                        Optional.empty(), Optional.of("K")),
                new RecordField("value_type", TypeRef.of("type_ref"), FieldState.REQUIRED,
                        Optional.empty(), Optional.of("V"))),
                List.of());
        return new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of("K", "V"), true, List.of(), List.of(),
                Optional.empty(), vocabulary);
    }

    private static final Map<String, TypeDefinition> META =
            Map.of("array", arrayConstructor(), "map", mapConstructor());

    private static SchemaDocument desugar(String declarations) {
        return desugar(declarations, Set.of());
    }

    private static SchemaDocument desugar(String declarations, Set<String> imported) {
        SchemaDocument document = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                %s
                }
                """.formatted(declarations)).parseSchemaDocument();
        return SchemaDesugarer.desugar(document, META, imported);
    }

    /** The injected declaration for the sole application in {@code document}, whichever name it got. */
    private static SchemaMap.Declaration onlyInjected(SchemaDocument document, String head) {
        List<SchemaMap.Declaration> matching = document.body().declarations().values().stream()
                .filter(d -> d.name().startsWith(head + "_")).toList();
        assertEquals(1, matching.size(), () -> "expected one injected " + head + " in "
                + document.body().declarations().keySet());
        return matching.get(0);
    }

    /** The type-ref of {@code declaration}'s first field, which every fixture here uses as the use site. */
    private static String firstFieldType(SchemaDocument document, String declaration) {
        StructuralTypeDef typeDef = (StructuralTypeDef) document.body().declarations().get(declaration).typeDef();
        FieldDef field = (FieldDef) ((RecordDef) typeDef.body()).entries().get(0);
        return ((SimpleRef) field.type().orElseThrow().typeRef()).name();
    }

    @Test
    void aDocumentWithNoApplicationsComesBackUntouched() {
        SchemaDocument document = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { plain => { a: text  b: integer? } }""").parseSchemaDocument();

        assertSame(document, SchemaDesugarer.desugar(document, META, Set.of()));
    }

    @Test
    void aFieldsApplicationBecomesAnInstanceDeclarationAndAReference() {
        SchemaDocument document = desugar("  holder => { entries: map<text, integer> }");

        SchemaMap.Declaration injected = onlyInjected(document, "map");
        assertEquals(firstFieldType(document, "holder"), injected.name(),
                "the use site refers to the injected declaration by name");
        assertTrue(injected.name().startsWith("map_text_integer_"), injected.name());

        // !map { key_type: text  value_type: integer } -- routed by each vocabulary field's own valueParam,
        // which is what makes this work for any constructor rather than needing a per-shape assembler.
        Instance instance = (Instance) injected.typeDef();
        assertEquals("map", instance.target());
        assertEquals("{ key_type: text  value_type: integer }", instanceBody(instance));
    }

    @Test
    void inlineArraySugarBecomesTheSameShapeAsAnExplicitApplication() {
        SchemaDocument sugar = desugar("  holder => { xs: [text] }");
        SchemaDocument explicit = desugar("  holder => { xs: array<text> }");

        assertEquals(onlyInjected(explicit, "array").name(), onlyInjected(sugar, "array").name(),
                "[T] and array<T> are the same application and must produce the same declaration");
        assertEquals("{ element_type: text }", instanceBody((Instance) onlyInjected(sugar, "array").typeDef()));
    }

    @Test
    void anInnerApplicationIsHoistedFirstAndReferredToByTheOuterOne() {
        // The walk is bottom-up, so the inner array is already a plain name when the outer map is built --
        // which is what keeps arbitrarily nested sugar working without a special case.
        SchemaDocument document = desugar("  holder => { m: map<text, [integer]> }");

        String innerName = onlyInjected(document, "array").name();
        assertEquals("{ element_type: integer }",
                instanceBody((Instance) onlyInjected(document, "array").typeDef()));
        assertEquals("{ key_type: text  value_type: " + innerName + " }",
                instanceBody((Instance) onlyInjected(document, "map").typeDef()));
    }

    /**
     * Two structurally identical applications share one declaration, wherever in the document they appear.
     * The name is derived from the application itself, so this falls out of naming rather than needing a
     * separate dedup table -- and it is what §8.2 asks for ("flattened applications that are structurally
     * equal denote the same type").
     */
    @Test
    void twoStructurallyIdenticalApplicationsBecomeOneDeclaration() {
        SchemaDocument document = desugar("""
                  first => { xs: [text] }
                  second => { ys: [text] }""");

        String injected = onlyInjected(document, "array").name(); // asserts there is exactly one
        assertEquals(injected, firstFieldType(document, "first"));
        assertEquals(injected, firstFieldType(document, "second"));
    }

    @Test
    void anApplicationAnImportAlreadyDeclaresIsReferencedNotRedeclared() {
        // The name is derived from the application, so an identical one in an imported schema has already
        // produced this type. Redeclaring it would be rejected as a local-vs-import collision -- which is how
        // this surfaced: meta.tn imports the meta-kernel and repeats several of its applications.
        String name = onlyInjected(desugar("  holder => { xs: [text] }"), "array").name();

        SchemaDocument reusing = desugar("  holder => { xs: [text] }", Set.of(name));

        assertEquals(name, firstFieldType(reusing, "holder"), "still refers to it");
        assertTrue(reusing.body().declarations().keySet().stream().noneMatch(n -> n.startsWith("array_")),
                "but declares nothing: " + reusing.body().declarations().keySet());
    }

    @Test
    void aDeclarationsOwnApplicationBecomesTheInstanceItself() {
        // §5.6: a declaration whose body is a fully-bound application resolves as a *construction*, so it
        // becomes the instance in place rather than a reference to an injected one. That is what keeps
        // `x => map<K, V>` a PRODUCT carrying a real body instead of a REFERENCE to one, and it is why
        // declaration position is handled separately from a use site.
        SchemaDocument document = desugar("  entries => map<text, integer>");

        Instance instance = (Instance) document.body().declarations().get("entries").typeDef();
        assertEquals("map", instance.target());
        assertEquals("{ key_type: text  value_type: integer }", instanceBody(instance));
        assertEquals(1, document.body().declarations().size(), "nothing injected alongside it");
    }

    @Test
    void aSizeLessDeclarationLevelArrayIsAlsoAnApplication() {
        // §5.6 again -- `x => [T]` is a top-level constructor application, which DefinitionResolver used to
        // reject outright. The *sized* forms are not: they desugar to array_min/array_max/array_ranged,
        // which are templates rather than constructors, so they stay on their existing path.
        SchemaDocument document = desugar("  ids => [text]");

        Instance instance = (Instance) document.body().declarations().get("ids").typeDef();
        assertEquals("array", instance.target());
        assertEquals("{ element_type: text }", instanceBody(instance));
    }

    @Test
    void sizedSugarBecomesTheSizeTemplateApplicationItStandsFor() {
        // §5.3: [T; N..M] is array_ranged<T, N, M>, [T; N..] is array_min, [T; ..M] is array_max, and an
        // exact [T; N] is array_ranged with the bound twice. Purely a change of spelling, which is why it
        // belongs here even though the targets are templates rather than constructors -- what a template
        // application then resolves to (§5.10 substitution) is a separate question this phase does not
        // answer, so the result stays an application rather than becoming an instance.
        //
        // Observable in isolation only because META declares no templates. Against a real governing meta the
        // rewrite still happens and is then rejected, since array_ranged is a template -- see
        // sizedSugarAgainstARealMetaIsRejectedAsTheTemplateApplicationItIs below.
        assertEquals("array_ranged<text, 1, 5>", application(desugar("  bounded => [text; 1..5]"), "bounded"));
        assertEquals("array_min<text, 2>", application(desugar("  atLeast => [text; 2..]"), "atLeast"));
        assertEquals("array_max<text, 9>", application(desugar("  atMost => [text; ..9]"), "atMost"));
        assertEquals("array_ranged<text, 3, 3>", application(desugar("  exact => [text; 3]"), "exact"));
    }

    /** Renders a declaration's application body as {@code head<arg, arg>} for readable assertions. */
    private static String application(SchemaDocument document, String declaration) {
        var ref = (io.ltr8.tson.compiler.ast.schema.ReferenceTypeDef)
                document.body().declarations().get(declaration).typeDef();
        var generic = (io.ltr8.tson.compiler.ast.schema.GenericRef) ref.ref();
        List<String> args = generic.args().stream()
                .map(a -> a instanceof io.ltr8.tson.compiler.ast.schema.TypeArg.Ref r
                        ? ((SimpleRef) r.ref()).name()
                        : ((io.ltr8.tson.compiler.ast.schema.TypeArg.Value) a).value().text())
                .toList();
        return generic.name() + "<" + String.join(", ", args) + ">";
    }

    @Test
    void aParameterizedDeclarationIsLeftEntirelyAlone() {
        // A template's body references its own parameters, so expanding array<T> here would inject a
        // declaration naming an unbound T. This is meta-kernel's own set/array_min/array_max/array_ranged.
        SchemaDocument document = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { my_set => <T> array<T> ^ { unique_items: = true } }""").parseSchemaDocument();

        assertSame(document, SchemaDesugarer.desugar(document, META, Set.of()));
    }

    @Test
    void applyingALocallyDeclaredTemplateIsRejectedHere() {
        // §5.10 substitution is unimplemented, so this phase cannot rewrite the application -- and leaving it
        // alone produced a schema that linked and compiled, then failed on the first read that reached the
        // field. Rejecting it at the application site puts the error where it can be acted on.
        SchemaDocument document = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  box => <T> { v: T }
                  holder => { b: box<text> }
                }""").parseSchemaDocument();

        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> SchemaDesugarer.desugar(document, META, Set.of()));
        assertTrue(thrown.getMessage().contains("'box' is a parameterized template"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("[T]"), "names the unbound parameters: " + thrown.getMessage());
    }

    /**
     * §5.3's sized sugar targets {@code array_ranged}, which the meta-kernel declares without {@code ~} and
     * whose parameters occur only in labelled <em>value</em> channels -- a <b>partial application</b>. Applying
     * one is evaluation, not instantiation: it closes to the plain {@code !array} construction its bindings
     * denote, headed at the nearest {@code ~} constructor in the source chain (§5.6), with no entry of its own
     * ({@code SPEC-FEEDBACK.md} #45). So the sized form lands on exactly the shape {@code [text]} does, one
     * bound apart.
     *
     * <p>Routing is the same mechanism a constructor application uses, because the template's resolved
     * vocabulary carries the same {@code value_param} channels: {@code element_type} from {@code T}, {@code
     * min_items} from {@code MIN}, {@code max_items} from {@code MAX}. Nothing here knows what an array is.
     */
    @Test
    void sizedSugarAgainstARealMetaClosesOntoItsConstructorsConstruction() {
        SchemaDocument document = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { tags => [text; 1..5] }""").parseSchemaDocument();
        Map<String, TypeDefinition> realMeta = MetaKernelBootstrapResolver.getMetaKernelSchema().entries();

        SchemaDocument desugared = SchemaDesugarer.desugar(document, realMeta, Set.of());

        Instance instance = assertInstanceOf(Instance.class,
                desugared.body().declarations().get("tags").typeDef());
        assertEquals("array", instance.target(), "headed at the nearest ~ constructor, not at the template");
        assertEquals("{ element_type: text  min_items: 1  max_items: 5 }", instanceBody(instance));
    }

    /**
     * §5.3 calls {@code [T; 0..]} vacuous and asks for a warning while desugaring it anyway;
     * {@code SPEC-FEEDBACK.md} #42 rejects the spelling instead, and §5.3's own sentence says why it is
     * worth rejecting rather than tolerating: application-structural identity (§8.2) makes it a distinct
     * entry meaning exactly what {@code [text]} means.
     */
    @Test
    void aVacuousZeroFloorIsRejectedRatherThanDesugared() {
        SchemaDocument document = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { tags => [text; 0..] }""").parseSchemaDocument();
        Map<String, TypeDefinition> realMeta = MetaKernelBootstrapResolver.getMetaKernelSchema().entries();

        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> SchemaDesugarer.desugar(document, realMeta, Set.of()));
        assertTrue(thrown.getMessage().contains("'[text]'"), "names the fix: " + thrown.getMessage());
    }

    /** Only the open-ended floor is vacuous: {@code 0..M} still pins a ceiling, and desugars as usual. */
    @Test
    void aZeroFloorWithACeilingIsStillARealConstraint() {
        SchemaDocument document = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { tags => [text; 0..5] }""").parseSchemaDocument();
        Map<String, TypeDefinition> realMeta = MetaKernelBootstrapResolver.getMetaKernelSchema().entries();

        SchemaDocument desugared = SchemaDesugarer.desugar(document, realMeta, Set.of());

        assertEquals("{ element_type: text  min_items: 0  max_items: 5 }", instanceBody(
                assertInstanceOf(Instance.class, desugared.body().declarations().get("tags").typeDef())));
    }

    /**
     * §5.3's variadic pair, tuple half. Run against the real meta-kernel rather than {@link #META}, because
     * the field the positions fill is read off {@code tuple}'s own vocabulary (its sole bare-{@code REQUIRED}
     * field, §5.6's positional-form rule) -- using the real declaration is what proves the routing rather
     * than a fixture built to agree with it.
     *
     * <p>At declaration position the bracket form <em>is</em> the construction, the treatment {@code [T]} and
     * {@code (A | B)} already get, so nothing is injected alongside it.
     */
    @Test
    void aDeclarationLevelTupleBecomesTheConstructionItDenotes() {
        SchemaDocument document = desugarAgainstTheRealMetaKernel("  pair => [integer, text]");

        Instance instance = assertInstanceOf(Instance.class,
                document.body().declarations().get("pair").typeDef());
        assertEquals("tuple", instance.target());
        assertEquals("[ { element_type: integer } { element_type: text } ]", tupleElements(instance));
        assertEquals(1, document.body().declarations().size(), "nothing injected alongside it");
    }

    /** At a field position it is hoisted and referred to by name, exactly as inline {@code [T]} is. */
    @Test
    void anInlineTupleIsHoistedIntoItsOwnDeclarationAndReferredToByName() {
        SchemaDocument document = desugarAgainstTheRealMetaKernel("  holder => { p: [integer, text] }");

        SchemaMap.Declaration injected = onlyInjected(document, "tuple");
        assertEquals(firstFieldType(document, "holder"), injected.name());
        assertTrue(injected.name().startsWith("tuple_integer_text_"), injected.name());
        assertEquals("[ { element_type: integer } { element_type: text } ]",
                tupleElements(assertInstanceOf(Instance.class, injected.typeDef())));
    }

    /**
     * A position's own {@code ?} (declaration position only -- the parser rejects one inline) becomes {@code
     * state: OPTIONAL}. A REQUIRED position writes no {@code state} at all: the member is REQUIRED_DEFAULT
     * ({@code state: element_state ~ REQUIRED}), so §5.2's default injection supplies it, the same way
     * {@code instanceFor} omits every defaulted vocabulary field.
     */
    @Test
    void anOptionalPositionStatesItsStateAndARequiredOneLetsTheDefaultSupplyIt() {
        SchemaDocument document = desugarAgainstTheRealMetaKernel("  pair => [integer?, text]");

        Instance instance = assertInstanceOf(Instance.class,
                document.body().declarations().get("pair").typeDef());
        assertEquals("[ { element_type: integer  state: OPTIONAL } { element_type: text } ]",
                tupleElements(instance));
    }

    // ── Nested bracket forms (§5.3, §12.1) ───────────────────────────────
    //    Declaration-level container syntax nests inside itself, and §5.3 fixes the order: `grid => <T, N>
    //    [[T; N]; N]` is `array_ranged<array_ranged<T, N, N>, N, N>`, "the inner form desugaring first".
    //    So the inner container is injected under its own derived name and the position that held it
    //    becomes a bare reference -- the bottom-up hoist an inline form at a type-ref position already gets.

    /**
     * A tuple position holding a nested <em>sized</em> array. The inner form desugars first, into the
     * {@code array_ranged} instantiation the flat spelling would produce, and the position refers to it by
     * name -- so what the outer tuple routes is a plain name like any other.
     */
    @Test
    void aTuplePositionHoldingANestedSizedArrayRefersToTheInjectedInnerArray() {
        SchemaDocument document = desugarAgainstTheRealMetaKernel("  grid => [[integer; 2], text]");

        SchemaMap.Declaration inner = onlyInjected(document, "array_ranged");
        assertEquals("{ element_type: integer  min_items: 2  max_items: 2 }",
                instanceBody(assertInstanceOf(Instance.class, inner.typeDef())));
        assertEquals("[ { element_type: " + inner.name() + " } { element_type: text } ]",
                tupleElements(assertInstanceOf(Instance.class,
                        document.body().declarations().get("grid").typeDef())));
    }

    /** The other nesting direction: a sized array <em>over</em> a nested plain array ({@code [[T]; N]}). */
    @Test
    void aSizedArrayOverANestedArrayRefersToTheInjectedInnerArray() {
        SchemaDocument document = desugarAgainstTheRealMetaKernel("  rows => [[integer]; 3]");

        SchemaMap.Declaration inner = onlyInjected(document, "array_integer");
        assertEquals("{ element_type: integer }",
                instanceBody(assertInstanceOf(Instance.class, inner.typeDef())));
        assertEquals("{ element_type: " + inner.name() + "  min_items: 3  max_items: 3 }",
                instanceBody(assertInstanceOf(Instance.class,
                        document.body().declarations().get("rows").typeDef())));
    }

    /**
     * Nesting is recursive, and bottom-up needs no special case for depth: {@code [[[T]]]} injects the
     * innermost array first and the middle one refers to it, exactly as the outermost refers to the middle.
     */
    @Test
    void nestingRecursesInnermostFirst() {
        SchemaDocument document = desugarAgainstTheRealMetaKernel("  deep => [[[integer]]]");

        SchemaMap.Declaration innermost = onlyInjected(document, "array_integer");
        SchemaMap.Declaration middle = onlyInjected(document, "array_array_integer");
        assertEquals("{ element_type: integer }",
                instanceBody(assertInstanceOf(Instance.class, innermost.typeDef())));
        assertEquals("{ element_type: " + innermost.name() + " }",
                instanceBody(assertInstanceOf(Instance.class, middle.typeDef())));
        assertEquals("{ element_type: " + middle.name() + " }",
                instanceBody(assertInstanceOf(Instance.class,
                        document.body().declarations().get("deep").typeDef())));
    }

    /**
     * A nested tuple's <em>position</em> optionality reaches the derived name. Without it {@code [T, U?]} and
     * {@code [T, U]} derive the same name from their element types alone, and the second one written collapses
     * onto the first one injected -- two different types on one entry, silently.
     */
    @Test
    void twoNestedTuplesDifferingOnlyInPositionOptionalityGetSeparateDeclarations() {
        SchemaDocument document = desugarAgainstTheRealMetaKernel("""
                  strict  => [[integer, text], boolean]
                  relaxed => [[integer, text?], boolean]""");

        List<SchemaMap.Declaration> injected = document.body().declarations().values().stream()
                .filter(declaration -> declaration.name().startsWith("tuple_")).toList();
        assertEquals(2, injected.size(), () -> "expected two injected tuples, got "
                + injected.stream().map(SchemaMap.Declaration::name).toList());
        assertEquals("[ { element_type: integer } { element_type: text } ]",
                tupleElements(assertInstanceOf(Instance.class, injected.get(0).typeDef())));
        assertEquals("[ { element_type: integer } { element_type: text  state: OPTIONAL } ]",
                tupleElements(assertInstanceOf(Instance.class, injected.get(1).typeDef())));
    }

    /** A nested position may carry its own {@code ?} as well, and that is the outer tuple's fact, not the inner's. */
    @Test
    void anOptionalPositionHoldingANestedFormKeepsItsOwnState() {
        SchemaDocument document = desugarAgainstTheRealMetaKernel("  loose => [[integer, text]?, boolean]");

        SchemaMap.Declaration inner = onlyInjected(document, "tuple");
        assertEquals("[ { element_type: " + inner.name() + "  state: OPTIONAL } { element_type: boolean } ]",
                tupleElements(assertInstanceOf(Instance.class,
                        document.body().declarations().get("loose").typeDef())));
    }

    /**
     * The <em>element</em> {@code ?} on an array ({@code [T?]}, §5.3's {@code state: OPTIONAL} on the resolved
     * array) is a separate gap this phase still builds nothing for, at any nesting depth. It stays unexpanded
     * and keeps whatever handling it already had, rather than being turned into a differently-broken shape
     * here -- and so does the container enclosing it, since a partially reduced one is no longer a
     * recognisable sugar form.
     */
    @Test
    void anOptionalArrayElementIsStillLeftAlone() {
        SchemaDocument document = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { holder => [[integer?]; 3] }""").parseSchemaDocument();

        assertSame(document, SchemaDesugarer.desugar(document,
                MetaKernelBootstrapResolver.getMetaKernelSchema().entries(), Set.of()));
    }

    /**
     * The check is narrow on purpose. A head naming nothing this document declares, and nothing in the
     * structure namespace either, is an ordinary unresolved reference the linker reports over the whole
     * schema -- so it stays this phase's business only to leave alone.
     */
    @Test
    void anUnknownHeadIsStillPassedThrough() {
        SchemaDocument document = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { holder => { b: nowhere<text> } }""").parseSchemaDocument();

        assertSame(document, SchemaDesugarer.desugar(document, META, Set.of()));
    }

    /** {@link #desugar(String)} against the real meta-kernel entries, for the forms whose routing needs them. */
    private static SchemaDocument desugarAgainstTheRealMetaKernel(String declarations) {
        SchemaDocument document = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                %s
                }
                """.formatted(declarations)).parseSchemaDocument();
        return SchemaDesugarer.desugar(document, MetaKernelBootstrapResolver.getMetaKernelSchema().entries(),
                Set.of());
    }

    /**
     * Renders a variadic {@code Instance}'s one collection-valued field as {@code [ { member: value  ... }
     * ... ]} -- {@link #instanceBody}'s counterpart for a body whose field holds records rather than tokens.
     */
    private static String tupleElements(Instance instance) {
        var record = (io.ltr8.tson.compiler.ast.RecordValue) instance.value().coreValue();
        var elements = (io.ltr8.tson.compiler.ast.ArrayValue) record.fields().get(0).value().value().coreValue();
        StringBuilder out = new StringBuilder("[");
        for (var element : elements.elements()) {
            var members = (io.ltr8.tson.compiler.ast.RecordValue) element.value().coreValue();
            StringBuilder rendered = new StringBuilder("{");
            for (var member : members.fields()) {
                var token = (io.ltr8.tson.compiler.ast.TokenValue) member.value().value().coreValue();
                rendered.append(' ').append(member.name()).append(": ").append(token.text()).append(' ');
            }
            out.append(' ').append(rendered.append('}').toString().replace("  }", " }"));
        }
        return out.append(" ]").toString();
    }

    /** Renders an {@code Instance}'s binding record as {@code { field: value  ... }} for readable assertions. */
    private static String instanceBody(Instance instance) {
        var record = (io.ltr8.tson.compiler.ast.RecordValue) instance.value().coreValue();
        StringBuilder out = new StringBuilder("{");
        for (var field : record.fields()) {
            var token = (io.ltr8.tson.compiler.ast.TokenValue) field.value().value().coreValue();
            out.append(' ').append(field.name()).append(": ").append(token.text()).append(' ');
        }
        return out.append('}').toString().replace("  }", " }");
    }

    /** Unused today; kept so a future stage asserting declaration order has the helper it needs. */
    private static Map<String, SchemaMap.Declaration> ordered(SchemaDocument document) {
        return new LinkedHashMap<>(document.body().declarations());
    }
}
