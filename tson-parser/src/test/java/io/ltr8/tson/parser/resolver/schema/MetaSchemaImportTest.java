package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.SchemaParser;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.ast.schema.SchemaMap;
import io.ltr8.tson.schema.MetaSchema;
import io.ltr8.tson.schema.SchemaRegistry;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code SchemaValidator}'s {@code !!import} merging (see its own Javadoc) against the
 * real {@code meta.tn1} fixture, which declares {@code !!import:"...meta-kernel.tn1"} -- register
 * meta-kernel first, then meta.tn1's own declarations, and confirm meta-kernel's names (e.g.
 * {@code atom}, {@code text_type}) are visible and correctly referenced from meta.tn1's own
 * composition-based declarations (e.g. {@code date_type => ~atom & atom_specification & {...}}).
 *
 * <p><b>meta.tn1 can't be registered in full yet</b> -- 4 of its 31 declarations
 * ({@code binary_encoding}, {@code ieee_format}, {@code complex_component}, {@code ordered}) are
 * {@code !enum [...]} constructor-application {@code Instance}s, a construct {@code SchemaResolver}
 * doesn't resolve generically outside {@code MetaKernelParser}'s own hand-rolled meta-kernel
 * bootstrap (see {@code SchemaResolver}'s own Javadoc -- "Instance" is explicitly out of scope
 * there). That's a separate, pre-existing gap, not something import merging itself is blocked on.
 * Three more declarations (`binary`, `float_type`, `complex_type`) reference one of those four as
 * a field type, so registering them too correctly *fails* reference validation -- proven directly
 * below, not worked around. This test registers the 24 declarations whose own dependency closure
 * is otherwise complete, proving the merge mechanism against real content, not just hand-built
 * schemas.
 */
class MetaSchemaImportTest {

    /** Depend (directly) on one of the four unresolved Instance declarations -- see class Javadoc. */
    private static final Set<String> EXCLUDED_TRANSITIVELY = Set.of("binary", "float_type", "complex_type");

    @Test
    void mergesMetaKernelIntoMetaTn1sOwnDeclarationsThatAlreadyResolve() throws IOException {
        MetaSchema metaKernel = MetaKernelParser.parse();
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(metaKernel);

        SchemaDocument doc = new SchemaParser(readMetaFixture()).parseSchemaDocument();
        SchemaResolver resolver = new SchemaResolver();

        // Seeded with meta-kernel's own entries so composition against an imported supertype
        // (date_type => ~atom & atom_specification & {...}) resolves -- but only meta.tn1's own new
        // entries go into the TsonSchema handed to the registry; the import itself supplies the rest.
        Map<String, TypeDefinition> namespace = new LinkedHashMap<>(metaKernel.entries());
        Map<String, TypeDefinition> localOnly = new LinkedHashMap<>();
        for (SchemaMap.Declaration declaration : doc.body().declarations().values()) {
            if (EXCLUDED_TRANSITIVELY.contains(declaration.name())) {
                continue;
            }
            try {
                TypeDefinition resolved = resolver.resolve(declaration, namespace);
                namespace.put(declaration.name(), resolved);
                localOnly.put(declaration.name(), resolved);
            } catch (UnsupportedOperationException ignored) {
                // !enum [...] Instance constructs -- see this class's own Javadoc.
            }
        }
        assertEquals(24, localOnly.size(), "expected exactly the declarations with a complete dependency closure");

        TsonSchema meta = new TsonSchema(doc.id(), doc.meta(), doc.imports(), localOnly);
        TsonSchema registered = registry.register(meta);

        // Meta-kernel's own imported entries are visible in the merged, validated namespace.
        assertTrue(registered.entries().containsKey("atom"));
        assertTrue(registered.entries().containsKey("text_type"));
        // meta.tn1's own composition against an imported supertype resolved and validated correctly.
        assertTrue(registered.entries().containsKey("date_type"));
    }

    @Test
    void registeringBinaryWithoutItsUnresolvedBinaryEncodingFieldCorrectlyFailsValidation() throws IOException {
        MetaSchema metaKernel = MetaKernelParser.parse();
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(metaKernel);

        SchemaDocument doc = new SchemaParser(readMetaFixture()).parseSchemaDocument();
        SchemaResolver resolver = new SchemaResolver();
        Map<String, TypeDefinition> namespace = new LinkedHashMap<>(metaKernel.entries());
        SchemaMap.Declaration binaryDeclaration = doc.body().declarations().get("binary");
        TypeDefinition binary = resolver.resolve(binaryDeclaration, namespace);

        TsonSchema withBinaryOnly = new TsonSchema(doc.id(), doc.meta(), doc.imports(), Map.of("binary", binary));

        assertThrows(io.ltr8.tson.schema.SchemaValidationException.class, () -> registry.register(withBinaryOnly));
    }

    private static String readMetaFixture() throws IOException {
        return Files.readString(Path.of("").toAbsolutePath().resolve("../spec/m/meta.tn1").normalize());
    }
}
