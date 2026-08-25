package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.tree.TsonValue;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tree mode end to end: compile a user schema via {@link TsonCompiledSchemaRegistry#tree} and read data into
 * a {@link TsonValue}, proving the tree is structure-preserving (a nested record stays a record, an array
 * stays an array -- neither collapsed to a Java {@code Map}/{@code List} as DOM mode would) with typed leaves
 * and preserved type-refs, and that navigation is null-safe.
 */
class TreeReadTest {

    private static final String SCHEMA_ID = "https://example.test/tree-read.tn";
    private static final String SCHEMA = """
            !!id:"https://example.test/tree-read.tn"
            !!meta:"https://tson.io/2026/33/m/meta.tn"
            !!import:"https://tson.io/2026/33/m/core.tn"
            {
              point => { x: int32  y: int32 }
              shape => { name: text  origin: point  tags: [text] }
            }
            """;
    private static final String DATA = """
            !shape {
              name: "square"
              origin: { x: 1  y: 2 }
              tags: ["a" "b"]
            }
            """;

    private static TsonCompiledMetaRegistry core() {
        TsonSchemaSource source = uri -> {
            if (TsonCanonicalIdentity.sameIdentity(uri, SCHEMA_ID)) {
                return SCHEMA;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        return TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
    }

    @Test
    void readsAUserSchemaIntoAStructurePreservingTree() {
        TsonCompiledSchema compiled = TsonCompiledSchemaRegistry.tree(core()).get(SCHEMA_ID);
        TsonValue shape = (TsonValue) compiled.get("shape").read(TestDocuments.document(DATA));

        assertTrue(shape.isRecord());
        assertEquals(Optional.of("shape"), shape.typeRef());
        assertEquals(Optional.of("square"), shape.get("name").asString());

        // a nested record stays a record (not collapsed to a map) and carries its own type-ref
        TsonValue origin = shape.get("origin");
        assertTrue(origin.isRecord());
        assertEquals(Optional.of("point"), origin.typeRef());
        assertEquals(1, shape.at("/origin/x").asInt().orElseThrow());
        assertEquals(2, shape.at("/origin/y").asInt().orElseThrow());
        assertEquals(Optional.of("int32"), shape.at("/origin/x").typeRef());

        // an array stays an array, with typed elements
        TsonValue tags = shape.get("tags");
        assertTrue(tags.isArray());
        assertEquals(Optional.of("a"), tags.get(0).asString());
        assertEquals(Optional.of("b"), shape.at("/tags/1").asString());

        // navigation is null-safe: a missing field yields TsonMissing, not an exception
        assertTrue(shape.get("nope").isMissing());
        assertTrue(shape.at("/no/such/path").isMissing());
    }
}
