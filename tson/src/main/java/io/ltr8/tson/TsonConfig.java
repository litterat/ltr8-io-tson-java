package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.*;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.compiler.config.TsonCompiledRegistry;
import io.ltr8.tson.compiler.config.ValueReaderFactoryResolver;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;

/**
 * Configures and builds a {@link Tson} -- reached via {@link Tson#builder()}, never constructed
 * directly. No configurable options beyond {@link #dataBindContext} yet: the standard library is
 * always meta-kernel/meta.tn/core.tn, fetched from {@link TsonBundledSchemas}; a future pluggable
 * {@link TsonSchemaSource} belongs here too, not as a breaking change to
 * {@link Tson}'s own public shape.
 */
public final class TsonConfig {

    private DataBindContext dataBindContext = TsonAtomContext.defaultContext();
    private TsonSchemaSource schemaSource = TsonSchemaSource.registeredOnly();

    TsonConfig() {
    }

    /**
     * A source for schema documents beyond the bundled standard library -- consulted by the built
     * {@link Tson}'s loader to fetch a {@code !!schema}/{@code !!import}/{@code !!meta} target it
     * doesn't already have registered. The bundled meta-kernel/meta.tn/core.tn are always served
     * first, so this source only needs to know its own URIs; it is a fallback, never an override of
     * the standard library. Defaults to {@link TsonSchemaSource#registeredOnly()} (nothing extra
     * fetchable). This is the seam for loading schemas from local files, or later from a whitelist of
     * allowed hosts/URIs.
     */
    public TsonConfig schemaSource(TsonSchemaSource schemaSource) {
        this.schemaSource = schemaSource;
        return this;
    }

    /**
     * The {@link DataBindContext} the built {@link Tson}'s own {@link Tson#objectReader()}/{@link
     * Tson#objectWriter()} bind against -- defaults to {@link TsonAtomContext#defaultContext()}, the
     * same default {@link TsonObjectReader}'s/{@link TsonObjectWriter}'s own no-arg constructors use.
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
                TsonSchemaCompiler.bind(SchemaMetaNameBinder.defaultContext());
        TsonSchemaRegistry schemaRegistry = new TsonSchemaRegistry();
        TsonCompiledRegistry compiledRegistry = new TsonCompiledRegistry(schemaRegistry, resolver);
        // Bundled standard library always served first; the configured source (default: none)
        // supplies any additional schema URIs -- e.g. local files the CLI collected, or later a
        // whitelist of allowed hosts. It never overrides the three bundled identities.
        TsonSchemaSource composed = uri -> isBundled(uri)
                ? TsonBundledSchemas.fetch(uri)
                : schemaSource.fetch(uri);
        TsonCompiledSchemaLoader loader = TsonSchemaResolver.defaultLoader(compiledRegistry, composed);

        // Meta-kernel's own bootstrap case, registered explicitly -- see TsonBundledSchemas's own
        // class Javadoc for why this step can't just be another loader.load(...) call.
        SchemaDocument metaKernelDocument = new TsonSchemaParser(
                TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID)).parseSchemaDocument();
        TsonSchema resolvedMetaKernel = new TsonSchemaResolver(loader).resolveSchema(metaKernelDocument);
        compiledRegistry.register(resolvedMetaKernel, loader.loadMeta(TsonBundledSchemas.META_KERNEL_ID));

        loader.load(TsonBundledSchemas.META_ID);
        loader.load(TsonBundledSchemas.CORE_ID);

        return new Tson(schemaRegistry, compiledRegistry, loader, dataBindContext);
    }

    private static boolean isBundled(String uri) {
        return uri.equals(TsonBundledSchemas.META_KERNEL_ID)
                || uri.equals(TsonBundledSchemas.META_ID)
                || uri.equals(TsonBundledSchemas.CORE_ID);
    }
}
