package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.SchemaParser;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.ast.schema.SchemaMap;
import io.ltr8.tson.schema.MetaSchema;
import io.ltr8.tson.schema.SchemaRegistry;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <p><b>meta.tn1 now registers in full, all 31 declarations</b> (2026-07-24, once {@code
 * SchemaResolver} gained generic {@code Instance} resolution -- Phase B step 4) -- previously 4 of
 * its 31 declarations ({@code binary_encoding}, {@code ieee_format}, {@code complex_component},
 * {@code ordered}, all {@code !enum [...]}) had to be skipped, and 3 more ({@code binary}, {@code
 * float_type}, {@code complex_type}) that reference one of those four as a field type had to be
 * excluded too (registering them without their dependency present correctly failed validation).
 * With generic {@code Instance} resolution in place, every one of the 31 resolves in a single
 * source-order pass (meta.tn1's own declaration order already has each dependency before its use --
 * unlike meta-kernel.tn1 itself, which needs {@code MetaKernelParser}'s own two-pass ordering for
 * forward references like {@code boolean => !enum [...]} preceding {@code enum}'s own declaration),
 * and the merged, validated registration succeeds outright.
 */
class MetaSchemaImportTest {

    @Test
    void mergesMetaKernelIntoAllThirtyOneOfMetaTn1sOwnDeclarations() throws IOException {
        MetaSchema metaKernel = MetaKernelParser.parse();
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(metaKernel);

        SchemaDocument doc = new SchemaParser(readMetaFixture()).parseSchemaDocument();
        SchemaResolver resolver = new SchemaResolver();

        // Seeded with meta-kernel's own entries so composition against an imported supertype
        // (date_type => ~atom & atom_specification & {...}) and constructor-application targets
        // reached through the structure namespace (binary_encoding => !enum [...], §3.3.1) both
        // resolve -- but only meta.tn1's own new entries go into the TsonSchema handed to the
        // registry; the import itself supplies the rest.
        Map<String, TypeDefinition> namespace = new LinkedHashMap<>(metaKernel.entries());
        Map<String, TypeDefinition> localOnly = new LinkedHashMap<>();
        for (SchemaMap.Declaration declaration : doc.body().declarations().values()) {
            TypeDefinition resolved = resolver.resolve(declaration, namespace);
            namespace.put(declaration.name(), resolved);
            localOnly.put(declaration.name(), resolved);
        }
        assertEquals(31, localOnly.size(), "expected every meta.tn1 declaration to resolve");

        TsonSchema meta = new TsonSchema(doc.id(), doc.meta(), doc.imports(), localOnly);
        TsonSchema registered = registry.register(meta);

        // Meta-kernel's own imported entries are visible in the merged, validated namespace.
        assertTrue(registered.entries().containsKey("atom"));
        assertTrue(registered.entries().containsKey("text_type"));
        // meta.tn1's own composition against an imported supertype resolved and validated correctly.
        assertTrue(registered.entries().containsKey("date_type"));
        // The four constructor-application (!enum [...]) declarations previously excluded now
        // resolve too, bound generically via TsonMapperReader against Atom.class.
        assertEquals(new EnumBody(List.of("BASE64", "BASE64URL", "BASE32", "HEX")),
                registered.entries().get("binary_encoding").body());
        // ...and the three declarations that reference one of those four as a field type now
        // register successfully as well, since their dependency is present in the same schema.
        assertTrue(registered.entries().containsKey("binary"));
        assertTrue(registered.entries().containsKey("float_type"));
        assertTrue(registered.entries().containsKey("complex_type"));
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
