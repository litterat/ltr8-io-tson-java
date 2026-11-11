package io.ltr8.tson.compiler;

import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
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
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.tree.TsonValue;

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
 * The container sugar forms in an ordinary user schema -- one importing {@code meta.tn} and
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
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
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
        TsonCompiledVocabularyRegistry core =
                TsonCompiledVocabularyRegistry.withStandardLibrary(SchemaModelNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    /** The type field {@code field} of {@code record} holds: the entry a name names, or a type written in place. */
    private static Top fieldTypeEntry(TsonCompiledSchema compiled, String record, String field) {
        RecordBody body = (RecordBody) compiled.schema().entries().get(record);
        io.ltr8.tson.schema.meta.TypeExpr type = body.fields().get(field).typeExpr();
        return switch (type) {
            case io.ltr8.tson.schema.meta.TypeInline inline -> inline.of();
            case TypeRef name -> compiled.schema().entries().get(name.name());
        };
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
     * <b>A choice's variant holds an application as every type slot does</b> ([TSON-SCHEMA] §5.10): {@code
     * box<text>} closes in place to its record, with no name, and the choice reads a brace value against it.
     */
    @Test
    void anApplicationIsAChoiceVariantLikeAnyOtherReference() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  u   => ( box<text> | int32 )""");

        ChoiceBody choice = assertInstanceOf(ChoiceBody.class, compiled.schema().entries().get("u"));
        TypeInline variant = assertInstanceOf(TypeInline.class, choice.variants().getFirst());
        RecordBody record = assertInstanceOf(RecordBody.class, variant.of());
        assertEquals(TypeRef.of("text"), record.fields().get("v").type());
        assertNotNull(compiled.get("u").read(TestDocuments.document("{ v: \"x\" }")));
        assertNotNull(compiled.get("u").read(TestDocuments.document("7")));
    }

    /** The tuple half of the same rule: an element's {@code element_type} is a type slot. */
    @Test
    void anApplicationIsATupleElementLikeAnyOtherReference() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  t   => [text, box<text>]""");

        TupleBody tuple = assertInstanceOf(TupleBody.class, compiled.schema().entries().get("t"));
        assertEquals(TypeRef.of("text"), tuple.elements().getFirst().elementType());
        TypeInline second = assertInstanceOf(TypeInline.class, tuple.elements().get(1).elementType());
        assertInstanceOf(RecordBody.class, second.of());

        TsonValue value = (TsonValue) compiled.get("t")
                .read(TestDocuments.document("[\"a\" { v: \"b\" }]"));
        assertNotNull(value);
    }

    /**
     * <b>Over a parameter, a choice is refused</b> ([TSON-SCHEMA] §5.10): a template's body is a record body, and
     * inside one a choice over a parameter is no reference -- an untagged sum over a parameter cannot be judged
     * disjoint until it is applied. A closed choice holding an application is admitted, above.
     */
    @Test
    void aChoiceOverAParameterIsRefused() {
        SchemaValidationException body = assertThrows(SchemaValidationException.class, () -> compile("""
                  box => <T> { v: T }
                  w   => <T> ( box<T> | int32 )"""));
        SchemaValidationException field = assertThrows(SchemaValidationException.class, () -> compile("""
                  box => <T> { v: T }
                  w   => <T> { c: ( box<T> | int32 ) }"""));

        assertTrue(body.getMessage().contains("a template's body is a record body"), body.getMessage());
        assertTrue(field.getMessage().contains("stands over the parameter 'T' and is no reference"),
                field.getMessage());
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
                          box      => <T> { v: T }
                          box_text => box<text>
                          u        => ( box_text | box_text )"""));

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
                  text_set => !meta.set_type { element_type: text }
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
                compile("  xs => !meta.set_type { element_type: text }").schema().entries().get("xs"));
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
        assertEquals(Optional.of("meta.array"), compiled.schema().constructorOf("tag_list"),
                "the constructor the sugar names");

        // !array { element_type: text  min_items: 1  max_items: 2 } -- only the fields the form binds; the
        // vocabulary's own defaults (state/ordered/unique_items) stay out of the binding record (§5.6).
        ArrayBody body = assertInstanceOf(ArrayBody.class, entry);
        assertEquals(TypeRef.of("text"), body.elementType());
        assertEquals(Optional.of(1L), body.minItems());
        assertEquals(Optional.of(2L), body.maxItems());
    }

    /** The map tier takes the same specifier, binding the same two fields on {@code map} instead. */
    @Test
    void aSizedMapBindsTheSameTwoFields() {
        TsonCompiledSchema compiled = compile("  index => {text => text; 1..2}");
        Top entry = compiled.schema().entries().get("index");

        assertEquals(Optional.of("meta.map"), compiled.schema().constructorOf("index"));
        MapBody body = assertInstanceOf(MapBody.class, entry);
        assertEquals(Optional.of(1L), body.minItems());
        assertEquals(Optional.of(2L), body.maxItems());
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
     * `enum.members` is typed through `meta.enum_set`, so members reach the contract by the other route — the
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
     * A container sugar form written over the enclosing template's own parameter is held as the application it
     * spells, and applying the template closes it to the container it always described, in place.
     */
    @Test
    void applyingATemplateWhoseBodyCarriesSugarClosesTheFormItHolds() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: [T] }
                  holder => { b: box<text> }""");

        RecordBody box = assertInstanceOf(RecordBody.class, fieldTypeEntry(compiled, "holder", "b"));
        TypeInline v = assertInstanceOf(TypeInline.class, box.fields().get("v").type());
        ArrayBody array = assertInstanceOf(ArrayBody.class, v.of());

        assertEquals(TypeRef.of("text"), array.elementType());
    }

    /**
     * §8.2's structural identity, across the two routes that produce one form: {@code [uuid]} written directly and
     * {@code [T]} closed with {@code T := uuid} are one type, each written in place with no entry.
     */
    @Test
    void aFormClosedFromATemplateIsTheSameTypeADirectOneIs() {
        TsonCompiledSchema compiled = compile("""
                  box    => <T> { v: [T] }
                  holder => { b: box<uuid>  direct: [uuid] }""");

        RecordBody box = assertInstanceOf(RecordBody.class, fieldTypeEntry(compiled, "holder", "b"));
        RecordBody holder = (RecordBody) compiled.schema().entries().get("holder");

        assertEquals(holder.fields().get("direct").type(), box.fields().get("v").type());
    }

    /**
     * The same where the form's element is itself an <b>application</b>: {@code [box<text>]} written directly and
     * {@code [box<T>]} closed with {@code T := text} hold equal records in place, so they are one type.
     */
    @Test
    void aFormWhoseElementIsAnApplicationIsAlsoOneType() {
        TsonCompiledSchema compiled = compile("""
                  box    => <T> { v: T }
                  outer  => <T> { a: [box<T>] }
                  use    => outer<text>
                  direct => { d: [box<text>] }""");

        assertEquals(((RecordBody) compiled.schema().entries().get("direct")).fields().get("d").type(),
                ((RecordBody) bodyOf(compiled, "use")).fields().get("a").type());
    }

    /**
     * <b>A collection-valued slot holds no parameter</b> (§5.10): a choice's variants and a tuple's elements are a
     * list, not reference fields, so a choice or a tuple over a parameter is no reference and is refused at the
     * declaration, naming the form.
     */
    @Test
    void aParameterInsideACollectionValuedSlotIsRefused() {
        for (String body : List.of("(T | text)", "[T, text]")) {
            SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                    () -> compile("  odd => <T> { v: %s }\n  use => odd<int32>".formatted(body)), body);
            assertTrue(thrown.getMessage().contains("stands over the parameter 'T' and is no reference"),
                    thrown.getMessage());
        }
    }

    /**
     * A container constructor's own name at an application head resolves -- the kernel's {@code meta.map} is in
     * every closure reaching core -- and constructs ([TSON-SCHEMA] §5.3's construction sugar): {@code
     * meta.map<text, text>} is {@code !meta.map { key_type: text  value_type: text }}, the construction the sugar
     * {@code {text => text}} is spelled for, and both name one entry.
     */
    @Test
    void aContainerConstructorsNameAtAHeadConstructs() {
        TsonCompiledSchema compiled = compile("  holder => { entries: meta.map<text, text>  sugar: {text => text} }");

        RecordBody holder = assertInstanceOf(RecordBody.class, compiled.schema().entries().get("holder"));
        assertEquals(holder.fields().get("sugar").type(), holder.fields().get("entries").type());
        MapBody map = assertInstanceOf(MapBody.class, fieldTypeEntry(compiled, "holder", "entries"));
        assertEquals("text", map.keyType().name());
        assertEquals("text", map.valueType().text());
    }
}
