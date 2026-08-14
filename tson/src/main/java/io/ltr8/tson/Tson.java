package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.*;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaLinker;
import io.ltr8.tson.schema.TsonSchemaRegistry;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The front door -- a small, curated entry point over {@code tson-compiler}'s own, larger and more
 * mechanical surface (lexer, both grammars, resolution, linking-adjacent validation, compilation,
 * config wiring), the way Retrofit sits on top of OkHttp or Apache HttpClient5 sits on top of
 * HttpCore5. Doesn't reimplement anything -- every method here just constructs/returns the real
 * {@code tson-compiler}/{@code tson-schema} class underneath. Built via {@link #builder()}, which
 * bootstraps meta-kernel/meta.tn/core.tn into a fresh, governed environment:
 *
 * <pre>{@code
 * Tson tson = Tson.builder().build();
 * tson.resolve(schemaText);                      // registers the schema by its own !!id
 * TsonNode value = tson.treeReader().withSchema(schemaId).readAs(dataText, "my_type");
 * }</pre>
 *
 * <p><b>The read mode is which registry you hold, not a parameter.</b> {@link #treeRegistry()} reads into an
 * immutable, queryable {@code TsonNode} (structure-preserving, typed leaves -- the recommended default);
 * {@link #bindRegistry()} reads to real Java objects (bound via {@link #dataBindContext()}). Both sit over one shared, bind-mode
 * resolution core: resolving an {@code Instance}/{@code AtomRefinement} declaration (meta.tn's/core.tn's own
 * {@code binary_encoding => !enum [...]}, {@code int32 => !integer ^ {...}}, and so on) binds it to a {@code
 * schema.meta.Top} object, which a non-binding reader's output can't stand in for -- so resolution is always
 * bind-anchored regardless of read mode, and {@link #resolve} takes no mode. Only the final compile of an
 * already-resolved, already-linked schema (a registry's own {@code compile}/{@code get}) picks a mode.
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

    private final TsonCompiledMetaRegistry core;
    private final TsonCompiledSchemaRegistry tree;
    private final TsonCompiledSchemaRegistry bind;
    private final DataBindContext dataBindContext;

    Tson(TsonCompiledMetaRegistry core, DataBindContext dataBindContext) {
        this.core = core;
        this.dataBindContext = dataBindContext;
        this.tree = TsonCompiledSchemaRegistry.tree(core);
        this.bind = TsonCompiledSchemaRegistry.bind(core, dataBindContext);
    }

    /** A fresh {@link TsonConfig} -- {@link TsonConfig#build()} bootstraps meta-kernel/meta.tn/core.tn and returns the resulting {@link Tson}. */
    public static TsonConfig builder() {
        return new TsonConfig();
    }

    /**
     * A schema-aware {@link TsonObjectReader} over this instance -- reads TSON text into bound Java objects
     * (via {@link #dataBindContext()}), validating against a self-describing document's {@code !!schema},
     * schemaless when it declares none. Built over {@link #bindRegistry()}, so every reader from this
     * instance shares one compiled-schema cache: a schema is compiled once here, not once per reader.
     */
    public TsonObjectReader objectReader() {
        return new TsonObjectReader(bind, dataBindContext);
    }

    /**
     * A schema-aware {@link TsonTreeReader} over this instance -- reads TSON text into an immutable,
     * queryable {@code TsonNode} tree, validating against a self-describing document's {@code !!schema},
     * schemaless when it declares none. The tree-producing peer of {@link #objectReader()}, and built over
     * {@link #treeRegistry()} on the same shared-cache terms -- which is what keeps {@link #validate}, which
     * makes a reader per call, from recompiling the schema for every document it checks.
     */
    public TsonTreeReader treeReader() {
        return new TsonTreeReader(tree);
    }

    /** A fresh, schemaless (Class 1) {@link TsonObjectWriter} bound to {@link #dataBindContext()} -- the inverse of {@link #objectReader()}. */
    public TsonObjectWriter objectWriter() {
        return new TsonObjectWriter(dataBindContext);
    }

    /** A {@link TsonTreeWriter} -- an immutable {@code TsonNode} tree back to TSON text, the inverse of {@link #treeReader()}. */
    public TsonTreeWriter treeWriter() {
        return new TsonTreeWriter();
    }

    /** The {@link DataBindContext} {@link #objectReader()}/{@link #objectWriter()}/{@link #bindRegistry()} bind against -- see {@link TsonConfig#dataBindContext} to customize it. */
    public DataBindContext dataBindContext() {
        return dataBindContext;
    }

    /**
     * Resolves, links, and registers {@code schemaText} -- its own {@code !!meta} must already be
     * registered here (meta-kernel, meta.tn, or core.tn), and any {@code !!import} it carries must
     * resolve the same way. Deliberately stops short of compiling -- compiling in a chosen mode is a
     * registry's job ({@link #treeRegistry()}/{@link #bindRegistry()}), independent of the object-binding
     * mode this always used internally.
     */
    public TsonLinkedSchema resolve(String schemaText) {
        SchemaDocument document = new TsonSchemaParser(schemaText).parseSchemaDocument();
        TsonSchema resolved = new TsonSchemaResolver(core).resolveSchema(document);
        return core.schemaRegistry().register(TsonSchemaLinker.link(resolved, core.schemaRegistry()));
    }

    /**
     * The recommended read registry -- reads user schemas into an immutable, queryable {@link
     * io.ltr8.tson.tree.TsonNode} tree: structure-preserving (record vs map, array vs tuple) with
     * typed leaves and null-safe navigation, and no Java class per schema type. This is what {@link
     * #validate} reads through.
     */
    public TsonCompiledSchemaRegistry treeRegistry() {
        return tree;
    }

    /** The object-binding read registry over this instance's resolution core -- reads user schemas to real Java objects, bound via {@link #dataBindContext()}. */
    public TsonCompiledSchemaRegistry bindRegistry() {
        return bind;
    }

    /**
     * Validates a data document, working out on its own whether a schema applies. If the document
     * declares a {@code !!schema}, that URI selects the schema (resolved through this instance's own
     * {@link TsonConfig#schemaSource} and compiled once, in tree mode) and the document's root type-ref
     * (e.g. {@code !person}) selects the type; with no {@code !!schema} it's validated schemalessly
     * (Class 1: base syntax, plus the built-in type vocabulary for whatever the wire tags).
     *
     * <p>Returns every problem found, an empty list meaning valid. A problem specific to this document
     * that isn't a value error -- malformed syntax, a schema document where data was expected, a {@code
     * !!schema} the source can't provide, a root type the schema doesn't declare, a missing root type-ref
     * -- comes back as a {@link Diagnostic} in the list too, so a caller has one shape to render and never
     * has to catch an exception for a bad input document.
     *
     * <p><b>This <em>is</em> {@link #treeReader()} with a collecting receiver</b>, both halves of it. The
     * reader already resolves a {@code !!schema}, selects the root type, checks a schemaless document's own
     * type-refs, and reports every failure around all of that as a diagnostic; validating is that read with
     * its result thrown away. There is no second implementation to drift from this one.
     */
    public List<Diagnostic> validate(String data) {
        return validate(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * {@link #validate(String)} straight off a stream -- the real body, since the reader underneath decodes
     * UTF-8 bytes rather than characters and every non-trivial caller (the CLI included) already holds a
     * stream. {@code data} is read incrementally and is not closed here.
     */
    public List<Diagnostic> validate(InputStream data) {
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        try {
            treeReader().withDiagnostics(problems).read(data);
        } catch (RuntimeException e) {
            // A base-syntax failure is this document's problem, so it renders like any other; anything else
            // is a fault in this library and rethrows itself from here.
            return List.of(Diagnostic.ofBaseSyntaxError(e));
        }
        return problems.diagnostics();
    }

    /** The underlying resolved-schema registry -- e.g. for {@code schemaRegistry().get(uri)} on an already-registered identity. */
    public TsonSchemaRegistry schemaRegistry() {
        return core.schemaRegistry();
    }

    /** The resolution core as the on-demand loader -- e.g. {@code loader().loadMeta(TsonBundledSchemas.META_ID)} to reach a compiled meta-schema directly. */
    public TsonCompiledSchemaLoader loader() {
        return core;
    }
}
