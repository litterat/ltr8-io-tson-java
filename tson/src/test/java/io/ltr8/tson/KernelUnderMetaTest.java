package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.SchemaPaths;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.net.Ipv4Type;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.net.UuidType;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.base.CanonicalIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundled documents' roots hold the vocabulary an author writes and their machinery sits under a map of each
 * document's own name ([TSON-SCHEMA] §9): meta.tn, the kernel, holds its wait cycle's closure and the
 * concepts that go with it under the open {@code meta}; numeric.tn, temporal.tn and net.tn each import the kernel alone
 * and declare their constructors under {@code numeric}, {@code temporal} and {@code net}; core.tn imports the three and
 * declares the author annotations; and a schema importing a document reserves its map names and its roots' names.
 */
class KernelUnderMetaTest {

    private static List<Diagnostic> problems(String body) {
        return problems("https://tson.io/2026/40/m/core.tn", body);
    }

    private static List<Diagnostic> problems(String imported, String body) {
        return problems(List.of(imported), body);
    }

    private static List<Diagnostic> problems(List<String> imported, String body) {
        StringBuilder imports = new StringBuilder();
        imported.forEach(id -> imports.append("!!import:\"").append(id).append("\"\n"));
        return Tson.standard().validateSchema("""
                !!id:"https://example.test/k.tn"
                %s!schema {
                %s
                }
                """.formatted(imports, body));
    }

    private static TsonLinkedSchema linked(String id) {
        return Tson.standard().schemaRegistry().get(id).orElseThrow();
    }

    /**
     * The roots hold the vocabulary an author writes, and the machinery is under nested maps: the kernel's under
     * {@code meta}, {@code atom}, {@code numeric}, {@code unicode}, {@code net} and {@code binary}, the libraries'
     * constructors under {@code numeric}, {@code temporal} and {@code net}.
     */
    @Test
    void theRootsHoldTheCommonVocabulary() {
        assertEquals(Set.of("void", "boolean", "integer", "int64", "text", "bytes", "iri", "iri_reference", "regex", "doc",
                "enum", "set", "schema"), rootNames(TsonBundledSchemas.META_ID));
        assertEquals(Set.of("number", "rational", "complex", "int8", "int16", "int32", "int128", "int256",
                "uint8", "uint16", "uint32", "uint64", "uint128", "uint256", "float32", "float64"),
                rootNames(TsonBundledSchemas.NUMERIC_ID));
        assertEquals(Set.of("date", "time", "datetime", "duration", "period"), rootNames(TsonBundledSchemas.TEMPORAL_ID));
        assertEquals(Set.of("email", "ipv4", "ipv6", "cidr4", "cidr6", "mac", "hostname", "host", "media_type",
                "uri_reference", "uri", "uuid"), rootNames(TsonBundledSchemas.NET_ID));
        assertEquals(Set.of("deprecated", "title", "comment", "examples", "read_only", "write_only"),
                rootNames(TsonBundledSchemas.CORE_ID));

        TsonLinkedSchema core = linked(TsonBundledSchemas.CORE_ID);
        assertEquals(CanonicalIdentity.canonicalize(TsonBundledSchemas.NET_ID), core.originOf("net.uuid_type"));
        assertEquals(CanonicalIdentity.canonicalize(TsonBundledSchemas.NUMERIC_ID), core.originOf("int32"));
        assertEquals(CanonicalIdentity.canonicalize(TsonBundledSchemas.TEMPORAL_ID),
                core.originOf("temporal.date_type"));
        assertEquals(CanonicalIdentity.canonicalize(TsonBundledSchemas.META_ID), core.originOf("binary.bytes_type"));
        assertTrue(core.isKernel("binary.bytes_type") && core.isKernel("bytes"));
        assertFalse(core.isKernel("numeric.decimal_type") || core.isKernel("net.uuid_type"));
        TsonSchema kernel = linked(TsonBundledSchemas.META_ID).schema();
        assertTrue(kernel.entries().containsKey("meta.type_ref") && kernel.entries().containsKey("meta.map")
                && kernel.entries().containsKey("atom.ordering"));
    }

