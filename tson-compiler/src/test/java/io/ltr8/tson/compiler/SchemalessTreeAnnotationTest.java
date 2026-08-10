package io.ltr8.tson.compiler;

import io.ltr8.tson.tree.MapNode;
import io.ltr8.tson.tree.TsonAnnotation;
import io.ltr8.tson.tree.TsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-annotation capture on the schemaless tree path ([TSON-DATA] §3.1).
 *
 * <p>{@code *annotation} appears in exactly one grammar production -- {@code data-value} (§7.4) -- so the
 * set of annotatable positions is exactly the set of places a {@code data-value} occurs: the document root,
 * a record field's value, an array element, either side of a map entry, and (since an annotation's own value
 * is itself a {@code data-value}) recursively inside any of those. {@link
 * #capturesAnnotationsAtEveryPositionTheGrammarPermits} walks that list; the rest cover the properties §3.1
 * states separately -- ordering, multiplicity, the valueless form, and value scope.
 *
 * <p>A record's field <em>name</em> is deliberately absent from that list: §2.5 forbids an annotation before
 * a field name, which is why {@code RecordNode.fields()} is keyed by a plain string while {@code
 * MapNode.Entry} holds a full node for its key.
 */
class SchemalessTreeAnnotationTest {

    private static TsonNode read(String source) {
        return new TsonTreeReader().read(source);
    }

    /** The annotation names on a node, in order -- what §3.1's ordering/multiplicity rules are stated over. */
    private static List<String> names(TsonNode node) {
        return node.annotations().stream().map(TsonAnnotation::name).toList();
    }

    /** The single annotation named {@code name} on {@code node}. */
    private static TsonAnnotation only(TsonNode node, String name) {
        List<TsonAnnotation> matching = node.annotations().stream().filter(a -> a.name().equals(name)).toList();
        assertEquals(1, matching.size(), () -> "expected exactly one @" + name + " on " + node.annotations());
        return matching.get(0);
    }

    @Test
    void capturesAnnotationsAtEveryPositionTheGrammarPermits() {
        TsonNode root = read("""
                @doc:"an order" !order {
                  tier: @deprecated GOLD
                  tags: [@first "a" "b"]
                  discounts: { @expires:"2026-12-31" WELCOME10 => @rate "10%" }
                }
                """);

        // 1. the document root value -- annotations precede the type-ref, both bind to the same data-value
        assertEquals(Optional.of("order"), root.typeRef());
        assertEquals(Optional.of("an order"), only(root, "doc").value().orElseThrow().asString());

        // 2. a record field's *value* (§2.5: metadata about a field attaches to its value, not its name)
        assertEquals(List.of("deprecated"), names(root.get("tier")));
        assertEquals(Optional.of("GOLD"), root.get("tier").asString());

        // 3. an array element -- and only the annotated one
        TsonNode tags = root.get("tags");
        assertEquals(List.of("first"), names(tags.get(0)));
        assertEquals(List.of(), names(tags.get(1)));

        // 4 & 5. either side of a map entry, annotated independently (§3.1)
        MapNode.Entry entry = ((MapNode) root.get("discounts")).entries().get(0);
        assertEquals(Optional.of("2026-12-31"), only(entry.key(), "expires").value().orElseThrow().asString());
        assertEquals(List.of("rate"), names(entry.value()));
        assertEquals(Optional.of("WELCOME10"), entry.key().asString());
        assertEquals(Optional.of("10%"), entry.value().asString());
    }

    @Test
    void aValuelessAnnotationHasNoValueRatherThanAnEmptyOne() {
        // §3.1: with no ":", presence is the whole of the information. Distinct from @name:_ , which
        // would carry the absent sentinel as a real value.
        TsonNode bare = read("{ tier: @deprecated GOLD }").get("tier");
        assertEquals(Optional.empty(), only(bare, "deprecated").value());

        TsonNode explicitlyAbsent = read("{ tier: @deprecated:_ GOLD }").get("tier");
        assertTrue(only(explicitlyAbsent, "deprecated").value().orElseThrow().isAbsent());
    }

    @Test
    void repeatsArePreservedInSourceOrder() {
        // §3.1 Multiplicity: "An annotation name MAY appear any number of times on a single value; all
        // occurrences are preserved in source order." So this is a List, not a Map keyed by name.
        TsonNode node = read("{ x: @tag:\"a\" @other @tag:\"b\" 1 }").get("x");

        assertEquals(List.of("tag", "other", "tag"), names(node));
        assertEquals(List.of(Optional.of("a"), Optional.of("b")),
                node.annotations().stream()
                        .filter(a -> a.name().equals("tag"))
                        .map(a -> a.value().orElseThrow().asString())
                        .toList());
    }

    @Test
    void anAnnotationValueIsItselfAnAnnotatableDataValue() {
        // §3.1 Value scope is right-recursive: in `@a:@b:val target`, @a's value is the whole data-value
        // `@b:val target` -- the core value `target`, annotated by @b, whose own value is `val`. The
        // trailing `extra` is what the enclosing value takes as its own core-value; without it the outer
        // data-value has none and this is a parse error, which is SPEC-FEEDBACK.md #3 (the spec's own
        // worked example is one token short of standing alone).
        TsonNode node = read("{ x: @a:@b:val target extra }").get("x");

        assertEquals(Optional.of("extra"), node.asString());
        TsonNode aValue = only(node, "a").value().orElseThrow();
        assertEquals(Optional.of("target"), aValue.asString());
        assertEquals(Optional.of("val"), only(aValue, "b").value().orElseThrow().asString());
    }

    @Test
    void annotationsOnAContainerBindToTheContainerNotItsChildren() {
        TsonNode root = read("@outer { items: @inner [1 2] }");

        assertEquals(List.of("outer"), names(root));
        assertEquals(List.of("inner"), names(root.get("items")));
        assertEquals(List.of(), names(root.get("items").get(0)));
    }

    @Test
    void anUnannotatedDocumentReportsEmptyEverywhere() {
        TsonNode root = read("{ a: 1  b: [2]  c: { k => 3 } }");

        assertEquals(List.of(), root.annotations());
        assertEquals(List.of(), root.get("a").annotations());
        assertEquals(List.of(), root.get("b").get(0).annotations());
        assertEquals(List.of(), ((MapNode) root.get("c")).entries().get(0).key().annotations());
    }
}
