package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.reader.EventSkip;
import io.ltr8.tson.compiler.reader.SchemalessTreeReader;
import io.ltr8.tson.compiler.stream.DocumentEnd;
import io.ltr8.tson.compiler.stream.DocumentStart;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TypeRef;
import io.ltr8.tson.tree.TsonArray;
import io.ltr8.tson.tree.TsonRecord;
import io.ltr8.tson.tree.TsonDocument;
import io.ltr8.tson.tree.TsonValue;

import java.io.InputStream;

/**
 * Reads a TSON data document into an immutable {@link TsonValue} tree -- the tree-producing read-side front
 * door, the inverse of {@link TsonTreeWriter} and the tree-shaped peer of {@link TsonObjectReader} (which
 * produces bound Java objects). Like Jackson's {@code readTree}.
 *
 * <p><b>Two modes, fixed at construction.</b> A reader from {@code Tson#treeReader()} (the {@code
 * (TsonCompiledSchemaRegistry)} constructor) is <i>schema-aware</i>: a document that declares a {@code
 * !!schema} is validated against it as the tree is built -- the schema resolves through that environment's
 * own source and the document's root type-ref (e.g. {@code !person}) selects the type, so the tree is
 * structure-preserving (record vs map, array vs tuple) with the schema's own leaf types. A reader built
 * standalone ({@link #TsonTreeReader()}) is <i>schemaless</i> (Class 1): any {@code !!schema} the document
 * declares is ignored and the wire is the source of truth -- an array is always an {@link
 * TsonArray} (only a schema-driven read produces a tuple), {@code {}} is an empty {@link
 * TsonRecord}, and leaves are typed by §4 base resolution or the built-in vocabulary.
 * When no {@code !!schema} is present the two modes behave identically. {@link #readWithoutSchema} forces
 * the schemaless path on a schema-aware reader.
 *
 * <p>Either way the tree is streamed off the event source ({@link TsonDataStream}) directly, building nodes
 * as events arrive without an intermediate {@code DataValue} AST. A read is fail-fast by default: a malformed
 * document or an out-of-range typed value throws {@link TsonReadException} at the first problem. {@link
 * #withDiagnostics} swaps that for any other {@link TsonDiagnosticsReceiver} -- a collector gathers every
 * problem in one pass and still hands back the (possibly partial) tree, in schema-aware and schemaless mode
 * alike. That is what makes this reader, with a collecting receiver, exactly what {@code Tson#validate}
 * delegates to.
 *
 * <p><b>Every problem with the document goes to the receiver, base syntax included.</b> A document that does
 * not lex or parse is reported like any other failure rather than thrown past the receiver, so a collecting
 * read never throws for a bad <i>document</i> -- it hands back no tree and the collector holds why, after
 * whatever it had already found. A fail-fast read still throws, because {@code throwing()} is what throws.
 * A fault in <i>this library</i> is not a document problem and still propagates as itself.
 *
 * <p>A schemaless read also checks type-refs: a built-in name ({@code !uuid}, {@code !date}) must sit on a
 * token and that token must satisfy the atom, and any other name -- having nothing to resolve against -- is
 * {@code UNKNOWN_TYPE_REF}. {@link #preservingUnknownTypeRefs} opts out of that last rule.
 *
 * <p>Wire annotations are captured on the <b>schemaless</b> path only -- a node read that way carries its
 * own {@code annotations()} (§3.1). A schema-driven read leaves them empty for now, so a document with a
 * {@code !!schema} loses them unless read through {@link #readWithoutSchema}.
 */
public final class TsonTreeReader {

    /** The schemaless engine this reader falls back to -- strict about type-refs unless {@link #preservingUnknownTypeRefs} said otherwise. */
    private final SchemalessTreeReader schemaless;

    /** The tree-mode compiled-schema registry a schema-aware reader validates through, or {@code null} for a schemaless reader (any {@code !!schema} is then ignored). */
    private final TsonCompiledSchemaRegistry tree;