    /** A library imports the kernel alone: a schema importing one sees its names and the kernel's, and no other's. */
    @Test
    void aLibraryImportedAloneBringsItsOwnNamesAndTheKernels() {
        assertEquals(List.of(), problems(TsonBundledSchemas.TEMPORAL_ID, "event => { at: date  label: text }"));
        assertFalse(problems(TsonBundledSchemas.TEMPORAL_ID, "count => { n: int32 }").isEmpty());
        assertFalse(problems(TsonBundledSchemas.NUMERIC_ID, "who => { id: uuid }").isEmpty());
        assertEquals(List.of(), problems(TsonBundledSchemas.NUMERIC_ID, "date => { day: uint8  month: uint8 }"),
                "temporal.tn's root names are free to a schema that does not import it");
        assertEquals(List.of(), problems(List.of(TsonBundledSchemas.NUMERIC_ID, TsonBundledSchemas.NET_ID),
                "host_load => { at: host  load: float64  id: uuid }"));
    }

    /** The names a document declares at its root, synthetics and its own maps aside. */
    private static Set<String> rootNames(String id) {
        TsonLinkedSchema linked = linked(id);
        String own = CanonicalIdentity.canonicalize(id);
        Set<String> names = new java.util.TreeSet<>();
        linked.schema().entries().forEach((name, value) -> {
            if (linked.originOf(name).equals(own) && !SchemaPaths.isPath(name) && !SchemaPaths.isNestedMap(value)
                    && !linked.schema().factsOf(name).synthetic()) {
                names.add(name);
            }
        });
        return names;
    }

    /**
     * The kernel's {@code meta} is open: a schema contributing a member sees one map, the union of the kernel's members
     * and its own, each keeping its declaring document.
     */
    @Test
    void metaIsOneOpenNamespaceAcrossItsContributors() {
        TsonLinkedSchema linked = Tson.standard().resolve("""
                !!id:"https://example.test/contributes.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema { meta => !schema { code_type => unicode.text_type ^ { max_length?: = 4 } } }
                """);
        Namespace<?> members = (Namespace<?>) ((ValueEntry) linked.schema().entries().get("meta")).value();
        assertTrue(members.containsKey("record") && members.containsKey("code_type"), members.keySet()::toString);
        assertEquals(CanonicalIdentity.canonicalize(TsonBundledSchemas.META_ID), linked.originOf("meta.record"));
        assertTrue(linked.isKernel("meta.record"));
        assertFalse(linked.isKernel("meta.code_type"));
    }

    /**
     * numeric.tn and net.tn add their constructors to the kernel's {@code numeric} and {@code net}: an importer sees one
     * map per path, each member keeping its declaring document, and every schema reserves the kernel's map names.
     */
    @Test
    void aLibraryAddsToTheKernelsMapOfItsName() {
        TsonLinkedSchema core = linked(TsonBundledSchemas.CORE_ID);
        Namespace<?> numeric = (Namespace<?>) ((ValueEntry) core.schema().entries().get("numeric")).value();
        assertTrue(numeric.containsKey("integer_type") && numeric.containsKey("decimal_type"),
                numeric.keySet()::toString);
        assertTrue(core.isKernel("numeric.integer_type") && core.isKernel("net.iri_type"));
        assertEquals(CanonicalIdentity.canonicalize(TsonBundledSchemas.NUMERIC_ID), core.originOf("numeric.decimal_type"));
        assertEquals(CanonicalIdentity.canonicalize(TsonBundledSchemas.NET_ID), core.originOf("net.uri_type"));
        for (String map : List.of("atom", "numeric", "unicode", "net", "binary")) {
            assertFalse(problems(TsonBundledSchemas.TEMPORAL_ID, map + " => { n: integer }").isEmpty(), map);
        }
    }

