package io.ltr8.tson.parser.config;

import io.ltr8.tson.parser.TsonSchemaParser;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.parser.compiler.ValueReaderFactoryRegistry;
import io.ltr8.tson.parser.resolver.BundledSchemaSource;
import io.ltr8.tson.parser.resolver.DefaultTsonCompiledSchemaLoader;
import io.ltr8.tson.parser.resolver.TsonSchemaResolver;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaLinker;
import io.ltr8.tson.schema.TsonSchemaRegistry;

/**
 * The "load the standard library" front door -- bootstraps meta-kernel/meta.tn1/core.tn1 into a
 * fresh, governed environment (previously a sequence every real schema-compiling test hand-rolled
 * for itself, e.g. {@code TinySchemaImportsCoreTn1Test}/{@code CoreSchemaImportTest} in this
 * module's own tests, and {@code tson-cli}'s own internal {@code StandardLibrary} helper, which this
 * class now replaces), then resolves and compiles caller-supplied schemas governed by one of them.
 *
 * <pre>{@code
 * TsonStandardLibrary library = TsonStandardLibrary.builder().build();
 * TsonCompiledMetaSchema compiled = library.compile(schemaText, ValueReaderFactoryRegistry.dom());
 * Object value = compiled.compiledSchema().get("my_type").read(dataValue);
 * }</pre>
 *
 * <p><b>Resolution always runs in object-binding mode, regardless of what mode a caller ultimately
 * wants to read data in.</b> Resolving an {@code Instance}/{@code AtomRefinement} declaration
 * (meta.tn1's/core.tn1's own {@code binary_encoding => !enum [...]}, {@code int32 => !integer ^
 * {...}}, and so on -- there are many) calls {@code DefinitionResolver.bindAtomInstance}, which
 * casts its governing meta-schema's own reader output straight to {@code schema.meta.Top} -- a DOM
 * reader's plain {@code Map}/{@code List} output fails that cast outright. Only *compilation* of an
 * already-resolved schema (see {@link #compile(TsonLinkedSchema, ValueReaderFactoryResolver)}) is
 * free to pick a different mode, since it just dispatches an already-built {@code Top} body tree to
 * a factory by constructor name -- it never re-runs resolution. {@link #resolve} therefore takes no
 * mode parameter at all; only {@link #compile} does.
 *
 * <p>Only supports a schema governed by (and importing only from) meta-kernel/meta.tn1/core.tn1 --
 * a real, disk/HTTP-backed {@link io.ltr8.tson.parser.resolver.TsonSchemaSource} for arbitrary other
 * governing chains is its own, separately tracked backlog item; {@link Builder} is the natural place
 * for that to plug in once it exists.
 */
public final class TsonStandardLibrary {

    private final TsonSchemaRegistry schemaRegistry;
    private final TsonCompiledRegistry compiledRegistry;
    private final DefaultTsonCompiledSchemaLoader loader;

    private TsonStandardLibrary(TsonSchemaRegistry schemaRegistry, TsonCompiledRegistry compiledRegistry,
                                 DefaultTsonCompiledSchemaLoader loader) {
        this.schemaRegistry = schemaRegistry;
        this.compiledRegistry = compiledRegistry;
        this.loader = loader;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builds a {@link TsonStandardLibrary} -- no configurable options yet (the standard library is
     * always meta-kernel/meta.tn1/core.tn1, fetched from {@link BundledSchemaSource}); a future
     * pluggable {@link io.ltr8.tson.parser.resolver.TsonSchemaSource} belongs here, not as a
     * breaking change to this class's own public shape.
     */
    public static final class Builder {

        private Builder() {
        }

        public TsonStandardLibrary build() {
            ValueReaderFactoryResolver resolver =
                    ValueReaderFactoryRegistry.bind(SchemaMetaNameBinder.defaultContext());
            TsonSchemaRegistry schemaRegistry = new TsonSchemaRegistry();
            TsonCompiledRegistry compiledRegistry = new TsonCompiledRegistry(schemaRegistry, resolver);
            DefaultTsonCompiledSchemaLoader loader =
                    new DefaultTsonCompiledSchemaLoader(compiledRegistry, BundledSchemaSource.INSTANCE);

            // Meta-kernel's own bootstrap case, registered explicitly -- see BundledSchemaSource's
            // own class Javadoc for why this step can't just be another loader.load(...) call.
            SchemaDocument metaKernelDocument = new TsonSchemaParser(
                    BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.META_KERNEL_ID)).parseSchemaDocument();
            TsonSchema resolvedMetaKernel = new TsonSchemaResolver(loader).resolveSchema(metaKernelDocument);
            compiledRegistry.register(resolvedMetaKernel, loader.load(BundledSchemaSource.META_KERNEL_ID));

            loader.load(BundledSchemaSource.META_TN1_ID);
            loader.load(BundledSchemaSource.CORE_TN1_ID);

            return new TsonStandardLibrary(schemaRegistry, compiledRegistry, loader);
        }
    }

    /**
     * Resolves, links, and registers {@code schemaText} -- its own {@code !!meta} must already be
     * registered here (meta-kernel, meta.tn1, or core.tn1), and any {@code !!import} it carries must
     * resolve the same way. Deliberately stops short of compiling -- see {@link #compile} for why
     * that's a separate step with its own mode.
     */
    public TsonLinkedSchema resolve(String schemaText) {
        SchemaDocument document = new TsonSchemaParser(schemaText).parseSchemaDocument();
        TsonSchema resolved = new TsonSchemaResolver(loader).resolveSchema(document);
        return schemaRegistry.register(TsonSchemaLinker.link(resolved, schemaRegistry));
    }

    /**
     * Compiles an already-resolved, already-linked schema fresh, in {@code mode} -- independent of
     * the object-binding mode {@link #resolve} always used internally. Safe because compiling only
     * ever dispatches an already-built {@code Top} body tree to a factory by constructor name; it
     * never re-resolves an {@code Instance}/{@code AtomRefinement} declaration, so nothing here needs
     * a real governing-meta reader the way resolution did.
     */
    public TsonCompiledMetaSchema compile(TsonLinkedSchema linked, ValueReaderFactoryResolver mode) {
        return TsonCompiledMetaSchema.bootstrap(linked, mode);
    }

    /** {@link #resolve} then {@link #compile(TsonLinkedSchema, ValueReaderFactoryResolver)} in one call -- the common case, when a caller has no other use for the intermediate {@link TsonLinkedSchema}. */
    public TsonCompiledMetaSchema compile(String schemaText, ValueReaderFactoryResolver mode) {
        return compile(resolve(schemaText), mode);
    }

    /** The underlying {@link TsonSchemaRegistry} -- e.g. for {@code schemaRegistry().get(uri)} on an already-registered identity. */
    public TsonSchemaRegistry schemaRegistry() {
        return schemaRegistry;
    }

    /** The underlying {@link TsonCompiledRegistry} -- e.g. for {@code compiledRegistry().get(id)} on an already-compiled identity. */
    public TsonCompiledRegistry compiledRegistry() {
        return compiledRegistry;
    }

    /** The underlying loader -- e.g. {@code loader().load(BundledSchemaSource.META_TN1_ID)} to reach the standard library's own compiled meta-schemas directly. */
    public DefaultTsonCompiledSchemaLoader loader() {
        return loader;
    }
}
