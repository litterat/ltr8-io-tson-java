package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.schema.FieldDef;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.compiler.ast.schema.RecordDef;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.ast.schema.SimpleRef;
import io.ltr8.tson.compiler.ast.schema.StructuralTypeDef;
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
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void aNonConstructorHeadIsPassedThrough() {
        // A template application (§5.10) is out of scope for this phase; `box` is not in the meta at all, so
        // it keeps its existing downstream handling rather than being turned into a different broken shape.
        SchemaDocument document = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  box => <T> { v: T }
                  holder => { b: box<text> }
                }""").parseSchemaDocument();

        assertSame(document, SchemaDesugarer.desugar(document, META, Set.of()));
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
