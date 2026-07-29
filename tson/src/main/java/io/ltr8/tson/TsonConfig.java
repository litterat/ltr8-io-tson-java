package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.compiler.ValueReaderFactoryRegistry;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.compiler.config.TsonCompiledRegistry;
import io.ltr8.tson.compiler.config.ValueReaderFactoryResolver;
import io.ltr8.tson.compiler.mapper.TsonMapperReader;
import io.ltr8.tson.compiler.mapper.TsonMapperWriter;
import io.ltr8.tson.compiler.resolver.DefaultTsonCompiledSchemaLoader;
import io.ltr8.tson.compiler.resolver.TsonSchemaResolver;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;

/**
 * Configures and builds a {@link Tson} -- reached via {@link Tson#builder()}, never constructed
 * directly. No configurable options beyond {@link #dataBindContext} yet: the standard library is
 * always meta-kernel/meta.tn/core.tn, fetched from {@link TsonBundledSchemas}; a future pluggable
 * {@link io.ltr8.tson.compiler.resolver.TsonSchemaSource} belongs here too, not as a breaking change to
 * {@link Tson}'s own public shape.
 */
public final class TsonConfig {

    private DataBindContext dataBindContext = TsonAtomContext.defaultContext();

    TsonConfig() {
    }

    /**
     * The {@link DataBindContext} the built {@link Tson}'s own {@link Tson#mapperReader()}/{@link
     * Tson#mapperWriter()} bind against -- defaults to {@link TsonAtomContext#defaultContext()}, the
     * same default {@link TsonMapperReader}'s/{@link TsonMapperWriter}'s own no-arg constructors use.
     * Unrelated to (and never overrides) the object-binding-mode context {@link #build()} always uses
     * internally to resolve the standard library itself -- see {@link Tson}'s own Javadoc for why
     * that one is fixed, not configurable.
     */
    public TsonConfig dataBindContext(DataBindContext dataBindContext) {
        this.dataBindContext = dataBindContext;
        return this;
    }

    public Tson build() {
        ValueReaderFactoryResolver resolver =
                ValueReaderFactoryRegistry.bind(SchemaMetaNameBinder.defaultContext());
        TsonSchemaRegistry schemaRegistry = new TsonSchemaRegistry();
        TsonCompiledRegistry compiledRegistry = new TsonCompiledRegistry(schemaRegistry, resolver);
        DefaultTsonCompiledSchemaLoader loader =
                new DefaultTsonCompiledSchemaLoader(compiledRegistry, TsonBundledSchemas::fetch);

        // Meta-kernel's own bootstrap case, registered explicitly -- see TsonBundledSchemas's own
        // class Javadoc for why this step can't just be another loader.load(...) call.
        SchemaDocument metaKernelDocument = new TsonSchemaParser(
                TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID)).parseSchemaDocument();
        TsonSchema resolvedMetaKernel = new TsonSchemaResolver(loader).resolveSchema(metaKernelDocument);
        compiledRegistry.register(resolvedMetaKernel, loader.load(TsonBundledSchemas.META_KERNEL_ID));

        loader.load(TsonBundledSchemas.META_ID);
        loader.load(TsonBundledSchemas.CORE_ID);

        return new Tson(schemaRegistry, compiledRegistry, loader, dataBindContext);
    }
}