    /** Where this reader's reads report their problems -- fail-fast unless {@link #withDiagnostics} said otherwise. */
    private final TsonDiagnosticsReceiver receiver;

    /** The schema {@link #readAs} validates against, or {@code null} until {@link #withSchema} names one. */
    private final String schemaUri;

    /** UTS #39 §5.2 over every token this reader pulls -- {@code null} means the default, which checks nothing. */
    private final TsonUnicodePolicy tokenPolicy;

    /**
     * Schema-aware -- validates a self-describing document against its {@code !!schema}, resolved and
     * compiled through {@code tree}. Used by {@code Tson#treeReader()}.
     *
     * <p><b>Takes the registry rather than building one</b>, so a caller holding a registry shares its
     * compiled-schema cache with every reader made from it instead of each reader compiling the same schema
     * again. That matters most where readers are cheap and frequent: {@code Tson#validate} makes one per
     * call, and a reader that built its own cache would recompile the schema for every document validated.
     *
     * @throws IllegalArgumentException if {@code tree} is an object-binding registry, whose readers produce
     *         bound objects this reader cannot assemble into a tree
     */
    public TsonTreeReader(TsonCompiledSchemaRegistry tree) {
        this(requireTreeMode(tree), TsonDiagnosticsReceiver.throwing(), null, new SchemalessTreeReader(), null);
    }

    /**
     * The compiled-schema registry this reader validates through, or {@code null} if it is schemaless.
     * Package-private: a consumer picks a registry by which one they pass in, and the interesting property
     * -- that every reader derived from this one keeps the same instance, so a schema compiles once -- is
     * otherwise unobservable, since compiling twice differs from compiling once only in cost.
     */
    TsonCompiledSchemaRegistry compiledSchemas() {
        return tree;
    }

    /** Rejects a wrong-mode registry where it is handed over, rather than at the first value it fails to produce a node for. */
    private static TsonCompiledSchemaRegistry requireTreeMode(TsonCompiledSchemaRegistry tree) {
        if (tree.mode() != TsonCompiledSchemaRegistry.Mode.TREE) {
            throw new IllegalArgumentException("a TsonTreeReader needs a tree-mode registry (TsonCompiledSchemaRegistry"
                    + ".tree(core)) -- an object-binding one reads to bound Java objects, not TsonNodes");
        }
        return tree;
    }

    /** Schemaless (Class 1) -- reads the wire structure into a tree, ignoring any {@code !!schema} the document declares. */
    public TsonTreeReader() {
        this(null, TsonDiagnosticsReceiver.throwing(), null, new SchemalessTreeReader(), null);
    }

    /** Shares {@code tree} rather than rebuilding it -- a derived reader must keep the original's compiled-schema cache, not start an empty one. */
    private TsonTreeReader(TsonCompiledSchemaRegistry tree, TsonDiagnosticsReceiver receiver, String schemaUri,
                           SchemalessTreeReader schemaless, TsonUnicodePolicy tokenPolicy) {
        this.tree = tree;
        this.receiver = receiver;
        this.schemaUri = schemaUri;
        this.schemaless = schemaless;
        this.tokenPolicy = tokenPolicy;
    }

    /**
     * This reader bound to the schema {@code schemaUri} names, for {@link #readAs} -- a new reader, leaving
     * this one unchanged, sharing its compiled-schema registry. The schema is resolved through the same
     * source and cache a self-describing document's own {@code !!schema} goes through, so it must already be
     * registered (e.g. via {@code Tson#resolve}) or be servable by the configured {@code TsonSchemaSource}.
     */
    public TsonTreeReader withSchema(String schemaUri) {
        if (tree == null) {
            throw new IllegalStateException("a schemaless TsonTreeReader has no schema environment to resolve '"
                    + schemaUri + "' through -- obtain one from Tson.treeReader()");
        }
        return new TsonTreeReader(tree, receiver, schemaUri, schemaless, tokenPolicy);
    }

