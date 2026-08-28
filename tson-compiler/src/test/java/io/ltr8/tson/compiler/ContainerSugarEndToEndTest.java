package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The container sugar forms in an ordinary user schema -- one governed by {@code meta.tn} and importing
 * {@code core.tn} -- driven through the real bundled chain. The end-to-end peer of {@code
 * SchemaDesugarerTest}, which pins the same rewrite on the AST alone.
 *
 * <p>Sugar and the {@code !} form are the <b>only</b> two ways to reach a container constructor. Bare names
 * and generic-application heads resolve in the type-name namespace only ([TSON-SCHEMA] §3.3.1), where
 * {@code array}/{@code set}/{@code map} are not reachable however the schema's {@code !!meta} chain is
 * arranged -- so {@code map<text, text>} is an unresolved reference and <code>{text =&gt; text}</code> is
 * the spelling. What makes every form work uniformly is that {@code SchemaDesugarer} rewrites each into a
 * real {@code !C value} declaration before resolution, off one fixed table.
 */
class ContainerSugarEndToEndTest {

    private static final String ID = "https://example.test/container-sugar.tn";

    /** Resolves, links and compiles a user schema whose body is {@code declarations}; throws whatever the pipeline throws. */
    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/container-sugar.tn"
                !!meta:"https://tson.io/2026/33/m/meta.tn"
                !!import:"https://tson.io/2026/33/m/core.tn"
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

    /**
     * An entry's body with every {@code REFERENCE} hop followed. How many hops an alias costs is this
     * resolver's own bookkeeping (§8.3 flattens uses, not entries), so a fixture asserting on the body a name
     * denotes should not also be asserting on the length of the chain to it.
     */
    private static Top bodyOf(TsonCompiledSchema compiled, String name) {
        Top body = compiled.schema().entries().get(name).body();
        while (body instanceof Reference reference) {
            body = compiled.schema().entries().get(reference.target().name()).body();
        }
        return body;
    }

    /** The whole arc in one assertion: a map-typed field resolves, links, compiles, and reads real data. */
    @Test
    void aMapTypedFieldReadsRealData() {
        TsonCompiledSchema compiled = compile("  holder => { entries: {text => text} }");

        MapBody body = assertInstanceOf(MapBody.class, fieldTypeEntry(compiled, "holder", "entries").body());
        assertEquals(TypeRef.of("text"), body.keyType());
        assertEquals(TypeRef.of("text"), body.valueType());

        TsonValue value = (TsonValue) compiled.get("holder")
                .read(TestDocuments.document("{ entries: { \"a\" => \"one\"  \"b\" => \"two\" } }"));
        assertNotNull(value);
    }

