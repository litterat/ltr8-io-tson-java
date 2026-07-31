package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.*;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.config.TsonCompiledRegistry;
import io.ltr8.tson.compiler.config.ValueReaderFactoryResolver;
import io.ltr8.tson.compiler.TsonObjectReader;
import io.ltr8.tson.compiler.TsonObjectWriter;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaLinker;
import io.ltr8.tson.schema.TsonSchemaRegistry;

/**
 * The front door -- a small, curated entry point over {@code tson-compiler}'s own, larger and more
 * mechanical surface (lexer, both grammars, resolution, linking-adjacent validation, compilation,
 * config wiring), the way Retrofit sits on top of OkHttp or Apache HttpClient5 sits on top of
 * HttpCore5. Doesn't reimplement anything -- every method here just constructs/returns the real
 * {@code tson-compiler}/{@code tson-schema} class underneath. Built via {@link #builder()}, which
 * bootstraps meta-kernel/meta.tn/core.tn into a fresh, governed environment (previously a
 * sequence every real schema-compiling test hand-rolled for itself, e.g. {@code tson-compiler}'s own
 * {@code TinySchemaImportsCoreTn1Test}/{@code CoreSchemaImportTest}, and {@code tson-cli}'s own
 * former internal {@code StandardLibrary} helper):
 *
 * <pre>{@code
 * Tson tson = Tson.builder().build();
 * TsonCompiledMetaSchema compiled = tson.compile(schemaText, ValueReaderFactoryRegistry.dom());
 * Object value = compiled.compiledSchema().get("my_type").read(dataValue);
 * }</pre>
 *
 * <p><b>Resolution always runs in object-binding mode, regardless of what mode a caller ultimately
 * wants to read data in.</b> Resolving an {@code Instance}/{@code AtomRefinement} declaration
 * (meta.tn's/core.tn's own {@code binary_encoding => !enum [...]}, {@code int32 => !integer ^
 * {...}}, and so on -- there are many) calls {@code DefinitionResolver.bindAtomInstance}, which
 * casts its governing meta-schema's own reader output straight to {@code schema.meta.Top} -- a DOM
 * reader's plain {@code Map}/{@code List} output fails that cast outright. Only *compilation* of an
 * already-resolved schema (see {@link #compile(TsonLinkedSchema, ValueReaderFactoryResolver)}) is
 * free to pick a different mode, since it just dispatches an already-built {@code Top} body tree to
 * a factory by constructor name -- it never re-runs resolution. {@link #resolve} therefore takes no
 * mode parameter at all; only {@link #compile} does.
 *
 * <p>Only supports a schema governed by (and importing only from) meta-kernel/meta.tn/core.tn --
 * a real, disk/HTTP-backed {@link TsonSchemaSource} for arbitrary other
 * governing chains is its own, separately tracked backlog item; {@link TsonConfig} is the natural
 * place for that to plug in once it exists.
 *
 * <p>{@link TsonObjectReader}/{@link TsonObjectWriter} live in {@code tson-compiler}'s own root
 * package, alongside the other read-side front doors -- {@code DefinitionResolver}, part of that
 * module's own resolution engine, has a real, current dependency on {@link TsonObjectWriter}
 * (atom-refinement merging), so they can't move to a module that depends *on* {@code tson-compiler}
 * without a cycle. {@link #objectReader()}/{@link #objectWriter()} bind them to this instance's own
 * {@link #dataBindContext()} (configurable via {@link TsonConfig#dataBindContext}), so a caller gets
 * one consistent binding configuration without having to wire it up twice.
 */
public final class Tson {

    private final TsonSchemaRegistry schemaRegistry;
    private final TsonCompiledRegistry compiledRegistry;
    private final TsonCompiledSchemaLoader loader;
    private final DataBindContext dataBindContext;

    Tson(TsonSchemaRegistry schemaRegistry, TsonCompiledRegistry compiledRegistry,
         TsonCompiledSchemaLoader loader, DataBindContext dataBindContext) {
        this.schemaRegistry = schemaRegistry;
        this.compiledRegistry = compiledRegistry;
        this.loader = loader;
        this.dataBindContext = dataBindContext;
    }

    /** A fresh {@link TsonConfig} -- {@link TsonConfig#build()} bootstraps meta-kernel/meta.tn/core.tn and returns the resulting {@link Tson}. */
    public static TsonConfig builder() {
        return new TsonConfig();
    }

    /** A fresh, schemaless (Class 1) {@link TsonObjectReader} bound to {@link #dataBindContext()} -- TSON text straight to plain Java objects, no schema involved. */
    public TsonObjectReader objectReader() {
        return new TsonObjectReader(dataBindContext);
    }

    /** A fresh, schemaless (Class 1) {@link TsonObjectWriter} bound to {@link #dataBindContext()} -- the inverse of {@link #objectReader()}. */
    public TsonObjectWriter objectWriter() {
        return new TsonObjectWriter(dataBindContext);
    }

    /** The {@link DataBindContext} {@link #objectReader()}/{@link #objectWriter()} are bound to -- see {@link TsonConfig#dataBindContext} to customize it. */
    public DataBindContext dataBindContext() {
        return dataBindContext;
    }

    /**
     * Resolves, links, and registers {@code schemaText} -- its own {@code !!meta} must already be
     * registered here (meta-kernel, meta.tn, or core.tn), and any {@code !!import} it carries must
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

    /** The underlying loader -- e.g. {@code loader().load(TsonBundledSchemas.META_ID)} to reach the standard library's own compiled meta-schemas directly. */
    public TsonCompiledSchemaLoader loader() {
        return loader;
    }
}
