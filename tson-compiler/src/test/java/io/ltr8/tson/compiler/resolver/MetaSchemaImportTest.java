package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.TsonCompiledSchemaLoader;
import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.TsonCompiledMetaRegistry;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.schema.meta.Ipv4Type;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.compiler.TsonSchemaLinker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code !!import} merging against a real bundled document importing the kernel: net.tn, resolved via {@link
 * SchemaResolver#resolveSchema} over a registered kernel, whose own constructors under {@code net} compose the
 * kernel's {@code meta.atom} and {@code meta.atom_specification} -- names visible only through the merge.
 */
class MetaSchemaImportTest {

    /**
     * The kernel resolved the ordinary way and registered (the registry refuses the raw bootstrap output, {@code
     * bootstrap() == true}), then net.tn's own document resolved over it -- its raw, unregistered, local-only result,
     * so {@code TsonSchemaRegistry#register}'s import merge runs one stage later, in the test.
     */
    private static TsonSchema parseNet(TsonSchemaRegistry registry) {
        DataBindContext context = SchemaMetaNameBinder.defaultContext();
        TsonCompiledMetaRegistry compiledRegistry = new TsonCompiledMetaRegistry(context);
        TsonCompiledSchemaLoader loader = compiledRegistry;

        SchemaDocument kernelDocument = new TsonSchemaParser(TsonBundledSchemas.fetch(TsonBundledSchemas.META_ID))
                .parseSchemaDocument();
        TsonSchema kernel = new SchemaResolver(loader).resolveSchema(kernelDocument);
        TsonLinkedSchema registeredKernel = registry.register(TsonSchemaLinker.link(kernel, registry));
        compiledRegistry.register(registeredKernel.schema());

        SchemaDocument netDocument = new TsonSchemaParser(TsonBundledSchemas.fetch(TsonBundledSchemas.NET_ID))
                .parseSchemaDocument();
        return new SchemaResolver(loader).resolveSchema(netDocument);
    }

    @Test
    void mergesTheKernelIntoNetTnsOwnDeclarations() {
        TsonSchemaRegistry registry = new TsonSchemaRegistry();

        TsonSchema net = parseNet(registry);
        // `net`, its eight constructors, the eight instances at the root, and the two arrays the desugar phase
        // lifts at the root: `within`/`excluding` are `[meta.value]` and `types`/`suffixes` `[meta.text]`.
        assertEquals(19, net.entries().size(), "expected every net.tn declaration to resolve");

        TsonLinkedSchema registered = registry.register(TsonSchemaLinker.link(net, registry));

        // The kernel's imported entries are visible in the merged, validated namespace.
        assertTrue(registered.schema().entries().containsKey("meta.atom"));
        assertTrue(registered.schema().entries().containsKey("meta.atom_specification"));
        // net.tn's own composition against an imported supertype resolved and validated correctly.
        assertTrue(registered.schema().entries().containsKey("net.ipv4_type"));
        assertEquals(Ipv4Type.UNCONSTRAINED, registered.schema().entries().get("ipv4"));
    }

    @Test
    void registeringAConstructorWithoutItsImportedVocabularyCorrectlyFailsValidation() {
        TsonSchemaRegistry registry = new TsonSchemaRegistry();

        TsonSchema net = parseNet(registry);
        Top cidr4Type = net.entries().get("net.cidr4_type");

        // net.cidr4_type's facets are typed by the kernel's entries, which this schema, importing nothing, lacks.
        TsonSchema withConstructorOnly = new TsonSchema(net.id(), List.of(), Map.of("net.cidr4_type", cidr4Type));

        assertThrows(io.ltr8.tson.base.SchemaValidationException.class,
                () -> registry.register(TsonSchemaLinker.link(withConstructorOnly, registry)));
    }
}
