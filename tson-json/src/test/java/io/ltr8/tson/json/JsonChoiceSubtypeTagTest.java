package io.ltr8.tson.json;

import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.io.ByteSource;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.json.stream.JsonStream;
import io.ltr8.tson.json.tree.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code $type} at a choice names a variant, or a type that IS-A exactly one variant, and the value is read at that
 * variant as at a position typed by it ([TSON-JSON] §3.3, §8.1; [TSON-SCHEMA] §5.4), in the inline form and the
 * wrapper form, with the verdicts the text encoding gives the same document.
 */
class JsonChoiceSubtypeTagTest {

    private static final String ID = "https://example.test/json-choice-subtype-tag.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/json-choice-subtype-tag.tn"
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
              tag     => { word: text }
              item    => ( text | shape )
              thing   => ( text | base )
              named   => ( base | sub )
              pair    => ( a | b )
              num     => ( int32 | text )
              pets    => ( text | pet )
              aliased => ( text | shape_alias )
              figure  => ( shape | tag )
              h => { i?: item  t?: thing  n?: named  p?: pair  u?: num  q?: pets  al?: aliased
                     is?: [item]  im?: { text => item } }
              hb => { i: figure  s: shape }
            }
            """;

    private static final Tson TSON = Tson.of(ProcessorConfig.defaults()
            .withSchemaAccess(SchemaAccess.of((SchemaSource) uri -> SCHEMA)));

    private static final JsonCompiledSchema COMPILED = JsonSchemaCompiler.compile(TSON.resolve(SCHEMA));

    private record Read(JsonValue value, List<Diagnostic> problems) {
    }

    private static Read json(String body) {
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of(body)) {
            JsonReadContext ctx = JsonReadContext.of(new JsonStream(bytes, ProcessorPolicy.defaults(), receiver),
                    receiver);
            ctx = COMPILED.rootDeclaration("h").map(ctx::underDeclaration).orElse(ctx);
            return new Read((JsonValue) COMPILED.get("h").read(ctx), List.copyOf(problems));
        }
    }

    private static List<String> tson(String body) {
        return TSON.validate("!!import:\"" + ID + "\"\n!h " + body).stream().map(Diagnostic::message).toList();
    }

    private static JsonValue accepted(String tsonBody, String jsonBody) {
        assertEquals(List.of(), tson(tsonBody), "the text encoding");
        Read read = json(jsonBody);
        assertEquals(List.of(), read.problems().stream().map(Diagnostic::message).toList(), "the JSON encoding");
        return read.value();
    }

    /**
     * Both encodings refuse once, with one code; the JSON refusal, for what is left. The JSON pointer may run one step
     * past the text one, into the {@code $type} or {@code $value} member, which a text annotation has no location
     * for ({@code CrossEncodingParityTest}'s tag cases).
     */
    private static Diagnostic refused(String tsonBody, String jsonBody) {
        List<Diagnostic> fromTson = TSON.validate("!!import:\"" + ID + "\"\n!h " + tsonBody);
        assertEquals(1, fromTson.size(), fromTson::toString);
        Read read = json(jsonBody);
        assertEquals(1, read.problems().size(), read.problems()::toString);
        Diagnostic text = fromTson.getFirst();
        Diagnostic refusal = read.problems().getFirst();
        assertEquals(text.code(), refusal.code(), "the two encodings disagree");
        String path = text.path().orElseThrow();
        assertTrue(refusal.path().orElseThrow().matches(Pattern.quote(path) + "(/\\$type|/\\$value)?"),
                refusal.path() + " against " + path);
        return refusal;
    }

    // ── a variant's subtype ──

    @Test
    void aSubtypeOfAnAbstractVariantSelectsItInBothForms() {
        accepted("""
                { i: !circle { name: "a"  r: 1 } }""", """
                {"i": {"$type": "circle", "name": "a", "r": 1}}""");
        accepted("""
                { i: !circle { name: "a"  r: 1 } }""", """
                {"i": {"$type": "circle", "$value": {"name": "a", "r": 1}}}""");
    }

    @Test
    void aSubtypeOfAConcreteVariantSelectsIt() {
        JsonValue read = accepted("""
                { t: !sub { id: "x"  extra: "y" } }""", """
                {"t": {"$type": "sub", "id": "x", "extra": "y"}}""");
        assertEquals("y", read.get("t").get("extra").asString());
    }

    @Test
    void aMemberTwoLevelsBelowAnAbstractVariantSelectsIt() {
        accepted("""
                { i: !disc { name: "a"  r: 1  filled: true } }""", """
                {"i": {"$type": "disc", "name": "a", "r": 1, "filled": true}}""");
    }

    @Test
    void anAbstractSubtypeOfAVariantHasNoValues() {
        Diagnostic refusal = refused("""
                { i: !round { name: "a"  r: 1 } }""", """
                {"i": {"$type": "round", "name": "a", "r": 1}}""");
        assertTrue(refusal.message().contains("'round' selects nothing"), refusal.message());
    }

    @Test
    void aSubtypeOfAVariantThroughAnAliasSelectsIt() {
        accepted("""
                { al: !circle { name: "a"  r: 1 } }""", """
                {"al": {"$type": "circle", "name": "a", "r": 1}}""");
    }

    /** The wrapper's {@code $value} is read as the refinement the tag names, its facets included. */
    @Test
    void anAtomRefinementSelectsItsBaseAndValidatesAsItself() {
        accepted("""
                { u: !small 5 }""", """
                {"u": {"$type": "small", "$value": 5}}""");
        Diagnostic refusal = refused("""
                { u: !small 50 }""", """
                {"u": {"$type": "small", "$value": 50}}""");
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, refusal.code());
    }

    @Test
    void aSubtypeTaggedValueIsHeldToTheSubtypesOwnFields() {
        assertEquals(Diagnostic.Code.FIELD_REQUIRED, refused("""
                { i: !circle { name: "a" } }""", """
                {"i": {"$type": "circle", "name": "a"}}""").code());
    }

    @Test
    void aChoiceAsAnArrayElementAndAMapValueTakesASubtypeTag() {
        accepted("""
                { is: [ !circle { name: "a"  r: 1 }  "t" ]  im: { k => !square { name: "b"  side: 2 } } }""", """
                {"is": [{"$type": "circle", "name": "a", "r": 1}, "t"],
                 "im": {"k": {"$type": "square", "name": "b", "side": 2}}}""");
        refused("""
                { is: [ "t"  !square { name: "b" } ] }""", """
                {"is": ["t", {"$type": "square", "name": "b"}]}""");
    }

    // ── which variant ──

    @Test
    void aVariantTheTagNamesIsSelectedBeforeOneItIsA() {
        accepted("""
                { n: !sub { id: "x"  extra: "y" } }""", """
                {"n": {"$type": "sub", "id": "x", "extra": "y"}}""");
    }

    @Test
    void aTagWhoseTypeIsATwoVariantsAndNamesNeitherSelectsNone() {
        Diagnostic refusal = refused("""
                { p: !both { } }""", """
                {"p": {"$type": "both"}}""");
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refusal.code());
        assertTrue(refusal.message().contains("(a | b)"), refusal.message());
        assertTrue(refused("""
                { n: !subsub { id: "x"  extra: "y"  more: "z" } }""", """
                {"n": {"$type": "subsub", "id": "x", "extra": "y", "more": "z"}}""")
                .message().contains("(base | sub)"));
    }

    @Test
    void aTagNamingATypeIsANoVariantIsRefused() {
        Diagnostic refusal = refused("""
                { i: !base { id: "x" } }""", """
                {"i": {"$type": "base", "id": "x"}}""");
        assertTrue(refusal.message().contains("nor a subtype of exactly one"), refusal.message());
    }

    // ── a tag naming the abstract variant, and an untagged value ──

    @Test
    void aTagNamingAnAbstractVariantIsRefusedWithoutOfferingToOmitIt() {
        Diagnostic refusal = refused("""
                { i: !shape { name: "a" } }""", """
                {"i": {"$type": "shape", "name": "a"}}""");
        assertFalse(refusal.message().contains("omit the tag"), refusal.message());
        assertTrue(refused("""
                { q: !pet { kind: "dog"  name: "rex" } }""", """
                {"q": {"$type": "pet", "kind": "dog", "name": "rex"}}""").message().endsWith("or omit the tag"));
    }

    @Test
    void anUntaggedRecordAtADisjointChoiceIsReadAsAtAPositionTypedByItsVariant() {
        refused("""
                { i: { name: "a"  r: 1 } }""", """
                {"i": {"name": "a", "r": 1}}""");
        accepted("""
                { q: { kind: "dog"  name: "rex" } }""", """
                {"q": {"kind": "dog", "name": "rex"}}""");
    }

    @Test
    void aNonDisjointChoiceKeepsItsTagRequired() {
        refused("""
                { p: { x: "1" } }""", """
                {"p": {"x": "1"}}""");
        accepted("""
                { p: !a { x: "1" } }""", """
                {"p": {"$type": "a", "x": "1"}}""");
    }

    // ── bind mode ──

    public sealed interface Figure permits Shape, Tag {
    }

    public sealed interface Shape extends Figure permits Round, Circle, Square {
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

    @Typename(name = "tag")
    public record Tag(String word) implements Figure {
    }

    public record Hb(Figure i, Shape s) {
    }

    private static Hb bind(String body) {
        Map<String, Class<?>> names = Map.of("figure", Figure.class, "tag", Tag.class, "shape", Shape.class,
                "round", Round.class, "disc", Disc.class, "circle", Circle.class, "square", Square.class,
                "hb", Hb.class);
        DataBindContext binding = DataBindContext.builder().nameBinder(DataNameBinder.ofMap(names)).build();
        return Json.of(ProcessorConfig.defaults().withDataBindContext(binding)).withSchemas(TSON.schemaRegistry())
                .objectReader().withSchema(ID).readAs(body, "hb", Hb.class);
    }

    /** The host receives at the choice what a field typed by the selected variant receives for the same tag. */
    @Test
    void aBoundSubtypeAtAChoiceIsWhatAFieldTypedByTheVariantReceives() {
        Hb read = bind("""
                {"i": {"$type": "disc", "name": "a", "r": 1, "filled": true},
                 "s": {"$type": "disc", "name": "a", "r": 1, "filled": true}}""");

        assertEquals(new Disc("a", 1, true), read.i());
        assertEquals(read.s(), read.i());
        assertEquals(new Circle("c", 2), bind("""
                {"i": {"$type": "circle", "$value": {"name": "c", "r": 2}},
                 "s": {"$type": "circle", "name": "c", "r": 2}}""").i());
    }
}