    /**
     * This reader, keeping a type-ref that names no built-in type instead of reporting it -- a new reader,
     * leaving this one unchanged, sharing its compiled-schema registry.
     *
     * <p>A schemaless read has nothing to resolve {@code !person} against, so by default it is {@code
     * UNKNOWN_TYPE_REF} -- a reader policy, §7.1 asking only that an unresolved annotation be treated as
     * informational. This is the opt-out for a caller who wants the wire
     * back as authored: reading the structure of a document whose {@code !!schema} defines those names but is
     * deliberately out of scope, or round-tripping a tree through {@link TsonTreeWriter}. Built-in type-refs
     * are still checked -- {@code !uuid nope} is a problem either way. Affects the schemaless path only; a
     * schema-aware read resolves type-refs against its compiled schema.
     */
    public TsonTreeReader preservingUnknownTypeRefs() {
        return new TsonTreeReader(tree, receiver, schemaUri, SchemalessTreeReader.preserving(), tokenPolicy);
    }

    /**
     * This reader with {@code policy} applied to every token it pulls -- a new reader, leaving this one unchanged
     * ({@code SPEC-FEEDBACK.md} #3 Step 4b). Orthogonal to {@link #withSchema} and {@link #withDiagnostics},
     * and available on a schemaless reader, which is the point: a Class 1 read has no schema and no registry,
     * and is where a value arrives least constrained.
     *
     * <p><b>The default checks nothing</b>, which is the opposite of a declared name's Highly Restrictive
     * default and right for the same reason in each case. A value is data, and data may legitimately be
     * anything -- a Greek quotation, a Cyrillic display name. A service that renders the values it reads, or
     * matches them against a list, raises this knowingly.
     *
     * <p><b>A name is a token, so this reaches names too.</b> Set stricter than the identifier policy, it
     * subsumes it: the check runs before anything knows which tokens are names, so a name has already cleared
     * the stricter rule by the time the name rule looks at it. That is the honest consequence of where the
     * check sits, and is why this is not called {@code withValuePolicy}.
     *
     * @throws IllegalArgumentException if {@code policy} is per-segment. {@code _} and {@code -} are word
     *         separators by convention in a name and ordinary characters in a value, so segmenting one admits
     *         UTS #39's own {@code Toys-Я-Us} -- the spoof a strict token policy exists to refuse. Refused
     *         rather than ignored, so a policy that cannot mean what it says is never silently accepted.
     */
    public TsonTreeReader withTokenPolicy(TsonUnicodePolicy policy) {
        if (policy != null && policy.isPerSegment()) {
            throw new IllegalArgumentException("a token policy cannot be per-segment: '_' and '-' are ordinary "
                    + "characters in a value, not word separators -- use the whole-text policy instead");
        }
        return new TsonTreeReader(tree, receiver, schemaUri, schemaless, policy);
    }

    /**
     * This reader, reporting through {@code receiver} instead of throwing at the first problem -- a new reader,
     * leaving this one unchanged, sharing its compiled-schema registry.
     *
     * <p>A receiver sees <b>every</b> problem with the document: a value the schema rejects, an unresolvable
     * {@code !!schema}, and a document that will not lex or parse. Only a fault in this library is left to
     * throw past it, which is the one thing a caller collecting problems must not have folded into their
     * list.</p>
     *
     * <pre>{@code
     * var problems = TsonDiagnosticsReceiver.collecting();
     * TsonValue tree = tson.treeReader().withDiagnostics(problems).read(source);
     * problems.diagnostics();      // every problem, alongside a possibly-partial tree
     * }</pre>
     *
     * <p>Applies to the whole-document entry points only. {@link #read(TsonReadContext)} takes a context that
     * carries its own receiver, and that one wins.
     */
    public TsonTreeReader withDiagnostics(TsonDiagnosticsReceiver receiver) {
        return new TsonTreeReader(tree, receiver, schemaUri, schemaless, tokenPolicy);
    }

    // ── Whole-document entry points ──────────────────────────────────────

