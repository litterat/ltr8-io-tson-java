package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.tree.TsonAnnotation;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-annotation capture <b>and type checking</b> on the schema-driven tree path, the peer of {@link
 * SchemalessTreeAnnotationTest} (which captures the same §3.1 positions but has no schema to check against).
 *
 * <p>Two mechanisms are pinned here. <b>Capture</b> works by hoisting: a compiled tree reader shares its
 * {@code *AbstractReader} base with the bind-mode subclass, and that base consumes the {@code annotation*
 * type-ref?} framing where the node is <em>not</em> built, so rather than widen four shared shape-check
 * signatures with a field only tree mode reads, each tree reader captures first and leaves the base's own
 * framing call a no-op. <b>Checking</b> follows [TSON-SCHEMA] §6: an annotation names a type, resolved one
 * hop against the governing schema (§3.3.3), and its value is read by that type's own compiled reader -- so a
 * wrong-typed value is a diagnostic simply because the right reader rejected it, with no separate validation
 * pass.
 *
 * <p>The schema declares its own annotation types, which §3.3.3 explicitly allows ("An annotation type
 * declared locally in a user schema is usable by that schema's <i>data documents</i>"), alongside core's own
 * {@code doc}.
 *
 * <p>A <em>dispatched</em> value (a subtype here, a choice variant elsewhere) needs a third mechanism: the
 * dispatcher must consume the annotations to reach the {@code !typeName} it dispatches on, so the reader that
 * builds the node never sees them, and they are re-attached to the finished node via {@link
 * TsonValue#withAnnotations}.
 *
 * <p>{@code TupleTreeReader} and {@code MapTreeReader} got the same treatment as the other containers but
 * have no fixture here -- no test in this repo builds a tuple from a schema, and a map-typed field does not
 * link yet ({@code BACKLOG.md}).
 */
class SchemaDrivenTreeAnnotationTest {

    private static final String SCHEMA_ID = "https://example.test/tree-annotations.tn";
    private static final String SCHEMA = """
            !!id:"https://example.test/tree-annotations.tn"
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
            {
              label => @annotation text
              checked => @annotation void
              point => { x: int32  y: int32 }
              shape => { name: text  origin: point  tags: [text] }
              circle => shape & { radius: int32 }
            }
            """;

    private static final String VALID = """
            @doc:"a shape" !shape {
              name: @label:"the name" "square"
              origin: @checked { x: 1  y: 2 }
              tags: @label:"all of them" [@checked "a" "b"]
            }
            """;

    private static TsonValue read(String data) {
        TsonSchemaSource source = uri -> {
            if (TsonCanonicalIdentity.sameIdentity(uri, SCHEMA_ID)) {
                return SCHEMA;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        return (TsonValue) TsonCompiledSchemaRegistry.tree(core).get(SCHEMA_ID).get("shape")
                .read(TestDocuments.document(data));
    }

    private static List<String> names(TsonValue node) {
        return node.annotations().stream().map(TsonAnnotation::name).toList();
    }

    @Test
    void capturesAnnotationsThroughEveryCompiledTreeReader() {
        TsonValue shape = read(VALID);

        // RecordTreeReader -- the root and a nested record
        assertEquals(List.of("doc"), names(shape));
        assertEquals(List.of("checked"), names(shape.get("origin")));
        assertTrue(shape.get("origin").isRecord());

        // AtomTreeReader -- a leaf, whose framing is consumed by the wrapped AtomTypeReader
        assertEquals(List.of("label"), names(shape.get("name")));

        // ArrayTreeReader -- the array itself and, independently, one element
        TsonValue tags = shape.get("tags");
        assertTrue(tags.isArray());
        assertEquals(List.of("label"), names(tags));
        assertEquals(List.of("checked"), names(tags.get(0)));
        assertEquals(List.of(), names(tags.get(1)));
    }

    @Test
    void anAnnotationValueIsReadByTheTypeItNames() {
        // @doc resolves through core's doc -> documentation -> text, @label through this schema's own
        // `label => @annotation text`. Both come back as read values, not raw tokens.
        TsonValue shape = read(VALID);

        assertEquals(Optional.of("a shape"), shape.annotations().get(0).value().orElseThrow().asString());
        assertEquals(Optional.of("the name"),
                shape.get("name").annotations().get(0).value().orElseThrow().asString());
    }

    @Test
    void theSchemaTypeRefStillWinsOverTheWireOne() {
        // Capture runs ahead of the base's own framing consumption, so the type-ref the base would have
        // returned is still consumed and still ignored -- a schema-driven node's typeRef is its schema type
        // name. Guards against the hoist accidentally rerouting the type-ref.
        TsonValue shape = read(VALID);

        assertEquals(Optional.of("shape"), shape.typeRef());
        assertEquals(Optional.of("point"), shape.get("origin").typeRef());
        assertEquals(Optional.of("text"), shape.get("name").typeRef());
    }

    @Test
    void anAnnotationNamingNoTypeIsReported() {
        // §6: an annotation names a type. A name the governing schema doesn't declare is an unresolved
        // reference, not silently-accepted metadata.
        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> read("!shape { name: @nosuchthing:\"x\" \"square\"  origin: { x: 1  y: 2 }  tags: [] }"));

        assertTrue(thrown.getMessage().contains("@nosuchthing"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("names no type"), thrown.getMessage());
    }

    @Test
    void anAnnotationValueOfTheWrongTypeIsReported() {
        // `label` is text-targeted, so a record where its value belongs is caught by text's own reader --
        // the check falls out of using the right reader, not from a separate validation pass.
        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> read("!shape { name: @label:{ a: 1 } \"square\"  origin: { x: 1  y: 2 }  tags: [] }"));

        assertTrue(thrown.getMessage().toLowerCase().contains("token"), thrown.getMessage());
    }

    @Test
    void aBareAnnotationOnANonVoidTypeIsReported() {
        // §6 makes bare `@T` shorthand for `@T:_`, so the type must admit the absent sentinel. `checked` is
        // void-targeted and does (see VALID); `label` is text-targeted and does not.
        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> read("!shape { name: @label \"square\"  origin: { x: 1  y: 2 }  tags: [] }"));

        assertTrue(thrown.getMessage().contains("@label"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("absent sentinel"), thrown.getMessage());
    }

    @Test
    void annotationsSurviveDispatchToASubtype() {
        // The hard position: `circle` is a subtype of `shape`, so VariantSchemaReader has to consume the
        // annotations to reach the !circle that tells it which reader to use -- meaning the reader that
        // builds the node never sees them. They are re-attached to the node afterwards, which is where they
        // belong: they were written against this value.
        TsonValue circle = read("""
                @label:"tagged" @checked !circle {
                  name: "c"  origin: { x: 1  y: 2 }  tags: []  radius: 3
                }
                """);

        assertEquals(Optional.of("circle"), circle.typeRef());
        assertEquals(List.of("label", "checked"), names(circle));
        assertEquals(Optional.of("tagged"), circle.annotations().get(0).value().orElseThrow().asString());
    }

    @Test
    void annotationsSurviveTheUndispatchedBranchToo() {
        // Same reader, the branch where the value is the base type itself -- no type-ref, so it reads
        // through ownParser rather than a resolved subtype. Both branches must re-attach.
        TsonValue shape = read("@label:\"plain\" { name: \"s\"  origin: { x: 0  y: 0 }  tags: [] }");

        assertEquals(List.of("label"), names(shape));
        assertEquals(Optional.of("shape"), shape.typeRef());
    }

    @Test
    void roundTripsThroughTheTreeWriter() {
        TsonValue shape = read(VALID);

        // Preserving: the re-read is schemaless, and the written text carries the schema's own type-refs,
        // which nothing is in scope to define on the way back in.
        String written = new TsonTreeWriter().toTson(shape);
        TsonValue reread = new TsonTreeReader().preservingUnknownTypeRefs().read(written);

        assertEquals(List.of("doc"), names(reread));
        assertEquals(List.of("label"), names(reread.get("name")));
        assertEquals(List.of("checked"), names(reread.get("origin")));
        assertEquals(List.of("label"), names(reread.get("tags")));
        assertEquals(List.of("checked"), names(reread.get("tags").get(0)));
    }
}
