package io.ltr8.tson;

import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tag at a choice names a variant, or a type that IS-A exactly one variant, and the value is read at that variant
 * as at a position typed by it ([TSON-SCHEMA] §5.4, §7.2).
 */
class ChoiceSubtypeTagTest {

    static final String ID = "https://example.test/choice-subtype-tag.tn";

    static final String SCHEMA = """
            !!id:"https://example.test/choice-subtype-tag.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              shape  => abstract { name: text }
              round  => abstract shape & { r: int32 }
              disc   => round & { filled: boolean }
              circle => shape & { r: int32 }
              square => shape & { side: int32 }
              base   => { id: text }
              sub    => base & { extra: text }
              subsub => sub & { more: text }
              small  => !int32 ^ { max: 10 }
              a      => { x?: text }
              b      => { y?: text }
              both   => a & b & { }
              pet    => abstract { kind: text =?  name: text }
              dog    => pet & { kind?: = "dog" }
              cat    => pet & { kind?: = "cat" }
              shape_alias => shape
              item    => ( text | shape )
              thing   => ( text | base )
              named   => ( base | sub )
              pair    => ( a | b )
              num     => ( int32 | text )
              pets    => ( text | pet )
              aliased => ( text | shape_alias )
              h => { i?: item  t?: thing  n?: named  p?: pair  u?: num  q?: pets  al?: aliased
                     s?: shape  w?: int32  is?: [item]  im?: { text => item } }
              tag     => { word: text }
              figure  => ( shape | tag )
              hb => { i: figure  s: shape }
            }
            """;

    static final Tson TSON = Tson.of(ProcessorConfig.defaults()
            .withSchemaAccess(SchemaAccess.of((SchemaSource) uri -> SCHEMA)));

    static List<Diagnostic> validate(String body) {
        return TSON.validate("!!import:\"" + ID + "\"\n!h " + body);
    }

    static TsonValue read(String body) {
        return TSON.treeReader().read("!!import:\"" + ID + "\"\n!h " + body);
    }

    static Diagnostic refusal(String body) {
        List<Diagnostic> problems = validate(body);
        assertEquals(1, problems.size(), problems::toString);
        return problems.getFirst();
    }

    static void valid(String body) {
        assertEquals(List.of(), validate(body).stream().map(Diagnostic::message).toList(), body);
    }

    // ── a variant's subtype ──

    @Test
    void aSubtypeOfAnAbstractVariantSelectsItAndReadsAsItself() {
        TsonValue value = read("{ i: !circle { name: \"a\"  r: 1 } }").get("i");

        assertEquals(Optional.of("circle"), value.typeRef());
        assertEquals(1, value.get("r").asInt().orElseThrow());
    }

    @Test
    void aSubtypeOfAConcreteVariantSelectsItAndReadsAsItself() {
        TsonValue value = read("{ t: !sub { id: \"x\"  extra: \"y\" } }").get("t");

        assertEquals(Optional.of("sub"), value.typeRef());
        assertEquals(Optional.of("y"), value.get("extra").asString());
    }

    @Test
    void aMemberTwoLevelsBelowAnAbstractVariantSelectsIt() {
        assertEquals(Optional.of("disc"),
                read("{ i: !disc { name: \"a\"  r: 1  filled: true } }").get("i").typeRef());
    }

