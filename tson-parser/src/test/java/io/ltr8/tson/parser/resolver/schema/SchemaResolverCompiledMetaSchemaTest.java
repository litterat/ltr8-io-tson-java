package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.Parser;
import io.ltr8.tson.parser.SchemaParser;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.resolver.schema.compiled.ParserFactoryRegistry;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonCompiledRegistry;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonSchemaParser;
import io.ltr8.tson.schema.MetaSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.Unit;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@link SchemaResolver#compiledMetaSchema} -- a new resolver, given a {@link
 * TsonCompiledRegistry} that already has meta-kernel and meta.tn1 registered and compiled, can
 * look up core.tn1's own real {@code !!meta} target (meta.tn1) and get back its *compiled* reader,
 * genuinely usable to read real data -- not merely present. Deliberately doesn't touch {@code
 * bindAtomInstance} at all (see that method's own Javadoc for why that's a separate, later step);
 * this only proves the wiring to *reach* a compiled governing schema works.
 */
class SchemaResolverCompiledMetaSchemaTest {

    private static String readBundledCoreSource() {
        try (InputStream in = SchemaResolverCompiledMetaSchemaTest.class.getResourceAsStream("/core.tn1")) {
            if (in == null) {
                throw new IOException("core.tn1 not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static TsonCompiledRegistry loadMetaKernelAndMeta() {
        TsonCompiledRegistry registry = new TsonCompiledRegistry(ParserFactoryRegistry.dom());
        MetaSchema metaKernel = MetaKernelParser.parse();
        registry.register(metaKernel);
        registry.register(MetaTn1Parser.parse(metaKernel));
        return registry;
    }

    @Test
    void coreTn1sOwnMetaTargetResolvesToMetaTn1sCompiledReader() {
        TsonCompiledRegistry registry = loadMetaKernelAndMeta();
        SchemaResolver resolver = new SchemaResolver(registry);
        SchemaDocument coreDocument = new SchemaParser(readBundledCoreSource()).parseSchemaDocument();

        assertEquals("https://tson.io/2026/32/m/meta.tn1", coreDocument.meta());

        Optional<TsonSchemaParser> compiledMeta = resolver.compiledMetaSchema(coreDocument);

        assertTrue(compiledMeta.isPresent());
    }

    @Test
    void theCompiledMetaSchemaGenuinelyReadsRealData() {
        TsonCompiledRegistry registry = loadMetaKernelAndMeta();
        SchemaResolver resolver = new SchemaResolver(registry);
        SchemaDocument coreDocument = new SchemaParser(readBundledCoreSource()).parseSchemaDocument();

        TsonSchemaParser compiledMeta = resolver.compiledMetaSchema(coreDocument).orElseThrow();
        Object result = compiledMeta.get("binary_encoding")
                .read(new Parser("BASE64").parseDocument().root());

        assertEquals("BASE64", result);
    }

    @Test
    void withNoRegistryTheLookupIsEmptyNotAnException() {
        SchemaResolver resolver = new SchemaResolver();
        SchemaDocument coreDocument = new SchemaParser(readBundledCoreSource()).parseSchemaDocument();

        assertTrue(resolver.compiledMetaSchema(coreDocument).isEmpty());
    }

    @Test
    void aRegistryThatNeverGotMetaTn1RegisteredIsAlsoEmpty() {
        TsonCompiledRegistry registry = new TsonCompiledRegistry(ParserFactoryRegistry.dom());
        registry.register(MetaKernelParser.parse()); // meta-kernel only -- no meta.tn1
        SchemaResolver resolver = new SchemaResolver(registry);
        SchemaDocument coreDocument = new SchemaParser(readBundledCoreSource()).parseSchemaDocument();

        assertTrue(resolver.compiledMetaSchema(coreDocument).isEmpty());
    }

    // ── resolveAll(SchemaDocument)'s own new validate-then-derive behavior ──

    private static final String MINI_DOCUMENT = """
            !!id:"https://example.test/mini.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn1"
            {
              void => !unit {}
            }
            """;

    @Test
    void resolveAllDerivesStructureNamespaceFromTheRegistryAutomatically() {
        TsonCompiledRegistry registry = loadMetaKernelAndMeta();
        SchemaResolver resolver = new SchemaResolver(registry);
        SchemaDocument miniDocument = new SchemaParser(MINI_DOCUMENT).parseSchemaDocument();

        // "unit" is neither local to mini.tn1 nor imported by it -- only reachable if resolveAll
        // itself derived the structure namespace from the registry's own meta.tn1 entry (which in
        // turn carries meta-kernel's own entries, merged in via meta.tn1's real !!import).
        TsonSchema resolved = resolver.resolveAll(miniDocument);

        TypeDefinition voidDef = resolved.entries().get("void");
        assertEquals(new Unit(), voidDef.body());
    }

    @Test
    void resolveAllThrowsClearlyWhenTheMetaTargetIsntRegisteredAtAll() {
        TsonCompiledRegistry registry = new TsonCompiledRegistry(ParserFactoryRegistry.dom());
        SchemaResolver resolver = new SchemaResolver(registry);
        SchemaDocument miniDocument = new SchemaParser(MINI_DOCUMENT).parseSchemaDocument();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> resolver.resolveAll(miniDocument));
        assertTrue(thrown.getMessage().contains("meta.tn1"));
        assertTrue(thrown.getMessage().contains("not registered"));
    }

    @Test
    void resolveAllThrowsClearlyWhenTheMetaTargetIsRegisteredButNeverCompiled() {
        TsonCompiledRegistry registry = new TsonCompiledRegistry(ParserFactoryRegistry.dom());
        // Bypasses TsonCompiledRegistry.register() -- registers directly into the wrapped
        // SchemaRegistry, so a resolved TsonSchema exists but no compiled reader was ever built for it.
        registry.schemaRegistry().register(new TsonSchema(
                Optional.of("https://tson.io/2026/32/m/meta.tn1"),
                "https://tson.io/2026/32/m/meta.tn1", java.util.List.of(), Map.of()));
        SchemaResolver resolver = new SchemaResolver(registry);
        SchemaDocument miniDocument = new SchemaParser(MINI_DOCUMENT).parseSchemaDocument();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> resolver.resolveAll(miniDocument));
        assertTrue(thrown.getMessage().contains("no compiled reader"));
    }

    private static final String MINI_DOCUMENT_NO_ID = """
            !!meta:"https://tson.io/2026/32/m/meta.tn1"
            {
              void => !unit {}
            }
            """;

    @Test
    void resolveAllThrowsClearlyWhenIdIsAbsent() {
        TsonCompiledRegistry registry = loadMetaKernelAndMeta();
        SchemaResolver resolver = new SchemaResolver(registry);
        SchemaDocument noIdDocument = new SchemaParser(MINI_DOCUMENT_NO_ID).parseSchemaDocument();

        assertTrue(noIdDocument.id().isEmpty());
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> resolver.resolveAll(noIdDocument));
        assertTrue(thrown.getMessage().contains("!!id"));
        assertTrue(thrown.getMessage().contains("absent"));
    }

