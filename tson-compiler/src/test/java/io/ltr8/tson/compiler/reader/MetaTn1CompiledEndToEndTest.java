package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TestDocuments;
import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.*;
import io.ltr8.tson.compiler.TsonCompiledMetaRegistry;
import io.ltr8.tson.compiler.TsonCompiledSchemaLoader;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.resolver.SchemaResolver;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.compiler.TsonSchemaLinker;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The same proof {@link MetaKernelEndToEndTest} gives for the bootstrap's output, for the kernel as it is
 * registered: meta.tn resolved the ordinary way through {@code SchemaResolver} against the pre-loaded stand-in,
 * linked, registered and compiled whole, then read with real TSON data text against one of its record types.
 */
class MetaTn1CompiledEndToEndTest {

    /**
     * The kernel resolved through {@code resolveSchema}/{@code bindAtomInstance}, whose instance declarations
     * ({@code boolean => !enum [...]}) read through the loader's object-binding-mode stand-in, then registered;
     * then numeric.tn resolved over it and registered. The outer compile this test exercises stays tree mode.
     */
    private static TsonLinkedSchema registerMeta() {
        TsonSchemaRegistry registry = new TsonSchemaRegistry();
        DataBindContext context = SchemaMetaNameBinder.defaultContext();
        TsonCompiledMetaRegistry compiledRegistry = new TsonCompiledMetaRegistry(context);
        TsonCompiledSchemaLoader loader = compiledRegistry;

        SchemaDocument kernelDocument = new TsonSchemaParser(TsonBundledSchemas.fetch(
                TsonBundledSchemas.META_ID)).parseSchemaDocument();
        TsonSchema kernel = new SchemaResolver(loader).resolveSchema(kernelDocument);
        TsonLinkedSchema linkedKernel = registry.register(TsonSchemaLinker.link(kernel, registry));
        // Registered with the compiled registry too, which compiles it as the kernel's vocabulary.
        compiledRegistry.register(linkedKernel.schema());

        SchemaDocument metaDocument = new TsonSchemaParser(TsonBundledSchemas.fetch(TsonBundledSchemas.NUMERIC_ID))
                .parseSchemaDocument();
        TsonSchema meta = new SchemaResolver(loader).resolveSchema(metaDocument);
        return registry.register(TsonSchemaLinker.link(meta, registry));
    }

    /**
     * The raw {@link TsonCompiledSchema} underneath a throwaway bootstrap {@link
     * TsonCompiledMetaSchema} -- this test's own outer compile is plain DOM mode against the global
     * registry directly, not against any real governing meta (mirrors {@code
     * MetaKernelEndToEndTest#rawCompile}).
     */
    private static TsonCompiledSchema rawCompile(TsonLinkedSchema linked) {
        return TsonSchemaCompiler.compile(linked, ValueReaderFactoryRegistry.tree());
    }

    /**
     * numeric.tn's registered entries -- its own, the kernel's it imports, and the synthetics their sugar lifts -- each
     * compile cleanly with the same factory set {@link MetaKernelEndToEndTest} proves against the bootstrap's output.
     */
    @Test
    void everyRealMetaEntryCompilesCleanly() {
        TsonLinkedSchema meta = registerMeta();
        TsonCompiledSchema compiled = rawCompile(meta);

        for (String name : meta.schema().entries().keySet()) {
            compiled.get(name);
        }
        assertEquals(115, meta.schema().entries().size());
    }

    @Test
    void readsTheOrderingEnumsMembersAgainstRealData() {
        TsonLinkedSchema meta = registerMeta();
        TsonCompiledSchema compiled = rawCompile(meta);

        Object result = Dom.of((TsonValue) compiled.get("atom.ordering")
                .read(TestDocuments.document("TOTAL")));

        assertEquals("TOTAL", result);
    }
}
