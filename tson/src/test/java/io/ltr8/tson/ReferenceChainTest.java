package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §8.3: a reference is a hop, not a rewrite.
 *
 * <p>Resolved output states the chain as the author wrote it. A type position naming a {@code REFERENCE}
 * entry keeps that name, no annotation records where it "really" points, and the chain stays walkable end to
 * end — which it had to anyway, since {@code reference.target} was never flattened and the entries never
 * left the namespace.
 *
 * <p><b>A processor collapses the chain when it compiles readers</b> — after linking, once per entry, where
 * the whole namespace is present ({@code TsonSchemaCompiler}). That is an implementation's choice of moment,
 * not a property of resolved output: the walk happened either way, and rewriting the output as well left two
 * representations to keep in step and a summary ({@code @alias}) that dropped the intermediate hops. §8.3
 * states both halves: a processor MAY collapse after linking, when it compiles for reading, and MUST NOT
 * collapse in resolved output.
 */
class ReferenceChainTest {

    private static Tson tson() {
        return Tson.builder().dataBindContext(SchemaMetaNameBinder.defaultContext()).build();
    }

    private static TypeRef fieldType(TypeDefinition definition, String field) {
        return assertInstanceOf(RecordBody.class, definition.body()).fields().stream()
                .filter(f -> f.name().equals(field)).findFirst().orElseThrow().type();
    }

    private static TypeDefinition kernelEntry(String name) {
        return tson().bindRegistry().core()
                .resolveLinked(TsonBundledSchemas.META_KERNEL_ID).schema().entries().get(name);
    }

    /** The kernel's own case: {@code record_field.name} is declared {@code field_name} and stays it. */
    @Test
    void aKernelReferenceAtAFieldTypeNamesWhatWasWritten() {
        TypeRef name = fieldType(kernelEntry("record_field"), "name");
        assertEquals("field_name", name.name());
        assertTrue(name.annotations().isEmpty(), "nothing is attached to record where it points");

        assertEquals("type_name", fieldType(kernelEntry("type_ref"), "name").name());
    }

    /** A position naming something that is not a reference is unaffected, as it always was. */
    @Test
    void aNonReferenceIsUnaffected() {
        assertEquals("type_ref", fieldType(kernelEntry("record_field"), "type").name());
    }

    private static final String CHAIN = """
              a => !text ^ { max_length: 3 }
              b => a
              holder => { f: b }""";

    private static TypeDefinition resolve(String body, String entry) {
        return Tson.builder().dataBindContext(SchemaMetaNameBinder.defaultContext()).build()
                .resolve(schema(body)).schema().entries().get(entry);
    }

    private static List<Diagnostic> validate(String document) {
        Tson tson = Tson.builder().build();
        tson.resolve(schema(CHAIN));
        return tson.validate("!!schema:\"https://example.test/chain.tn\"\n" + document);
    }

    private static String schema(String body) {
        return """
                !!id:"https://example.test/chain.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                { %s }
                """.formatted(body);
    }

    /** Every hop is stated: the use site names {@code b}, {@code b} names {@code a}, {@code a} is the type. */
    @Test
    void aChainIsStatedAsWritten() {
        assertEquals("b", fieldType(resolve(CHAIN, "holder"), "f").name());
        assertEquals(TypeRef.of("a"), assertInstanceOf(io.ltr8.tson.schema.meta.Reference.class,
                resolve(CHAIN, "b").body()).target());
        assertInstanceOf(io.ltr8.tson.schema.meta.TextType.class, resolve(CHAIN, "a").body());
    }

    /** An argument list is no different -- a lifted synthetic's element type names what was written too. */
    @Test
    void aNestedArgumentNamesWhatWasWritten() {
        TypeDefinition holder = resolve("  a => text\n  holder => { f: [a] }", "holder");
        TypeDefinition injected = resolve("  a => text\n  holder => { f: [a] }",
                fieldType(holder, "f").name());
        assertEquals("a", assertInstanceOf(io.ltr8.tson.schema.meta.ArrayBody.class,
                injected.body()).elementType().name());
    }

    /**
     * The behavioural half, and the reason none of the above costs anything: a read still reaches the end of
     * the chain, because the reader for a {@code REFERENCE} entry <em>is</em> its target's reader. The
     * constraint at the end of the chain applies at a position two hops in front of it.
     */
    @Test
    void aReadStillReachesTheEndOfTheChain() {
        List<Diagnostic> ok = validate("!holder { f: abc }");
        assertTrue(ok.isEmpty(), ok.toString());
        assertEquals(1, validate("!holder { f: abcd }").size());
    }

    /**
     * And it is named for the hop the author wrote, not the type at the end. The chain here ends in
     * {@code text}, which the author never mentions at this position.
     */
    @Test
    void aDiagnosticNamesTheHopTheAuthorWrote() {
        Diagnostic problem = validate("!holder { f: abcd }").getFirst();
        assertTrue(problem.message().contains("'b'"), problem.message());
    }
}