    /** Reads {@code source}'s whole document into a {@link TsonValue} tree, fail-fast -- validated against its {@code !!schema} if this reader is schema-aware and the document declares one, schemaless otherwise. */
    public TsonValue read(String source) {
        return readRoot(new TsonDataStream(source), false);
    }

    /** {@link #read(String)} straight off a stream -- reads {@code source}'s bytes (UTF-8) incrementally, never buffering the whole document into a {@code String} first; {@code source} is not closed here. */
    public TsonValue read(InputStream source) {
        return readRoot(new TsonDataStream(source), false);
    }

    /**
     * Reads {@code source} into a {@link TsonDocument} -- the value <b>and</b> the header directives that
     * govern it, where {@link #read} keeps only the value.
     *
     * <p><b>For a caller who must reproduce or re-route the document without having held its URI.</b> A
     * schema-driven read already records each node's type, which is what lets a writer put the root's
     * {@code !typeName} back; what it discarded was the document's own {@code !!schema} and {@code !!id},
     * so round-tripping worked only for a caller who still remembered what governed it. That is fine when
     * the reader and the writer are the same code and a nuisance the moment a tree is handed on -- a server
     * routing a body it has parsed cannot ask the sender again.
     *
     * <p>Reads exactly as {@link #read} does, validating against the document's {@code !!schema} where this
     * reader is schema-aware and one is declared. A document declaring no directives is not an error: its
     * header components come back empty.
     *
     * @return the document, or {@code null} where {@link #read} would also return nothing -- a document that
     *         will not lex or parse, reported through this read's receiver rather than thrown past it
     */
    public TsonDocument readDocument(String source) {
        return readDocument(new TsonDataStream(source));
    }

    /** {@link #readDocument(String)} straight off a stream; {@code source} is not closed here. */
    public TsonDocument readDocument(InputStream source) {
        return readDocument(new TsonDataStream(source));
    }

    /** Like {@link #read(String)} but always schemaless -- reads the wire structure, even when the document declares a {@code !!schema}. (A schemaless reader's {@link #read} already does this.) */
    public TsonValue readWithoutSchema(String source) {
        return readRoot(new TsonDataStream(source), true);
    }

    /** {@link #readWithoutSchema(String)} straight off a stream. */
    public TsonValue readWithoutSchema(InputStream source) {
        return readRoot(new TsonDataStream(source), true);
    }

    /**
     * Reads {@code source} as {@code typeName}, declared by the schema {@link #withSchema} named -- for data
     * that isn't self-describing, where you hold the schema out of band. The caller supplies what a {@code
     * !!schema} plus a root type-ref would otherwise say, and validation is identical either way; a root
     * type-ref the data does carry is read as part of the value, not used to select the type.
     */
    public TsonValue readAs(String source, String typeName) {
        return readRootAs(new TsonDataStream(source), typeName);
    }

    /** {@link #readAs(String, String)} straight off a stream. */
    public TsonValue readAs(InputStream source, String typeName) {
        return readRootAs(new TsonDataStream(source), typeName);
    }

    /**
     * Reads one value at {@code ctx}'s current position into a tree -- the low-level form for a caller
     * managing their own {@link TsonReadContext}. Always schemaless and frame-free: it neither inspects a
     * {@code !!schema} (an arbitrary position carries no document framing to hold one) nor checks for
     * trailing content; use the {@code String}/{@code InputStream} entry points for a whole document.
     */
    public TsonValue read(TsonReadContext ctx) {
        return schemaless.read(ctx);
    }

    // ── Internals ────────────────────────────────────────────────────────

    /**
     * The whole document, header included. Structured as {@link #readRoot} is and for the same reason -- the
     * {@code DocumentStart} event already carries both directives, so keeping them costs a field read rather
     * than a second pass over the source.
     */
    private TsonDocument readDocument(TsonDataStream stream) {
        try {
            TsonReadContext ctx = TsonReadContext.of(
                    TokenPolicyEventSource.wrap(stream, tokenPolicy, receiver), receiver);
            DocumentStart start = (DocumentStart) ctx.next();
            TsonValue root = (tree == null || start.schema().isEmpty())
                    ? schemaless.read(ctx)
                    : readAgainstSchema(start.schema().get(), ctx, null);
            requireDocumentEnd(ctx);
            return new TsonDocument(start.id(), start.schema(), root);
        } catch (RuntimeException e) {
            baseSyntaxFailure(e);
            return null;
        }
    }

