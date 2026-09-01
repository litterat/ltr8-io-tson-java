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
 * The same proof {@link MetaKernelEndToEndTest} gives for meta-kernel.tn, one rung up the schema
 * ladder: compiles the ENTIRE real, registered {@code meta.tn} (meta-kernel + meta.tn, chained
 * the way {@code io.ltr8.tson.compiler.resolver.MetaSchemaImportTest} registers them) and
 * reads real TSON data text against one of meta.tn's own genuinely useful record types.
 */
class MetaTn1CompiledEndToEndTest {

    /**
     * Resolving meta.tn itself now goes through {@code resolveSchema}/{@code bindAtomInstance}, which
     * needs an object-binding-mode compiled reader for meta-kernel (its own Instance declarations,
     * e.g. {@code binary_encoding => !enum [...]}, go through it) -- so meta.tn's own resolution
     * step below is object mode internally, even though the *outer* compile this test itself
     * exercises stays DOM mode (a separate, fresh compilation of the final, already-resolved {@code
     * TsonSchema}, unrelated to how it got resolved).
     */
    private static TsonLinkedSchema registerMeta() {
        TsonSchemaRegistry registry = new TsonSchemaRegistry();
        DataBindContext context = SchemaMetaNameBinder.defaultContext();
        TsonCompiledMetaRegistry compiledRegistry = new TsonCompiledMetaRegistry(context);
        TsonCompiledSchemaLoader loader = compiledRegistry;

        String metaKernelSource = TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID);
        SchemaDocument metaKernelDocument = new TsonSchemaParser(metaKernelSource).parseSchemaDocument();
        TsonSchema metaKernel = new SchemaResolver(loader).resolveSchema(metaKernelDocument);
        TsonLinkedSchema metaKernelMaterialized = registry.register(TsonSchemaLinker.link(metaKernel, registry));
        // meta-kernel governs itself -- loader.load(META_KERNEL_ID) re-bootstraps a fresh
        // TsonCompiledMetaSchema (never cached for that identity, see TsonCompiledMetaRegistry's
        // own Javadoc), which is exactly what register's own governingMeta argument needs here.
        compiledRegistry.register(metaKernelMaterialized.schema(), loader.loadMeta(TsonBundledSchemas.META_KERNEL_ID));

        String source = TsonBundledSchemas.fetch(TsonBundledSchemas.META_ID);
        SchemaDocument metaDocument = new TsonSchemaParser(source).parseSchemaDocument();
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
     * {@code meta.tn} declares 31 entries of its own, but the *registered* schema this compiles --
     * the one a real reader actually needs, since it's what {@link TsonSchemaCompiler#compile} accepts
     * -- also carries meta-kernel's own entries (merged in via meta.tn's real {@code !!import}) plus
     * whatever array-sugar materialization synthesized, matching {@code MetaSchemaImportTest}'s own
     * counts. Every one of them still compiles cleanly with the same registry this whole atom-family
     * + composite factory set already proves against meta-kernel.tn in {@link
     * MetaKernelEndToEndTest}.
     */
    @Test
    void everyRealMetaEntryCompilesCleanly() {
        TsonLinkedSchema meta = registerMeta();
        TsonCompiledSchema compiled = rawCompile(meta);

        for (String name : meta.schema().entries().keySet()) {
            compiled.get(name);
        }
        assertEquals(88, meta.schema().entries().size());
    }

    @Test
    void readsBinaryEncodingEnumMembersAgainstRealData() {
        TsonLinkedSchema meta = registerMeta();
        TsonCompiledSchema compiled = rawCompile(meta);

        Object result = Dom.of((TsonValue) compiled.get("binary_encoding")
                .read(TestDocuments.document("BASE64")));

        assertEquals("BASE64", result);
    }
}
