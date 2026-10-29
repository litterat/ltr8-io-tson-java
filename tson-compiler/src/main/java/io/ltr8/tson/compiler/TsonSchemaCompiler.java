package io.ltr8.tson.compiler;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.compiler.reader.*;
import io.ltr8.tson.compiler.reader.DeferredTypeReader;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.compiler.reader.Subsumption;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Compiles a {@link TsonLinkedSchema} into a {@link TsonCompiledSchema} -- the "compile" stage of
 * this project's own parse -&gt; resolve -&gt; link -&gt; register -&gt; compile -&gt; read
 * pipeline vocabulary: this class is the verb, {@link TsonCompiledSchema} is the noun it produces.
 * Requires a {@link TsonLinkedSchema}, not a bare {@code TsonSchema} -- every {@code type_ref}
 * reachable from a body must already be argument-free (materialisation closes every {@code <...>}
 * application into a reference to the entry it mints), and every name a body refers to must
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
 * but rejects this particular entry. Every constructor meta-kernel.tn and meta.tn declare has a factory,
 * so the first case is a constructor a schema declared itself that this library has never seen.
 *
 * <p><b>An entry declaring type parameters never reaches a factory at all</b>, and compiles to one of two
 * readers. A template whose held body carries {@code extension} is a <em>family base</em> and compiles to
 * an {@link AbstractTemplateReader}, which dispatches to one of its instantiations by tag or by the
 * discriminators exactly as a closed abstract or sealed record does. Every other template is not a type
 * (§5.10) and compiles to an {@link OpenTemplateReader}, which reports against the data and skips the
 * value: only a <em>data</em> type-ref can reach one -- a schema naming a template without applying it is
 * refused when it links -- and that is the author's error, not a gap.
 *
 * <p>A compile dispatches each body's constructor name to a factory through one factory set ({@link
 * #compile(TsonLinkedSchema, ValueReaderFactoryResolver)}), with no scoping: linking already established that every
 * head a schema applies is in its namespace. One non-factory case is checked first: {@link Reference} (a bare {@code
 * name => other_name} entry, §8.3) delegates straight to the target's own reader, resolved recursively rather than
 * built fresh. A <b>bootstrap</b> compile ({@link #compileBootstrap}) is a compile of the meta-kernel
 * entries ready so far, in which a kernel entry not yet read is a {@link PendingTypeReader} rather than a missing name.
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
     * {@code factories} alone. A read registry compiles a schema in its mode this way, and {@link
     * TsonCompiledMetaSchema#compile} compiles vocabulary in object-binding mode, meta-kernel's pre-loaded
     * stand-in ({@link TsonSchema#bootstrap()}) among it.
     */
    public static TsonCompiledSchema compile(TsonLinkedSchema linkedSchema, ValueReaderFactoryResolver factories) {
        return compileWith(linkedSchema, factories::resolve, Set.of());
    }

    /**
     * <b>Bootstrap compile</b>: compiles the part of meta-kernel's bootstrap that is ready, in object-binding mode
     * over {@code context}, with {@code pending} naming the kernel's declarations not yet read. A pending name
     * compiles to a {@link PendingTypeReader}, which stops a read that reaches it so the bootstrap can make that entry
     * ready first, and binds a held value typed by it as written. {@code linkedSchema} comes from {@code
     * TsonSchemaLinker.linkBootstrap(TsonSchema, Set)}; nothing but {@code MetaKernelBootstrapResolver} compiles a
     * schema with names left pending.
     *
     * @throws IllegalArgumentException if {@code linkedSchema} is not the bootstrap's
     */
    public static TsonCompiledSchema compileBootstrap(TsonLinkedSchema linkedSchema, DataBindContext context,
                                                      Set<String> pending) {
        if (!linkedSchema.schema().bootstrap()) {
            throw new IllegalArgumentException("'" + linkedSchema.schema().id() + "' was not produced by the "
                    + "meta-kernel bootstrap -- only its partial kernel compiles with entries pending");
        }
        return compileWith(linkedSchema, ValueReaderFactoryRegistry.bind(context)::resolve, Set.copyOf(pending));
    }

    /**
     * The shared eager walk (see the "Eager, not lazy" note) -- {@code factoryFor} is the only thing that
     * differs between a compile and a bootstrap compile's.
     */
    private static TsonCompiledSchema compileWith(
            TsonLinkedSchema linkedSchema, Function<String, ValueReaderFactory> factoryFor, Set<String> pending) {
        Compilation compilation = new Compilation(linkedSchema, factoryFor, pending);
        for (String name : linkedSchema.schema().entries().keySet()) {
            compilation.resolve(name);
        }
        // Every reader exists now, so the values readers hold as written -- a field's default or pin -- are
        // decoded through their types here, still before the schema is handed out (see CompiledReaders).
        compilation.readers.settle();
        TsonCompiledSchema compiled = new TsonCompiledSchema(linkedSchema, Map.copyOf(compilation.finished));
        // Hand every reader that resolves at *read* time over to the finished schema, releasing this
        // compilation -- see CompiledReaders, and the "never escape" invariant below that this preserves.
        compilation.readers.bind(compiled);
        return compiled;
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
         * The inverse of §8.3's alias hop, built once: which written names reach each entry. A binding map is
         * keyed on the names an author wrote and a materialised entry has none of its own (§8.2), so a bind
         * lookup asks under these before the entry's own name -- and, like {@code namesMeaning}, this is a
         * property of the schema rather than of the entry, so deriving it per entry would be quadratic.
         */
        private final Map<String, List<String>> referrers;

        /**
         * What every built reader is handed for its own name lookups. Resolves through this compilation
         * while the walk runs, then is rebound to the finished schema so nothing here outlives the call --
         * a reader that resolves at read time (dispatch, annotations) holds this, not {@code this}.
         */
        private final CompiledReaders readers = new CompiledReaders(this::resolve);

        /** The entries a bootstrap compile reads as {@link PendingTypeReader}s; empty for every other compile. */
        private final Set<String> pending;

        Compilation(TsonLinkedSchema linked, Function<String, ValueReaderFactory> factoryFor, Set<String> pending) {
            this.pending = pending;
            this.linked = linked;
            this.schema = linked.schema();
            this.factoryFor = factoryFor;
            this.namesMeaning = Subsumption.namesMeaning(schema.entries());
            this.referrers = ValueReaderContext.referrers(schema.entries());
        }

        TsonTypeReader<?> resolve(String name) {
            TsonTypeReader<?> done = finished.get(name);
            if (done != null) {
                return done;
            }
            if (pending.contains(name)) {
                return new PendingTypeReader(name); // a bootstrap's kernel entry not yet read
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
                    // Deliberately not an ErrorReader. Every other build failure is one entry's problem and
                    // deferring it keeps the rest of the schema usable; this one is a wiring mistake between
                    // the schema and the caller's own classes, and deferring it to the first document that
                    // happens to have that type is exactly what makes it expensive to find. See the
                    // exception's own Javadoc.
                    throw e;
                } catch (RuntimeException e) {
                    built = new ErrorReader(name, e);
                }
                finished.put(name, built);
                return built;
            } finally {
                building.remove(name);
            }
        }

        private TsonTypeReader<?> build(String name, Top definition) {
            if (OpenEntry.isOpen(definition)) {
                ValueReaderContext context =
                        new ValueReaderContext(linked, readers, namesMeaning, referrers);
                if (OpenEntry.isFamilyBase(definition)) {
                    // A *family base* -- a record template (§5.10). No
                    // value is read against it: one of its instantiations is, selected by a tag or by the
                    // discriminators, so it dispatches exactly as a closed abstract or sealed record does.
                    return new AbstractTemplateReader(name, definition, context, readers);
                }
                // Every other template is not a type ([TSON-SCHEMA] §5.10). See OpenTemplateReader for why the
                // refusal is the entry's own reader rather than a check at the root.
                return new OpenTemplateReader(name, OpenEntry.parameterNames(definition), context.locationOf(name));
            }
            Top body = definition;
            if (body instanceof Reference r) {
                // A reference is a hop, and this is where the chain is collapsed: after linking, once per
                // entry, at the moment a reader is built. Resolved output states the chain the author wrote,
                // so the target's reader is named for the entry doing the referring -- which is what a use
                // site naming `pct` over `pct => small` needs, and what names a self-reaching instantiation by
                // the application it records rather than by its synthetic's content-derived key.
                return UseSite.named(resolve(r.target().name()),
                        EntryDisplayName.of(name, definition, linked.schema().factsOf(name),
                                linked.schema().applicationOf(name)));
            }
            // A namespace type resolves to a record of the facets it states, and a value of it is the map they
            // describe, never a record of those facets. Its keys are read under a pinned key family, and by their
            // spelling alone where the family is a default the host supplies (MapBody.Namespace#read): a record's
            // field names, which linking judges by the record's name_type.
            Top read = linked.namespace(name).<Top>map(MapBody.Namespace::read).orElse(body);
            ValueReaderFactory factory = factoryFor.apply(TsonCompiledMetaSchema.typenameOf(read));
            TsonTypeReader<?> built = factory.create(name, read,
                    new ValueReaderContext(linked, readers, namesMeaning, referrers));
            // §7.2's subsumption rule, applied at every position it governs rather than only where a record
            // happened to have subtypes -- see Subsumption for which kinds it deliberately leaves alone.
            return Subsumption.guard(name, definition, built, namesMeaning, readers);
        }
    }
}
