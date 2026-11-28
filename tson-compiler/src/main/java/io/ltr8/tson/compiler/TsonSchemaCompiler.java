package io.ltr8.tson.compiler;

import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.compiler.reader.*;
import io.ltr8.tson.compiler.reader.DeferredTypeReader;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.NamespaceBody;
import io.ltr8.tson.schema.meta.ParametricEntry;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.Extern;
import io.ltr8.tson.schema.ExternAdmission;
import io.ltr8.tson.schema.ExternTarget;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.compiler.reader.Subsumption;
import io.ltr8.tson.compiler.stream.EventSkip;
import io.ltr8.tson.encoding.TsonSchemaBinding;
import io.ltr8.tson.encoding.plan.BindingTable;
import io.ltr8.tson.encoding.plan.EntryBindings;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Compiles a {@link TsonLinkedSchema} into a {@link TsonCompiledSchema} -- the "compile" stage of
 * this project's own parse -&gt; resolve -&gt; link -&gt; register -&gt; compile -&gt; read
 * pipeline vocabulary: this class is the verb, {@link TsonCompiledSchema} is the noun it produces.
 * Requires a {@link TsonLinkedSchema}, not a bare {@code TsonSchema} -- every reference
 * reachable from a body must already be argument-free (materialisation closes every {@code <...>}
 * application, into the declaration that names it or in place as a type written there), and every name a body refers to must
 * actually be present in {@code linkedSchema.schema().entries()}; a referenced-but-missing name is
 * treated as a bug, not a normal failure (see {@link Compilation#resolve}'s own {@code
 * IllegalStateException}).
 *
 * <p><b>Eager, not lazy.</b> {@link #compile} walks every one of {@code linkedSchema.schema()
 * .entries()} and resolves each before returning, so a caller that only ever reads a handful of a
 * large schema's own types still gets the same assurance that every other entry compiles too, and
 * any genuinely broken entry is discovered at compile time rather than piecemeal whenever some
 * future caller happens to {@code get} it.
 *
 * <p>A build failure for one specific entry doesn't abort the whole walk -- {@link
 * Compilation#resolve} catches it and substitutes an {@link ErrorReader}, so the schema as a whole
 * still compiles; only actually reading a value against that one entry fails, at that point. This
 * covers both a constructor with no {@link ValueReaderFactory} to dispatch to, and a factory that exists
 * but rejects this particular entry. Every constructor the kernel and the three libraries declare has a factory,
 * so the first case is a constructor a schema declared itself that this library has never seen.
 *
 * <p><b>An entry declaring type parameters never reaches a factory at all.</b> No position names a parametric record
 * bare (§5.10), and one compiles to a {@link ParametricRecordReader}, which reports against the data and skips the value:
 * only a <em>data</em> type-ref can reach one -- a schema naming a parametric record without applying it is refused when it
 * links -- and that is the author's error, not a gap.
 *
 * <p>A compile dispatches each body's constructor name to a factory through one factory set ({@link
 * #compile(TsonLinkedSchema, ValueReaderFactoryResolver)}), with no scoping: linking already established that every
 * head a schema applies is in its namespace. One non-factory case is checked first: {@link Alias} (a bare {@code
 * name => other_name} entry, §8.3) delegates straight to the target's own reader, resolved recursively rather than
 * built fresh. A compile of a resolution in progress ({@link #compileReady}) compiles the entries ready so far, in
 * which a declaration not yet ready is a {@link PendingTypeReader} rather than a missing name.
 *
 * <p><b>A held value is decoded after the walk, not during it.</b> A record field's default or pin is held as
 * written while the readers are built and decoded through the field's reader once every entry has one ({@link
 * CompiledReaders#settle}), so building a reader never reads through another -- a pin typed by an entry still
 * on the build stack holds nothing back -- and the compiled schema is handed out with every value decoded.
 *
 * <p>The per-compilation mutable state (cycle-detection bookkeeping) lives in a private nested
 * {@link Compilation} helper, one instance per {@link #compile} call, discarded once it returns --
 * {@link TsonCompiledSchema} itself holds nothing but the finished, immutable result.
 */
public final class TsonSchemaCompiler {

    private TsonSchemaCompiler() {
    }

    /**
     * Compiles {@code linkedSchema} against a factory set -- every body's constructor is dispatched through
     * {@code factories} alone. A read registry compiles a schema in its mode this way, loading in bind mode the
     * schema's {@link TsonSchemaBinding}, which fails the compile where a class it binds cannot be described.
     */
    public static TsonCompiledSchema compile(TsonLinkedSchema linkedSchema, ValueReaderFactoryResolver factories) {
        return compile(linkedSchema, factories, null, true);
    }

    /**
     * {@link #compile(TsonLinkedSchema, ValueReaderFactoryResolver)}, the schema an extern names compiled by {@code
     * foreign} ([TSON-SCHEMA] §7.9): a registry's cache, so each foreign schema compiles once in its mode and is
     * shared by every importer. Null compiles each foreign schema once for this compile ({@link #onePerCompile}).
     * {@code strict} is false for a compile the resolution core makes, which holds a class its bind context cannot
     * describe as bound to nothing ({@link BindingTable#checkpoint}), so a consumer's class fails only a read of it.
     */
    static TsonCompiledSchema compile(TsonLinkedSchema linkedSchema, ValueReaderFactoryResolver factories,
                                      ForeignSchemas foreign, boolean strict) {
        return compileWith(linkedSchema, factories::resolve, Set.of(), false, foreign, bindingOf(factories), strict);
    }

    /** The context {@code factories} binds objects with, or {@code null} for a table that reads trees. */
    static io.ltr8.bind.DataBindContext bindingOf(ValueReaderFactoryResolver factories) {
        return factories instanceof ValueReaderFactoryRegistry registry ? registry.binding() : null;
    }

    /**
     * Where a compile obtains the compiled schema a foreign value is read in ([TSON-SCHEMA] §7.9), in its own mode:
     * the schema an extern names, or the one declaring an entry a tag reaches at an extern position.
     */
    interface ForeignSchemas {

        TsonCompiledSchema compiled(TsonLinkedSchema linked);

        /** The schema of canonical identity {@code identity}, compiled; empty where this compile cannot obtain it. */
        Optional<TsonCompiledSchema> compiled(String identity);
    }

    /**
     * Each foreign schema compiled once for one compile and whatever it reaches, in that compile's mode, for a compile
     * with no registry, which obtains no schema by identity it has not compiled.
     */
    private static ForeignSchemas onePerCompile(Function<String, ValueReaderFactory> factoryFor,
                                                io.ltr8.bind.DataBindContext binding, boolean strict) {
        Map<String, TsonCompiledSchema> compiled = new java.util.HashMap<>();
        return new ForeignSchemas() {
            @Override
            public TsonCompiledSchema compiled(TsonLinkedSchema linked) {
                String identity = linked.identity();
                TsonCompiledSchema done = compiled.get(identity);
                if (done == null) {
                    done = compileWith(linked, factoryFor, Set.of(), false, this, binding, strict);
                    compiled.put(identity, done);
                }
                return done;
            }

            @Override
            public Optional<TsonCompiledSchema> compiled(String identity) {
                return Optional.ofNullable(compiled.get(identity));
            }
        };
    }

    /**
     * Compiles the part of a schema whose resolution is in progress that is ready, {@code pending} naming its
     * declarations not yet ready: [TSON-SCHEMA] §3.4.1's wait order. A pending name
     * compiles to a {@link PendingTypeReader}, which stops a read of a schema document's value that reaches it, so the
     * resolution can make that entry ready first, and binds a held value typed by it as written. {@code
     * linkedSchema} comes from {@code TsonSchemaLinker.linkReady}; nothing but a resolution reading its own
     * document's entries compiles a schema with names left pending.
     */
    public static TsonCompiledSchema compileReady(TsonLinkedSchema linkedSchema, ValueReaderFactoryResolver factories,
                                                  Set<String> pending) {
        return compileReady(linkedSchema, factories, pending, null);
    }

    /** {@link #compileReady}, a value read through an extern compiled by {@code foreign} ({@link #compile}). */
    static TsonCompiledSchema compileReady(TsonLinkedSchema linkedSchema, ValueReaderFactoryResolver factories,
                                           Set<String> pending, ForeignSchemas foreign) {
        return compileWith(linkedSchema, factories::resolve, Set.copyOf(pending), true, foreign,
                bindingOf(factories), false);
    }

    /**
     * The shared eager walk (see the "Eager, not lazy" note) over {@code factoryFor}'s factories, {@code pending}
     * naming the declarations of a resolution in progress not yet ready, {@code checkpoint} whether this compiles
     * one ({@link #compileReady}), {@code foreign} where a foreign schema is compiled (null: once for this compile),
     * {@code binding} the context {@code factoryFor}'s readers bind with, {@code null} where they read trees, and
     * {@code strict} whether its bindings load as a read's ({@link TsonSchemaBinding#load}) or a resolution's ({@link
     * BindingTable#checkpoint}), which the compiled schema does not hand out.
     */
    static TsonCompiledSchema compileWith(
            TsonLinkedSchema linkedSchema, Function<String, ValueReaderFactory> factoryFor, Set<String> pending,
            boolean checkpoint, ForeignSchemas foreign, io.ltr8.bind.DataBindContext binding, boolean strict) {
        TsonSchemaBinding loaded = binding != null && strict ? TsonSchemaBinding.load(linkedSchema, binding) : null;
        EntryBindings bindings = loaded != null || binding == null ? loaded
                : BindingTable.checkpoint(linkedSchema, binding);
        Obtained obtained = new Obtained(foreign != null ? foreign : onePerCompile(factoryFor, binding, strict));
        Compilation compilation = new Compilation(linkedSchema, factoryFor, pending, checkpoint, obtained, binding,
                bindings);
        for (String name : linkedSchema.schema().entries().keySet()) {
            compilation.resolve(name);
        }
        // Every reader exists now, so the values readers hold as written -- a field's default or pin -- are
        // decoded through their types here, still before the schema is handed out (see CompiledReaders).
        compilation.readers.settle();
        TsonCompiledSchema compiled = new TsonCompiledSchema(linkedSchema, Map.copyOf(compilation.finished),
                compilation.readers, loaded, Map.copyOf(obtained.byIdentity));
        // Hand every reader that resolves at *read* time over to the finished schema, releasing this
        // compilation -- see CompiledReaders, and the "never escape" invariant below that this preserves.
        compilation.readers.bind(compiled);
        return compiled;
    }

    /** The foreign schemas one compile obtains from {@code source}, kept by canonical identity for the compiled one. */
    private static final class Obtained implements ForeignSchemas {
        private final ForeignSchemas source;
        private final Map<String, TsonCompiledSchema> byIdentity = new LinkedHashMap<>();

        Obtained(ForeignSchemas source) {
            this.source = source;
        }

        @Override
        public TsonCompiledSchema compiled(TsonLinkedSchema linked) {
            TsonCompiledSchema compiled = source.compiled(linked);
            byIdentity.putIfAbsent(linked.identity(), compiled);
            return compiled;
        }

        @Override
        public Optional<TsonCompiledSchema> compiled(String identity) {
            Optional<TsonCompiledSchema> compiled = source.compiled(identity);
            compiled.ifPresent(found -> byIdentity.putIfAbsent(identity, found));
            return compiled;
        }
    }

    /**
     * Builds the reader of a type written in place ({@link TypeInline}) from its body, through the factory its
     * constructor dispatches to, as an entry's reader is built: a container, a tuple, a choice, a reference, or the
     * record an application closes to in place, named for a message by the form as written. It has no entry, so it
     * has no name a binding map, a schema location or a tree's tag keys on, nothing IS-A it, so §7.2's guard at its
     * position admits no tag, and it is no alias. It holds the factories, the linked schema and the readers' handle,
     * never the {@link Compilation}, so the handle may build one at read time.
     */
    private static final class InlineReaders {
        private final TsonLinkedSchema linked;
        private final Function<String, ValueReaderFactory> factoryFor;
        private final Map<String, Set<String>> namesMeaning;
        private final EntryBindings loaded;
        private CompiledReaders readers;

        InlineReaders(TsonLinkedSchema linked, Function<String, ValueReaderFactory> factoryFor,
                      Map<String, Set<String>> namesMeaning, EntryBindings loaded) {
            this.linked = linked;
            this.factoryFor = factoryFor;
            this.namesMeaning = namesMeaning;
            this.loaded = loaded;
        }

        TsonTypeReader<?> build(TypeInline type) {
            Top read = type.of();
            ValueReaderFactory factory = factoryFor.apply(TsonCompiledVocabulary.typenameOf(read));
            TsonTypeReader<?> built = factory.create(type.text(), read,
                    new ValueReaderContext(linked, readers, namesMeaning, loaded));
            // §7.2 at the position: no tag names a type written in place, so the guard admits none.
            return Subsumption.guard(type.text(), read, built, namesMeaning, readers);
        }
    }

    /**
     * One {@link #compile} call's own private, per-compilation mutable state and recursive build
     * logic -- {@code finished}/{@code building} never escape a single {@link #compile} invocation
     * (the finished map is copied into an immutable {@link TsonCompiledSchema} once compilation
     * completes; {@code building} is discarded entirely).
     *
     * <p>That invariant needs {@link CompiledReaders} to hold. A reader that resolves a name at <em>read</em>
     * time -- a dispatch reader choosing a variant, an annotation resolving the type it names -- has to keep
     * whatever it was handed for lookups, so handing it {@code this::resolve} directly would keep this whole
     * object, and both mutable collections, reachable for as long as any reader is. The handle is what every
     * reader gets instead, and rebinding it to the finished schema at the end of the walk is what actually
     * makes "never escape" true rather than aspirational.
     */
    private static final class Compilation {
        private final TsonLinkedSchema linked;
        private final TsonSchema schema;
        private final Function<String, ValueReaderFactory> factoryFor;
        private final Map<String, TsonTypeReader<?>> finished = new LinkedHashMap<>();
        private final Set<String> building = new LinkedHashSet<>();

        /**
         * §7.2's alias index, built once: which written names mean each entry. A property of the schema
         * rather than of the entry being guarded, so deriving it per entry would make the walk quadratic in
         * the schema's size for an answer that never changes.
         */
        private final Map<String, Set<String>> namesMeaning;

        /**
         * What every built reader is handed for its own name lookups. Resolves through this compilation
         * while the walk runs, then is rebound to the finished schema so nothing here outlives the call --
         * a reader that resolves at read time (dispatch, annotations) holds this, not {@code this}.
         */
        private final CompiledReaders readers;

        /** The entries a compile of a resolution in progress reads as {@link PendingTypeReader}s; else empty. */
        private final Set<String> pending;

        /** Whether this compiles a checkpoint of a resolution in progress ({@link #compileReady}). */
        private final boolean checkpoint;

        /** Where an extern's foreign schema is compiled, in this compile's mode. */
        private final ForeignSchemas foreign;

        /** The context this compile's readers bind with; {@code null} where they read trees. */
        private final io.ltr8.bind.DataBindContext binding;

        /** What each entry binds to under {@link #binding}, loaded before any reader is built; null for trees. */
        private final EntryBindings loaded;

        Compilation(TsonLinkedSchema linked, Function<String, ValueReaderFactory> factoryFor, Set<String> pending,
                    boolean checkpoint, ForeignSchemas foreign, io.ltr8.bind.DataBindContext binding,
                    EntryBindings loaded) {
            this.foreign = foreign;
            this.binding = binding;
            this.loaded = loaded;
            this.pending = pending;
            this.checkpoint = checkpoint;
            this.linked = linked;
            this.schema = linked.schema();
            this.factoryFor = factoryFor;
            this.namesMeaning = Subsumption.namesMeaning(schema.entries());
            InlineReaders inline = new InlineReaders(linked, factoryFor, namesMeaning, loaded);
            this.readers = new CompiledReaders(this::resolve, inline::build);
            inline.readers = readers;
        }

        TsonTypeReader<?> resolve(String name) {
            TsonTypeReader<?> done = finished.get(name);
            if (done != null) {
                return done;
            }
            if (pending.contains(name)) {
                return new PendingTypeReader(name); // a declaration of a resolution in progress, not yet ready
            }
            if (!building.add(name)) {
                return new DeferredTypeReader<>(name, finished);
            }
            try {
                Top definition = schema.entries().get(name);
                if (definition == null) {
                    throw new IllegalStateException("'" + name + "' is referenced but not present in the schema -- "
                            + "TsonSchemaLinker should already have rejected this before compilation ever started");
                }
                TsonTypeReader<?> built;
                try {
                    built = build(name, definition);
                } catch (MissingBindingException e) {
                    // A type with no class at all is deferred, not fatal: a schema legitimately declares
                    // types a given consumer never binds, and failing the compile for those would make bind
                    // mode unusable. It reaches the first read of this specific type still saying what it is.
                    built = new ErrorReader(name, e);
                } catch (BindMismatchException e) {
                    if (!checkpoint) {
                        // Deliberately not an ErrorReader. Every other build failure is one entry's problem and
                        // deferring it keeps the rest of the schema usable; this one is a wiring mistake between
                        // the schema and the caller's own classes, and deferring it to the first document that
                        // happens to have that type is exactly what makes it expensive to find. See the
                        // exception's own Javadoc.
                        throw e;
                    }
                    // A checkpoint holds entries before materialisation closes their applications and before
                    // their subtypes join, so a class may disagree with what is not yet the entry: the mismatch is
                    // a read's that reaches it, and the finished schema's compile judges the rest.
                    built = refusedAtCheckpoint(name, ctx -> {
                        throw e;
                    });
                } catch (RuntimeException e) {
                    built = new ErrorReader(name, e);
                    if (checkpoint) {
                        built = refusedAtCheckpoint(name, built);
                    }
                }
                finished.put(name, built);
                return built;
            } finally {
                building.remove(name);
            }
        }

        /**
         * {@code failing}, a reader a checkpoint could not build, which may have failed for want of an entry the
         * checkpoint lacks: which reader a record gets depends on its subtypes. So a read stops first where a later
         * checkpoint may build it otherwise -- at the value's tag ({@link EventSkip#typeRefAhead}) and at a declaration
         * not held that may be IS-A {@code name} -- and the failure is the read's only where neither holds.
         */
        private static TsonTypeReader<?> refusedAtCheckpoint(String name, TsonTypeReader<?> failing) {
            return ctx -> {
                EventSkip.typeRefAhead(ctx);
                ctx.pinsUnmatched(name, Set.of(), (selector, pin) -> true);
                return failing.read(ctx);
            };
        }

        /**
         * The boundary at a position typed by the extern {@code name} ([TSON-SCHEMA] §7.9): every name of this
         * namespace that reaches, past its alias chain and its externs, the entry {@code name} lands on or one it
         * admits -- one IS-A it, §7.2's subsumption, or a variant of it where it is a choice -- judged of the entries
         * by identity, each with the foreign reader it crosses through.
         */
        private TsonTypeReader<?> boundary(String name, ValueReaderContext context) {
            ExternAdmission admission = ExternAdmission.of(linked, name);
            ExternTarget.Landing landing = admission.landing();
            ExternReader.Crossing own = crossing(name, landing);
            Map<String, ExternReader.Crossing> admitted = new LinkedHashMap<>();
            List<String> narrower = new java.util.ArrayList<>();
            Map<String, ExternReader.Crossing> byEntry = new java.util.HashMap<>(Map.of(name, own));
            admission.admitted().forEach((written, reach) -> {
                String terminal = reach.terminal();
                if (reach.through().isPresent()) {
                    admitted.put(written, byEntry.computeIfAbsent(terminal,
                            extern -> crossing(extern, reach.through().get())));
                } else {
                    // A name of this scope that is no extern: the target itself says no more than the position does,
                    // and anything narrower is read where the position's foreign schema reads it.
                    admitted.put(written, reach.same() ? own : byEntry.computeIfAbsent(terminal,
                            entry -> imported(name, landing, entry, reach.identity(), reach.reached())));
                }
                // What a message offers is a tag that is read: an abstract record naming no selectors would refuse in turn.
                if (!reach.same() && !ExternAdmission.namesASubtype(reach.reached())) {
                    narrower.add(written);
                }
            });
            return new ExternReader(name, context, own, admitted, admission.selectsNone(),
                    String.join(" | ", narrower), DefaultTsonReadContext.CROSSING, binding);
        }

        private ExternReader.Crossing crossing(String extern, ExternTarget.Landing landing) {
            ExternTarget target = linked.externs().get(extern);
            return new ExternReader.Crossing(foreign.compiled(target.foreign()).get(target.type()),
                    new ForeignScope(extern, linked.declaringIdentity(extern), target.schema()),
                    ExternAdmission.namesASubtype(landing.definition()));
        }

        /**
         * The crossing at the extern {@code position} for {@code entry}, a name of this namespace that is no extern and
         * reaches a subtype or a variant of its target: read in the position's foreign schema, or in the namespace its
         * target lands in, where either holds that entry, else in the schema declaring it.
         */
        private ExternReader.Crossing imported(String position, ExternTarget.Landing landing, String entry,
                                               String identity, Top definition) {
            String declaredBy = linked.declaringIdentity(entry);
            String through = linked.declaringIdentity(position);
            boolean namesASubtype = ExternAdmission.namesASubtype(definition);
            for (TsonLinkedSchema in : List.of(linked.externs().get(position).foreign(), landing.in())) {
                if (in.schema().entries().containsKey(entry) && in.declaringIdentity(entry).equals(declaredBy)) {
                    return new ExternReader.Crossing(foreign.compiled(in).get(entry), new ForeignScope(position,
                            through, in.identity()), namesASubtype);
                }
            }
            TsonTypeReader<?> reader = foreign.compiled(declaredBy).<TsonTypeReader<?>>map(compiled -> compiled.get(entry))
                    .orElseGet(() -> ctx -> {
                        ctx.report(io.ltr8.tson.base.Diagnostic.Code.NOT_IMPLEMENTED, "'" + entry + "' reaches '"
                                + identity + "', which a compile with no registry cannot read in its own schema's scope "
                                + "([TSON-SCHEMA] §7.9)", "a schema this compile can obtain", entry);
                        EventSkip.coreValue(ctx);
                        return null;
                    });
            return new ExternReader.Crossing(reader, new ForeignScope(position, through, declaredBy), namesASubtype);
        }

        private TsonTypeReader<?> build(String name, Top definition) {
            if (ParametricEntry.isParametric(definition)) {
                // No position names a parametric record bare ([TSON-SCHEMA] §5.10). See ParametricRecordReader for why
                // the refusal is the entry's own reader rather than a check at the root.
                return new ParametricRecordReader(name, ParametricEntry.parameterNames(definition),
                        new ValueReaderContext(linked, readers, namesMeaning, loaded).locationOf(name));
            }
            Top body = definition;
            if (body instanceof Alias r) {
                // An alias is a hop, and this is where the chain is collapsed: after linking, once per
                // entry, at the moment a reader is built. Resolved output states the chain the author wrote,
                // so the target's reader is named for the entry doing the referring -- which is what a use
                // site naming `pct` over `pct => small` needs.
                return UseSite.named(resolve(r.target().name()),
                        EntryDisplayName.of(name, definition, linked.schema().factsOf(name),
                                linked.schema().applicationOf(name)));
            }
            if (body instanceof Extern) {
                return boundary(name, new ValueReaderContext(linked, readers, namesMeaning, loaded));
            }
            if (body instanceof ValueEntry value) {
                // A value, which nothing is read against: a data tag naming it is the document's mistake.
                return new ValueEntryReader(name, value.type(),
                        new ValueReaderContext(linked, readers, namesMeaning, loaded).locationOf(name));
            }
            if (body instanceof io.ltr8.tson.schema.meta.IncompleteEntry incomplete) {
                return new IncompleteEntryReader(name, incomplete,
                        new ValueReaderContext(linked, readers, namesMeaning, loaded).locationOf(name));
            }
            // A namespace type's value is the map it describes (NamespaceBody#read), its keys read under its key
            // family.
            Top read = body instanceof NamespaceBody namespace ? namespace.read() : body;
            ValueReaderFactory factory = factoryFor.apply(TsonCompiledVocabulary.typenameOf(read));
            TsonTypeReader<?> built = factory.create(name, read,
                    new ValueReaderContext(linked, readers, namesMeaning, loaded));
            if (body instanceof NamespaceBody && TsonSchema.SCHEMA.equals(name) && linked.isKernel(name)) {
                // A value of the kernel's `schema` is a schema document, a resolved form read as data: a name at a
                // kind or reference in it resolves in that document, which these readers' closure does not hold, so
                // its grammar is read and what it names is left to that schema's link or to ingest (§4.2, §8.1).
                TsonTypeReader<?> map = built;
                built = ctx -> DefaultTsonReadContext.readingSchemaNames(ctx, () -> map.read(ctx));
            }
            // §7.2's subsumption rule, applied at every position it governs rather than only where a record
            // happened to have subtypes -- see Subsumption for which kinds it deliberately leaves alone.
            return Subsumption.guard(name, definition, built, namesMeaning, readers);
        }
    }
}
