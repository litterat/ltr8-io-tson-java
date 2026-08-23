package io.ltr8.tson.compiler.resolver;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Annotations;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonCompiledSchemaLoader;
import io.ltr8.tson.compiler.TsonDiagnosticsReceiver;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
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
 * feeds and passed as {@code namespace::get} -- a plain method reference onto the map, not a copy, so
 * the resolver sees each newly-added entry on the very next loop iteration.
 */
public final class SchemaResolver {

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
     * namespace -- the same {@code namespace} map (exposed to {@link DefinitionResolver} as {@code
     * namespace::get}) its own composition/refinement/atom-refinement resolution looks a
     * supertype/refinement-source straight up in, with no fallback of any kind. meta.tn1's own {@code
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
        return resolveSchema(document, Map.of());
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
                                    Map<SchemaMap.Declaration, ? extends SourcePosition> declarationPositions) {
        return resolve(document, declarationPositions, null);
    }

    /**
     * {@link #resolveSchema(SchemaDocument, Map)} reporting each declaration that fails to resolve through
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
                                    Map<SchemaMap.Declaration, ? extends SourcePosition> declarationPositions,
                                    TsonDiagnosticsReceiver receiver) {
        Objects.requireNonNull(receiver, "receiver");
        return resolve(document, declarationPositions, receiver);
    }

    /** The shared body; {@code receiver} is {@code null} for the fail-fast overloads, which rethrow instead. */
    private TsonSchema resolve(SchemaDocument document,
                               Map<SchemaMap.Declaration, ? extends SourcePosition> declarationPositions,
                               TsonDiagnosticsReceiver receiver) {
        String id = document.id().orElseThrow(() -> new IllegalStateException(
                "'" + document.meta() + "': !!id is required to register this schema, but is absent"));
        TsonCanonicalIdentity.validate(id);
        for (String importUri : document.imports()) {
            TsonCanonicalIdentity.validate(importUri);
        }

        TsonCompiledMetaSchema metaParser = loader.loadMeta(document.meta());
        Map<String, TypeDefinition> namespace = mergeImports(document);

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
        Map<SchemaMap.Declaration, SourcePosition> positions = new IdentityHashMap<>(declarationPositions);
        SchemaDocument desugared = SchemaDesugarer.desugar(document,
                namespace.keySet(), receiver == null ? null : (declaration, error) ->
                        receiver.report(Diagnostic.ofSchemaError(TsonCanonicalIdentity.canonicalize(id),
                                declaration.name(), error.getMessage(),
                                Optional.ofNullable(positions.get(declaration)))), positions);
        Map<String, SchemaMap.Declaration> declarations = desugared.body().declarations();
        // The names desugaring generated, as opposed to the ones the author wrote -- the difference between
        // the two documents, and nothing subtler. An application of a generated name is machinery closing
        // its own intermediate form; an application of an authored one is a use site worth recording.
        Set<String> generated = new LinkedHashSet<>(declarations.keySet());
        generated.removeAll(document.body().declarations().keySet());

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
        // dependency -- and the only recursion that cannot resolve. `resolving` catches such a cycle;
        // ordinary recursion through field references (a linked list, or `x => { y: y }` / `y => { x: x }`)
        // never enters it and resolves fine. `holder` breaks the construction cycle between the resolver and
        // the on-demand getter it needs.
        DefinitionResolver[] holder = new DefinitionResolver[1];
        Set<String> resolving = new LinkedHashSet<>();
        DefinitionGetter namespaceGetter = name -> {
            TypeDefinition already = namespace.get(name);
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
            Optional<SourcePosition> position = Optional.ofNullable(positions.get(declaration));
            try {
                TypeDefinition resolved = holder[0].resolve(declaration, position);
                namespace.put(name, resolved);
                return resolved;
            } catch (TsonSchemaValidationException e) {
                if (receiver == null) {
                    throw e;
                }
                // Report and carry on, rather than abandoning the other declarations. Catching *here*, inside
                // the memoized getter, rather than around the driving loop below, is what makes that correct:
                // resolution follows dependencies, not source order, so a failure often happens inside a
                // nested resolve -- the loop would attribute it to whichever declaration triggered it, then
                // reach the real one and report it a second time. The memo makes it exactly once, against
                // itself. Same shape as TsonSchemaCompiler.Compilation.resolve substituting an ErrorReader.
                receiver.report(Diagnostic.ofSchemaError(TsonCanonicalIdentity.canonicalize(id), name,
                        e.getMessage(), position));
                namespace.put(name, unresolved(position, SchemaDesugarer.typeParams(declaration.typeDef())));
                return namespace.get(name);
            } finally {
                resolving.remove(name);
            }
        };
        // One materialiser for the whole schema, created before the driving loop because resolution itself
        // closes applications on demand: a supertype or refinement source has to absorb the *closed* entry's
        // fields, and cannot wait for the batch pass below. Sharing the instance is what makes an on-demand
        // closing and a later batch closing of the same application land on one entry.
        TemplateMaterialiser materialiser = new TemplateMaterialiser(namespaceGetter, namespace::put,
                (type, value) -> (Top) read(metaParser.reader(type), value), generated);

        // The same compiled reader serves both hooks; they differ in what the caller does with the result,
        // which is why they are separate types rather than one Object-returning one.
        holder[0] = new DefinitionResolver(
                (type, value) -> (Top) read(metaParser.reader(type), value),
                // An annotation names an ordinary entry, not a constructor (§6), so this goes through the
                // compiled schema's own reader for that name rather than the constructor vocabulary.
                (type, value) -> read(metaParser.get(type), value),
                metaParser.schema().entries()::get, namespaceGetter, materialiser::closeApplication);

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
        Set<String> irregular = TemplateRegularity.check(resolvedLocals, receiver == null ? null
                : (name, error) -> receiver.report(Diagnostic.ofSchemaError(
                        TsonCanonicalIdentity.canonicalize(id), name, error.getMessage(),
                        Optional.ofNullable(positions.get(declarations.get(name))))));

        // A condemned template is replaced before materialisation, on the same terms as a declaration that
        // failed to resolve: the verdict is in, and closing an application of one only reports the same
        // defect a second time -- from the depth backstop, 64 instantiations deep, against whichever entry
        // happened to apply it rather than the one that wrote it. Both maps, because the two are read by
        // different halves: `materialise` walks `resolvedLocals`, while the application's head is looked up
        // through the getter over `namespace`.
        for (String name : irregular) {
            TypeDefinition condemned = resolvedLocals.get(name);
            TypeDefinition placeholder = unresolved(condemned.position(), condemned.parameters());
            resolvedLocals.put(name, placeholder);
            namespace.put(name, placeholder);
        }
        Map<String, TypeDefinition> instantiations = materialiser.materialise(resolvedLocals,
                receiver == null ? null : (name, error) -> receiver.report(Diagnostic.ofSchemaError(
                        TsonCanonicalIdentity.canonicalize(id), name, error.getMessage(),
                        Optional.ofNullable(positions.get(declarations.get(name))))));
        namespace.putAll(resolvedLocals);
        namespace.putAll(instantiations);

        // §6: an annotation written before the declared name binds to the *name*, not to the definition,
        // and "the resolver does not hoist annotations from key to value". A resolved schema is a
        // {type_name => type_definition}, so the name is this map's key -- which is where they are kept.
        // The two sets stay separate: a declaration's own annotations are on its TypeDefinition.
        // Binding a name's annotations can fail the same way a definition's can (an annotation type §3.3.3
        // cannot reach), and this loop runs outside the memoized getter that catches those -- so it catches
        // its own, once per name, leaving the entry itself intact and unannotated rather than losing the
        // whole schema to a bad @doc.
        AnnotatedMap<String, TypeDefinition> localOnly = new AnnotatedMap<>();
        for (String name : declarations.keySet()) {
            Annotations nameAnnotations;
            try {
                nameAnnotations = holder[0].annotationsFor(name, declarations.get(name).nameAnnotations());
            } catch (TsonSchemaValidationException e) {
                if (receiver == null) {
                    throw e;
                }
                receiver.report(Diagnostic.ofSchemaError(TsonCanonicalIdentity.canonicalize(id), name,
                        e.getMessage(), Optional.ofNullable(positions.get(declarations.get(name)))));
                nameAnnotations = Annotations.empty();
            }
            localOnly.put(name, resolvedLocals.get(name), nameAnnotations);
        }
        // An instantiation entry has no declared name to carry annotations from.
        instantiations.forEach(localOnly::put);
        return new TsonSchema(id, document.meta(), document.imports(), localOnly, false);
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
     * #resolveSchema(SchemaDocument, Map, TsonDiagnosticsReceiver)}) hands the caller a receiver whose report
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
        return new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, parameters, false, List.of(), List.of(),
                Optional.empty(), RecordBody.of(List.of()), position, Annotations.empty());
    }

    /** One already-resolved {@code DataValue} replayed through a compiled reader. */
    private static Object read(TsonTypeReader<?> reader,
                               io.ltr8.tson.compiler.ast.DataValue value) {
        return reader.read(TsonReadContext.throwing(new ListEventSource(DataValueEvents.of(value))));
    }

    /**
     * Stage 1 of {@link #resolveSchema(SchemaDocument)} -- every {@code !!import}'s whole namespace, in
     * declaration order, merged as-is (never re-resolved against the importer). Mirrors {@code
     * TsonSchemaLinker.mergeImports} exactly, including its transitivity and its identity-based collision
     * rule ({@code SPEC-FEEDBACK.md} #55), since this is the same concept discovered one stage earlier: an
     * import contributes everything its namespace holds, one schema reached by several routes unifies, and
     * two different schemas declaring one name is the error.
     *
     * <p>Unlike the linker's, this merge has no {@code subtypes} to reconcile when a route repeats --
     * {@code subtypes} is populated at link time, one phase later, so both copies are still as their
     * declaring schema resolved them and the first is kept.
     */
    private Map<String, TypeDefinition> mergeImports(SchemaDocument document) {
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
                origins.put(name, origin);
            }
        }
        return merged;
    }
}