    /**
     * <b>A collection-valued slot holds an application like every other {@code type_ref}-typed slot.</b>
     * {@code variants} is a {@code [type_ref]}, so a §5.10 application standing in one is spelled in {@code
     * type_ref}'s record form and rewritten to the entry it denotes one pass later -- the same route a field
     * type, an {@code element_type} and a map's {@code key_type}/{@code value_type} already took.
     *
     * <p>{@code choiceBinding} refused a variant that was not a bare name instead, which did not fail where
     * it decided: refusing left the <em>whole</em> choice unlifted, so what reached {@code DefinitionResolver}
     * was a {@code ChoiceRef} it has no case for, and the author of a closed, ordinary type was told that
     * only "fresh record constructions, composition, simple type references ... are resolved so far".
     */
    @Test
    void anApplicationIsAChoiceVariantLikeAnyOtherReference() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  u   => ( box<text> | int32 )""");

        ChoiceBody choice = assertInstanceOf(ChoiceBody.class, compiled.schema().entries().get("u").body());
        String variant = choice.variants().getFirst().name();
        assertTrue(variant.startsWith("box_text_"), () -> "names the instantiation, not 'box': " + variant);
        assertEquals(List.of(), choice.variants().getFirst().arguments(),
                "and names it as a closed reference, the application having been rewritten");
        assertInstanceOf(RecordBody.class, compiled.schema().entries().get(variant).body());
    }

    /** The tuple half of the same rule: {@code elements} is a collection of {@code tuple_element} records. */
    @Test
    void anApplicationIsATupleElementLikeAnyOtherReference() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  t   => [text, box<text>]""");

        TupleBody tuple = assertInstanceOf(TupleBody.class, compiled.schema().entries().get("t").body());
        assertEquals(TypeRef.of("text"), tuple.elements().getFirst().elementType());
        String second = tuple.elements().get(1).elementType().name();
        assertTrue(second.startsWith("box_text_"), () -> "names the instantiation: " + second);

        TsonValue value = (TsonValue) compiled.get("t")
                .read(TestDocuments.document("[\"a\" { v: \"b\" }]"));
        assertNotNull(value);
    }

    /**
     * <b>The open case, which failed worse than the closed one.</b> With the variant refused, the choice was
     * never lifted, so holding {@code w}'s body handed the un-lifted {@code ChoiceRef} to the producer that
     * spells a {@code type_ref} -- a {@code ClassCastException} out of the resolver, where the two shapes it
     * accepts are {@code SimpleRef} and {@code GenericRef}. Lifting the choice is what keeps that producer's
     * input to the two shapes it documents.
     */
    @Test
    void anApplicationIsAChoiceVariantInsideATemplateToo() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  w   => <T> ( box<T> | int32 )
                  u   => w<text>""");

        ChoiceBody choice = assertInstanceOf(ChoiceBody.class, bodyOf(compiled, "u"));
        assertTrue(choice.variants().getFirst().name().startsWith("box_text_"), choice::toString);
    }

    /**
     * The property that says the fix is a fix rather than a new path: an application variant is the same
     * type as the bare reference to it, so §5.4's distinctness sees through the spelling. Two applications of
     * one template are one variant, exactly as two occurrences of one name are.
     */
    @Test
    void twoApplicationsOfOneTemplateAreOneVariant() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> compile("""
                          box => <T> { v: T }
                          u   => ( box<text> | box<text> )"""));

        assertTrue(thrown.getMessage().contains("twice"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("§5.4"), thrown.getMessage());
    }

    @Test
    void theSameFormLinksAsATopLevelDeclaration() {
        // §5.6: a declaration whose body is a fully-bound constructor application is a construction. The
        // desugar phase makes the field position above take this exact same path.
        assertNotNull(compile("""
                  entries => {text => text}
                  holder => { xs: entries }"""));
    }

    /**
     * The three routes to a container, side by side: sugar at a field position, sugar at a declaration
     * position, and the explicit {@code !} form for {@code set}, which has no sugar of its own and so is
     * reached the way any other constructor without one is -- a named declaration, since {@code !} forms stay
     * prohibited at field positions (§5.2).
     */
    @Test
    void everyRouteToAContainerLinks() {
        assertNotNull(compile("  holder => { xs: [text] }"));
        assertNotNull(compile("  ids => [text]"));
        assertNotNull(compile("""
                  text_set => !set { element_type: text }
                  holder => { xs: text_set }"""));
    }

    /**
     * {@code set} and {@code array} share a body shape, so the only thing distinguishing them is the defaults
     * {@code set}'s own vocabulary tightens (§5.7). Binding {@code !set { element_type: text }} through the
     * compiled reader applies those schema-composed defaults, so this needs no {@code set}-specific handling
     * anywhere -- worth pinning precisely because nothing names {@code set}.
     */
    @Test
    void aSetCarriesItsOwnTightenedDefaultsNotArrays() {
        ArrayBody asSet = assertInstanceOf(ArrayBody.class,
                compile("  xs => !set { element_type: text }").schema().entries().get("xs").body());
        assertTrue(asSet.unordered());
        assertTrue(asSet.uniqueItems());

        ArrayBody asArray = assertInstanceOf(ArrayBody.class,
                compile("  xs => [text]").schema().entries().get("xs").body());
        assertFalse(asArray.unordered());
        assertFalse(asArray.uniqueItems());
    }

    /**
     * §5.3's sized sugar, end to end. The size specifier binds {@code min_items}/{@code max_items} on the
     * {@code array} binding record directly -- there is no size template in between any more, so all three
     * array spellings land on the same shape, one bound apart
     * ({@link #everySpellingOfAnArrayDeclarationRecordsNoSupertypes}).
     */
    @Test
    void sizedSugarBindsItsBoundsOnAConstructionOfArray() {
        TsonCompiledSchema compiled = compile("""
                  tag_list => [text; 1..2]
                  holder => { tags: tag_list }""");

        TypeDefinition entry = compiled.schema().entries().get("tag_list");
        assertEquals(TypeKind.PRODUCT, entry.kind(), "the constructor's kind");
        assertEquals(List.of(), entry.parameters(), "closed -- §5.10");
        assertEquals(List.of(), entry.supertypes(),
                "empty: this is a construction of `array`, and a constructor is not a supertype");
        assertEquals(TypeRef.of("array"), entry.source().orElseThrow(), "the constructor the sugar names");

        // !array { element_type: text  min_items: 1  max_items: 2 } -- only the fields the form binds; the
        // vocabulary's own defaults (state/unordered/unique_items) stay out of the binding record (§5.6).
        ArrayBody body = assertInstanceOf(ArrayBody.class, entry.body());
        assertEquals(TypeRef.of("text"), body.elementType());
        assertEquals(Optional.of(BigInteger.ONE), body.minItems());
        assertEquals(Optional.of(BigInteger.TWO), body.maxItems());
    }

    /** The map tier takes the same specifier, binding the same two fields on {@code map} instead. */
    @Test
    void aSizedMapBindsTheSameTwoFields() {
        TypeDefinition entry = compile("  index => {text => text; 1..2}").schema().entries().get("index");

        assertEquals(TypeRef.of("map"), entry.source().orElseThrow());
        MapBody body = assertInstanceOf(MapBody.class, entry.body());
        assertEquals(Optional.of(BigInteger.ONE), body.minItems());
        assertEquals(Optional.of(BigInteger.TWO), body.maxItems());
    }

    /**
     * Every spelling of an array declaration agrees about the hierarchy: a bound is a constraint, not a
     * change of place. All of them close to a binding record headed by a constructor, and a constructor is not
     * something a value can have as its type -- so none records a supertype (§5.6: "no supertypes").
     */
    @Test
    void everySpellingOfAnArrayDeclarationRecordsNoSupertypes() {
        TsonCompiledSchema compiled = compile("""
                  id_list => [text]
                  tag_list => [text; 1..2]
                  triple => [text; 3]""");

        for (String name : List.of("id_list", "tag_list", "triple")) {
            TypeDefinition entry = compiled.schema().entries().get(name);
            assertInstanceOf(ArrayBody.class, entry.body(), name);
            assertEquals(List.of(), entry.supertypes(), name);
        }
    }

    /** And the bounds are live: the construction is a real array body, so the compiled reader enforces them. */
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
     * The map tier's bounds are live in the same way -- and {@code {}} counts against them. [TSON-DATA] §2.8
     * resolves an empty brace to "the empty container of that type", so at a map position it is a map with
     * zero entries, not a value exempt from the size rule. It used to pass silently while {@code max_items}
     * on the same declaration reported correctly, which is what made the gap look like a bound problem
     * rather than a shape one.
     */
    @Test
    void aSizedMapsBoundsAreEnforcedWhenReadingEmptyBracesIncluded() {
        TsonCompiledSchema compiled = compile("""
                  index => {text => text; 1..2}
                  holder => { entries: index }""");

        assertNotNull(compiled.get("holder")
                .read(TestDocuments.document("{ entries: { \"a\" => \"one\" } }")));
        assertTrue(assertThrows(TsonReadException.class,
                () -> compiled.get("holder")
                        .read(TestDocuments.document("{ entries: {} }"))).getMessage().contains("minimum 1"),
                "an empty brace at a map position is a map with no entries");
        assertTrue(assertThrows(TsonReadException.class,
                () -> compiled.get("holder").read(TestDocuments.document(
                        "{ entries: { \"a\" => \"1\"  \"b\" => \"2\"  \"c\" => \"3\" } }")))
                .getMessage().contains("maximum 2"));
    }

    /**
     * <code>{K =&gt; V?}</code> through the real bundled chain: the marker reaches the kernel's {@code map}
     * {@code state} field (issue #227), so the value may be the absent sentinel and the map's own type says
     * so. The peer of {@code [T?]}, and the end-to-end half of {@code SchemaDesugarerTest}'s binding check.
     */
    @Test
    void anOptionalMapValueResolvesToAMapBodyCarryingTheState() {
        TsonCompiledSchema compiled = compile("""
                  loose => {text => text?}
                  strict => {text => text}""");

        assertEquals(ElementState.OPTIONAL, ((MapBody) bodyOf(compiled, "loose")).state());
        assertEquals(ElementState.REQUIRED, ((MapBody) bodyOf(compiled, "strict")).state());
    }

    /**
     * And it is live at read time, which is the point of the field: the same document is valid under one
     * declaration and a {@code FIELD_REQUIRED} under the other. Before {@code map} carried a state, both
     * accepted it and no schema could say otherwise ({@code SPEC-FEEDBACK.md} #12).
     */
    @Test
    void anAbsentMapValueIsAcceptedOnlyWhereTheSchemaMarkedItOptional() {
        TsonCompiledSchema compiled = compile("""
                  loose => {text => text?}
                  strict => {text => text}
                  holder => { a: loose  b: strict }""");

        assertNotNull(compiled.get("holder")
                .read(TestDocuments.document("{ a: { \"k\" => _ }  b: { \"k\" => \"v\" } }")));
        assertTrue(assertThrows(TsonReadException.class, () -> compiled.get("holder")
                .read(TestDocuments.document("{ a: { \"k\" => _ }  b: { \"k\" => _ } }")))
                .getMessage().contains("is absent, but values are required"));
    }

    /**
     * §5.3's bound-coherence rule, reported where the author wrote the bounds: {@code min <= max}, checked at
     * schema load wherever both bounds are literal.
     *
     * <p>The rule is the container family's own ({@code ArrayBody}/{@code MapBody}), not the sugar's, which
     * is what makes this form and the {@code !array { ... }} body it denotes get the same answer --
     * {@code ContainerBoundCoherenceTest} is where every spelling is compared.
     */
    @Test
    void aSizedArrayWhoseBoundsCannotBeSatisfiedIsAResolverError() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> compile("  impossible => [text; 5..3]"));

        assertTrue(thrown.getMessage().contains("min_items 5 is above max_items 3"), thrown.getMessage());
    }

    /**
     * A container sugar form written over the enclosing template's own parameter: it lifts to an <b>open</b>
     * synthetic, and applying the template closes it into the concrete container it always described.
     */
    @Test
    void applyingATemplateWhoseBodyCarriesSugarClosesTheFormItHolds() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: [T] }
                  holder => { b: box<text> }""");

        RecordBody box = assertInstanceOf(RecordBody.class, fieldTypeEntry(compiled, "holder", "b").body());
        ArrayBody array = assertInstanceOf(ArrayBody.class, compiled.schema().entries()
                .get(box.fields().get(0).type().name()).body());

        assertEquals(TypeRef.of("text"), array.elementType());
    }

    /**
     * §8.2's one-entry-per-form rule, across the two channels that produce one. {@code [text]} written
     * directly lifts at desugar; {@code [T]} closed with {@code T := text} arrives from materialisation --
     * and they are the same type, so they must be the same entry. Both are named by one function of one
     * binding record, which is what makes it so.
     */
    @Test
    void aFormClosedFromATemplateIsTheSameEntryADirectOneProduces() {
        TsonCompiledSchema compiled = compile("""
                  box    => <T> { v: [T] }
                  holder => { b: box<text>  direct: [text] }""");

        RecordBody box = assertInstanceOf(RecordBody.class, fieldTypeEntry(compiled, "holder", "b").body());
        RecordBody holder = (RecordBody) compiled.schema().entries().get("holder").body();

        assertEquals(holder.fields().get(1).type().name(), box.fields().get(0).type().name());
        assertEquals(1, compiled.schema().entries().keySet().stream()
                .filter(n -> n.startsWith("array_text_")).count(),
                () -> "one array_text entry, shared: " + compiled.schema().entries().keySet());
    }

    /**
     * The sugar form Revision 33 had no open representation for, and now the ordinary case. A {@code
     * template_argument} was {@code param | value | type_ref} with no collection case (§8.1), so a parameter
     * inside {@code choice}'s {@code variants} -- or {@code tuple}'s {@code elements} -- had nowhere to sit,
     * and the declaration writing one was refused. A held body is not read against that vocabulary at all
     * until materialisation substitutes, so the parameter is a token inside an array and lifts like any
     * other form ({@code SPEC-FEEDBACK.md} #5).
     */
    @Test
    void aParameterInsideACollectionValuedSlotLiftsLikeAnyOther() {
        for (String body : List.of("(T | text)", "[T, text]")) {
            TsonCompiledSchema compiled = assertDoesNotThrow(
                    () -> compile("  odd => <T> { v: %s }\n  use => odd<int32>".formatted(body)), body);

            // The closure is a real body of the constructor the sugar names, not a deferred anything.
            String closure = compiled.schema().entries().keySet().stream()
                    .filter(n -> n.startsWith("odd_int32_")).findFirst().orElseThrow();
            RecordBody closed = assertInstanceOf(RecordBody.class,
                    compiled.schema().entries().get(closure).body(), body);
            Top variant = compiled.schema().entries().get(closed.fields().getFirst().type().name()).body();
            assertTrue(variant instanceof ChoiceBody || variant instanceof TupleBody,
                    () -> body + " closed to " + variant);
        }
    }

    /**
     * And a container constructor's own name is no longer reachable at a head, however the {@code !!meta}
     * chain is arranged: {@code map} resolves in the type-name namespace, finds nothing, and is an ordinary
     * unresolved reference. This is the migration the change to type-name-only head resolution forces, and the
     * error an author writing {@code map<text, text>} out of habit will see.
     */
    @Test
    void aContainerConstructorsNameAtAHeadIsAnUnresolvedReference() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> compile("  holder => { entries: map<text, text> }"));

        assertTrue(thrown.getMessage().contains("unresolved reference 'map'"), thrown.getMessage());
    }
}
