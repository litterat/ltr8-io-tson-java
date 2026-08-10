package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.tree.TsonAnnotation;
import io.ltr8.tson.tree.TsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-annotation capture on the <b>schema-driven</b> tree path, the peer of {@link
 * SchemalessTreeAnnotationTest}. Same §3.1 positions, reached through compiled readers rather than the
 * schemaless engine.
 *
 * <p>The mechanism differs, and that is what this pins. A compiled tree reader shares its {@code
 * *AbstractReader} base with the bind-mode subclass, and that base consumes the {@code annotation*
 * type-ref?} framing where the node is <em>not</em> built. Rather than widen four shared shape-check
 * signatures with a field only tree mode reads, each tree reader captures the annotations itself before
 * calling the base -- the base's own framing call then finds nothing left, which is a no-op because it
 * discards the result anyway. {@link io.ltr8.tson.compiler.reader.AnnotationCapture} carries the reasoning.
 *
 * <p>Not covered here: a value sitting directly at a <em>dispatched</em> position (a choice/variant/subtype),
 * where the dispatcher must consume the annotations to reach the type-ref it dispatches on and has no way to
 * hand them to the delegate that builds the node. Its children still keep theirs.
 *
 * <p>Two reader families are untested here for want of a fixture, not for want of the change -- each got the
 * identical one-line hoist. {@code TupleTreeReader}: no test in this repo builds a tuple from a schema.
 * {@code MapTreeReader}: a field typed {@code map<text, text>} does not link yet, because {@code
 * TsonSchemaLinker} gives the structure-namespace fallback only to a {@code source} reference and not to a
 * generic-application head, which §3.3.1 also lists as a constructor role -- see {@code BACKLOG.md} and
 * {@code SPEC-FEEDBACK.md} #28. {@link SchemalessTreeAnnotationTest} does cover map keys and values, on the
 * schemaless path.
 */
class SchemaDrivenTreeAnnotationTest {

    private static final String SCHEMA_ID = "https://example.test/tree-annotations.tn";
    private static final String SCHEMA = """
            !!id:"https://example.test/tree-annotations.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            {
              point => { x: int32  y: int32 }
              shape => { name: text  origin: point  tags: [text] }
            }
            """;

    private static final String DATA = """
            @doc:"a shape" !shape {
              name: @label "square"
              origin: @where { x: 1  y: 2 }
              tags: @all [@first "a" "b"]
            }
            """;

    private static List<String> names(TsonNode node) {
        return node.annotations().stream().map(TsonAnnotation::name).toList();
    }

    private static TsonNode readShape() {
        TsonSchemaSource source = uri -> {
            if (TsonSchemaRegistry.canonicalIdentity(uri).equals(TsonSchemaRegistry.canonicalIdentity(SCHEMA_ID))) {
                return SCHEMA;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        TsonCompiledSchema compiled = TsonCompiledSchemaRegistry.tree(core).get(SCHEMA_ID);
        return (TsonNode) compiled.get("shape").read(DATA);
    }

    @Test
    void capturesAnnotationsThroughEveryCompiledTreeReader() {
        TsonNode shape = readShape();

        // RecordTreeReader -- both the root and a nested record
        assertEquals(List.of("doc"), names(shape));
        assertEquals(List.of("where"), names(shape.get("origin")));
        assertTrue(shape.get("origin").isRecord());

        // AtomNodeReader -- a leaf, whose framing is consumed by the wrapped AtomValueReader
        assertEquals(List.of("label"), names(shape.get("name")));

        // ArrayTreeReader -- the array itself and, independently, one element
        TsonNode tags = shape.get("tags");
        assertTrue(tags.isArray());
        assertEquals(List.of("all"), names(tags));
        assertEquals(List.of("first"), names(tags.get(0)));
        assertEquals(List.of(), names(tags.get(1)));
    }

    @Test
    void theSchemaTypeRefStillWinsOverTheWireOne() {
        // Capture happens ahead of the base's own framing consumption, so the type-ref the base would have
        // returned is still consumed and still ignored -- a schema-driven node's typeRef is its schema type
        // name, not whatever the wire said. Guards against the hoist accidentally rerouting the type-ref.
        TsonNode shape = readShape();

        assertEquals(java.util.Optional.of("shape"), shape.typeRef());
        assertEquals(java.util.Optional.of("point"), shape.get("origin").typeRef());
        assertEquals(java.util.Optional.of("text"), shape.get("name").typeRef());
    }

    @Test
    void roundTripsThroughTheTreeWriter() {
        TsonNode shape = readShape();

        String written = new TsonTreeWriter().toTson(shape);
        TsonNode reread = new TsonTreeReader().read(written);

        assertEquals(List.of("doc"), names(reread));
        assertEquals(List.of("label"), names(reread.get("name")));
        assertEquals(List.of("where"), names(reread.get("origin")));
        assertEquals(List.of("all"), names(reread.get("tags")));
        assertEquals(List.of("first"), names(reread.get("tags").get(0)));
    }
}
