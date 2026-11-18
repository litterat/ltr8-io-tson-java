package io.ltr8.tson.json;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code $type} at a choice whose variants are externs, or at a position typed by an extern of a choice
 * ([TSON-SCHEMA] §5.4, §7.9, [TSON-JSON] §8.1, §8.6): it names a variant, or a type IS-A exactly one, each name followed
 * through its extern and the entries compared by identity, and the value is read against the entry the tag reaches in
 * its own foreign scope. Each verdict is the text encoding's for the same document.
 */
class JsonExternChoiceTagTest {

    static final String CORE = "https://tson.io/2026/40/m/core.tn";
    static final String A1 = "https://example.test/a1.tn";
    static final String A2 = "https://example.test/a2.tn";
    static final String ENV = "https://example.test/env.tn";

    static final Map<String, String> DOCUMENTS = new LinkedHashMap<>();

    static {
        schema(A1, """
                order    => abstract { id: text }
                rush     => order & { by: text }
                standard => order & { note?: text }
                parcel   => { id: text }
                bulky    => parcel & { weight: text }
                card     => { number: text }
                left     => { l: text }
                right    => { r: text }
                both     => left & right
                shape    => abstract { name: text }
                circle   => shape & { radius: text }
                item     => ( text | shape )
                sides    => ( left | right )
                """);
        schema(A2, """
                order    => abstract { ref: text }
                rush     => order & { due: text }
                parcel   => { id: text }
                bulky    => parcel & { crane: text }
                """);
        schema(ENV, extern("order_v1", A1, "order") + extern("order_v2", A2, "order")
                + extern("rush_v1", A1, "rush") + extern("rush_v2", A2, "rush")
                + extern("rush_again", A1, "rush") + extern("parcel_v1", A1, "parcel")
                + extern("parcel_v2", A2, "parcel") + extern("bulky_v1", A1, "bulky")
                + extern("bulky_v2", A2, "bulky") + extern("card_v1", A1, "card")
                + extern("left_v1", A1, "left") + extern("right_v1", A1, "right")
                + extern("both_v1", A1, "both") + extern("shape_v1", A1, "shape")
                + extern("circle_v1", A1, "circle") + extern("item_v1", A1, "item")
                + extern("sides_v1", A1, "sides") + """
                r1       => rush_v1
                envelope => {
                  id:      text
                  body?:   ( order_v1 | order_v2 )
                  box?:    ( parcel_v1 | parcel_v2 )
                  named?:  ( order_v1 | rush_v1 )
                  side?:   ( left_v1 | right_v1 )
                  thing?:  ( text | shape_v1 )
                  item?:   item_v1
                  sides?:  sides_v1
                }
                bound    => { id: text  body: ( order_v1 | order_v2 ) }
                """);
    }

    static void schema(String id, String body) {
        DOCUMENTS.put(id, "!!id:\"" + id + "\"\n!!import:\"" + CORE + "\"\n!schema {\n" + body.indent(2) + "}\n");
    }

    static String extern(String entry, String schema, String type) {
        return entry + " => !meta.extern { schema: \"" + schema + "\"  type: " + type + " }\n";
    }

    public sealed interface Order permits Rush1, Rush2 {
    }

    public record Rush1(String id, String by) implements Order {
    }

    public record Rush2(String ref, String due) implements Order {
    }

    public record Bound(String id, Order body) {
    }

    private static String identity(String uri) {
        return CanonicalIdentity.canonicalize(uri);
    }

