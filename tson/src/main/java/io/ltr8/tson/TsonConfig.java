package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.*;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.compiler.TsonCompiledSchemaRegistry;

/**
 * Configures and builds a {@link Tson} -- reached via {@link Tson#builder()}, never constructed
 * directly. Two options: {@link #dataBindContext} (for object binding) and {@link #schemaSource} (for
 * fetching user schemas beyond the bundled standard library). {@link #build()} constructs a {@link
 * TsonCompiledSchemaRegistry} and has it load the bundled meta-kernel/meta.tn/core.tn standard library, then
 * wraps it as a {@link Tson}.
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
        // The compiled registry is both the store and the on-demand loader; withStandardLibrary loads
        // the bundled meta-kernel/meta/core, and schemaSource is consulted only for other, later URIs.
        // It compiles the standard library in object-binding mode -- the only mode that can (a DOM
        // reader can't resolve the !enum/!integer instances a meta-schema declares), which is why it
        // takes the bind context rather than a resolver that might be the wrong mode.
        TsonCompiledSchemaRegistry compiledRegistry =
                TsonCompiledSchemaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), schemaSource);
        return new Tson(compiledRegistry.schemaRegistry(), compiledRegistry, compiledRegistry, dataBindContext);
    }
}
