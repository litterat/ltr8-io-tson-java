package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.TsonUnicodePolicy;
import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Annotation;
import io.ltr8.annotation.Annotations;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonBindMismatchException;
import io.ltr8.tson.compiler.TsonCompiledSchemaLoader;
import io.ltr8.tson.compiler.TsonDiagnosticsReceiver;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.SchemaPositions;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.compiler.stream.ListEventSource;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a whole {@link SchemaDocument} into a {@link TsonSchema}: header-directive validation
 * ({@code !!id}/{@code !!import}), deriving the structure namespace from this resolver's own {@link
 * TsonCompiledSchemaLoader}, merging {@code !!import} entries into the type-name namespace, and
 * handing each local declaration off to a {@link DefinitionResolver} for the actual, per-declaration
 * resolution work (Part 2 §4, §8's {@code type_definition} shape).
 *
 * <p>A {@link TsonCompiledSchemaLoader} is required: a document-level resolution that can't validate
 * its own {@code !!id} or reach its own {@code !!meta} isn't a degraded version of this job, it's a
 * different one -- per-declaration resolution against a hand-built namespace, which is exactly what
 * {@link DefinitionResolver} is for directly.
 *
 * <p>{@link #resolveSchema(SchemaDocument)} builds a fresh {@link DefinitionResolver} per call, not a
 * reused field, since a single instance of this class can resolve documents governed by *different*
 * meta-schemas across separate calls -- both the compiled reader and the structure namespace have to
 * be bound to that call's own {@code metaParser}. The type-name namespace ({@code namespace}, this
 * method's own local, {@code !!import}-seeded map) is built before the {@link DefinitionResolver} it
 * feeds and reaches it through {@link OnDemand}, a memo over that same map rather than a copy -- so the
 * resolver sees each newly-added entry immediately, and asking for one not yet resolved resolves it.
 */
public final class SchemaResolver {

    /**
     * [TSON-SCHEMA] §8.2's derived marker, attached below to the key of every entry this resolver
     * materialised from a sugar form. Valueless -- presence at the key is the whole of the information --
     * and built by name rather than resolved through the governing meta the way an author-written annotation
     * is: there is no author to resolve against, and §8.1 makes it derived, discarded and recomputed on
     * ingest rather than read from a document.
     */
    private static final Annotations SYNTHETIC = Annotations.of(List.of(Annotation.of("synthetic")));

    private final TsonCompiledSchemaLoader loader;

    /**
     * @param loader consulted by {@link #resolveSchema(SchemaDocument)} to resolve a document's own
     *               {@code !!meta}/{@code !!import} targets -- fetching, resolving, registering, and
     *               compiling each as needed rather than requiring them to already exist somewhere.
     *               Required.
     *
     *               <p>Deliberately the {@link TsonCompiledSchemaLoader} interface, not the concrete
     *               registry that implements it -- and a loader rather than a plain "look it up, throw
     *               if missing" lookup, because such a lookup has no way to bootstrap meta-kernel's own
     *               document: resolving it means resolving *its own* {@code !!meta}, which names itself,
     *               so a lookup-only resolver would need meta-kernel already registered before it could
     *               ever register meta-kernel. The loader's implementation ({@code TsonCompiledMetaRegistry})
     *               recognizes that one case and answers it directly (its own hand-written bootstrap)
     *               instead of looping forever.
     */
    public SchemaResolver(TsonCompiledSchemaLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /**
     * Resolves every declaration in {@code document}'s body, on demand and dependency-following rather than
     * strict source order (§3.4.1) -- so a declaration may compose or refine one declared later in the same
     * schema, with a circular composition/refinement chain rejected as unresolvable -- and carries {@code
     * document}'s own header directives (§2.2: {@code !!id}?/{@code !!meta}/{@code !!import}*) straight into the
     * result's {@link TsonSchema#id()}/{@link TsonSchema#meta()}/{@link TsonSchema#imports()}.
     *
     * <p><b>Two things are validated up front, before any declaration is resolved</b>, rather than
     * silently proceeding (or failing confusingly deep inside some unrelated declaration): (1)
     * {@code document.id()} must be present -- required by policy for a publishable schema (§2.2.1:
     * "publishing a schema... REQUIRES !!id"), and this resolver is about to treat {@code document}
     * as one that's going to *be* registered; (2) that {@code !!id} must be a well-formed
     * canonical-identity candidate ({@link TsonCanonicalIdentity#validate}) -- the same check
     * {@link TsonSchemaRegistry#register} would run anyway, just surfaced here, before resolution
     * work is spent on a document that could never actually be registered. Both throw {@link
     * IllegalStateException} (or, for (2), {@link TsonSchemaValidationException}, in an already-
     * established shape) naming the actual problem. {@code document.meta()} itself is then resolved
     * via this resolver's own {@link TsonCompiledSchemaLoader} (fetched/bootstrapped/compiled on
     * demand if it wasn't already available, rather than requiring it to pre-exist) -- and its
     * resolved entries become the structure namespace every declaration resolves against.
     *
     * <p><b>{@code !!import} is merged into the type-name namespace the same way</b> -- each import's
     * own URI is validated the same way {@code !!id} is, then resolved via the same {@link
     * TsonCompiledSchemaLoader} and its entries merged in, *before* any local declaration is
     * resolved. This is genuinely required, not cosmetic: unlike the structure namespace (consulted
     * only for constructor-application targets), an import's own entries feed the *type-name*
     * namespace -- the same {@code namespace} map (exposed to {@link DefinitionResolver} through {@link
     * OnDemand}) its own composition/refinement/atom-refinement resolution looks a
     * supertype/refinement-source straight up in, with no fallback of any kind. meta.tn's own {@code
     * date_type => ~atom & atom_specification & {...}}, composing with two meta-kernel entries it
     * only has via its own {@code !!import}, would fail to resolve at all without this. Collision
     * handling mirrors {@code TsonSchemaLinker.mergeImports}'s own rule exactly: a name declared by
     * more than one import, or by an import *and* a local declaration, is a {@link
     * TsonSchemaValidationException} -- checked as each import is merged in, and again as each local
     * declaration is about to be resolved, so a collision is caught at the earliest point either side
     * of it becomes known. <b>Merged entries keep their home namespace</b>, same as {@code
     * TsonSchemaLinker}'s own note on this: an imported entry is copied in exactly as its own schema
     * resolved it, never re-resolved or re-materialized against the importer. The result's own {@link
     * TsonSchema#entries()} is local-only -- imported entries are visible *during* resolution but are
     * never part of what this method itself returns; a caller that wants the merged whole gets it
     * from {@code TsonSchemaRegistry.register}'s own eventual output instead.
     */
    public TsonSchema resolveSchema(SchemaDocument document) {
        return resolveSchema(document, SchemaPositions.none());
    }

    /**
     * {@link #resolveSchema(SchemaDocument)} with each declaration's own source position, so every resolved
     * {@link TypeDefinition} carries where it was declared -- {@code TsonSchemaParser#declarationPositions()}
     * for the same document is what a caller passes.
     *
     * <p><b>Identity-keyed, which is why {@code SchemaDesugarer} shares structure <em>and</em> carries
     * positions across the rewrites it cannot avoid.</b> The map comes from an {@code IdentityHashMap}, so
     * only a declaration the parser itself built is found in it as given. Structural sharing keeps that true
     * for everything with no sugar in it ({@code SchemaDesugarerTest}'s {@code assertSame} is the invariant),
     * and {@code SchemaDesugarer.schemaMap} re-registers the position of every declaration it does rebuild --
     * without which any record holding a single {@code [T]} field would resolve with no position at all. An
     * <em>injected</em> declaration still has none, which is correct: it has no source text of its own.
     *
     * <p>The position reaches a diagnostic two ways: {@code TypeDefinition.position()} is what a read-time
     * {@code Diagnostic}'s {@code schemaPosition} is populated from, so a value error can say where the type
     * it violated was declared; and it is what schema-side reporting will attach its own problems to.
     */
    public TsonSchema resolveSchema(SchemaDocument document,
                                    SchemaPositions declarationPositions) {
        return resolve(document, declarationPositions, null);
    }

    /**
     * {@link #resolveSchema(SchemaDocument, SchemaPositions)} reporting each declaration that fails to resolve through
     * {@code receiver} instead of throwing at the first one ([TSON-DATA] §8.1: implementations SHOULD
     * "continue processing after an error to report multiple issues in a single pass"). Every declaration is
     * attempted; a failed one is reported and replaced with a placeholder so its siblings and dependents still
     * resolve, and the schema comes back with as much resolved as could be.
     *
     * <p><b>The result is only trustworthy if nothing was reported.</b> A schema that produced diagnostics
     * contains placeholder entries and must not be linked, registered or compiled -- the caller checks the
     * receiver and stops, which is the phase boundary javac and Swift both draw (javac attributes every entry
     * before {@code shouldStopPolicyIfError} blocks the next phase; Swift never reaches SILGen after a Sema
     * error). {@link TsonDiagnosticsReceiver#throwing()}, the default the other overloads pass, makes the
     * first failure an exception again and so keeps that impossible by construction.
     *
     * <p>Only a {@link TsonSchemaValidationException} -- the schema is wrong -- becomes a diagnostic. An
     * {@code UnsupportedOperationException} means this library hasn't implemented the construct and keeps
     * propagating: a gap is not a verdict on the author's schema, and reporting it as one sends them looking
     * for a fix that doesn't exist.
     *
     * <p><b>The fail-fast overloads do not route through {@link TsonDiagnosticsReceiver#throwing()}</b>, which
     * would raise {@code TsonReadException} and so quietly change the exception type every existing caller
     * sees -- a schema that fails to resolve is not a read failure, and the CLI's own exit codes turn on that
     * distinction. They rethrow the original instead, unwrapped and with its stack intact.
     *
     * @param receiver where a failed declaration is reported; must not be {@code null}
     */
    public TsonSchema resolveSchema(SchemaDocument document,
                                    SchemaPositions declarationPositions,
                                    TsonDiagnosticsReceiver receiver) {
        Objects.requireNonNull(receiver, "receiver");
        return resolve(document, declarationPositions, receiver);
    }

    /** The shared body; {@code receiver} is {@code null} for the fail-fast overloads, which rethrow instead. */
    private TsonSchema resolve(SchemaDocument document,
                               SchemaPositions declarationPositions,
                               TsonDiagnosticsReceiver receiver) {
        String id = document.id().orElseThrow(() -> new IllegalStateException(
                "'" + document.meta() + "': !!id is required to register this schema, but is absent"));
        TsonCanonicalIdentity.validate(id);
        for (String importUri : document.imports()) {
            TsonCanonicalIdentity.validate(importUri);
        }

        TsonCompiledMetaSchema metaParser = loader.loadMeta(document.meta());
        Map<String, Annotations> nameAnnotations = new LinkedHashMap<>();
        Map<String, TypeDefinition> namespace = mergeImports(document, nameAnnotations);

        // Expand the sugar forms before anything reads a declaration, so resolution below only ever sees a
        // bare reference or `!C value` (§5.3/§5.6 define these as desugarings; §3.3.1 names their targets).
        // Sits after `metaParser` because the expansion needs the governing meta's own constructor
        // vocabulary, and returns `document` itself when there is no sugar to expand.
        //
        // Desugaring reports through the same receiver resolution does, rather than throwing at the first bad
        // sugar form. It needs no phase gate of its own: it runs inside this method, so whatever it reports is
        // already behind the one gate the caller checks after this call returns. The reporter is built here
        // because everything Diagnostic.ofSchemaError wants -- the canonical schema id, the identity-keyed
        // position table -- is this method's, not an AST-to-AST rewrite's.
        // A mutable identity-keyed copy, because desugaring rewrites the declarations that contain sugar and
        // has to carry each one's position onto the node it produced -- see SchemaDesugarer.schemaMap. Every
        // position lookup below goes through this copy, so a rewritten declaration is located like any other.
        SchemaPositions positions = declarationPositions.copy();
        Problems problems = new Problems(TsonCanonicalIdentity.canonicalize(id), positions, receiver);
        SchemaDocument desugared = SchemaDesugarer.desugar(document,
                namespace.keySet(), problems.collecting() ? problems::report : null, positions);
        Map<String, SchemaMap.Declaration> declarations = desugared.body().declarations();
        // The names desugaring generated, as opposed to the ones the author wrote. These are the schema's
        // synthetic entries (§5.3's lift rule), so they are both what carries the derived @synthetic marker
        // at its key below (§8.2) and what tells a generated head closing its own intermediate form from an
        // authored one: an application of a generated name is machinery, an application of an authored one
        // is a use site worth recording.
        Set<String> generated = SchemaDesugarer.lifted(document, desugared);

        // Local-vs-import collisions, up front (local names are already unique -- SchemaMap dedupes them).
        for (String name : declarations.keySet()) {
            if (namespace.containsKey(name)) {
                throw new TsonSchemaValidationException("'" + name
                        + "' collides with an entry of the same name brought in by !!import");
            }
        }

        // Resolve on demand, following dependencies rather than source order, so a declaration may reference
        // one declared later in the same schema (§3.4.1). Only composition supertypes and refinement/
        // atom-refinement sources consult this namespace (a field/variant/element type is carried as a bare
        // name, verified later by the linker), so those are the only edges that create a resolution
        // dependency -- and the only recursion that cannot resolve.
        OnDemand namespaceGetter = new OnDemand(namespace, declarations, problems);
        // One materialiser for the whole schema, created before the driving loop because resolution itself
        // closes applications on demand: a supertype or refinement source has to absorb the *closed* entry's
        // fields, and cannot wait for the batch pass below. Sharing the instance is what makes an on-demand
        // closing and a later batch closing of the same application land on one entry.
        TemplateMaterialiser materialiser = new TemplateMaterialiser(namespaceGetter, namespace::put,
                (type, value) -> (Top) read(metaParser.reader(type), value), generated,
                metaParser.schema().entries()::get);

        // The same compiled reader serves both hooks; they differ in what the caller does with the result,
        // which is why they are separate types rather than one Object-returning one.
        DefinitionResolver resolver = new DefinitionResolver(
                (type, value) -> (Top) read(metaParser.reader(type), value),
                // An annotation names an ordinary entry, not a constructor (§6), so this goes through the
                // compiled schema's own reader for that name rather than the constructor vocabulary.
                (type, value) -> read(metaParser.get(type), value),
                metaParser.schema().entries()::get, namespaceGetter, materialiser::closeApplication, positions);
        namespaceGetter.resolveWith(resolver);

        for (String name : declarations.keySet()) {
            namespaceGetter.getTypeDefinition(name);
        }

        // §5.10 materialisation, after every declaration has resolved and before anything reads the result:
        // a template application reaches here as a type-ref carrying arguments, and closing it needs the
        // template's own *resolved* open form, which only exists once the driving loop above has run. The
        // entries it produces are local to this schema and carry no source position -- they are named by
        // derivation from the application, not declared -- so they join the map after the declared ones.
        Map<String, TypeDefinition> resolvedLocals = new LinkedHashMap<>();
        for (String name : declarations.keySet()) {
            resolvedLocals.put(name, namespace.get(name));
        }

        // §5.10's regularity boundary, before anything closes: a template that grows its argument on every
        // recursive step has no finite set of types to build, and catching it at the declaration means a
        // broken template is rejected even if nobody ever applies it. Materialisation's own depth guard
        // stays as a backstop.
        Set<String> irregular = TemplateRegularity.check(resolvedLocals, problems.collecting()
                ? (name, error) -> problems.report(declarations.get(name), error) : null);

        // A condemned template is replaced before materialisation, on the same terms as a declaration that
        // failed to resolve: the verdict is in, and closing an application of one only reports the same
        // defect a second time -- from the depth backstop, 64 instantiations deep, against whichever entry
        // happened to apply it rather than the one that wrote it. Both maps, because the two are read by
        // different halves: `materialise` walks `resolvedLocals`, while the application's head is looked up
        // through the getter over `namespace`.
        condemn(irregular, resolvedLocals, namespace);
        // §5.10's parameter kinds, inferred by use, before anything closes: an argument is "read by the
        // position it lands in", and once a parameter's kind is known that position is known at the
        // application rather than after substitution. Here because it needs every declaration resolved (a
        // slot's declared type comes from the constructor's own vocabulary) and nothing yet closed.
        Set<String> unkinded = new LinkedHashSet<>();
        materialiser.parameterKinds(ParameterKinds.inferAll(namespace, declarations.keySet(),
                metaParser.schema().entries()::get,
                (name, error) -> {
                    if (!problems.collecting()) {
                        throw error;
                    }
                    unkinded.add(name);
                    problems.report(declarations.get(name), "'" + name + "': " + error.getMessage(), error);
                }));
        // Condemned on the same terms as an irregular template: the verdict is in, and closing an application
        // of a template whose parameters cannot be classified only reports the consequence -- the substituted
        // body failing its constructor's vocabulary -- against whichever entry happened to apply it.
        condemn(unkinded, resolvedLocals, namespace);

        Map<String, TypeDefinition> instantiations = materialiser.materialise(resolvedLocals,
                problems.collecting() ? (name, error) -> problems.report(declarations.get(name), error) : null);
        republish(namespace, resolvedLocals, instantiations);

        // §8.2's merge, at the moment that section names -- "identity settles after Pass 2, when references
        // have resolved". A form the desugar phase lifted with an application in a slot was named before that
        // application had an entry to be named for; every application is closed now, so it re-derives to the
        // name the other channel already gave the same form. See SyntheticMerge.
        Map<String, String> merged = SyntheticMerge.renames(declarations, generated, materialiser);
        if (!merged.isEmpty()) {
            SyntheticMerge.rewrite(resolvedLocals, merged);
            SyntheticMerge.rewrite(instantiations, merged);
            merged.forEach((from, to) -> {
                // Dropped where the other channel already published the form, moved where it did not: the
                // eager name was this schema's only one for it, and an entry nothing names is not an entry.
                TypeDefinition eager = resolvedLocals.remove(from);
                namespace.remove(from);
                generated.remove(from);
                generated.add(to);
                if (!namespace.containsKey(to)) {
                    resolvedLocals.put(to, eager);
                }
            });
            republish(namespace, resolvedLocals, instantiations);
        }

        // §6's name-position annotations, resolved here rather than after flattening because §8.3's walk
        // consults them: an alias's declaration annotations are carried to the use site the flattening drops
        // it from, and a hop whose annotations had not been bound yet would contribute nothing. Binding one
        // can fail the way a definition's can (an annotation type §3.3.3 cannot reach), and this loop runs
        // outside the memoized getter that catches those -- so it catches its own, once per name, leaving
        // the entry intact and unannotated rather than losing the whole schema to a bad @doc.
        for (String name : declarations.keySet()) {
            // A merged form is keyed by the name it merged onto, and contributes nothing at all where that
            // name belongs to the materialised half -- the entry is there, marked, and this is its old key.
            String key = merged.getOrDefault(name, name);
            if (!resolvedLocals.containsKey(key)) {
                continue;
            }
            try {
                nameAnnotations.put(key, resolver.annotationsFor(name, declarations.get(name).nameAnnotations()));
            } catch (TsonSchemaValidationException | UnsupportedOperationException
                    | TsonBindMismatchException e) {
                if (!problems.collecting()) {
                    throw e;
                }
                problems.report(declarations.get(name), e);
                nameAnnotations.put(key, Annotations.empty());
            }
        }

        // §8.3, last because it needs everything above already in the namespace: a type position naming a
        // REFERENCE entry is rewritten to the end of its chain and keeps the author's own name as @alias.
        // After materialisation specifically, so an alias to an application flattens onto the entry that
        // application minted rather than onto the alias in front of it.
        Map<String, TypeDefinition> flatLocals = ReferenceFlattener.flatten(resolvedLocals, namespace,
                instantiations.keySet(), nameAnnotations::get);
        resolvedLocals.putAll(flatLocals);
        instantiations = ReferenceFlattener.flatten(instantiations, namespace, instantiations.keySet(),
                nameAnnotations::get);
        republish(namespace, resolvedLocals, instantiations);

        // §6: an annotation written before the declared name binds to the *name*, not to the definition,
        // and "the resolver does not hoist annotations from key to value". A resolved schema is a
        // {type_name => type_definition}, so the name is this map's key -- which is where they go. The two
        // sets stay separate: a declaration's own annotations are on its TypeDefinition. Bound above,
        // because §8.3's walk needs them; this only places them.
        AnnotatedMap<String, TypeDefinition> localOnly = new AnnotatedMap<>();
        for (String name : declarations.keySet()) {
            String key = merged.getOrDefault(name, name);
            if (!resolvedLocals.containsKey(key)) {
                continue;
            }
            // §8.2: a synthetic entry's key carries the derived @synthetic marker, and a lifted declaration
            // has nothing else at its key -- there is no source text in front of a name nobody wrote.
            localOnly.put(key, resolvedLocals.get(key), generated.contains(key)
                    ? SYNTHETIC : nameAnnotations.getOrDefault(key, Annotations.empty()));
        }
        // An entry the materialiser minted has no declared name to carry author annotations from. The
        // synthetic half of them still gets the derived marker: a template's open form closes into the same
        // closed synthetic a directly-written form lifts to, and §8.2 marks it wherever it came from. An
        // instantiation entry gets none, by the same section -- its source is an application, which is what
        // tells the two families apart without a marker.
        Set<String> minted = materialiser.syntheticNames();
        instantiations.forEach((name, definition) -> {
            if (minted.contains(name)) {
                localOnly.put(name, definition, SYNTHETIC);
            } else {
                localOnly.put(name, definition);
            }
        });
        return new TsonSchema(id, document.meta(), document.imports(), localOnly, false);
    }

    /**
     * One run's diagnostic vocabulary: the canonical schema id, the identity-keyed position table, and the
     * receiver -- everything a phase needs to state a problem, so that no phase states it a sixth way.
     *
     * <p><b>A declaration rather than a name</b> is what {@link #report} takes, because the name alone does
     * not locate anything: a position comes from the declaration node, through the identity-keyed table a
     * rewritten declaration is carried onto. Every caller has the node, and asking for it is what stops the
     * lookup being spelled out at each site.
     *
     * <p><b>The code is chosen by the project's own exception classification</b>, which has three outcomes
     * and not two. A {@code TsonSchemaValidationException} is the author's error and an {@code
     * UnsupportedOperationException} is this library's gap -- one says fix your schema, the other says this
     * could not be checked. A {@link TsonBindMismatchException} is neither: it says the reading application
     * is wired wrong, and it is the one a caller most easily acts on, the message naming one of their own
     * classes. Collapsing it into either of the others is what {@code TsonMissingBindingException} exists to
     * prevent -- its Javadoc records a downstream service turning the library-gap shape into a 501 for what
     * was a missing line of configuration.
     *
     * <p>All three are reported per declaration and all three leave a placeholder, so one failing
     * declaration does not cost every other declaration its verdict.
     */
    private record Problems(String schemaId, SchemaPositions positions, TsonDiagnosticsReceiver receiver) {

        /** {@code false} for the fail-fast overloads, whose callers rethrow rather than collect. */
        boolean collecting() {
            return receiver != null;
        }

        void report(SchemaMap.Declaration declaration, RuntimeException error) {
            report(declaration, error.getMessage(), error);
        }

        /** The same, for a caller that has composed its own message rather than taking the exception's. */
        void report(SchemaMap.Declaration declaration, String message, RuntimeException error) {
            Optional<SourcePosition> position = positions.of(declaration);
            receiver.report(switch (error) {
                case TsonBindMismatchException mismatch ->
                        Diagnostic.ofSchemaBindMismatch(schemaId, declaration.name(), mismatch, position);
                case UnsupportedOperationException ignored ->
                        Diagnostic.ofSchemaGap(schemaId, declaration.name(), message, position);
                default -> Diagnostic.ofSchemaError(schemaId, declaration.name(), message, position);
            });
        }
    }

    /**
     * Replaces each named entry with the placeholder {@link #unresolved} builds, keeping its position and its
     * type parameters -- the same treatment a declaration that failed to resolve gets, applied to a template
     * whose verdict is already in.
     *
     * <p><b>Both maps, because the two halves are read by different phases</b>: {@code materialise} walks the
     * locals, while an application's head is looked up through the getter over the namespace. Leaving one
     * behind would close applications against the condemned template through whichever map was missed.
     */
    private static void condemn(Set<String> names, Map<String, TypeDefinition> resolvedLocals,
                                Map<String, TypeDefinition> namespace) {
        for (String name : names) {
            TypeDefinition condemned = resolvedLocals.get(name);
            TypeDefinition placeholder = unresolved(condemned.position(), condemned.parameters());
            resolvedLocals.put(name, placeholder);
            namespace.put(name, placeholder);
        }
    }

    /**
     * Puts the local and materialised entries back into the namespace, which every later phase and the
     * on-demand getter both read through. Called after each pass that rewrites either map, since the two are
     * kept in step by hand: the namespace also holds the imported entries, so it cannot simply be replaced.
     */
    private static void republish(Map<String, TypeDefinition> namespace,
                                  Map<String, TypeDefinition> resolvedLocals,
                                  Map<String, TypeDefinition> instantiations) {
        namespace.putAll(resolvedLocals);
        namespace.putAll(instantiations);
    }

    /**
     * The type-name namespace as the driving loop and every phase after it sees one: a memo over the entries,
     * resolving a local declaration the first time it is asked for and remembering the answer.
     *
     * <p><b>On demand rather than in source order</b>, so a declaration may reference one declared later
     * (§3.4.1). Only composition supertypes and refinement sources consult it -- a field, variant or element
     * type is carried as a bare name and verified later by the linker -- so those are the only edges that
     * create a resolution dependency, and the only recursion that cannot resolve. {@link #resolving} catches
     * such a cycle; ordinary recursion through field references ({@code x => { y: y }} / {@code y => { x: x }})
     * never enters it and resolves fine.
     *
     * <p><b>A failure is caught here, inside the memo, rather than around the driving loop.</b> Resolution
     * follows dependencies rather than source order, so a failure often happens inside a nested resolve --
     * the loop would attribute it to whichever declaration triggered it, then reach the real one and report
     * it a second time. The memo makes it exactly once, against itself. Same shape as {@code
     * TsonSchemaCompiler.Compilation.resolve} substituting an {@code ErrorReader}.
     *
     * <p><b>The resolver arrives after construction</b> ({@link #resolveWith}) because the two are mutually
     * constructed: a {@link DefinitionResolver} needs this getter, and this getter calls that resolver.
     */
    private static final class OnDemand implements DefinitionGetter {

        private final Map<String, TypeDefinition> entries;
        private final Map<String, SchemaMap.Declaration> declarations;
        private final Problems problems;

        /** The chain currently being resolved -- a name arriving twice is a composition/refinement cycle. */
        private final Set<String> resolving = new LinkedHashSet<>();

        private DefinitionResolver resolver;

        OnDemand(Map<String, TypeDefinition> entries, Map<String, SchemaMap.Declaration> declarations,
                 Problems problems) {
            this.entries = entries;
            this.declarations = declarations;
            this.problems = problems;
        }

        /** Supplies the resolver this getter drives; called once, immediately after it is built. */
        void resolveWith(DefinitionResolver resolver) {
            this.resolver = Objects.requireNonNull(resolver, "resolver");
        }

        @Override
        public TypeDefinition getTypeDefinition(String name) {
            TypeDefinition already = entries.get(name);
            if (already != null) {
                return already;
            }
            SchemaMap.Declaration declaration = declarations.get(name);
            if (declaration == null) {
                return null; // not a local entry -- an as-yet-unverified reference the linker validates
            }
            if (!resolving.add(name)) {
                throw new TsonSchemaValidationException("'" + name + "' is part of a circular composition/"
                        + "refinement chain (" + String.join(" -> ", resolving) + " -> " + name + ") -- a "
                        + "supertype or refinement source cannot depend, directly or transitively, on the type "
                        + "it helps define");
            }
            Optional<SourcePosition> position = problems.positions().of(declaration);
            try {
                TypeDefinition resolved = resolver.resolve(declaration, position);
                refuseHeadAbstraction(name, resolved);
                entries.put(name, resolved);
                return resolved;
            } catch (TsonSchemaValidationException | UnsupportedOperationException
                    | TsonBindMismatchException e) {
                if (!problems.collecting()) {
                    throw e;
                }
                problems.report(declaration, e);
                entries.put(name, unresolved(position, SchemaDesugarer.typeParams(declaration.typeDef())));
                return entries.get(name);
            } finally {
                resolving.remove(name);
            }
        }
    }

    /**
     * §5.10 admits no head abstraction: a type parameter stands for a type, never for a template, so
     * {@code <T> { v: T<text> }} is no form. Refused here, over every application the held body writes,
     * because here is the last point at which the author's own spelling is still what fails.
     *
     * <p><b>It cannot wait for the linker</b>, which checks the rest of §5.10's arity rule off the same
     * accessor: materialisation runs first, and by then the parameter has been substituted away. What
     * arrives at the {@code type_name} head is then whatever bound it -- a bare entry name if the argument
     * was closed, reported as an arity error against a content-derived name nobody typed, or {@code
     * type_ref}'s record form if it was not, reported as a wire-vocabulary mismatch. Neither names what the
     * author did.
     */
    private static void refuseHeadAbstraction(String name, TypeDefinition resolved) {
        if (!(resolved.body() instanceof io.ltr8.tson.schema.meta.TemplateBody held)) {
            return;
        }
        for (io.ltr8.tson.schema.meta.TypeRef application : held.applications()) {
            if (resolved.parameters().contains(application.name())) {
                throw new TsonSchemaValidationException("'" + name + "': '" + application.name()
                        + "' is a type parameter applied to arguments -- a parameter stands for a type, never "
                        + "for a template, and §5.10 admits no head abstraction, so '" + application.name()
                        + "<...>' is no form. Take the applied type as the parameter instead");
            }
        }
    }

    /**
     * The placeholder a declaration that failed to resolve leaves behind, so the declarations that reference
     * it -- and the ones merely queued after it -- still resolve instead of collapsing into a cascade of
     * consequences of one original error. The counterpart of {@code TsonSchemaCompiler}'s {@link
     * io.ltr8.tson.compiler.reader.ErrorReader} one phase later.
     *
     * <p><b>Producing one means a diagnostic has already been reported</b> (Swift's {@code ErrorType}
     * obligation). It is not a resolution and must never be linked, registered or compiled -- guaranteed
     * structurally rather than by inspection, because the only overload that can produce one ({@link
     * #resolveSchema(SchemaDocument, SchemaPositions, TsonDiagnosticsReceiver)}) hands the caller a receiver whose report
     * count is the signal to stop at the phase boundary. It carries the failed declaration's own position so
     * anything that does surface it can still point at the source.
     *
     * <p><b>It keeps the failed declaration's own type parameters.</b> Answering "how many type parameters?"
     * with zero is answering wrongly, not absorbing: a downstream {@code bl<text>} is then told that
     * {@code bl} "declares no type parameters ... drop the argument list", which is advice that would break
     * the schema further, the actual fix being upstream at the declaration that failed. With the arity
     * intact the application closes against an empty body and says nothing.
     *
     * <p><b>An empty record, because the point is to absorb rather than to be recognised</b> -- javac's model,
     * where the error type answers every question, rather than Swift's, where every questioner must first ask
     * whether it is looking at one. A dependent that composes with a failed declaration ({@code parent =>
     * child & { ... }}) then resolves cleanly, contributing no fields, instead of failing a second time and
     * reporting a problem that is purely a consequence of the first. Getting this wrong is not a small
     * mismatch: a `Sum`-bodied placeholder makes every dependent report too, which is the cascade the
     * placeholder exists to prevent.
     */
    private static TypeDefinition unresolved(Optional<SourcePosition> position, List<String> parameters) {
        // An open placeholder holds its body like every other open entry, so nothing downstream has to keep
        // a second substitution path for the one shape that did not -- see WireForm.heldEmptyRecord.
        Top body = parameters.isEmpty() ? RecordBody.of(List.of())
                : new HeldBody(WireForm.heldEmptyRecord());
        return new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, parameters, false, List.of(), List.of(),
                Optional.empty(), body, position, Annotations.empty());
    }

    /** One already-resolved {@code DataValue} replayed through a compiled reader. */
    private static Object read(TsonTypeReader<?> reader,
                               io.ltr8.tson.compiler.ast.DataValue value) {
        // Unrestricted deliberately: these events come from a resolved schema value, not from document text.
        // A schema's own names are the identifier policy's surface, applied by TsonSchemaLinker.
        return reader.read(TsonReadContext.throwing(new ListEventSource(DataValueEvents.of(value)),
                TsonUnicodePolicy.unrestricted()));
    }

    /**
     * Stage 1 of {@link #resolveSchema(SchemaDocument)} -- every {@code !!import}'s whole namespace, in
     * declaration order, merged as-is (never re-resolved against the importer). Mirrors {@code
     * TsonSchemaLinker.mergeImports} exactly, including its transitivity and its identity-based collision
     * rule (§2.2.3), since this is the same concept applied one stage earlier: an
     * import contributes everything its namespace holds, one schema reached by several routes unifies, and
     * two different schemas declaring one name is the error.
     *
     * <p>Unlike the linker's, this merge has no {@code subtypes} to reconcile when a route repeats --
     * {@code subtypes} is populated at link time, one phase later, so both copies are still as their
     * declaring schema resolved them and the first is kept.
     */
    private Map<String, TypeDefinition> mergeImports(SchemaDocument document,
            Map<String, Annotations> importedNameAnnotations) {
        Map<String, TypeDefinition> merged = new LinkedHashMap<>();
        Map<String, String> origins = new LinkedHashMap<>();
        Set<String> alreadyImported = new LinkedHashSet<>();
        for (String importUri : document.imports()) {
            if (!alreadyImported.add(TsonCanonicalIdentity.canonicalize(importUri))) {
                continue;
            }
            TsonLinkedSchema imported = loader.resolveLinked(importUri);
            for (Map.Entry<String, TypeDefinition> entry : imported.schema().entries().entrySet()) {
                String name = entry.getKey();
                String origin = imported.originOf(name);
                String incumbent = origins.get(name);
                if (incumbent != null) {
                    if (!incumbent.equals(origin)) {
                        throw new TsonSchemaValidationException("'" + name + "' is declared by two different "
                                + "schemas reached through !!import ('" + incumbent + "' and '" + origin
                                + "') -- distinct types cannot share one name in the flat namespace; import "
                                + "one of them, or a version of each that agrees on where '" + name
                                + "' is declared");
                    }
                    continue;
                }
                merged.put(name, entry.getValue());
                // §8.3's walk carries a dropped hop's declaration annotations to the use site, and an alias
                // is as often imported as local -- so the key annotations travel with the entry rather than
                // being dropped at the import boundary, where the hop would lose them for a second reason.
                importedNameAnnotations.put(name, imported.schema().entries().getAnnotations(name));
                origins.put(name, origin);
            }
        }
        return merged;
    }
}