    /** [TSON-SCHEMA] §5.2: an abstract type has no values, at a choice as at a field, though it IS-A a variant. */
    @Test
    void anAbstractSubtypeOfAVariantHasNoValues() {
        Diagnostic refused = refusal("{ i: !round { name: \"a\"  r: 1 } }");

        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused.code());
        assertTrue(refused.message().contains("'round' selects nothing"), refused.message());
    }

    @Test
    void aSubtypeOfAVariantThroughAnAliasSelectsIt() {
        assertEquals(Optional.of("circle"), read("{ al: !circle { name: \"a\"  r: 1 } }").get("al").typeRef());
    }

    /** [TSON-SCHEMA] §5.4: an atom refinement IS-A its base, and validates as itself. */
    @Test
    void anAtomRefinementSelectsItsBaseAndValidatesAsItself() {
        assertEquals(Optional.of("small"), read("{ u: !small 5 }").get("u").typeRef());

        Diagnostic refused = refusal("{ u: !small 50 }");
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, refused.code());
        assertTrue(refused.message().contains("'small'"), refused.message());
    }

    /** The value validates against the tag's type in full, its own fields included. */
    @Test
    void aSubtypeTaggedValueIsHeldToTheSubtypesOwnFields() {
        Diagnostic refused = refusal("{ i: !circle { name: \"a\" } }");

        assertEquals(Diagnostic.Code.FIELD_REQUIRED, refused.code());
        assertEquals(Optional.of("/i/r"), refused.path());
    }

    @Test
    void aChoiceAsAnArrayElementAndAMapValueTakesASubtypeTag() {
        valid("{ is: [ !circle { name: \"a\"  r: 1 }  !square { name: \"b\"  side: 2 }  \"t\" ] }");
        valid("{ im: { k => !disc { name: \"a\"  r: 1  filled: false } } }");
        assertEquals(Optional.of("/is/1/side"), refusal("{ is: [ \"t\"  !square { name: \"b\" } ] }").path());
    }

    // ── which variant ──

    /** A variant the tag names is selected before one its type only IS-A. */
    @Test
    void aVariantTheTagNamesIsSelectedBeforeOneItIsA() {
        assertEquals(Optional.of("sub"), read("{ n: !sub { id: \"x\"  extra: \"y\" } }").get("n").typeRef());
        assertEquals(Optional.of("base"), read("{ n: !base { id: \"x\" } }").get("n").typeRef());
    }

    @Test
    void aTagWhoseTypeIsATwoVariantsAndNamesNeitherSelectsNone() {
        Diagnostic refused = refusal("{ p: !both { } }");

        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused.code());
        assertEquals(Optional.of("/p"), refused.path());
        assertTrue(refused.message().contains("[a, b]"), refused.message());

        Diagnostic deeper = refusal("{ n: !subsub { id: \"x\"  extra: \"y\"  more: \"z\" } }");
        assertTrue(deeper.message().contains("[base, sub]"), deeper.message());
    }

    /** The choice is not refused at its declaration for a type IS-A two of its variants: the schema loads. */
    @Test
    void aChoiceWhoseVariantsShareASubtypeLoads() {
        assertEquals(List.of(), Tson.standard().validateSchema(SCHEMA));
    }

    @Test
    void aTagNamingATypeIsANoVariantIsRefused() {
        Diagnostic refused = refusal("{ i: !base { id: \"x\" } }");

        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused.code());
        assertTrue(refused.message().contains("or a type that IS-A exactly one"), refused.message());
    }

    // ── a tag naming the abstract variant, and an untagged value ──

    @Test
    void aTagNamingAnAbstractVariantIsRefusedWithoutOfferingToOmitIt() {
        Diagnostic refused = refusal("{ i: !shape { name: \"a\" } }");

        assertTrue(refused.message().contains("'shape' selects nothing"), refused.message());
        assertFalse(refused.message().contains("omit the tag"), refused.message());
        assertFalse(refusal("{ i: !shape_alias { name: \"a\" } }").message().contains("omit the tag"));
    }

    /** Where the abstract variant names discriminators an untagged value is placed by them, so omitting is a remedy. */
    @Test
    void aTagNamingAnAbstractVariantWithDiscriminatorsOffersToOmitIt() {
        Diagnostic refused = refusal("{ q: !pet { kind: \"dog\"  name: \"rex\" } }");

        assertTrue(refused.message().endsWith("or omit the tag"), refused.message());
    }

    @Test
    void anUntaggedRecordAtADisjointChoiceIsReadAsAtAPositionTypedByItsVariant() {
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refusal("{ i: { name: \"a\"  r: 1 } }").code());
        assertEquals(Optional.of("dog"), read("{ q: { kind: \"dog\"  name: \"rex\" } }").get("q").typeRef());
        assertEquals(Optional.of("cat"),
                read("{ q: !cat { kind: \"cat\"  name: \"tom\" } }").get("q").typeRef());
    }

    @Test
    void aNonDisjointChoiceKeepsItsTagRequired() {
        assertTrue(refusal("{ p: { x: \"1\" } }").message().contains("requires an explicit type annotation"));
        valid("{ p: !a { x: \"1\" } }");
    }

    // ── bind mode ──

    public sealed interface Figure permits Shape, Tag {
    }

    public sealed interface Shape extends Figure permits Round, Circle, Square {
    }

    @Typename(name = "tag")
    public record Tag(String word) implements Figure {
    }

    public sealed interface Round extends Shape permits Disc {
    }

    @Typename(name = "disc")
    public record Disc(String name, int r, boolean filled) implements Round {
    }

    @Typename(name = "circle")
    public record Circle(String name, int r) implements Shape {
    }

    @Typename(name = "square")
    public record Square(String name, int side) implements Shape {
    }

    public record Hb(Figure i, Shape s) {
    }

    static Tson bound() {
        Map<String, Class<?>> names = Map.of("figure", Figure.class, "tag", Tag.class, "shape", Shape.class,
                "round", Round.class, "disc", Disc.class, "circle", Circle.class, "square", Square.class,
                "hb", Hb.class);
        DataNameBinder binder = DataNameBinder.ofMap(names).orElse(SchemaModelNameBinder.INSTANCE);
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of((SchemaSource) uri -> SCHEMA))
                .withDataBindContext(DataBindContext.builder().nameBinder(binder)
                        .registerAtoms(AtomContext.hostTypes()).build()));
    }

    static Hb bind(String body) {
        return bound().objectReader().read("!!import:\"" + ID + "\"\n!hb " + body, Hb.class);
    }

    /** The host receives at the choice what a field typed by the selected variant receives for the same tag. */
    @Test
    void aBoundSubtypeAtAChoiceIsWhatAFieldTypedByTheVariantReceives() {
        Hb read = bind("{ i: !disc { name: \"a\"  r: 1  filled: true }  s: !disc { name: \"a\"  r: 1  filled: true } }");

        assertEquals(new Disc("a", 1, true), read.i());
        assertEquals(read.s(), read.i());
        assertEquals(new Tag("w"), bind("{ i: !tag { word: \"w\" }  s: !circle { name: \"c\"  r: 2 } }").i());
    }
}
