package io.ltr8.tson;

import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonSchemaValidationException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-DATA] §2.2.3's import cycle, reported as an ordinary schema error naming the path that closes it.
 *
 * <p><b>Why a cycle cannot be found by looking in a cache.</b> A schema is registered only once it has
 * linked, so while {@code a.tn} is resolving it is in no registry at all -- {@code b.tn} importing it back
 * finds nothing and fetches it again. Unguarded that is unbounded mutual recursion ending in a {@code
 * StackOverflowError}: an {@code Error} raised by ordinary author input, which no {@code Diagnostic} ever
 * sees and which the CLI's exit-code policy cannot classify. Being <em>in flight</em> is the whole signal,
 * and the chain of identities in flight is what makes the message actionable.
 *
 * <p>The same guard covers the {@code !!meta} chain, every link of which is resolved the same way.
 */
class ImportCycleTest {

    private static final String META = "https://tson.io/2026/35/m/meta.tn";
    private static final String CORE = "https://tson.io/2026/35/m/core.tn";

    private final Map<String, String> documents = new LinkedHashMap<>();

    /**
     * A schema importing every {@code imports} entry and declaring one record of its own. core.tn comes
     * along always, as it does in any practical schema -- it is bundled and resolves before the fixtures
     * do, so it never appears in a reported chain.
     */
    private void schema(String id, String meta, List<String> imports, String body) {
        StringBuilder text = new StringBuilder("!!id:\"" + id + "\"\n!!meta:\"" + meta + "\"\n");
        text.append("!!import:\"").append(CORE).append("\"\n");
        imports.forEach(each -> text.append("!!import:\"").append(each).append("\"\n"));
        documents.put(id, text.append("{\n").append(body).append("\n}\n").toString());
    }

    private Tson tson() {
        TsonSchemaSource source = uri -> {
            for (Map.Entry<String, String> document : documents.entrySet()) {
                if (TsonCanonicalIdentity.sameIdentity(uri, document.getKey())) {
                    return document.getValue();
                }
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        return Tson.builder().schemaSource(source).build();
    }

    private String rejected(String uri) {
        return assertThrows(TsonSchemaValidationException.class, () -> tson().treeRegistry().get(uri))
                .getMessage();
    }

    /** Two schemas importing each other -- the ordinary case, and the one that used to overflow the stack. */
    @Test
    void twoSchemasImportingEachOtherAreRejectedWithBothNamed() {
        schema("https://example.test/a.tn", META, List.of("https://example.test/b.tn"), "  a_type => { x: b_type }");
        schema("https://example.test/b.tn", META, List.of("https://example.test/a.tn"), "  b_type => { y: a_type }");

        String message = rejected("https://example.test/a.tn");

        assertTrue(message.contains("is part of an import cycle"), message);
        assertTrue(message.contains("example.test/a.tn -> example.test/b.tn -> example.test/a.tn"),
                "names the path that closes the cycle: " + message);
    }

    /** A schema importing itself: the shortest cycle there is, and it still reads as a cycle. */
    @Test
    void aSchemaImportingItselfIsRejected() {
        schema("https://example.test/self.tn", META, List.of("https://example.test/self.tn"),
                "  self_type => { x: text }");

        String message = rejected("https://example.test/self.tn");

        assertTrue(message.contains("example.test/self.tn -> example.test/self.tn"), message);
    }

    /**
     * A longer cycle names every link, and only the links: the entry the resolution started from is
     * dropped from the path when it is not itself on the cycle, so the message points at the edge to break
     * rather than at wherever the load happened to begin.
     */
    @Test
    void aThreeSchemaCycleNamesEveryLinkAndNothingElse() {
        schema("https://example.test/entry.tn", META, List.of("https://example.test/one.tn"),
                "  entry_type => { x: one_type }");
        schema("https://example.test/one.tn", META, List.of("https://example.test/two.tn"),
                "  one_type => { x: two_type }");
        schema("https://example.test/two.tn", META, List.of("https://example.test/three.tn"),
                "  two_type => { x: three_type }");
        schema("https://example.test/three.tn", META, List.of("https://example.test/one.tn"),
                "  three_type => { x: one_type }");

        String message = rejected("https://example.test/entry.tn");

        assertTrue(message.contains(
                "example.test/one.tn -> example.test/two.tn -> example.test/three.tn -> example.test/one.tn"),
                message);
        assertTrue(!message.contains("entry.tn"), "the entry point is not on the cycle: " + message);
    }

    /** A {@code !!meta} chain that closes on itself is the same defect, and the same guard catches it. */
    @Test
    void aMetaChainCycleIsRejectedToo() {
        schema("https://example.test/meta-a.tn", "https://example.test/meta-b.tn", List.of(), "  ma => { x: text }");
        schema("https://example.test/meta-b.tn", "https://example.test/meta-a.tn", List.of(), "  mb => { x: text }");
        schema("https://example.test/uses.tn", "https://example.test/meta-a.tn", List.of(), "  u => { x: text }");

        String message = rejected("https://example.test/uses.tn");

        assertTrue(message.contains("is part of an import cycle"), message);
        assertTrue(message.contains("example.test/meta-a.tn"), message);
    }

    /**
     * The guard is about cycles, not about repetition: a diamond reaches one schema by two routes and is
     * ordinary. Pinned here beside the rejections because an in-flight marker that outlived its resolve
     * would turn every diamond into a false cycle -- which is the way this check fails if it is wrong.
     */
    @Test
    void aDiamondIsNotACycle() {
        schema("https://example.test/shared.tn", META, List.of(), "  shared_type => { x: text }");
        schema("https://example.test/left.tn", META, List.of("https://example.test/shared.tn"),
                "  left_type => { x: shared_type }");
        schema("https://example.test/right.tn", META, List.of("https://example.test/shared.tn"),
                "  right_type => { x: shared_type }");
        schema("https://example.test/top.tn", META,
                List.of("https://example.test/left.tn", "https://example.test/right.tn"),
                "  top_type => { l: left_type  r: right_type }");

        assertTrue(tson().treeRegistry().get("https://example.test/top.tn").schema()
                .entries().containsKey("shared_type"));
    }
}