    /** The machinery's names are free at a schema's root, the bundled ones being paths. */
    @Test
    void aSchemaImportingCoreDeclaresTheMachinerysNames() {
        assertEquals(List.of(), problems("""
                record     => { id: uuid }
                map        => { m: {text => int32} }
                namespace  => [text]
                template   => !enum [a b]
                type_ref  => { k: text }
                field_name => !text ^ { max_length: 8 }
                bounded    => !integer ^ { min: 0  max: 9 }
                value      => { v: int32 }
                uuid_type  => { u: uuid }
                identifier => !text ^ { min_length: 1 }
                core       => { c: text }
                """));
    }

    /** The common vocabulary is at the roots, so a schema importing it may not declare those names. */
    @Test
    void theCommonVocabularyIsReservedAtTheRoot() {
        for (String name : List.of("integer", "boolean", "text", "bytes", "doc", "enum", "set", "iri", "uuid", "int32",
                "date", "title", "meta", "numeric", "temporal", "net")) {
            assertFalse(problems(name + " => { x: uuid }").isEmpty(), name);
        }
    }

    /** {@code enum} is at the kernel's root, so an enumeration applies it bare, and there is no {@code meta.enum}. */
    @Test
    void anEnumIsAppliedBare() {
        assertEquals(List.of(), problems("role => !enum [admin member guest]"));
        assertFalse(problems("role => !meta.enum [admin member guest]").isEmpty());
    }

    /** A value declaring {@code meta} is no schema map, and collides with the kernel's. */
    @Test
    void aSchemaDeclaringMetaAsAnythingButASchemaMapCollides() {
        assertFalse(problems("meta => { id: uuid }").isEmpty());
    }

    /** A member a schema contributes to {@code meta} collides with a kernel member of the same path. */
    @Test
    void aContributedMemberCollidesWithTheKernelsOfOnePath() {
        assertFalse(problems("meta => !schema { record => { id: uuid } }").isEmpty());
        assertFalse(problems("meta => !schema { map => { id: uuid } }").isEmpty());
    }

    /** A schema may contribute a constructor to {@code meta}, and an ordinary declaration names it by its path. */
    @Test
    void aSchemaContributesAConstructorToMeta() {
        assertEquals(List.of(), problems("""
                meta => !schema { short_text_type => unicode.text_type ^ { max_length?: = 16 } }
                name => !meta.short_text_type {}
                """));
        assertEquals(List.of(), problems("""
                meta => !schema { pick_type => meta.atom & atom.specification & { picks?: numeric.count } }
                """));
    }

    /** A bare kernel constructor names nothing: there is no lexical scope reaching into {@code meta}. */
    @Test
    void aBareKernelConstructorIsUnresolved() {
        assertFalse(problems("pct => !integer_type { min: 0  max: 100 }").isEmpty());
        assertEquals(List.of(), problems("pct => !numeric.integer_type { min: 0  max: 100 }"));
    }

    /** {@code integer} is the kernel's own entry, and a refinement of it names it bare. */
    @Test
    void aRefinementNamesTheKernelsInstanceBare() {
        TsonSchema schema = Tson.standard().resolve("""
                !!id:"https://example.test/r.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema { small => !integer ^ { min: 0 } }
                """).schema();
        assertEquals(List.of("integer"), TypeChain.supertypesOf(schema.entries().get("small")));
    }

    /** The annotation types are at their documents' roots, so an annotation is written bare. */
    @Test
    void anAnnotationIsWrittenBare() {
        assertEquals(List.of(), problems("""
                @doc:"a record" a => { x: text }
                @atom.numeric b => int32
                """));
    }