    private TsonValue readRoot(TsonDataStream stream, boolean ignoreSchema) {
        try {
            TsonReadContext ctx = TsonReadContext.of(
                    TokenPolicyEventSource.wrap(stream, tokenPolicy, receiver), receiver);
            DocumentStart start = (DocumentStart) ctx.next();
            TsonValue result = (ignoreSchema || tree == null || start.schema().isEmpty())
                    ? schemaless.read(ctx)
                    : readAgainstSchema(start.schema().get(), ctx, null);
            requireDocumentEnd(ctx);
            return result;
        } catch (RuntimeException e) {
            return baseSyntaxFailure(e);
        }
    }

    private TsonValue readRootAs(TsonDataStream stream, String typeName) {
        if (schemaUri == null) {
            throw new IllegalStateException("readAs needs a schema -- call withSchema(uri) first");
        }
        try {
            TsonReadContext ctx = TsonReadContext.of(
                    TokenPolicyEventSource.wrap(stream, tokenPolicy, receiver), receiver);
            ctx.next(); // DocumentStart -- any !!schema it declares is overridden by withSchema
            TsonValue result = readAgainstSchema(schemaUri, ctx, typeName);
            requireDocumentEnd(ctx);
            return result;
        } catch (RuntimeException e) {
            return baseSyntaxFailure(e);
        }
    }

    /**
     * A document that will not lex or parse, reported through this read's own receiver rather than thrown
     * past it -- so a collecting read never throws for a bad <i>document</i>, and a fail-fast one still
     * throws, because its receiver does when handed this.
     *
     * <p><b>Why the receiver rather than the caller.</b> The lexer is fail-fast and the stream is lazy, so a
     * base-syntax failure surfaces mid-read, after any earlier value-level problem has already been reported
     * -- which used to leave a collecting caller holding a populated collector <em>and</em> an exception,
     * with no way to tell that the two belonged to one document. Reporting it is also the only way the
     * problem reaches a caller who asked for problems: {@code Diagnostic.ofBaseSyntaxError} is public
     * precisely because one of the three exception types is not nameable outside this module, and having
     * every caller invoke it was the library conceding the classification is required while making each of
     * them ask for it.
     *
     * <p>Nothing continues past this, and nothing pretends to: the read is over and hands back no tree. That
     * is the same shape an unreachable {@code !!schema} already had ({@link #readAgainstSchema}) -- report
     * once, abandon the value -- rather than a new one.
     *
     * <p>{@code ofBaseSyntaxError} rethrows anything that is not one of §8.1's three base-syntax failures,
     * so a fault in this library still reaches the caller as itself: a bug is not a verdict on the document.
     */
    private TsonValue baseSyntaxFailure(RuntimeException e) {
        receiver.report(Diagnostic.ofBaseSyntaxError(e));
        return null;
    }

    /**
     * Pulls the event after the document's value, which must be {@code DocumentEnd}.
     *
     * <p><b>The pull is the point, not the assertion.</b> {@link TsonDataStream} is lazy, and its root frame
     * is what rejects trailing content -- but only when something asks for an event past the root value. Drop
     * this call and {@code "{ a: 1 } junk"} reads clean. The {@code instanceof} check is then belt-and-braces:
     * the pull itself throws {@code TsonParseException} first on any real document.
     */
    private static void requireDocumentEnd(TsonReadContext ctx) {
        TsonEvent trailing = ctx.next();
        if (!(trailing instanceof DocumentEnd)) {
            throw new IllegalStateException("unexpected trailing event after the document's value: " + trailing);
        }
    }

