package io.ltr8.tson;

import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.tree.TsonDocument;
import io.ltr8.tson.tree.TsonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.ltr8.tson.ExternWorld.extern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tag at a choice whose variants are externs, or at a position typed by an extern of a choice ([TSON-SCHEMA] §5.4,
 * §7.9): it names a variant, or a type IS-A exactly one, each name followed through its extern and the entries
 * compared by identity, and the value is read against the entry the tag reaches, in its own foreign scope.
 */
public class ExternChoiceTagTest {

    static final String A1 = "https://example.test/a1.tn";
    static final String A2 = "https://example.test/a2.tn";
    static final String ENV = "https://example.test/env.tn";

    final ExternWorld world = new ExternWorld()
            .schema(A1, """
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
                    """)
            .schema(A2, """
                    order    => abstract { ref: text }
                    rush     => order & { due: text }
                    parcel   => { id: text }
                    bulky    => parcel & { crane: text }
                    """)
            .schema(ENV, extern("order_v1", A1, "order") + extern("order_v2", A2, "order")
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
                      outer?:  ( card_v1 | item_v1 )
                    }
                    bound    => { id: text  body: ( order_v1 | order_v2 ) }
                    """);

    private List<Diagnostic> problems(String data) {
        List<Diagnostic> reported = new ArrayList<>();
        world.tson().treeReader().withDiagnostics(reported::add).read(document(data));
        return reported;
    }

    private List<String> shown(String data) {
        return problems(data).stream().map(ExternWorld::shown).toList();
    }

    private static String document(String data) {
        return "!!import:\"" + ENV + "\"\n!envelope { id: e  " + data + " }";
    }

    private void assertReadsBack(String data) {
        List<Diagnostic> reported = new ArrayList<>();
        TsonDocument read = world.tson().treeReader().withDiagnostics(reported::add).readDocument(document(data));
        assertEquals(List.of(), reported.stream().map(ExternWorld::shown).toList());
        String written = world.tson().treeWriter().toTson(read);
        List<Diagnostic> again = new ArrayList<>();
        TsonDocument reread = world.tson().treeReader().withDiagnostics(again::add).readDocument(written);
        assertEquals(List.of(), again.stream().map(ExternWorld::shown).toList(), written);
        assertEquals(read.root(), reread.root(), written);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "body: !rush_v1 { id: x  by: y }",
            "body: !rush_v2 { ref: x  due: y }",
            "body: !rush_again { id: x  by: y }",
            "body: !r1 { id: x  by: y }",
            "box: !bulky_v1 { id: x  weight: w }",
            "box: !bulky_v2 { id: x  crane: c }",
            "box: !parcel_v2 { id: x }",
            "named: !rush_v1 { id: x  by: y }",
            "thing: !circle_v1 { name: n  radius: r }",
            "thing: plain",
            "item: !circle_v1 { name: n  radius: r }",
            "sides: !left_v1 { l: a }"})
    void aTagNamingAVariantOrATypeIsAExactlyOneSelectsItThroughTheExtern(String data) {
        assertReadsBack(data);
    }

    @Test
    void theValueIsReadAgainstTheEntryItsTagReachesInItsOwnScope() {
        TsonValue root = world.tson().treeReader().read(document("body: !rush_v2 { ref: x  due: y }"));
        assertEquals(Optional.of("rush_v2"), root.get("body").typeRef());
        List<Diagnostic> got = problems("body: !rush_v2 { ref: x  due: y  by: z }");
        assertEquals(1, got.size(), got.toString());
        Diagnostic diagnostic = got.getFirst();
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, diagnostic.code(), diagnostic.toString());
        assertEquals(Optional.of("/body/by"), diagnostic.path(), diagnostic.toString());
        assertEquals("example.test/a2.tn", diagnostic.schemaId());
    }

    @Test
    void anAbstractVariantNamingNoSelectorsOffersATagThatIsAdmitted() {
        List<String> got = shown("body: !order_v1 { id: x  by: y }");
        assertEquals(1, got.size(), String.join("\n", got));
        assertTrue(got.getFirst().contains("by a name of the enclosing scope, one of (rush_v1 | rush_again | r1)"),
                got.getFirst());
        assertReadsBack("body: !rush_v1 { id: x  by: y }");
    }

    @Test
    void aTypeIsANoVariantSelectsNone() {
        List<String> got = shown("body: !card_v1 { number: n }");
        assertEquals(List.of("TYPE_MISMATCH /envelope/body: 'card_v1' is not a declared variant of '(order_v1 | "
                + "order_v2)' -- expected one of [order_v1, order_v2], or a type that IS-A exactly one"), got);
    }

    @Test
    void aTypeIsATwoVariantsAndNamingNeitherSelectsNone() {
        List<String> got = shown("side: !both_v1 { l: a  r: b }");
        assertEquals(List.of("TYPE_MISMATCH /envelope/side: 'both_v1' is a subtype of more than one variant of '("
                + "left_v1 | right_v1)' -- [left_v1, right_v1] -- and names none of them, so it selects none "
                + "(§5.4)"), got);
    }

    @Test
    void aForeignSchemasOwnNameIsUnresolvedAtTheChoice() {
        List<Diagnostic> got = problems("body: !rush { id: x  by: y }");
        assertEquals(1, got.size(), got.toString());
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF, got.getFirst().code(), got.toString());
        assertEquals(Optional.of("/body"), got.getFirst().path());
    }

    @Test
    void atAnExternOfAChoiceATypeIsATwoVariantsSelectsNone() {
        List<String> got = shown("sides: !both_v1 { l: a  r: b }");
        assertEquals(1, got.size(), String.join("\n", got));
        assertTrue(got.getFirst().startsWith("TYPE_MISMATCH /envelope/sides: 'both_v1' is a subtype of more than "
                + "one variant of the choice a 'sides_v1' position is"), got.getFirst());
        assertTrue(got.getFirst().contains("[left, right]"), got.getFirst());
    }

    @Test
    void aSubtypeOfAVariantOfAnExternsChoiceIsNoSubtypeOfTheChoice() {
        // §5.4 selects by IS-A, and a type IS-A a choice's variant is IS-A no choice: the outer choice selects nothing.
        List<String> got = shown("outer: !circle_v1 { name: n  radius: r }");
        assertEquals(1, got.size(), String.join("\n", got));
        assertTrue(got.getFirst().startsWith("TYPE_MISMATCH /envelope/outer: 'circle_v1' is not a declared variant"),
                got.getFirst());
    }

    // ── bind mode ────────────────────────────────────────────────────────

    public sealed interface Order permits Rush1, Rush2 {
    }

    public record Rush1(String id, String by) implements Order {
    }

    public record Rush2(String ref, String due) implements Order {
    }

    public record Envelope(String id, Order body) {
    }

    @Test
    void aBoundValueIsReadAgainstTheEntryItsTagReaches() {
        Tson tson = world.tson(DataNameBinder.byNamespace(Map.of(
                CanonicalIdentity.canonicalize(ENV), DataNameBinder.ofMap(Map.of("bound", Envelope.class)),
                CanonicalIdentity.canonicalize(A1), DataNameBinder.ofMap(Map.of("order", Order.class,
                        "rush", Rush1.class)),
                CanonicalIdentity.canonicalize(A2), DataNameBinder.ofMap(Map.of("order", Order.class,
                        "rush", Rush2.class))), DataNameBinder.ofMap(Map.of())));
        String header = "!!import:\"" + ENV + "\"\n";
        assertEquals(new Envelope("e", new Rush1("x", "y")), tson.objectReader().read(header
                + "!bound { id: e  body: !rush_v1 { id: x  by: y } }", Envelope.class));
        assertEquals(new Envelope("e", new Rush2("x", "y")), tson.objectReader().read(header
                + "!bound { id: e  body: !rush_v2 { ref: x  due: y } }", Envelope.class));
    }
}
