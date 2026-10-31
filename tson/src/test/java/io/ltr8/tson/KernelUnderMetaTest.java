package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Ipv4Type;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.UuidType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kernel under its own name ({@code SPEC-FEEDBACK.md #15}): every kernel declaration is a member of meta.tn's one
 * root entry, {@code meta}, named by its path; core names the common ones at its root by alias, an alias being the
 * same entry; and a constructor composing the kernel is an ordinary declaration whose instances this library backs
 * only for the forms it implements.
 */
class KernelUnderMetaTest {

    private static List<Diagnostic> problems(String body) {
        return Tson.standard().validateSchema("""
                !!id:"https://example.test/k.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                %s
                }
                """.formatted(body));
    }

    /** The kernel document holds one root entry, and every other entry is a member of it, named by its path. */
    @Test
    void theKernelsRootHoldsOneEntryAndEveryDeclarationIsAMemberOfIt() {
        TsonSchema kernel = Tson.standard().schemaRegistry().get(TsonBundledSchemas.META_ID).orElseThrow().schema();
        assertTrue(kernel.entries().containsKey("meta"));
        assertTrue(kernel.entries().containsKey("meta.record"));
        assertTrue(kernel.entries().containsKey("meta.uuid_type"));
        assertFalse(kernel.entries().containsKey("record"));
        assertTrue(kernel.entries().keySet().stream()
                .allMatch(name -> name.equals("meta") || name.startsWith("meta.") || name.startsWith("array_")));
    }

    /** A name the kernel declares is free at a schema's root once the kernel's own names sit under {@code meta}. */
    @Test
    void aSchemaImportingCoreDeclaresTheKernelsMemberNames() {
        assertEquals(List.of(), problems("""
                record    => { id: uuid }
                map       => { m: {text => int32} }
                namespace => [text]
                bounded   => !integer ^ { min: 0 }
                """));
    }

    /** Core names {@code enum} at its root, so an enum applies it bare; the kernel's path applies the same entry. */
    @Test
    void anEnumAppliesCoresAliasOrTheKernelsPath() {
        assertEquals(List.of(), problems("""
                role  => !enum [admin member guest]
                level => !meta.enum [low high]
                """));
    }

    /** A schema importing the kernel may not declare the kernel's one root name. */
    @Test
    void aSchemaDeclaringMetaCollidesWithTheKernel() {
        assertFalse(problems("meta => { id: uuid }").isEmpty());
    }

    /** A bare kernel constructor names nothing: there is no lexical scope reaching into {@code meta}. */
    @Test
    void aBareKernelConstructorIsUnresolved() {
        assertFalse(problems("pct => !integer_type { min: 0  max: 100 }").isEmpty());
        assertEquals(List.of(), problems("pct => !meta.integer_type { min: 0  max: 100 }"));
    }

    /** Core's {@code integer} is an alias of {@code meta.integer}: a refinement through it IS-A the kernel's entry. */
    @Test
    void aRefinementThroughCoresAliasNamesTheKernelsInstance() {
        TsonSchema schema = Tson.standard().resolve("""
                !!id:"https://example.test/r.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema { small => !integer ^ { min: 0 } }
                """).schema();
        assertEquals(List.of("meta.integer"), TypeChain.supertypesOf(schema.entries().get("small")));
    }

    /** An annotation names its type by a qualified name, and core's alias of it is the same annotation type. */
    @Test
    void anAnnotationIsNamedByItsPathOrByCoresAlias() {
        assertEquals(List.of(), problems("""
                @meta.doc:"by path" a => { x: text }
                @doc:"by core's alias" b => { y: text }
                @meta.deprecated c => { z: text }
                """));
    }

    /** A constructor composing the kernel declares; an instance of it is a new form this library does not back. */
    @Test
    void aKernelExtensionDeclaresAndItsInstanceIsAGap() {
        String extension = "uuid2_type => meta.atom & meta.atom_specification & { version?: meta.non_negative_integer }";
        assertEquals(List.of(), problems(extension));
        List<Diagnostic> instance = problems(extension + "\n  id2 => !uuid2_type {}");
        assertEquals(List.of(Diagnostic.Code.NOT_IMPLEMENTED), instance.stream().map(Diagnostic::code).toList(),
                instance::toString);
    }

    /** A name under the kernel's {@code meta} or net.tn's {@code net} binds to the schema model by its last segment. */
    @Test
    void theSchemaModelBindsAKernelPathByItsLastSegment() throws Exception {
        assertEquals(RecordBody.class, SchemaMetaNameBinder.INSTANCE.resolve("meta.record"));
        assertEquals(UuidType.class, SchemaMetaNameBinder.INSTANCE.resolve("meta.uuid_type"));
        assertEquals(Ipv4Type.class, SchemaMetaNameBinder.INSTANCE.resolve("net.ipv4_type"));
        assertThrows(io.ltr8.bind.DataBindException.class, () -> SchemaMetaNameBinder.INSTANCE.resolve("record"));
    }

    /** A resolved form's root, tagged {@code !schema}, reads as a value of the kernel's {@code meta.schema}. */
    @Test
    void theRootTagIsTheSpellingOfTheKernelsSchema() {
        Tson tson = Tson.of(ProcessorConfig.defaults().withDataBindContext(SchemaMetaNameBinder.defaultContext()));
        List<Diagnostic> problems = new java.util.ArrayList<>();
        tson.treeReader().withImports(List.of(TsonBundledSchemas.META_ID)).withDiagnostics(problems::add)
                .readAs("!schema { a => !meta.record { fields: {} } }", TsonSchema.SCHEMA);
        assertEquals(List.of(), problems);
    }
}
