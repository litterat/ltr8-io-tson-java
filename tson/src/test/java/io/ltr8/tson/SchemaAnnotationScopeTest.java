package io.ltr8.tson;
import io.ltr8.tson.base.ProcessorConfig;

import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.Top;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §3.3.3 on the schema side, under one namespace per schema (SPEC-FEEDBACK.md #7): a schema document's
 * annotations resolve in its own namespace, its entries and its whole import closure, the {@code !!meta} target
 * among them. An annotation type an import declares binds its value through that schema's compiled reader; one this
 * document declares itself is in the namespace, but reading a value against it waits on wait-order resolution, a
 * gap reported as {@code NOT_IMPLEMENTED}; a name nowhere in the closure is the author's error.
 *
 * <p><b>Every case is loud.</b> An annotation kept by name with its value quietly discarded is the worst outcome:
 * the schema loads clean and the metadata is not there. Every case asserts a value bound or a diagnostic naming the
 * declaration.
 */
class SchemaAnnotationScopeTest {

    private static final String LIB_ID = "https://example.test/annotation-lib.tn";
    private static final String LIB = """
            !!id:"https://example.test/annotation-lib.tn"
            !!import:"https://tson.io/2026/39/m/meta.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            !schema {
              status => @annotation int32
            }
            """;

    /** The §3.3.3 remedy: the annotation type in a meta-layer schema, named by the user schema's !!meta. */
    private static final String META_HTTP_ID = "https://example.test/meta-http.tn";
    private static final String META_HTTP = """
            !!id:"https://example.test/meta-http.tn"
            !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
            !!import:"https://tson.io/2026/39/m/meta.tn"
            !schema {
              method => @annotation text
            }
            """;

    private static Tson tson() {
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source())));
    }

    private static SchemaSource source() {
        return uri -> {
            if (CanonicalIdentity.sameIdentity(uri, LIB_ID)) {
                return LIB;
            }
            if (CanonicalIdentity.sameIdentity(uri, META_HTTP_ID)) {
                return META_HTTP;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
    }

    private static List<Diagnostic> validate(String schema) {
        return tson().validateSchema(schema);
    }

    /**
     * {@code method} is this schema's own declaration, so reading {@code @method:"POST"} waits on it -- on the name
     * side of {@code =>} and on the definition side alike, the two §6 keeps separate. Each is reported against the
     * declaration that wrote it, as a gap and never a verdict.
     */
    @Test
    void aLocallyDeclaredAnnotationTypeIsNotYetImplementedAtEveryUseSite() {
        List<Diagnostic> problems = validate("""
                !!id:"https://example.test/local-annotations.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  method => @annotation text
                  @method:"POST" order => { id: int32 }
                  item => @method:"GET" { id: int32 }
                }
                """);

        assertEquals(List.of("/item", "/order"),
                problems.stream().map(d -> d.schemaPointer().orElseThrow()).sorted().toList());
        assertTrue(problems.stream().allMatch(d -> d.message().contains("'@method'")
                && d.code() == Diagnostic.Code.NOT_IMPLEMENTED), problems.toString());
        assertTrue(problems.getFirst().message().contains("declared in this same schema, which is not yet supported"),
                problems.getFirst().message());
    }

    /** An annotation type an {@code !!import} declares is in the namespace, and its value binds. */
    @Test
    void anImportedAnnotationTypeBindsItsValue() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validateSchema("""
                !!id:"https://example.test/imported-annotations.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !!import:"https://example.test/annotation-lib.tn"
                !schema {
                  order => @status:201 { id: int32 }
                }
                """));

        var schema = tson.schemaRegistry().get("https://example.test/imported-annotations.tn").orElseThrow().schema();
        assertEquals(Optional.of(201), schema.factsOf("order").annotations().value("status", Integer.class));
    }

    /** A name nowhere in the closure is the author's error. */
    @Test
    void anAnnotationTypeThatExistsNowhereIsReportedToo() {
        List<Diagnostic> problems = validate("""
                !!id:"https://example.test/unknown-annotation.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  order => @no_such_annotation:"x" { id: int32 }
                }
                """);

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().message().contains("'@no_such_annotation'"),
                problems.getFirst().message());
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code());
    }

    /** A bare marker names a type too ({@code @T} is {@code @T:_}, §6), so it waits like a valued one. */
    @Test
    void aValuelessMarkerIsCheckedAsWell() {
        List<Diagnostic> problems = validate("""
                !!id:"https://example.test/marker-annotation.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  reviewed => @annotation void
                  order => @reviewed { id: int32 }
                }
                """);

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().message().contains("'@reviewed'"), problems.getFirst().message());
        assertEquals(Diagnostic.Code.NOT_IMPLEMENTED, problems.getFirst().code());
    }

    /**
     * {@code method} declared in a meta-layer schema and reached through {@code !!meta} binds its value, on both
     * sides of {@code =>}, as through any import.
     */
    @Test
    void anAnnotationTypeInTheGoverningMetaBindsItsValue() {
        Tson tson = tson();
        String schema = """
                !!id:"https://example.test/meta-governed.tn"
                !!import:"https://example.test/meta-http.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  @method:"POST" order => { id: int32 }
                  item => @method:"GET" { id: int32 }
                }
                """;

        assertEquals(List.of(), tson.validateSchema(schema));

        var governed = tson.schemaRegistry().get("https://example.test/meta-governed.tn").orElseThrow().schema();
        Namespace<Top> entries = governed.entries();
        assertEquals(Optional.of("POST"), entries.getAnnotations("order").value("method", String.class));
        assertEquals(Optional.of("GET"), governed.factsOf("item").annotations().value("method", String.class));
    }
}
