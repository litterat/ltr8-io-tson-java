package io.ltr8.tson.parser.compiler;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.parser.TsonDataParser;
import io.ltr8.tson.parser.TsonSchemaParser;
import io.ltr8.tson.parser.ast.Document;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.binder.TsonObjectBinding;
import io.ltr8.tson.parser.base.TsonAtomContext;
import io.ltr8.tson.parser.resolver.BundledSchemaSource;
import io.ltr8.tson.parser.resolver.DefaultTsonCompiledSchemaLoader;
import io.ltr8.tson.parser.resolver.MetaKernelBootstrapResolver;
import io.ltr8.tson.parser.resolver.TsonSchemaResolver;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.schema.TsonSchemaLinker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The same proof {@link MetaKernelEndToEndTest} gives for meta-kernel.tn1, one rung up the schema
 * ladder: compiles the ENTIRE real, registered {@code meta.tn1} (meta-kernel + meta.tn1, chained
 * the way {@link io.ltr8.tson.parser.base.schema.MetaSchemaImportTest} registers them) and
 * reads real TSON data text against one of meta.tn1's own genuinely useful record types.
 */
class MetaTn1CompiledEndToEndTest {

    /**
     * Resolving meta.tn1 itself now goes through {@code resolveSchema}/{@code bindAtomInstance}, which
     * needs an object-binding-mode compiled reader for meta-kernel (its own Instance declarations,
     * e.g. {@code binary_encoding => !enum [...]}, go through it) -- so meta.tn1's own resolution
     * step below is object mode internally, even though the *outer* compile this test itself
     * exercises stays DOM mode (a separate, fresh compilation of the final, already-resolved {@code
     * TsonSchema}, unrelated to how it got resolved).
     */
    private static TsonSchema registerMeta() {
        TsonSchema metaKernelBootstrap = MetaKernelBootstrapResolver.getMetaKernelSchema();
        TsonSchemaRegistry registry = new TsonSchemaRegistry();
        TsonLinkedSchema materializedMetaKernelBootstrap = TsonSchemaLinker.linkBootstrap(metaKernelBootstrap);

        DataBindContext context = TsonAtomContext.defaultContext();
        TsonParserFactoryRegistry objectFactories = TsonObjectBinding.factoryRegistry(materializedMetaKernelBootstrap, context);
        TsonCompiledRegistry compiledRegistry = new TsonCompiledRegistry(objectFactories);
        DefaultTsonCompiledSchemaLoader loader = new DefaultTsonCompiledSchemaLoader(compiledRegistry);

        String metaKernelSource = BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.META_KERNEL_ID);
        SchemaDocument metaKernelDocument = new TsonSchemaParser(metaKernelSource).parseSchemaDocument();
        TsonSchema metaKernel = new TsonSchemaResolver(loader).resolveSchema(metaKernelDocument);
        TsonLinkedSchema metaKernelMaterialized = registry.register(TsonSchemaLinker.link(metaKernel, registry));
        compiledRegistry.register(metaKernelMaterialized.schema());

        String source = BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.META_TN1_ID);
        SchemaDocument metaDocument = new TsonSchemaParser(source).parseSchemaDocument();
        TsonSchema meta = new TsonSchemaResolver(loader).resolveSchema(metaDocument);

        return registry.register(TsonSchemaLinker.link(meta, registry)).schema();
    }

    /**
     * {@code meta.tn1} declares 31 entries of its own, but the *registered* schema this compiles --
     * the one a real reader actually needs, since it's what {@link TsonCompiledSchema#compile} accepts
     * -- also carries meta-kernel's own entries (merged in via meta.tn1's real {@code !!import}) plus
     * whatever array-sugar materialization synthesized, matching {@code MetaSchemaImportTest}'s own
     * counts. Every one of them still compiles cleanly with the same registry this whole atom-family
     * + composite factory set already proves against meta-kernel.tn1 in {@link
     * MetaKernelEndToEndTest}.
     */
    @Test
    void everyRealMetaEntryCompilesCleanly() {
        TsonSchema meta = registerMeta();
        TsonCompiledSchema compiled = TsonSchemaCompiler.compile(meta, TsonParserFactoryRegistry.dom());

        for (String name : meta.entries().keySet()) {
            compiled.get(name);
        }
        assertEquals(90, meta.entries().size());
    }

    @Test
    void readsBinaryEncodingEnumMembersAgainstRealData() {
        TsonSchema meta = registerMeta();
        TsonCompiledSchema compiled = TsonSchemaCompiler.compile(meta, TsonParserFactoryRegistry.dom());
        Document document = new TsonDataParser("BASE64").parseDocument();

        Object result = compiled.get("binary_encoding").read(document.root());

        assertEquals("BASE64", result);
    }
}
