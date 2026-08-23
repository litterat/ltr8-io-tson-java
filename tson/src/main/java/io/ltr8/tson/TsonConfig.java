package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.*;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.compiler.TsonCompiledMetaRegistry;

import java.util.Map;
import java.util.Objects;

/**
 * Configures and builds a {@link Tson} -- reached via {@link Tson#builder()}, never constructed
 * directly. {@link #schemaSource} fetches user schemas beyond the bundled standard library;
 * {@link #bindings}/{@link #profile} say which Java classes the schema's types bind to and, where a class
 * offers several shapes, which one ({@link #dataBindContext} is the long form of the same thing, and the two
 * are mutually exclusive); {@link #metaNameBinder} binds a governing meta's own constructors; and
 * {@link #lenientBinding} lets a class hold fewer fields than its schema declares. {@link #build()}
 * constructs a {@link TsonCompiledMetaRegistry} and has it
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
    private boolean strictBinding = true;
    private Map<String, Class<?>> bindings;
    private String profile;
    private boolean dataBindContextSupplied;

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
        this.dataBindContextSupplied = true;
        return this;
    }

    /**
     * The schema types this application binds, as {@code name -> class} -- the short way to say what
     * {@link #dataBindContext} says the long way.
     *
     * <pre>{@code
     * Tson.builder().schemaSource(source).bindings(Map.of("order", Order.class)).build();
     * }</pre>
     *
     * <p><b>It exists because the long way has three steps and two of them are invisible.</b> A caller who
     * builds only a {@link DataNameBinder} gets atoms unbound ({@code TsonAtomContext.registerDefaults} is
     * the step nothing reminds you of), and a caller who maps their own names without chaining loses the
     * kernel's vocabulary for the schema types that need it. This does all three.
     *
     * <p><b>A name outside the map is an error naming the map</b>, not a class-not-found from whatever was
     * consulted last. The map is this application's statement of what it binds, so a name missing from it is
     * a gap in that statement and the message says as much -- the kernel's own account is kept as the cause.
     *
     * <p>Binds the <em>data</em> a schema describes. A governing meta's own vocabulary is
     * {@link #metaNameBinder}, deliberately a separate namespace: one holding both would collide the first
     * time a schema type and a meta-layer constructor shared a name. Note also that this direction is for
     * <em>reading</em> -- writing resolves a value's type name from its class's own {@code @Typename}, so a
     * class mapped here without one reads but cannot be written.
     *
     * @throws IllegalStateException from {@link #build()} if {@link #dataBindContext} was also supplied --
     *                               a context is built or given, not both
     */
    public TsonConfig bindings(Map<String, Class<?>> bindings) {
        this.bindings = Map.copyOf(bindings);
        return this;
    }

    /**
     * The binding profile for the context {@link #bindings} builds -- selecting among a class's
     * {@code @Profile} constructors, so one class can serve several versions of a schema.
     *
     * <p>A {@link Tson} is one profile: a server speaking two versions builds one per version, each with its
     * own profile, and routes a document to the right one. Nothing here derives the profile from the schema a
     * document names; that mapping is the application's, and it is the one thing the application knows better
     * than this library.
     *
     * <p>Pointing a profile at the wrong version does not bind quietly -- the constructor it selects is
     * checked against that schema's fields, and a disagreement is a {@code TsonBindMismatchException}.
     *
     * @throws IllegalStateException from {@link #build()} if {@link #dataBindContext} was also supplied --
     *                               a profile is fixed when a context is built, so it cannot apply to one
     *                               that arrives already built
     */
    public TsonConfig profile(String profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
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

    /**
     * Lets a bound class hold fewer fields than the schema declares, silently -- off by default.
     *
     * <p>By default the two must agree, and a mismatch is a {@link
     * io.ltr8.tson.compiler.TsonBindMismatchException} when the schema is compiled in bind mode, which is
     * startup for anything compiling its schemas once. That default is the asymmetry between the two ways of
     * being wrong: a strict reader that is wrong says so at startup, in one message naming both sides, and
     * is fixed in minutes; a lenient one that is wrong drops a value from every document and surfaces much
     * later as a field that mysteriously holds its default.
     *
     * <p>Leniency is a real position, not just an escape hatch -- versioned evolution, where a v1 consumer
     * deliberately reads a v2 document and means to ignore what it does not know. This is where that
     * intention gets written down -- and it is the only path on which a field is dropped at all, every
     * mismatch otherwise being settled before a document exists. It is silent by necessity: reporting
     * abandons the construction ({@code ConstructionGuard}), so a lenient reader that reported would hand
     * back {@code null} for exactly the documents it exists to accept.
     *
     * <p>The narrower alternative to reaching for this is {@code @Unbound} on the one component that is the
     * class's own business rather than the wire's.
     */
    public TsonConfig lenientBinding() {
        this.strictBinding = false;
        return this;
    }

    public Tson build() {
        if (dataBindContextSupplied && (bindings != null || profile != null)) {
            throw new IllegalStateException("supply dataBindContext, or bindings/profile which build one for "
                    + "you -- not both. A profile is fixed when a context is built, so it cannot be applied to "
                    + "one that arrives already built");
        }
        if (bindings != null || profile != null) {
            dataBindContext = boundContext();
        }
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
        return new Tson(core, dataBindContext, strictBinding);
    }

    /**
     * The {@link DataBindContext} {@link #bindings}/{@link #profile} describe: the map as a {@link
     * DataNameBinder}, chained over the kernel's own vocabulary rather than replacing it, with this
     * library's atom registrations applied.
     *
     * <p>The chain is a backstop, not the main path -- a map-only binder resolves an ordinary user schema
     * perfectly well, the kernel's names being resolved through the resolution core's own context. It earns
     * its keep where a schema names a kernel or meta record type. That is also why the <em>map</em> authors
     * the failure: the last binder consulted is the backstop, and letting it speak would report a missing
     * line of this application's configuration as "not kernel vocabulary".
     */
    private DataBindContext boundContext() {
        Map<String, Class<?>> mapped = bindings == null ? Map.of() : bindings;
        DataNameBinder binder = name -> {
            Class<?> bound = mapped.get(name);
            if (bound != null) {
                return bound;
            }
            try {
                return SchemaMetaNameBinder.INSTANCE.resolve(name);
            } catch (DataBindException notKernelVocabulary) {
                throw new DataBindException("'" + name + "' is not bound: bindings(...) maps "
                        + mapped.keySet() + ", and it is not the kernel's own vocabulary either",
                        notKernelVocabulary);
            }
        };
        DataBindContext.Builder builder = DataBindContext.builder().nameBinder(binder);
        if (profile != null) {
            builder.profile(profile);
        }
        return TsonAtomContext.registerDefaults(builder.build());
    }
}