    private static final String MINI_DOCUMENT_MALFORMED_ID = """
            !!id:"mini.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn1"
            {
              void => !unit {}
            }
            """;

    @Test
    void resolveAllThrowsClearlyWhenIdIsNotAValidCanonicalIdentity() {
        TsonCompiledRegistry registry = loadMetaKernelAndMeta();
        SchemaResolver resolver = new SchemaResolver(registry);
        SchemaDocument malformedIdDocument = new SchemaParser(MINI_DOCUMENT_MALFORMED_ID).parseSchemaDocument();

        // "mini.tn1" alone is a syntactically valid relative-reference URI, but has no scheme --
        // CanonicalIdentity.of's own rejection, surfaced here via SchemaRegistry.validateIdentity.
        assertThrows(io.ltr8.tson.schema.SchemaValidationException.class,
                () -> resolver.resolveAll(malformedIdDocument));
    }

    private static final String MINI_DOCUMENT_MALFORMED_IMPORT = """
            !!id:"https://example.test/mini.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn1"
            !!import:"meta-kernel.tn1"
            {
              void => !unit {}
            }
            """;

    @Test
    void resolveAllThrowsClearlyWhenAnImportUriIsNotAValidCanonicalIdentity() {
        TsonCompiledRegistry registry = loadMetaKernelAndMeta();
        SchemaResolver resolver = new SchemaResolver(registry);
        SchemaDocument malformedImportDocument = new SchemaParser(MINI_DOCUMENT_MALFORMED_IMPORT).parseSchemaDocument();

        assertEquals(1, malformedImportDocument.imports().size());
        // "meta-kernel.tn1" alone is a syntactically valid relative-reference URI, but has no scheme.
        assertThrows(io.ltr8.tson.schema.SchemaValidationException.class,
                () -> resolver.resolveAll(malformedImportDocument));
    }

