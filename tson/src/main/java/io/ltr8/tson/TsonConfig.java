package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.*;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.compiler.TsonCompiledMetaRegistry;

import java.util.Objects;

/**
 * Configures and builds a {@link Tson} -- reached via {@link Tson#builder()}, never constructed
 * directly. Three options: {@link #dataBindContext} (for object binding), {@link #schemaSource} (for
 * fetching user schemas beyond the bundled standard library), and {@link #metaNameBinder} (for a consumer's
 * own meta-layer constructors). {@link #build()} constructs a {@link TsonCompiledMetaRegistry} and has it
 * load the bundled meta-kernel/meta.tn/core.tn standard library, then wraps it as a {@link Tson}.
 *
 * <p>The two binding options are deliberately separate and never merged. {@link #dataBindContext} binds
 * <em>data</em> ({@code order} -> {@code Order}); {@link #metaNameBinder} binds a governing meta's own
 * <em>vocabulary</em> ({@code operation} -> {@code Operation}). One name means different things on the two
 * sides, so one namespace holding both would collide the first time a schema type and a meta-layer
 * constructor shared a name.
 */
public final class TsonConfig {

    private DataBindContext dataBindContext = TsonAtomContext.defaultContext();
    private TsonSchemaSource schemaSource = TsonSchemaSource.registeredOnly();
    private DataNameBinder metaNameBinder;

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
     * internally to resolve the standard library itself -- see {@link Tson}'s own Javadoc for why that
     * one's mode is fixed, and {@link #metaNameBinder} for the one thing about it a consumer may extend.
     */
    public TsonConfig dataBindContext(DataBindContext dataBindContext) {
        this.dataBindContext = dataBindContext;
        return this;
    }

    /**
     * The names a consumer's own <b>meta layer</b> adds -- consulted when resolving a schema whose governing
     * meta declares constructors of its own, so that {@code search => !operation { ... }} can bind to that
     * consumer's own {@code @Typename("operation")} class. Defaults to unset: the kernel's own vocabulary
     * alone, which is every schema governed by the bundled meta.tn.
     *
     * <p><b>Composed over the library's own binder, never replacing it</b> ({@link
     * SchemaMetaNameBinder#contextExtendedWith}): {@code record}/{@code enum}/{@code integer_type} and the
     * rest resolve first, and this binder answers only for a name the kernel does not declare. So it adds
     * names and gives up nothing -- in particular not the object-binding mode the standard library must be
     * compiled in (see {@link Tson}), which is what {@code build()} fixes and this does not touch.
     *
     * <p>Distinct from {@link #dataBindContext}, which binds the <em>data</em> a schema describes; this
     * binds the <em>schema vocabulary</em> a meta describes. A consumer with both supplies both.
     */
    public TsonConfig metaNameBinder(DataNameBinder metaNameBinder) {
        this.metaNameBinder = Objects.requireNonNull(metaNameBinder, "metaNameBinder");
        return this;
    }

    public Tson build() {
        // The resolution core is both the store and the on-demand loader; withStandardLibrary loads the
        // bundled meta-kernel/meta/core, and schemaSource is consulted only for other, later URIs. It
        // compiles the standard library in object-binding mode -- the only mode that can (a DOM reader
        // can't resolve the !enum/!integer instances a meta-schema declares), which is why it takes the
        // bind context rather than a resolver that might be the wrong mode. The *mode* is what is fixed
        // here; which names that binder knows is metaNameBinder's to extend. Tson builds the per-mode read
        // registries (DOM and object-binding, the latter bound to dataBindContext) over this one core.
        DataBindContext schemaContext = metaNameBinder == null
                ? SchemaMetaNameBinder.defaultContext()
                : SchemaMetaNameBinder.contextExtendedWith(metaNameBinder);
        TsonCompiledMetaRegistry core = TsonCompiledMetaRegistry.withStandardLibrary(schemaContext, schemaSource);
        return new Tson(core, dataBindContext);
    }
}
