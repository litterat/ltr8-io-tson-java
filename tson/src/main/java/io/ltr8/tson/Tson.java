package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.*;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.TsonCompiledMetaRegistry;
import io.ltr8.tson.compiler.config.ValueReaderFactoryResolver;
import io.ltr8.tson.compiler.TsonObjectReader;
import io.ltr8.tson.compiler.TsonObjectWriter;
import io.ltr8.tson.compiler.stream.DocumentStart;
import io.ltr8.tson.compiler.stream.TypeRef;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaLinker;
import io.ltr8.tson.schema.TsonSchemaRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
 * TsonCompiledSchema compiled = tson.compile(schemaText, ValueReaderFactoryRegistry.dom());
 * Object value = compiled.get("my_type").read(dataValue);
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
    private final TsonCompiledMetaRegistry compiledRegistry;
    private final TsonCompiledSchemaLoader loader;
    private final DataBindContext dataBindContext;

    // The one bit of mutable state: a per-!!schema-URI cache of DOM-mode compiled schemas, so
    // validating many data documents that name the same schema compiles it once. A cache only.
    private final Map<String, TsonCompiledSchema> validationSchemas = new ConcurrentHashMap<>();

    Tson(TsonSchemaRegistry schemaRegistry, TsonCompiledMetaRegistry compiledRegistry,
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
     * Compiles an already-resolved, already-linked schema fresh, in {@code mode} -- independent of the
     * object-binding mode {@link #resolve} always used internally. A standalone compile ({@link
     * TsonSchemaCompiler#compile(TsonLinkedSchema, ValueReaderFactoryResolver)}): the schema was
     * already validated when it resolved, so this just builds readers for its entries in {@code mode},
     * with no governing-meta scoping. Returns a plain {@link TsonCompiledSchema} -- a user schema is
     * not a meta-schema.
     */
    public TsonCompiledSchema compile(TsonLinkedSchema linked, ValueReaderFactoryResolver mode) {
        return TsonSchemaCompiler.compile(linked, mode);
    }

    /** {@link #resolve} then {@link #compile(TsonLinkedSchema, ValueReaderFactoryResolver)} in one call -- the common case, when a caller has no other use for the intermediate {@link TsonLinkedSchema}. */
    public TsonCompiledSchema compile(String schemaText, ValueReaderFactoryResolver mode) {
        return compile(resolve(schemaText), mode);
    }

    /**
     * Validates a data document, working out on its own whether a schema applies. If the document
     * declares a {@code !!schema}, that URI selects the schema (resolved through this instance's own
     * {@link TsonConfig#schemaSource} and compiled once, in DOM mode) and the document's root type-ref
     * (e.g. {@code !person}) selects the type; with no {@code !!schema} it's validated schemalessly
     * (Class 1: base syntax plus built-in / core-vocabulary atoms).
     *
     * <p>Returns every problem found, an empty list meaning valid. A problem specific to this document
     * that isn't a value error -- a {@code !!schema} the source can't provide, a root type the schema
     * doesn't declare, a missing root type-ref -- comes back as a {@link Diagnostic} in the list too,
     * so a caller has one shape to render and never has to catch an exception for a bad input document.
     */
    public List<Diagnostic> validate(String data) {
        TsonDataStream stream = new TsonDataStream(data);
        DocumentStart start = (DocumentStart) stream.next();

        if (start.schema().isEmpty()) {
            return SchemalessValidator.validate(data);
        }

        TsonCompiledSchema compiled;
        try {
            compiled = domCompiled(start.schema().get());
        } catch (RuntimeException e) {
            return List.of(problem(Diagnostic.Code.SCHEMA_ERROR, e.getMessage()));
        }
        if (!(stream.peek() instanceof TypeRef typeRef)) {
            return List.of(problem(Diagnostic.Code.VALIDATION_ERROR,
                    "data declares a !!schema but has no root type-ref (e.g. `!person`) to select a type"));
        }
        TsonValueReader<?> reader;
        try {
            reader = compiled.get(typeRef.name());
        } catch (RuntimeException e) {
            return List.of(problem(Diagnostic.Code.UNKNOWN_TYPE, e.getMessage()));
        }

        TsonReadContext ctx = TsonReadContext.collecting(stream);
        reader.read(ctx);
        return ctx.diagnostics();
    }

    /**
     * {@link #validate(String)} from a stream. The document is read into memory first -- validation
     * reads the whole document anyway (collecting mode never stops early, and the schemaless path
     * builds the full tree), and it must be re-readable for the schemaless branch.
     */
    public List<Diagnostic> validate(InputStream data) {
        try {
            return validate(new String(data.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The DOM-mode compiled schema for {@code schemaUri}, resolved+registered through the source and compiled once. */
    private TsonCompiledSchema domCompiled(String schemaUri) {
        // Resolve/register through the loader on every call -- not only on a compile-cache miss -- so
        // this reference's own ?sha256= pin is verified against the identity's content each time, even
        // when the DOM compile is already cached (a later reference with a conflicting pin must error,
        // §10.2). The loader itself caches by canonical identity, so a repeat is cheap.
        loader.load(schemaUri);
        String identity = TsonSchemaRegistry.canonicalIdentity(schemaUri);
        return validationSchemas.computeIfAbsent(identity, id -> {
            TsonLinkedSchema linked = schemaRegistry.get(schemaUri).orElseThrow(() ->
                    new IllegalStateException("schema \"" + schemaUri + "\" resolved but is not registered"));
            return compile(linked, TsonSchemaCompiler.dom());
        });
    }

    private static Diagnostic problem(Diagnostic.Code code, String message) {
        return new Diagnostic("", code, message, "", "", Optional.empty(), Optional.empty());
    }

    /** The underlying {@link TsonSchemaRegistry} -- e.g.  for {@code schemaRegistry().get(uri)} on an already-registered identity. */
    public TsonSchemaRegistry schemaRegistry() {
        return schemaRegistry;
    }

    /** The underlying {@link TsonCompiledMetaRegistry} -- e.g. for {@code compiledRegistry().get(id)} on an already-compiled identity. */
    public TsonCompiledMetaRegistry compiledRegistry() {
        return compiledRegistry;
    }

    /** The underlying loader -- e.g. {@code loader().load(TsonBundledSchemas.META_ID)} to reach the standard library's own compiled meta-schemas directly. */
    public TsonCompiledSchemaLoader loader() {
        return loader;
    }
}
