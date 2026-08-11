package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.TsonCompiledSchemaLoader;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.compiler.stream.ListEventSource;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
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
     * canonical-identity candidate ({@link TsonSchemaRegistry#validateIdentity}) -- the same check
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
        String id = document.id().orElseThrow(() -> new IllegalStateException(
                "'" + document.meta() + "': !!id is required to register this schema, but is absent"));
        TsonSchemaRegistry.validateIdentity(id);
        for (String importUri : document.imports()) {
            TsonSchemaRegistry.validateIdentity(importUri);
        }

        TsonCompiledMetaSchema metaParser = loader.loadMeta(document.meta());
        Map<String, TypeDefinition> namespace = mergeImports(document);

        // Expand the sugar forms before anything reads a declaration, so resolution below only ever sees a
        // bare reference or `!C value` (§5.3/§5.6 define these as desugarings; §3.3.1 names their targets).
        // Sits after `metaParser` because the expansion needs the governing meta's own constructor
        // vocabulary, and returns `document` itself when there is no sugar to expand.
        SchemaDocument desugared = SchemaDesugarer.desugar(document);
        Map<String, SchemaMap.Declaration> declarations = desugared.body().declarations();

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
            try {
                TypeDefinition resolved = holder[0].resolve(declaration);
                namespace.put(name, resolved);
                return resolved;
            } finally {
                resolving.remove(name);
            }
        };
        holder[0] = new DefinitionResolver(
                (type, value) -> (Top) metaParser.reader(type)
                        .read(TsonReadContext.throwing(new ListEventSource(DataValueEvents.of(value)))),
                metaParser.schema().entries()::get, namespaceGetter);

        for (String name : declarations.keySet()) {
            namespaceGetter.getTypeDefinition(name);
        }
        Map<String, TypeDefinition> localOnly = new LinkedHashMap<>();
        for (String name : declarations.keySet()) {
            localOnly.put(name, namespace.get(name));
        }
        return new TsonSchema(id, document.meta(), document.imports(), localOnly);
    }

    /** Stage 1 of {@link #resolveSchema(SchemaDocument)} -- every {@code !!import}'s own entries, in declaration order, merged as-is (never re-resolved against the importer). Mirrors {@code TsonSchemaLinker.mergeImports} exactly, including its collision rule, since this is the same concept discovered one stage earlier. */
    private Map<String, TypeDefinition> mergeImports(SchemaDocument document) {
        Map<String, TypeDefinition> merged = new LinkedHashMap<>();
        for (String importUri : document.imports()) {
            TsonSchema imported = loader.resolveLinked(importUri).schema();
            for (Map.Entry<String, TypeDefinition> entry : imported.entries().entrySet()) {
                if (merged.containsKey(entry.getKey())) {
                    throw new TsonSchemaValidationException(
                            "'" + entry.getKey() + "' is declared by more than one !!import");
                }
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }
}