    /** A schema importing the kernel alone has its common vocabulary, every sugar form and the references. */
    @Test
    void aSchemaImportingTheKernelAloneHasItsVocabularyAndSugar() {
        String kernel = TsonBundledSchemas.META_ID;
        assertEquals(List.of(), problems(kernel, """
                @doc:"a point" point => { x: integer  label?: text  shown?: boolean ~ true  tags?: [meta.identifier] }
                """));
        assertEquals(List.of(), problems(kernel, """
                lookup => { m: {text => integer}  pair: [text, integer]  either: (text | integer)
                            k: meta.ref<meta.record> }
                """), "the container sugar's constructors and the references are the kernel's");
    }

    /** A constructor composing the kernel declares; an instance of it is a new form this library does not back. */
    @Test
    void aKernelExtensionDeclaresAndItsInstanceIsAGap() {
        String extension = "uuid2_type => meta.atom & atom.specification & { version?: numeric.count }";
        assertEquals(List.of(), problems(extension));
        List<Diagnostic> instance = problems(extension + "\n  id2 => !uuid2_type {}");
        assertEquals(List.of(Diagnostic.Code.NOT_IMPLEMENTED), instance.stream().map(Diagnostic::code).toList(),
                instance::toString);
    }

    /**
     * The schema model binds what the kernel declares, and the members of the libraries' {@code numeric}, {@code
     * temporal} and {@code net}, by path into the package of its namespace; a path a consumer contributes to an open
     * map is the consumer's binder's.
     */
    @Test
    void theSchemaModelBindsTheKernelDocumentsDeclarations() throws Exception {
        assertEquals(RecordBody.class, SchemaModelNameBinder.INSTANCE.resolve("meta.record"));
        assertEquals(UuidType.class, SchemaModelNameBinder.INSTANCE.resolve("net.uuid_type"));
        assertEquals(RecordField.class, SchemaModelNameBinder.INSTANCE.resolve("meta.record_field"));
        assertEquals(Ipv4Type.class, SchemaModelNameBinder.INSTANCE.resolve("net.ipv4_type"));
        assertEquals(io.ltr8.tson.schema.numeric.DecimalType.class,
                SchemaModelNameBinder.INSTANCE.resolve("numeric.decimal_type"));
        assertEquals(io.ltr8.tson.schema.temporal.DateType.class,
                SchemaModelNameBinder.INSTANCE.resolve("temporal.date_type"));
        assertEquals(io.ltr8.tson.schema.binary.BytesType.class,
                SchemaModelNameBinder.INSTANCE.resolve("binary.bytes_type"));
        assertEquals(io.ltr8.tson.schema.temporal.DateTimeType.class,
                SchemaModelNameBinder.INSTANCE.resolve("temporal.datetime_type"));
        assertEquals(io.ltr8.tson.schema.numeric.IntegerType.class,
                SchemaModelNameBinder.INSTANCE.resolve("numeric.integer_type"));
        assertEquals(io.ltr8.tson.schema.unicode.TextType.class,
                SchemaModelNameBinder.INSTANCE.resolve("unicode.text_type"));
        assertThrows(io.ltr8.bind.DataBindException.class, () -> SchemaModelNameBinder.INSTANCE.resolve("record"));
        assertThrows(io.ltr8.bind.DataBindException.class,
                () -> SchemaModelNameBinder.INSTANCE.resolve("meta.short_text_type"));
    }

    /**
     * A resolved form's root, tagged {@code !schema}, reads as a value of the kernel's {@code meta.schema}: the tag
     * names the kernel root's {@code schema}, an alias of it.
     */
    @Test
    void theRootTagIsTheSpellingOfTheKernelsSchema() {
        Tson tson = Tson.of(ProcessorConfig.defaults().withDataBindContext(SchemaModelNameBinder.defaultContext()));
        List<Diagnostic> problems = new java.util.ArrayList<>();
        tson.treeReader().withImports(List.of(TsonBundledSchemas.META_ID)).withDiagnostics(problems::add)
                .readAs("!schema { a => !meta.record { fields: {} } }", TsonSchema.SCHEMA);
        assertEquals(List.of(), problems);
    }
}