    private static ProcessorConfig config() {
        SchemaSource source = uri -> DOCUMENTS.entrySet().stream()
                .filter(document -> CanonicalIdentity.sameIdentity(uri, document.getKey()))
                .map(Map.Entry::getValue).findFirst()
                .orElseThrow(() -> new SchemaFetchException(uri, SchemaFetchException.Reason.NOT_FOUND,
                        "no document is served under '" + uri + "'", null));
        DataNameBinder binder = DataNameBinder.byNamespace(Map.of(
                identity(ENV), DataNameBinder.ofMap(Map.of("bound", Bound.class)),
                identity(A1), DataNameBinder.ofMap(Map.of("order", Order.class, "rush", Rush1.class)),
                identity(A2), DataNameBinder.ofMap(Map.of("order", Order.class, "rush", Rush2.class))),
                DataNameBinder.ofMap(Map.of()));
        return ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source)).withDataBindContext(
                DataBindContext.builder().nameBinder(binder).registerAtoms(AtomContext.hostTypes()).build());
    }

    static final Tson TSON = Tson.of(config());
    static final Json JSON = Json.of(config()).withSchemas(TSON.schemaRegistry());

    static {
        DOCUMENTS.values().forEach(TSON::resolve);
    }

    private static List<Diagnostic> json(String fields) {
        List<Diagnostic> problems = new ArrayList<>();
        JSON.treeReader().withSchema(ENV).withDiagnostics(problems::add)
                .readAs("{\"id\": \"e\", " + fields + "}", "envelope");
        return problems;
    }

    private static List<Diagnostic> text(String fields) {
        List<Diagnostic> problems = new ArrayList<>();
        TSON.treeReader().withDiagnostics(problems::add)
                .read("!!import:\"" + ENV + "\"\n!envelope { id: e  " + fields + " }");
        return problems;
    }

    private static List<String> shown(List<Diagnostic> problems) {
        return problems.stream().map(d -> d.code() + " " + d.path().orElse("") + ": " + d.message()).toList();
    }

    private static List<Diagnostic.Code> codes(List<Diagnostic> problems) {
        return problems.stream().map(Diagnostic::code).toList();
    }

    /** Each case is the JSON document's fields, then the text document's, after a {@code |}. */
    @ParameterizedTest
    @ValueSource(strings = {
            "\"body\": {\"$type\": \"rush_v1\", \"id\": \"x\", \"by\": \"y\"}|body: !rush_v1 { id: x  by: y }",
            "\"body\": {\"$type\": \"rush_v1\", \"$value\": {\"id\": \"x\", \"by\": \"y\"}}"
                    + "|body: !rush_v1 { id: x  by: y }",
            "\"body\": {\"$type\": \"rush_v2\", \"ref\": \"x\", \"due\": \"y\"}|body: !rush_v2 { ref: x  due: y }",
            "\"body\": {\"$type\": \"rush_again\", \"id\": \"x\", \"by\": \"y\"}|body: !rush_again { id: x  by: y }",
            "\"body\": {\"$type\": \"r1\", \"id\": \"x\", \"by\": \"y\"}|body: !r1 { id: x  by: y }",
            "\"box\": {\"$type\": \"bulky_v1\", \"id\": \"x\", \"weight\": \"w\"}|box: !bulky_v1 { id: x  weight: w }",
            "\"box\": {\"$type\": \"bulky_v2\", \"id\": \"x\", \"crane\": \"c\"}|box: !bulky_v2 { id: x  crane: c }",
            "\"box\": {\"$type\": \"parcel_v2\", \"id\": \"x\"}|box: !parcel_v2 { id: x }",
            "\"named\": {\"$type\": \"rush_v1\", \"id\": \"x\", \"by\": \"y\"}|named: !rush_v1 { id: x  by: y }",
            "\"thing\": {\"$type\": \"circle_v1\", \"name\": \"n\", \"radius\": \"r\"}"
                    + "|thing: !circle_v1 { name: n  radius: r }",
            "\"thing\": \"plain\"|thing: plain",
            "\"item\": {\"$type\": \"circle_v1\", \"name\": \"n\", \"radius\": \"r\"}"
                    + "|item: !circle_v1 { name: n  radius: r }"})
    void aTagNamingAVariantOrATypeIsAExactlyOneSelectsItInBoth(String jsonAndText) {
        String[] parts = jsonAndText.split("\\|", 2);
        assertEquals(List.of(), shown(text(parts[1])), parts[1]);
        assertEquals(List.of(), shown(json(parts[0])), parts[0]);
    }

    @Test
    void theValueIsReadAgainstTheEntryItsTagReachesInItsOwnScopeInBoth() {
        List<Diagnostic> got = json("\"body\": {\"$type\": \"rush_v2\", \"ref\": \"x\", \"due\": \"y\", \"by\": \"z\"}");
        assertEquals(1, got.size(), shown(got).toString());
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, got.getFirst().code());
        assertEquals(Optional.of("/body/by"), got.getFirst().path());
        assertEquals("example.test/a2.tn", got.getFirst().schemaId());
        List<Diagnostic> fromText = text("body: !rush_v2 { ref: x  due: y  by: z }");
        assertEquals(fromText.stream().map(d -> d.code() + " " + d.path() + " " + d.schemaId()).toList(),
                got.stream().map(d -> d.code() + " " + d.path() + " " + d.schemaId()).toList());
    }

    /**
     * Each case is the JSON document's fields, then the text document's. The code agrees; a choice reports its
     * refusal at the {@code $type} member in JSON and at the value in text, as at every choice.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "\"body\": {\"$type\": \"card_v1\", \"number\": \"n\"}|body: !card_v1 { number: n }",
            "\"side\": {\"$type\": \"both_v1\", \"l\": \"a\", \"r\": \"b\"}|side: !both_v1 { l: a  r: b }",
            "\"body\": {\"$type\": \"rush\", \"id\": \"x\", \"by\": \"y\"}|body: !rush { id: x  by: y }",
            "\"sides\": {\"$type\": \"both_v1\", \"l\": \"a\", \"r\": \"b\"}|sides: !both_v1 { l: a  r: b }",
            "\"body\": {\"$type\": \"order_v1\", \"id\": \"x\"}|body: !order_v1 { id: x }"})
    void aTagSelectingNoVariantIsRefusedOnOneRuleInBoth(String jsonAndText) {
        String[] parts = jsonAndText.split("\\|", 2);
        List<Diagnostic.Code> fromText = codes(text(parts[1]));
        assertFalse(fromText.isEmpty(), "the text side reported nothing, so this compares nothing");
        assertEquals(fromText, codes(json(parts[0])), parts[0]);
    }

    @Test
    void anUnresolvedTagAndOneSelectingNoneAreTheirOwnRefusals() {
        assertEquals(List.of(Diagnostic.Code.UNKNOWN_TYPE_REF),
                codes(json("\"body\": {\"$type\": \"rush\", \"id\": \"x\", \"by\": \"y\"}")));
        List<String> none = shown(json("\"side\": {\"$type\": \"both_v1\", \"l\": \"a\", \"r\": \"b\"}"));
        assertEquals(1, none.size(), none.toString());
        assertTrue(none.getFirst().startsWith("TYPE_MISMATCH /side/$type: '$type' names 'both_v1', a subtype of more "
                + "than one variant"), none.getFirst());
    }

    @Test
    void aBoundValueIsReadAgainstTheEntryItsTagReaches() {
        assertEquals(new Bound("e", new Rush1("x", "y")), JSON.objectReader().withSchema(ENV).readAs("""
                {"id": "e", "body": {"$type": "rush_v1", "id": "x", "by": "y"}}""", "bound", Bound.class));
        assertEquals(new Bound("e", new Rush2("x", "y")), JSON.objectReader().withSchema(ENV).readAs("""
                {"id": "e", "body": {"$type": "rush_v2", "ref": "x", "due": "y"}}""", "bound", Bound.class));
    }
}
