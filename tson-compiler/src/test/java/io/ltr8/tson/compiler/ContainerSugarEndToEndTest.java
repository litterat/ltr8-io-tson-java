package io.ltr8.tson.compiler;

import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.meta.EntryKind;
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
 * <p>Sugar and the {@code !} form are the <b>only</b> two ways to build a container. A generic-application head
 * resolves in the schema's one namespace, where the kernel's {@code map} is a closed constructor taking no type
 * arguments -- so {@code map<text, text>} is refused and <code>{text =&gt; text}</code> is the spelling. What makes
 * every form work uniformly is that {@code SchemaDesugarer} rewrites each into a real {@code !C value} declaration
 * before resolution, off one fixed table.
 */
class ContainerSugarEndToEndTest {

    private static final String ID = "https://example.test/container-sugar.tn";

    /** Resolves, links and compiles a user schema whose body is {@code declarations}; throws whatever the pipeline throws. */
    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/container-sugar.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                %s
                }
                """.formatted(declarations);
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    /** The entry a field's type names, which every fixture here reaches the injected declaration through. */
    private static Top fieldTypeEntry(TsonCompiledSchema compiled, String record, String field) {
        RecordBody body = (RecordBody) compiled.schema().entries().get(record);
        String name = body.fields().entrySet().stream().filter(f -> f.getKey().equals(field))
                .map(java.util.Map.Entry::getValue).findFirst().orElseThrow()
                .type().name();
        return compiled.schema().entries().get(name);
    }

    /**
     * An entry's body with every {@code ALIAS} hop followed. How many hops an alias costs is this
     * resolver's own bookkeeping (§8.3 flattens uses, not entries), so a fixture asserting on the body a name
     * denotes should not also be asserting on the length of the chain to it.
     */
    private static Top bodyOf(TsonCompiledSchema compiled, String name) {
        Top body = compiled.schema().entries().get(name);
        while (body instanceof Alias alias) {
            body = compiled.schema().entries().get(alias.target().name());
        }
        return body;
    }

    /** The whole arc in one assertion: a map-typed field resolves, links, compiles, and reads real data. */
    @Test
    void aMapTypedFieldReadsRealData() {
        TsonCompiledSchema compiled = compile("  holder => { entries: {text => text} }");

        MapBody body = assertInstanceOf(MapBody.class, fieldTypeEntry(compiled, "holder", "entries"));
        assertEquals(TypeRef.of("text"), body.keyType());
        assertEquals(TypeRef.of("text"), body.valueType());

        TsonValue value = (TsonValue) compiled.get("holder")
                .read(TestDocuments.document("{ entries: { \"a\" => \"one\"  \"b\" => \"two\" } }"));
        assertNotNull(value);
    }

    /**
     * <b>A collection-valued slot holds an application like every other {@code type_kind}-typed slot.</b>
     * {@code variants} is a {@code [type_kind]}, so a §5.10 application standing in one is spelled as its
     * reference and rewritten to the entry it denotes one pass later -- the same route a field
     * type, an {@code element_type} and a map's {@code key_type}/{@code value_type} already took. Refusing the
     * variant in {@code choiceBinding} instead would leave the <em>whole</em> choice unlifted, and an unlifted
     * sugar form at {@code DefinitionResolver} is a broken invariant, never a verdict on the author's schema.
     */
    @Test
    void anApplicationIsAChoiceVariantLikeAnyOtherReference() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  u   => ( box<text> | int32 )""");

        ChoiceBody choice = assertInstanceOf(ChoiceBody.class, compiled.schema().entries().get("u"));
        String variant = choice.variants().getFirst().name();
        assertTrue(variant.startsWith("record_v_text_"), () -> "names the expansion, not 'box': " + variant);
        assertEquals(List.of(), choice.variants().getFirst().arguments(),
                "and names it as a closed reference, the application having been rewritten");
        assertInstanceOf(RecordBody.class, compiled.schema().entries().get(variant));
    }

    /** The tuple half of the same rule: {@code elements} is a collection of {@code tuple_element} records. */
    @Test
    void anApplicationIsATupleElementLikeAnyOtherReference() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  t   => [text, box<text>]""");

        TupleBody tuple = assertInstanceOf(TupleBody.class, compiled.schema().entries().get("t"));
        assertEquals(TypeRef.of("text"), tuple.elements().getFirst().elementType());
        String second = tuple.elements().get(1).elementType().name();
        assertTrue(second.startsWith("record_v_text_"), () -> "names the expansion: " + second);

        TsonValue value = (TsonValue) compiled.get("t")
                .read(TestDocuments.document("[\"a\" { v: \"b\" }]"));
        assertNotNull(value);
    }

    /**
     * <b>The open case, which failed worse than the closed one.</b> With the variant refused, the choice was
     * never lifted, so holding {@code w}'s body handed the un-lifted {@code ChoiceRef} to the producer that
     * spells a reference -- a {@code ClassCastException} out of the resolver, where the two shapes it
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
        assertTrue(choice.variants().getFirst().name().startsWith("record_v_text_"), choice::toString);
    }

    /**
     * The property that says the fix is a fix rather than a new path: an application variant is the same
     * type as the bare reference to it, so §5.4's distinctness sees through the spelling. Two applications of
     * one template are one variant, exactly as two occurrences of one name are.
     */
    @Test
    void twoApplicationsOfOneTemplateAreOneVariant() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
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
                  text_set => !set_type { element_type: text }
                  holder => { xs: text_set }"""));
    }

    /**
     * {@code set} and {@code array} share a body shape, so the only thing distinguishing them is the defaults
     * {@code set}'s own vocabulary tightens (§5.7). Binding {@code !set_type { element_type: text }} through the
     * compiled reader applies those schema-composed defaults, so this needs no {@code set}-specific handling
     * anywhere -- worth pinning precisely because nothing names {@code set}.
     */
    @Test
    void aSetCarriesItsOwnTightenedDefaultsNotArrays() {
        ArrayBody asSet = assertInstanceOf(ArrayBody.class,
                compile("  xs => !set_type { element_type: text }").schema().entries().get("xs"));
        assertFalse(asSet.ordered());
        assertTrue(asSet.uniqueItems());

        ArrayBody asArray = assertInstanceOf(ArrayBody.class,
                compile("  xs => [text]").schema().entries().get("xs"));
        assertTrue(asArray.ordered());
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

        Top entry = compiled.schema().entries().get("tag_list");
        assertEquals(EntryKind.PRODUCT, EntryKind.of(entry, n -> true), "the constructor's kind");
        assertEquals(List.of(), OpenEntry.parameterNames(entry), "closed -- §5.10");
        assertEquals(List.of(), TypeChain.supertypesOf(entry),
                "empty: this is a construction of `array`, and a constructor is not a supertype");
        assertEquals(Optional.of("array"), compiled.schema().constructorOf("tag_list"),
                "the constructor the sugar names");

        // !array { element_type: text  min_items: 1  max_items: 2 } -- only the fields the form binds; the
        // vocabulary's own defaults (state/ordered/unique_items) stay out of the binding record (§5.6).
        ArrayBody body = assertInstanceOf(ArrayBody.class, entry);
        assertEquals(TypeRef.of("text"), body.elementType());
        assertEquals(Optional.of(BigInteger.ONE), body.minItems());
        assertEquals(Optional.of(BigInteger.TWO), body.maxItems());
    }

    /** The map tier takes the same specifier, binding the same two fields on {@code map} instead. */
    @Test
    void aSizedMapBindsTheSameTwoFields() {
        TsonCompiledSchema compiled = compile("  index => {text => text; 1..2}");
        Top entry = compiled.schema().entries().get("index");

        assertEquals(Optional.of("map"), compiled.schema().constructorOf("index"));
        MapBody body = assertInstanceOf(MapBody.class, entry);
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
            Top entry = compiled.schema().entries().get(name);
            assertInstanceOf(ArrayBody.class, entry, name);
            assertEquals(List.of(), TypeChain.supertypesOf(entry), name);
        }
    }

    /** And the bounds are live: the construction is a real array body, so the compiled reader enforces them. */
    @Test
    void aSizedArraysBoundsAreEnforcedWhenReading() {
        TsonCompiledSchema compiled = compile("""
                  tag_list => [text; 1..2]
                  holder => { tags: tag_list }""");

        assertNotNull(compiled.get("holder").read(TestDocuments.document("{ tags: [\"a\" \"b\"] }")));
        assertTrue(assertThrows(ReadException.class,
                () -> compiled.get("holder")
                        .read(TestDocuments.document("{ tags: [] }"))).getMessage().contains("minimum 1"));
        assertTrue(assertThrows(ReadException.class,
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
        assertTrue(assertThrows(ReadException.class,
                () -> compiled.get("holder")
                        .read(TestDocuments.document("{ entries: {} }"))).getMessage().contains("minimum 1"),
                "an empty brace at a map position is a map with no entries");
        assertTrue(assertThrows(ReadException.class,
                () -> compiled.get("holder").read(TestDocuments.document(
                        "{ entries: { \"a\" => \"1\"  \"b\" => \"2\"  \"c\" => \"3\" } }")))
                .getMessage().contains("maximum 2"));
    }

    /**
     * <code>{K =&gt; V?}</code> through the real bundled chain: the marker reaches the kernel's {@code map}
     * {@code state} field (issue #227), so the value may be the void sentinel and the map's own type says
     * so. The peer of {@code [T?]}, and the end-to-end half of {@code SchemaDesugarerTest}'s binding check.
     */
    @Test
    void anOptionalMapValueResolvesToAMapBodyCarryingTheState() {
        TsonCompiledSchema compiled = compile("""
                  loose => {text => text?}
                  strict => {text => text}""");

        assertTrue(((MapBody) bodyOf(compiled, "loose")).voidable());
        assertFalse(((MapBody) bodyOf(compiled, "strict")).voidable());
    }

    /**
     * And it is live at read time, which is the point of the field: the same document is valid under one
     * declaration and a {@code FIELD_REQUIRED} under the other. Before {@code map} carried a state, both
     * accepted it and no schema could say otherwise; §5.3 and §7.6 now make the permission conditional on
     * the map's own value state.
     */
    @Test
    void aVoidMapValueIsAcceptedOnlyWhereTheSchemaMarkedItVoidable() {
        TsonCompiledSchema compiled = compile("""
                  loose => {text => text?}
                  strict => {text => text}
                  holder => { a: loose  b: strict }""");

        assertNotNull(compiled.get("holder")
                .read(TestDocuments.document("{ a: { \"k\" => _ }  b: { \"k\" => \"v\" } }")));
        assertTrue(assertThrows(ReadException.class, () -> compiled.get("holder")
                .read(TestDocuments.document("{ a: { \"k\" => _ }  b: { \"k\" => _ } }")))
                .getMessage().contains("is absent, but values are required"));
    }

    /**
     * A declared field name is a value of its record's field name type, an identifier by default
     * ([TSON-SCHEMA] §5.2): the grammar admits any single-line token, and the linker refuses a name the record's
     * type does not admit, at schema load.
     */
    @Test
    void aDeclaredFieldNameMustBeAnIdentifierByDefault() {
        for (String[] c : new String[][] {
                {"\"first name\"", "U+0020 at index 5 cannot appear in an identifier"},
                {"a.b", "U+002E at index 1 cannot appear in an identifier"},
                {"42", "cannot start an identifier -- an identifier never begins with a digit or a sign"},
        }) {
            io.ltr8.tson.base.SchemaValidationException thrown = assertThrows(
                    io.ltr8.tson.base.SchemaValidationException.class,
                    () -> compile("  ok => { " + c[0] + ": text }"), c[0]);
            assertTrue(thrown.getMessage().contains("is not an identifier"), thrown.getMessage());
            assertTrue(!thrown.getMessage().contains("name_type"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains(c[1]), thrown.getMessage());
        }
    }

    /** And the ordinary shapes keep working, including the one extension `Continue` retains. */
    @Test
    void ordinaryFieldNamesAreUnaffected() {
        assertNotNull(compile("  ok => { my-field: text  a_b: text  日本語: text }"));
    }

    /**
     * `enum.members` is typed through `enum_set`, so members reach the contract by the other route — the
     * constructor body read back against the kernel's own vocabulary — and pick up `min_items: 1` and the
     * set's uniqueness with it. That each member is an identifier is `enum`'s `type`, checked at linking.
     */
    @Test
    void enumMembersAreIdentifiersAndAtLeastOneAndUnique() {
        assertNotNull(compile("  e => !enum [OPEN DONE]"));

        assertTrue(assertThrows(SchemaValidationException.class, () -> compile("  e => !enum []"))
                .getMessage().contains("fewer than the minimum 1"));
        assertTrue(assertThrows(SchemaValidationException.class,
                () -> compile("  e => !enum [\"in progress\" DONE]"))
                .getMessage().contains("U+0020 at index 2 cannot appear in an identifier"));
        assertTrue(assertThrows(SchemaValidationException.class, () -> compile("  e => !enum [OPEN OPEN]"))
                .getMessage().contains("requires unique elements"));
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
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
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

        RecordBody box = assertInstanceOf(RecordBody.class, fieldTypeEntry(compiled, "holder", "b"));
        ArrayBody array = assertInstanceOf(ArrayBody.class, compiled.schema().entries()
                .get(box.fields().values().stream().toList().get(0).type().name()));

        assertEquals(TypeRef.of("text"), array.elementType());
    }

    /**
     * §8.2's one-entry-per-form rule, across the two channels that produce one. {@code [uuid]} written
     * directly lifts at desugar; {@code [T]} closed with {@code T := uuid} arrives from materialisation --
     * and they are the same type, so they must be the same entry. Both are named by one function of one
     * binding record, which is what makes it so.
     */
    @Test
    void aFormClosedFromATemplateIsTheSameEntryADirectOneProduces() {
        TsonCompiledSchema compiled = compile("""
                  box    => <T> { v: [T] }
                  holder => { b: box<uuid>  direct: [uuid] }""");

        RecordBody box = assertInstanceOf(RecordBody.class, fieldTypeEntry(compiled, "holder", "b"));
        RecordBody holder = (RecordBody) compiled.schema().entries().get("holder");

        assertEquals(holder.fields().values().stream().toList().get(1).type().name(),
                box.fields().values().stream().toList().get(0).type().name());
        assertEquals(1, compiled.schema().entries().keySet().stream()
                .filter(n -> n.startsWith("array_uuid_")).count(),
                () -> "one array_uuid entry, shared: " + compiled.schema().entries().keySet());
    }

    /**
     * The same rule where the lifted form's element is itself an <b>application</b> ([TSON-SCHEMA] §8.2's own
     * example, {@code [box<text>]} written directly against {@code [box<T>]} closed with {@code T := text}).
     *
     * <p>This is the case the two lift channels cannot name alike on their own. Both derive a name from the
     * binding record with every inner form reduced to its entry name -- {@code SchemaDesugarer} gets there by
     * lifting innermost-first, {@code TemplateMaterialiser} by closing applications before it names -- but an
     * application has no entry until materialisation, so the desugar channel names an unreduced record and
     * the two forms land apart. §8.2 requires the merge pass that settles it: "identity settles after Pass
     * 2 ... eagerly-lifted synthetics that become structurally identical under resolution merge into one
     * entry".
     */
    @Test
    void aFormWhoseElementIsAnApplicationIsAlsoOneEntry() {
        TsonCompiledSchema compiled = compile("""
                  box    => <T> { v: T }
                  outer  => <T> { a: [box<T>] }
                  use    => outer<text>
                  direct => { d: [box<text>] }""");

        String direct = ((RecordBody) compiled.schema().entries().get("direct"))
                .fields().values().iterator().next().type().name();
        String closed = ((RecordBody) bodyOf(compiled, "use")).fields().values().iterator().next().type().name();

        assertEquals(closed, direct, () -> "one entry for one form: " + compiled.schema().entries().keySet());
        // The closed form only: `array_box_p0_...` is the template's own open synthetic, which stays an entry
        // of its own -- §8.2 gives open synthetics a separate identity rule and nothing merges them.
        assertEquals(1, compiled.schema().entries().keySet().stream()
                .filter(n -> n.startsWith("array_record_v_text")).count(),
                () -> "one closed array of the expanded box, shared: " + compiled.schema().entries().keySet());
    }

    /**
     * And the name they agree on is the one derived from the <b>reduced</b> record -- the surviving entry is
     * named for the entry its element became, not for the application the author wrote. That is what makes
     * the name a function of the resolved form alone, so two schemas reaching this form by different
     * spellings agree on it (§8.2's determinism SHOULD, and what lets the import merge unify rather than
     * collide).
     */
    @Test
    void theSurvivingNameIsDerivedFromTheElementsOwnEntry() {
        TsonCompiledSchema compiled = compile("""
                  box    => <T> { v: T }
                  direct => { d: [box<text>] }""");

        String array = ((RecordBody) compiled.schema().entries().get("direct"))
                .fields().values().iterator().next().type().name();
        String element = ((ArrayBody) compiled.schema().entries().get(array)).elementType().name();

        assertTrue(array.contains(element),
                () -> "'" + array + "' should be derived from its element entry '" + element + "'");
    }

    /**
     * <b>Collection-valued slots are parameterizable</b> (§5.10). A held body is not read against the
     * constructor's vocabulary at all until materialisation substitutes, so a parameter inside {@code
     * choice}'s {@code variants} -- or {@code tuple}'s {@code elements} -- is a token inside an array and
     * the form lifts like any other.
     */
    @Test
    void aParameterInsideACollectionValuedSlotLiftsLikeAnyOther() {
        for (String body : List.of("(T | text)", "[T, text]")) {
            TsonCompiledSchema compiled = assertDoesNotThrow(
                    () -> compile("  odd => <T> { v: %s }\n  use => odd<int32>".formatted(body)), body);

            // The closure is a real body of the constructor the sugar names, not a deferred anything -- and
            // it is `use` itself, a declaration naming an application being that instantiation (#15).
            RecordBody closed = assertInstanceOf(RecordBody.class,
                    compiled.schema().entries().get("use"), body);
            Top variant = compiled.schema().entries().get(closed.fields().values().iterator().next().type().name());
            assertTrue(variant instanceof ChoiceBody || variant instanceof TupleBody,
                    () -> body + " closed to " + variant);
        }
    }

    /**
     * A container constructor's own name at an application head resolves -- the kernel's {@code map} is in every
     * closure reaching core -- and is refused as what it is: a constructor, closed, taking no type arguments. An
     * author writing {@code map<text, text>} out of habit is told to drop the arguments, and the sugar
     * {@code {text => text}} is the spelling.
     */
    @Test
    void aContainerConstructorsNameAtAHeadTakesNoArguments() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> compile("  holder => { entries: map<text, text> }"));

        assertTrue(thrown.getMessage().contains("'map' declares no type parameters"), thrown.getMessage());
    }
}