    /**
     * Reads the root value against {@code schemaUri}'s type -- {@code typeName} when {@link #readAs} supplied
     * one, else the document's own root type-ref.
     *
     * <p>A problem reaching the schema is reported through {@code ctx} like any other, so a fail-fast reader
     * still throws while a collecting one gets it as a {@link Diagnostic} -- the same promise {@link
     * Tson#validate} makes. The document's own value is then skipped so the stream still lands on {@code
     * DocumentEnd} and {@link #requireDocumentEnd} stays meaningful.
     */
    private TsonValue readAgainstSchema(String schemaUri, TsonReadContext ctx, String typeName) {
        TsonCompiledSchema compiled;
        try {
            // Every problem with the schema, not just the first, and each keeping the declaration and
            // position it was reported against. They go to this reader's own receiver rather than through
            // ctx.report, which would rebuild them from the *data* cursor -- stamping a data position on a
            // problem in a schema and discarding the schema pointer, which is the misattribution this
            // reports its way out of.
            compiled = tree.get(schemaUri, receiver);
        } catch (RuntimeException e) {
            // No compiled schema at all: one problem, and there is nothing to enumerate. What kind of
            // problem is SchemaFailure's -- an unfetchable or malformed schema is the author's, a bind
            // mismatch is the reading application's, and coding them alike would make the second read as
            // the first.
            SchemaFailure failure = SchemaFailure.of(e);
            return abandon(ctx, failure.code(), e.getMessage(), failure.expected(), schemaUri);
        }
        if (compiled == null) {
            // Reported above; skip the value so the stream still lands on DocumentEnd.
            EventSkip.dataValue(ctx);
            return null;
        }
        String name = typeName;
        if (name == null) {
            // Past any leading annotations, which are part of the root value rather than something before it:
            // `@doc:"..." !api { ... }` annotates and types one value ([TSON-DATA] §3.3), and TSON having no
            // comment syntax makes an annotation the only way a document can say what it is for. Looked past
            // rather than consumed, so the reader below still builds them into what it returns.
            String found = EventSkip.typeRefAhead(ctx).orElse(null);
            if (found == null) {
                return abandon(ctx, Diagnostic.Code.VALIDATION_ERROR,
                        "data declares a !!schema but has no root type-ref (e.g. `!person`) to select a type",
                        "a root type-ref", "(none)");
            }
            name = found;
        }
        TsonTypeReader<?> reader = compiled.find(name).orElse(null);
        if (reader == null) {
            // The root-position analogue of an unrecognized field, answered the same way: the prose names
            // the nearest declared type, and `expected` carries the closed set the ref could have named.
            return abandon(ctx, Diagnostic.Code.UNKNOWN_TYPE, compiled.unknownTypeMessage(name),
                    compiled.declaredTypeNames(), name);
        }
        // Seeded with the name the author wrote, so a pointer into a template-derived type says
        // /order_response/items rather than naming the entry the resolver minted -- see rootDeclaration.
        return (TsonValue) reader.read(rooted(ctx, compiled, name));
    }

    /** {@code ctx} rooted at the declaration a read entered through, when the schema declares one. */
    private static TsonReadContext rooted(TsonReadContext ctx, TsonCompiledSchema compiled, String typeName) {
        return compiled.rootDeclaration(typeName).map(ctx::underDeclaration).orElse(ctx);
    }

    /**
     * Reports {@code code}/{@code message}, discards the root value, and yields no tree -- see {@link
     * #readAgainstSchema}. There is deliberately no overload that omits {@code expected}/{@code actual}: a
     * facade-level failure is exactly the kind a machine consumer must be able to act on without reading
     * prose, and the omitting overload this used to have is how three of them ended up with a blank
     * structured half. See {@code docs/readers-and-diagnostics.md} on what each field is for.
     */
    private static TsonValue abandon(TsonReadContext ctx, Diagnostic.Code code, String message, String expected,
            String actual) {
        ctx.report(code, message, expected, actual);
        EventSkip.dataValue(ctx);
        return null;
    }
}