    private static final String MINI_DOCUMENT_IMPORT_MERGED = """
            !!id:"https://example.test/mini.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn1"
            !!import:"https://tson.io/2026/32/m/meta-kernel.tn1"
            {
              my_type => unit & {}
            }
            """;

    @Test
    void resolveAllGenuinelyMergesImportedEntriesIntoTheTypeNameNamespace() {
        // A bare type-ref (§8.3) is carried through unverified regardless of whether the target
        // exists anywhere, so that alone wouldn't prove anything -- composition is the real test:
        // resolveComposition does exactly one resolved.get(supertypeName), no fallback, so "unit"
        // (meta-kernel's own, zero fields) is only findable here if !!import's own entries were
        // genuinely merged into the type-name namespace, not just validated as well-formed URIs.
        TsonCompiledRegistry registry = loadMetaKernelAndMeta();
        SchemaResolver resolver = new SchemaResolver(registry);
        SchemaDocument miniDocument = new SchemaParser(MINI_DOCUMENT_IMPORT_MERGED).parseSchemaDocument();

        TsonSchema resolved = resolver.resolveAll(miniDocument);

        // Transitive, per SchemaResolver's own induction: direct supertype + its own supertype chain.
        assertEquals(List.of("unit", "atom", "top"), resolved.entries().get("my_type").supertypes());
        // Imported entries are visible during resolution but never part of the result itself.
        assertEquals(java.util.Set.of("my_type"), resolved.entries().keySet());
    }

    private static final String MINI_DOCUMENT_IMPORT_COLLIDES_WITH_LOCAL = """
            !!id:"https://example.test/mini.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn1"
            !!import:"https://tson.io/2026/32/m/meta-kernel.tn1"
            {
              void => !unit {}
            }
            """;

    @Test
    void resolveAllRejectsALocalDeclarationCollidingWithAnImportedName() {
        // meta-kernel itself already declares "void" -- redeclaring it locally while also importing
        // meta-kernel is exactly SchemaValidator's own "collides with an entry of the same name
        // brought in by !!import" rule, now caught here too, one stage earlier.
        TsonCompiledRegistry registry = loadMetaKernelAndMeta();
        SchemaResolver resolver = new SchemaResolver(registry);
        SchemaDocument miniDocument = new SchemaParser(MINI_DOCUMENT_IMPORT_COLLIDES_WITH_LOCAL).parseSchemaDocument();

        io.ltr8.tson.schema.SchemaValidationException thrown = assertThrows(
                io.ltr8.tson.schema.SchemaValidationException.class, () -> resolver.resolveAll(miniDocument));
        assertTrue(thrown.getMessage().contains("void"));
        assertTrue(thrown.getMessage().contains("!!import"));
    }

    private static final String MINI_DOCUMENT_TWO_IMPORTS_COLLIDE = """
            !!id:"https://example.test/mini.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn1"
            !!import:"https://tson.io/2026/32/m/meta-kernel.tn1"
            !!import:"https://tson.io/2026/32/m/meta.tn1"
            {
              placeholder => unit
            }
            """;

    @Test
    void resolveAllRejectsTheSameNameDeclaredByMoreThanOneImport() {
        // meta.tn1's own registered entries already carry meta-kernel's whole namespace merged in
        // (via meta.tn1's own real !!import) -- so importing both here means "unit" (among many
        // others) is declared by both imports, the "more than one !!import" case specifically.
        TsonCompiledRegistry registry = loadMetaKernelAndMeta();
        SchemaResolver resolver = new SchemaResolver(registry);
        SchemaDocument miniDocument = new SchemaParser(MINI_DOCUMENT_TWO_IMPORTS_COLLIDE).parseSchemaDocument();

        io.ltr8.tson.schema.SchemaValidationException thrown = assertThrows(
                io.ltr8.tson.schema.SchemaValidationException.class, () -> resolver.resolveAll(miniDocument));
        assertTrue(thrown.getMessage().contains("more than one !!import"));
    }
}
