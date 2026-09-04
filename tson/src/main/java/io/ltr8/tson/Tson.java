package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.*;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.tree.TsonValue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

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
 * TsonValue value = tson.treeReader().withSchema(schemaId).readAs(dataText, "my_type");
 * }</pre>
 *
 * <p><b>Build one and share it.</b> An instance is immutable and <b>safe to read through from any number of
 * threads</b> -- which is the point of building one, since a schema compiles once per instance and a
 * per-request {@code Tson} re-bootstraps the standard library and recompiles every schema. Everything a read
 * touches is either immutable or per-read: the compiled readers hold no mutable state, a {@code Lexer} and
 * its stream are built per read and shared with nothing, and the two caches a read passes through are
 * concurrent maps whose hits take no lock. Even the first concurrent use of a schema this instance has not
 * seen is safe: two threads may both do the resolution work, and the caches settle it by keeping one entry
 * and handing it to both -- <b>work can be duplicated on a race, state never is</b>.
 *
 * <p><b>Registering is not part of that guarantee.</b> {@link #resolve} and {@link #validateSchema} mutate
 * this instance's registry, and registering one identity explicitly twice is an error however many threads
 * are involved -- so two threads resolving the same schema is a race one of them loses, not a way to warm a
 * cache. Resolve every schema the process needs at startup, then read; a {@link
 * io.ltr8.bind.DataBindContext} must likewise not be mutated once reads are running through it. The design
 * note {@code docs/linking-and-compilation.md} has the mechanics, and {@code ReadPathConcurrencyTest} pins
 * the read-path half.
 *
 * <p><b>The read mode is which registry you hold, not a parameter.</b> {@link #treeRegistry()} reads into an
 * immutable, queryable {@code TsonValue} (structure-preserving, typed leaves -- the recommended default);
 * {@link #bindRegistry()} reads to real Java objects (bound via {@link #dataBindContext()}). Both sit over one shared, bind-mode
 * resolution core: resolving an {@code Instance}/{@code AtomRefinement} declaration (meta.tn's/core.tn's own
 * {@code binary_encoding => !enum [...]}, {@code int32 => !integer ^ {...}}, and so on) binds it to a {@code
 * schema.meta.Top} object, which a non-binding reader's output can't stand in for -- so resolution is always
 * bind-anchored regardless of read mode, and {@link #resolve} takes no mode. Only the final compile of an
 * already-resolved, already-linked schema (a registry's own {@code compile}/{@code get}) picks a mode.
 *
 * <p><b>What that fixes is the mode, not the vocabulary.</b> The resolution core's own binder knows the
 * kernel's names; a governing meta of a consumer's own may declare constructors beyond them ({@code
 * operation => ~data & { ... }}), and {@link TsonConfig#metaNameBinder} adds the classes those bind to --
 * composed over the library's binder, so the mode and every kernel name stand.
 *
 * <p>Out of the box this serves only meta-kernel/meta.tn/core.tn: {@link TsonSchemaSource#registeredOnly()}
 * is the default source, so a schema governed by or importing anything else has to be registered first, or
 * reachable through a source configured on {@link TsonConfig} -- {@link TsonHttpSchemaSource} and {@link
 * TsonFileSchemaSource} ship, and both deny by default.
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

    /** UTS #39 §5.2 over every token a read off this instance pulls -- the token-surface half of the
     * two policies, where {@code core.identifierPolicy()} is the declared-name half. */
    private final TsonUnicodePolicy tokenPolicy;

    /** [TSON-DATA] §9.1's bounds every read off this instance applies -- {@link TsonConfig#limits}. */
    private final TsonLimitsPolicy limits;

    Tson(TsonCompiledMetaRegistry core, DataBindContext dataBindContext, boolean strictBinding,
         TsonUnicodePolicy tokenPolicy, TsonLimitsPolicy limits) {
        this.tokenPolicy = tokenPolicy;
        this.limits = limits;
        this.core = core;
        this.dataBindContext = dataBindContext;
        this.tree = TsonCompiledSchemaRegistry.tree(core);
        this.bind = TsonCompiledSchemaRegistry.bind(core, dataBindContext, strictBinding);
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
     *
     * <p><b>Both [TSON-DATA] §8.2 policies come from this instance</b>, {@link TsonConfig#identifierPolicy}
     * included -- a reader built here judges the names in a document under the same policy the linker judged
     * the schema's declared names under. They are one processor, and {@link #processorPolicy()} reports one
     * answer for it, which is only true if one answer is what both ends use.
     */
    public TsonObjectReader objectReader() {
        return new TsonObjectReader(bind, dataBindContext)
                .withTokenPolicy(tokenPolicy).withIdentifierPolicy(core.identifierPolicy()).withLimits(limits);
    }

    /**
     * A schema-aware {@link TsonTreeReader} over this instance -- reads TSON text into an immutable,
     * queryable {@code TsonValue} tree, validating against a self-describing document's {@code !!schema},
     * schemaless when it declares none. The tree-producing peer of {@link #objectReader()}, and built over
     * {@link #treeRegistry()} on the same shared-cache terms -- which is what keeps {@link #validate}, which
     * makes a reader per call, from recompiling the schema for every document it checks.
     *
     * <p>Carries both §8.2 policies from this instance, for the reason {@link #objectReader()} states.
     */
    public TsonTreeReader treeReader() {
        return new TsonTreeReader(tree)
                .withTokenPolicy(tokenPolicy).withIdentifierPolicy(core.identifierPolicy()).withLimits(limits);
    }

    /** A fresh, schemaless (Class 1) {@link TsonObjectWriter} bound to {@link #dataBindContext()} -- the inverse of {@link #objectReader()}. */
    public TsonObjectWriter objectWriter() {
        return new TsonObjectWriter(dataBindContext);
    }

    /** A {@link TsonTreeWriter} -- an immutable {@code TsonValue} tree back to TSON text, the inverse of {@link #treeReader()}. */
    public TsonTreeWriter treeWriter() {
        return new TsonTreeWriter();
    }

    /**
     * The two [TSON-DATA] §8.2 Unicode policies this instance applies -- {@link TsonConfig#identifierPolicy}
     * over declared names and {@link TsonConfig#tokenPolicy} over token values -- and the Unicode data
     * version they are computed against, under the names that configured them.
     *
     * <p><b>What a run or a response states beside its diagnostics</b>, and what a deployment can publish
     * with no document in hand at all. §8.2's rules read data the UCD does not freeze and are applied at a
     * level this deployment chose, so the same bytes may be accepted here and refused elsewhere; this is the
     * only statement of why, and the only one a sender can consult <em>before</em> writing a document rather
     * than after being refused. A reader derived with {@link TsonTreeReader#withIdentifierPolicy} answers for
     * itself ({@link TsonTreeReader#processorPolicy()}).
     */
    public TsonUnicodeProcessorPolicy processorPolicy() {
        return TsonUnicodeProcessorPolicy.of(core.identifierPolicy(), tokenPolicy);
    }

    /**
     * The [TSON-DATA] §9.1 resource limits this instance applies -- {@link TsonConfig#limits}.
     *
     * <p><b>{@link #processorPolicy()}'s companion, and stated for the same reason.</b> A limit is the
     * reading deployment's own choice, so the same bytes may be read here and refused elsewhere; a sender
     * that can consult the bound writes a document that fits, where one that cannot learns it from a refusal.
     * The two are separate values rather than one because they answer separate questions -- what this
     * processor will <em>read</em>, and what it will <em>admit as a name</em> -- and a deployment that has
     * changed one has said nothing about the other.
     */
    public TsonLimitsPolicy limitsPolicy() {
        return limits;
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
        TsonSchemaParser parser = new TsonSchemaParser(schemaText);
        SchemaDocument document = parser.parseSchemaDocument();
        TsonSchema resolved = new TsonSchemaResolver(core).resolveSchema(document, parser.schemaPositions());
        return core.schemaRegistry().register(
                TsonSchemaLinker.link(resolved, core.schemaRegistry(), core.identifierPolicy()));
    }

    /**
     * The recommended read registry -- reads user schemas into an immutable, queryable {@link
     * TsonValue} tree: structure-preserving (record vs map, array vs tuple) with
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
        // No catch: a collecting read reports a base-syntax failure through the receiver like every other
        // problem, so it arrives in `problems` in the order it was found, after whatever the read had already
        // reported. A fault in this library still throws itself out of here, which is the intended difference.
        treeReader().withDiagnostics(problems).read(data);
        return problems.diagnostics();
    }

    /**
     * Validates a *schema* document, the schema-side peer of {@link #validate(String)}: resolves, links,
     * registers and compiles it, and returns every problem found, an empty list meaning it is sound. Like
     * {@link #validate}, it never throws for a bad input document -- malformed syntax, a broken declaration,
     * an unresolved reference and an unloadable {@code !!import} all come back as {@link Diagnostic}s.
     *
     * <p><b>It stops at the first phase that reports anything</b> ([TSON-DATA] §8.1's four error categories
     * are per layer). Parsing is such a phase too: every declaration is parsed and each syntax error reported,
     * and a document that didn't parse whole is not resolved at all -- resolving what survived would report
     * every reference to a broken declaration as unresolved, on top of the syntax error that is the real
     * problem. Then every declaration is resolved
     * before the verdict is given, and only if resolution was clean does linking run -- so a schema with three broken declarations
     * reports three, but a schema with a broken declaration *and* an unresolved reference reports only the
     * former, since the reference may well resolve once the declaration does. Both javac and Swift draw the
     * boundary here: javac attributes every entry before {@code shouldStopPolicyIfError} blocks the next
     * phase, and Swift never reaches SILGen after a Sema error. The alternative -- carrying placeholders
     * forward -- reports consequences of the first error as though they were independent problems.
     *
     * <p><b>A schema is registered only if it is sound.</b> Nothing is added to this instance's registry when
     * the returned list is non-empty, so a failed call leaves no half-resolved entry behind for a later one
     * to trip over.
     */
    public List<Diagnostic> validateSchema(String schemaText) {
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        try {
            TsonSchemaParser parser = new TsonSchemaParser(schemaText);
            Optional<SchemaDocument> parsed = parser.parseSchemaDocument(problems);
            if (parsed.isEmpty()) {
                return problems.diagnostics();
            }
            SchemaDocument document = parsed.get();

            TsonSchema resolved = new TsonSchemaResolver(core)
                    .resolveSchema(document, parser.schemaPositions(), problems);
            if (!problems.isEmpty()) {
                return problems.diagnostics();
            }
            TsonLinkedSchema linked = TsonSchemaLinker.link(resolved, core.schemaRegistry(), problems,
                    core.identifierPolicy());
            if (!problems.isEmpty()) {
                return problems.diagnostics();
            }
            treeRegistry().compile(core.schemaRegistry().register(linked));
        } catch (TsonSchemaFetchException e) {
            // An !!import or !!meta naming an identity no configured source will serve. The document fails
            // either way, but it was never checked: nothing here saw the imported schema, so "this schema
            // is wrong" is a verdict this call has no grounds for. Same root pointer as the rest -- what
            // differs is the code and the exception's own Reason, which say respectively that no schema was
            // obtained and whose doing that was.
            problems.report(Diagnostic.ofSchemaUnavailable("", "", e, Optional.empty()));
        } catch (TsonBindMismatchException e) {
            // A class bound to this schema's governing meta cannot work with it -- a Data body returning null
            // from references(), say. The schema may be perfectly good and this call cannot say either way,
            // so it reports the wiring mistake rather than letting a bare runtime exception past a caller who
            // asked for a list of problems, which would read as a fault in this library.
            problems.report(Diagnostic.ofSchemaBindMismatch("", "", e, Optional.empty()));
        } catch (TsonSchemaValidationException e) {
            // Whatever the phases still raise rather than report: a document with no !!id, an !!import that
            // loaded and would not link, a !!meta that may not govern. Author errors about the document as
            // a whole, so they carry the root pointer rather than naming a declaration.
            problems.report(Diagnostic.ofSchemaError("", "", e.getMessage(), Optional.empty()));
        } catch (RuntimeException e) {
            // Base syntax is this document's problem; anything else is a fault in this library and rethrows
            // itself from here rather than being laundered into a false verdict. The schema-side factory,
            // because this document is a schema: the position belongs at the schema end, not the data end.
            problems.report(Diagnostic.ofSchemaSyntaxError("", e));
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
