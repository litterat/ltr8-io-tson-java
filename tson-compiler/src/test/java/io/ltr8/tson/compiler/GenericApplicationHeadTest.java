package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonValue;

import java.math.BigInteger;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generic application ({@code head<args>}) in an ordinary user schema -- one governed by {@code meta.tn} and
 * importing {@code core.tn} -- driven through the real bundled chain. The end-to-end peer of {@code
 * SchemaDesugarerTest}, which pins the same rewrite against a hand-built governing meta.
 *
 * <p>[TSON-SCHEMA] §3.3.1 lists "generic-application heads -- the name before {@code <} when the name is not
 * otherwise in scope" among the <b>constructor roles</b> at which the structure namespace is consulted, and
 * gives {@code map<text, text>} as its own example. A user schema's {@code !!meta} is {@code meta.tn}, which
 * imports the meta-kernel, so every container constructor is in that schema's structure namespace.
 *
 * <p>What makes these work uniformly is that {@code SchemaDesugarer} rewrites the application into a real
 * {@code !C value} declaration before resolution, so {@code map} takes the same path as {@code array} rather
 * than a per-shape assembler that only some constructors have.
 */
class GenericApplicationHeadTest {

    private static final String ID = "https://example.test/generic-head.tn";

    /** Resolves, links and compiles a user schema whose body is {@code declarations}; throws whatever the pipeline throws. */
    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/generic-head.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                {
                %s
                }
                """.formatted(declarations);
        TsonSchemaSource source = uri -> {
            if (TsonCanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    /** The entry a field's type names, which every fixture here reaches the injected declaration through. */
    private static TypeDefinition fieldTypeEntry(TsonCompiledSchema compiled, String record, String field) {
        RecordBody body = (RecordBody) compiled.schema().entries().get(record).body();
        String name = body.fields().stream().filter(f -> f.name().equals(field)).findFirst().orElseThrow()
                .type().name();
        return compiled.schema().entries().get(name);
    }

    /** The whole arc in one assertion: a {@code map}-typed field resolves, links, compiles, and reads real data. */
    @Test
    void aMapTypedFieldReadsRealData() {
        TsonCompiledSchema compiled = compile("  holder => { entries: map<text, text> }");

        MapBody body = assertInstanceOf(MapBody.class, fieldTypeEntry(compiled, "holder", "entries").body());
        assertEquals(TypeRef.of("text"), body.keyType());
        assertEquals(TypeRef.of("text"), body.valueType());

        TsonValue value = (TsonValue) compiled.get("holder")
                .read(TestDocuments.document("{ entries: { \"a\" => \"one\"  \"b\" => \"two\" } }"));
        assertNotNull(value);
    }

    @Test
    void theSameApplicationLinksAsATopLevelDeclaration() {
        // §5.6: a declaration whose body is a fully-bound constructor application is a construction. The
        // desugar phase makes the field position above take this exact same path.
        assertNotNull(compile("""
                  entries => map<text, text>
                  holder => { xs: entries }"""));
    }

    @Test
    void arraySugarAndExplicitArrayOrSetApplicationsAllLink() {
        assertNotNull(compile("  holder => { xs: [text] }"));
        assertNotNull(compile("  holder => { xs: array<text> }"));
        assertNotNull(compile("  holder => { xs: set<text> }"));
    }

    /**
     * {@code set} and {@code array} share a body shape, so the only thing distinguishing them is the defaults
     * {@code set}'s own vocabulary tightens (§5.7). Binding the injected {@code !set { element_type: text }}
     * through the compiled reader applies those schema-composed defaults, so this needs no {@code
     * set}-specific handling anywhere -- worth pinning precisely because nothing names {@code set}.
     */
    @Test
    void aSetApplicationCarriesItsOwnTightenedDefaultsNotArrays() {
        ArrayBody asSet = assertInstanceOf(ArrayBody.class,
                fieldTypeEntry(compile("  holder => { xs: set<text> }"), "holder", "xs").body());
        assertTrue(asSet.unordered());
        assertTrue(asSet.uniqueItems());

        ArrayBody asArray = assertInstanceOf(ArrayBody.class,
                fieldTypeEntry(compile("  holder => { xs: array<text> }"), "holder", "xs").body());
        assertFalse(asArray.unordered());
        assertFalse(asArray.uniqueItems());
    }

    /**
     * §5.3's sized sugar, end to end. {@code [text; 1..2]} desugars to {@code array_ranged<text, 1, 2>}, and
     * {@code array_ranged} is a <em>template</em> (declared without {@code ~}), so it materialises as §8.2's
     * instantiation entry rather than resolving in place: the body is headed at the nearest {@code ~}
     * constructor in the source chain, and the flattened application comes through as {@code source}.
     * Asserted against §8.2's own worked example shape, {@code supertypes} apart.
     */
    @Test
    void sizedSugarMaterializesTheInstantiationEntrySpecifiedByEightTwo() {
        TsonCompiledSchema compiled = compile("""
                  tag_list => [text; 1..2]
                  holder => { tags: tag_list }""");

        TypeDefinition entry = compiled.schema().entries().get("tag_list");
        assertEquals(TypeKind.PRODUCT, entry.kind(), "the template's kind");
        assertEquals(List.of(), entry.parameters(), "closed -- §5.10");
        assertEquals(List.of(), entry.supertypes(),
                "empty, against §8.2's transfer of the template's -- a closure of a size template is a "
                        + "construction of `array`, and a constructor is not a supertype (SPEC-FEEDBACK.md #45)");
        assertEquals(new TypeRef("array_ranged", List.of(
                        new TypeArgument.Ref(TypeRef.of("text")),
                        new TypeArgument.Value(new Token("1", Token.Form.UNQUOTED)),
                        new TypeArgument.Value(new Token("2", Token.Form.UNQUOTED)))),
                entry.source().orElseThrow(), "the flattened fully-bound application");

        // !array { element_type: text  min_items: 1  max_items: 2 } -- only the parameter-routed fields; the
        // vocabulary's own defaults (state/unordered/unique_items) stay out of the binding record (§5.6).
        ArrayBody body = assertInstanceOf(ArrayBody.class, entry.body());
        assertEquals(TypeRef.of("text"), body.elementType());
        assertEquals(Optional.of(BigInteger.ONE), body.minItems());
        assertEquals(Optional.of(BigInteger.TWO), body.maxItems());
    }

    /**
     * The three spellings of an array-family declaration agree about the hierarchy: a bound is a constraint,
     * not a change of place. {@code [text]} and {@code vector<text, 3>} are constructions (§5.5 transfers only
     * the target's kind) and {@code [text; 1..2]} is an instantiation, but all three close to a binding record
     * headed by a constructor, and a constructor is not something a value can have as its type -- so none of
     * them records a supertype ({@code SPEC-FEEDBACK.md} #33/#45).
     */
    @Test
    void everySpellingOfAnArrayFamilyDeclarationRecordsNoSupertypes() {
        TsonCompiledSchema compiled = compile("""
                  id_list => [text]
                  tag_list => [text; 1..2]
                  triple => vector<text, 3>""");

        for (String name : List.of("id_list", "tag_list", "triple")) {
            TypeDefinition entry = compiled.schema().entries().get(name);
            assertInstanceOf(ArrayBody.class, entry.body(), name);
            assertEquals(List.of(), entry.supertypes(), name);
        }
    }

    /** And the bounds are live: the instantiation is a real array body, so the compiled reader enforces them. */
    @Test
    void aSizedArraysBoundsAreEnforcedWhenReading() {
        TsonCompiledSchema compiled = compile("""
                  tag_list => [text; 1..2]
                  holder => { tags: tag_list }""");

        assertNotNull(compiled.get("holder").read(TestDocuments.document("{ tags: [\"a\" \"b\"] }")));
        assertTrue(assertThrows(TsonReadException.class,
                () -> compiled.get("holder")
                        .read(TestDocuments.document("{ tags: [] }"))).getMessage().contains("minimum 1"));
        assertTrue(assertThrows(TsonReadException.class,
                () -> compiled.get("holder")
                        .read(TestDocuments.document("{ tags: [\"a\" \"b\" \"c\"] }")))
                .getMessage().contains("maximum 2"));
    }

    /**
     * §8.2 defers a family coherence rule whose operands were parameters until substitution makes them
     * concrete, and requires "a resolver error reported at the materialising application". {@code min <= max}
     * (§5.3) is the rule the kernel's own templates route parameters into, and the sugar is how an author
     * reaches it.
     */
    @Test
    void aSizedArrayWhoseBoundsCannotBeSatisfiedIsAResolverError() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> compile("  impossible => [text; 5..3]"));

        assertTrue(thrown.getMessage().contains("min_items 5 above max_items 3"), thrown.getMessage());
    }

    /**
     * A non-constructor generic head -- a locally declared <em>record</em> template. Unlike the size
     * templates above, its parameter appears as a <em>field type</em> ({@code v: T}), so instantiating it
     * means rewriting the body rather than routing arguments into a constructor's vocabulary -- genuine
     * §5.10 substitution, still unimplemented. The desugar phase rejects it where it is written; left alone,
     * this schema linked and compiled and then failed on the first read that reached the field.
     */
    @Test
    void applyingALocallyDeclaredTemplateIsRejectedWhereItIsWritten() {
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> compile("""
                          box => <T> { v: T }
                          holder => { b: box<text> }"""));

        assertTrue(thrown.getMessage().contains("'box' is a parameterized template"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("not implemented"), thrown.getMessage());
    }
}
