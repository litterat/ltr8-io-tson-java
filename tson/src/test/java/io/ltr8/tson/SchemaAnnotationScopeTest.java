package io.ltr8.tson;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.meta.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §3.3.3's one hop, on the schema side: a schema document's annotations resolve against its
 * {@code !!meta} target's namespace and nothing else -- "neither the local declarations of the document being
 * authored nor any further rung of the ladder participates". An annotation type a schema declares itself, or
 * brings in through {@code !!import}, is therefore usable by that schema's <em>data documents</em> (which is
 * what {@code SchemaDrivenTreeAnnotationTest} pins one layer down) and not within the schema document itself.
 *
 * <p><b>The rule only helps if breaking it is loud.</b> An unreachable annotation type has no contract to
 * validate its value against, so the value cannot be bound -- and an annotation kept by name with its value
 * quietly discarded is the worst of the three outcomes: the schema loads clean and the metadata is not there.
 * Every case here asserts a diagnostic naming the declaration, and the meta-layer case asserts the value the
 * author actually gets once the type is where §3.3.3 wants it.
 */
class SchemaAnnotationScopeTest {

    private static final String LIB_ID = "https://example.test/annotation-lib.tn";
    private static final String LIB = """
            !!id:"https://example.test/annotation-lib.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              status => @annotation int32
            }
            """;

    /** The §3.3.3 remedy: the annotation type in a meta-layer schema, named by the user schema's !!meta. */
    private static final String META_HTTP_ID = "https://example.test/meta-http.tn";
    private static final String META_HTTP = """
            !!id:"https://example.test/meta-http.tn"
            !!meta:"https://tson.io/2026/35/m/meta-kernel.tn"
            !!import:"https://tson.io/2026/35/m/meta.tn"
            {
              method => @annotation text
            }
            """;

    private static Tson tson() {
        return Tson.builder().schemaSource(source()).build();
    }

    private static TsonSchemaSource source() {
        return uri -> {
            if (TsonCanonicalIdentity.sameIdentity(uri, LIB_ID)) {
                return LIB;
            }
            if (TsonCanonicalIdentity.sameIdentity(uri, META_HTTP_ID)) {
                return META_HTTP;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
    }

    private static List<Diagnostic> validate(String schema) {
        return tson().validateSchema(schema);
    }

    /**
     * {@code method} is this schema's own declaration, so {@code @method:"POST"} names nothing the annotation
     * namespace can reach -- on the name side of {@code =>} and on the definition side alike, the two §6 keeps
     * separate. Each is reported against the declaration that wrote it, and the message names the remedy.
     */
    @Test
    void aLocallyDeclaredAnnotationTypeIsReportedAtEveryUseSite() {
        List<Diagnostic> problems = validate("""
                !!id:"https://example.test/local-annotations.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                {
                  method => @annotation text
                  @method:"POST" order => { id: int32 }
                  item => @method:"GET" { id: int32 }
                }
                """);

        assertEquals(List.of("/item", "/order"),
                problems.stream().map(d -> d.schemaPointer().orElseThrow()).sorted().toList());
        assertTrue(problems.stream().allMatch(d -> d.message().contains("'@method'")), problems.toString());
        assertTrue(problems.getFirst().message().contains("declare the annotation type in a meta-schema"),
                problems.getFirst().message());
    }

    /** An {@code !!import} is no closer than a local declaration: §3.3.3's hop is through {@code !!meta} only. */
    @Test
    void anImportedAnnotationTypeIsReportedTheSameWay() {
        List<Diagnostic> problems = validate("""
                !!id:"https://example.test/imported-annotations.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                !!import:"https://example.test/annotation-lib.tn"
                {
                  order => @status:201 { id: int32 }
                }
                """);

        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Optional.of("/order"), problems.getFirst().schemaPointer());
        assertTrue(problems.getFirst().message().contains("'@status'"), problems.getFirst().message());
        assertTrue(problems.getFirst().message().contains("brought in by !!import"),
                problems.getFirst().message());
    }

    /** A name nowhere in reach at all -- same verdict, without the "you declared it yourself" half. */
    @Test
    void anAnnotationTypeThatExistsNowhereIsReportedToo() {
        List<Diagnostic> problems = validate("""
                !!id:"https://example.test/unknown-annotation.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                {
                  order => @no_such_annotation:"x" { id: int32 }
                }
                """);

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().message().contains("'@no_such_annotation'"),
                problems.getFirst().message());
        // No remedy clause: there is no declaration of this name anywhere to move, so the only thing to say
        // is that the name does not resolve.
        assertFalse(problems.getFirst().message().contains("brought in by !!import"),
                problems.getFirst().message());
    }

    /** A bare marker names a type too ({@code @T} is {@code @T:_}, §6), so it is checked like a valued one. */
    @Test
    void aValuelessMarkerIsCheckedAsWell() {
        List<Diagnostic> problems = validate("""
                !!id:"https://example.test/marker-annotation.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                {
                  reviewed => @annotation void
                  order => @reviewed { id: int32 }
                }
                """);

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().message().contains("'@reviewed'"), problems.getFirst().message());
    }

    /**
     * The remedy, end to end: {@code method} moved into a meta-layer schema and reached through {@code
     * !!meta} binds its value, on both sides of {@code =>}.
     */
    @Test
    void anAnnotationTypeInTheGoverningMetaBindsItsValue() {
        Tson tson = tson();
        String schema = """
                !!id:"https://example.test/meta-governed.tn"
                !!meta:"https://example.test/meta-http.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                {
                  @method:"POST" order => { id: int32 }
                  item => @method:"GET" { id: int32 }
                }
                """;

        assertEquals(List.of(), tson.validateSchema(schema));

        AnnotatedMap<String, TypeDefinition> entries =
                tson.schemaRegistry().get("https://example.test/meta-governed.tn").orElseThrow().schema().entries();
        assertEquals(Optional.of("POST"), entries.getAnnotations("order").value("method", String.class));
        assertEquals(Optional.of("GET"), entries.get("item").annotations().value("method", String.class));
    }
}
