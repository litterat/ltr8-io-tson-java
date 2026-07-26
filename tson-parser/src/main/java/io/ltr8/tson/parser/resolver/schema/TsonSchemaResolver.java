package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.ast.schema.SchemaMap;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonCompiledSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves a whole {@link SchemaDocument} into a {@link TsonSchema}: header-directive validation
 * ({@code !!id}/{@code !!import}), deriving the structure namespace from this resolver's own {@link
 * SchemaCoordinator}, merging {@code !!import} entries into the type-name namespace, and handing each
 * local declaration off to a {@link DefinitionResolver} for the actual, per-declaration resolution
 * work (Part 2 §4, §8's {@code type_definition} shape).
 *
 * <p>A {@link SchemaCoordinator} is required: a document-level resolution that can't validate its own
 * {@code !!id} or reach its own {@code !!meta} isn't a degraded version of this job, it's a different
 * one -- per-declaration resolution against a hand-built namespace, which is exactly what {@link
 * DefinitionResolver} is for directly.
 *
 * <p>{@link #resolveSchema(SchemaDocument)} builds a fresh {@link DefinitionResolver} per call, not a
 * reused field, since a single instance of this class can resolve documents governed by *different*
 * meta-schemas across separate calls -- both the compiled reader and the structure namespace have to
 * be bound to that call's own {@code metaParser}. The type-name namespace ({@code namespace}, this
 * method's own local, {@code !!import}-seeded map) is built before the {@link DefinitionResolver} it
 * feeds and passed as {@code namespace::get} -- a plain method reference onto the map, not a copy, so
 * the resolver sees each newly-added entry on the very next loop iteration.
 */
public final class TsonSchemaResolver {

    private final SchemaCoordinator coordinator;

    /**
     * @param coordinator consulted by {@link #resolveSchema(SchemaDocument)} to resolve a document's
     *                     own {@code !!meta}/{@code !!import} targets -- fetching, resolving,
     *                     registering, and compiling each as needed rather than requiring them to
     *                     already exist somewhere. Required.
     *
     *                     <p>Deliberately a {@link SchemaCoordinator}, not a bare {@code
     *                     TsonCompiledRegistry} -- a plain "look it up, throw if missing" registry
     *                     has no way to bootstrap meta-kernel's own document: resolving it means
     *                     resolving *its own* {@code !!meta}, which names itself, so a registry-only
     *                     resolver would need meta-kernel already registered before it could ever
     *                     register meta-kernel. {@link SchemaCoordinator}'s own default
     *                     implementation recognizes that one case and answers it directly (see
     *                     {@link DefaultSchemaCoordinator}'s own Javadoc) instead of looping forever.
     */
    public TsonSchemaResolver(SchemaCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    /**
     * The compiled form of {@code document}'s own governing meta-schema -- its {@code !!meta}
     * target, resolved via this resolver's own {@link SchemaCoordinator}, fetched/bootstrapped/
     * compiled on demand if it wasn't already available, not merely a registry lookup.
     *
     * <p>Throws, rather than returning empty, if it can't be resolved -- a {@link SchemaCoordinator}
     * is *supposed* to make its target available (fetching/bootstrapping as needed); if it still
     * can't, that is a real, nameable failure (see {@link SchemaCoordinator#resolve}'s own Javadoc
     * for the possible causes), not a "maybe try again later."
     *
     * <p>A same-module, cross-package reach from {@code resolver.schema} up into {@code
     * resolver.schema.compiled} -- worth naming plainly, since every other layering note in this
     * codebase describes the *opposite* direction ({@code compiled} sitting "on top of" {@code
     * DefinitionResolver}'s own resolution, per {@code TsonCompiledSchema}'s own Javadoc). Not a
     * cycle (nothing in {@code resolver.schema.compiled}'s own main code imports back from {@code
     * resolver.schema}), and both packages live in the same module regardless, but a real,
     * deliberate exception to that "compiled depends on schema, never the other way" framing --
     * made because {@link #resolveSchema(SchemaDocument)} genuinely needs the higher layer's own
     * compiled output, not just its resolved one, to build the {@link DefinitionMetaReader} {@code
     * DefinitionResolver#bindAtomInstance} binds a constructor-application/refinement value against
     * (that method itself no longer touches {@code resolver.schema.compiled} at all -- see {@link
     * DefinitionResolver}'s own Javadoc).
     */
    TsonCompiledSchema compiledMetaSchema(SchemaDocument document) {
        return coordinator.resolve(document.meta());
    }

    /**
     * Resolves every declaration in {@code document}'s body, one entry at a time, in source
     * order, each seeing every entry resolved before it -- and carries {@code document}'s own
     * header directives (§2.2: {@code !!id}?/{@code !!meta}/{@code !!import}*) straight into the
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
     * via {@link #compiledMetaSchema} -- fetched/bootstrapped/compiled by this resolver's own {@link
     * SchemaCoordinator} if it wasn't already available, rather than requiring it to pre-exist --
     * and its resolved entries become the structure namespace every declaration resolves against.
     *
     * <p><b>{@code !!import} is merged into the type-name namespace the same way</b> -- each import's
     * own URI is validated the same way {@code !!id} is, then resolved via the same {@link
     * SchemaCoordinator} and its entries merged in, *before* any local declaration is resolved. This
     * is genuinely required, not cosmetic: unlike the structure namespace (consulted only for
     * constructor-application targets), an import's own entries feed the *type-name* namespace --
     * the same {@code namespace} map (exposed to {@link DefinitionResolver} as {@code
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

        TsonCompiledSchema metaParser = compiledMetaSchema(document);
        Map<String, TypeDefinition> namespace = mergeImports(document);
        DefinitionResolver definitionResolver = new DefinitionResolver(
                (type, value) -> (Top) metaParser.get(type).read(value), metaParser.schema().entries()::get, namespace::get);

        Map<String, TypeDefinition> localOnly = new LinkedHashMap<>();
        for (SchemaMap.Declaration declaration : document.body().declarations().values()) {
            if (namespace.containsKey(declaration.name())) {
                throw new TsonSchemaValidationException("'" + declaration.name()
                        + "' collides with an entry of the same name brought in by !!import");
            }
            TypeDefinition resolved = definitionResolver.resolve(declaration);
            namespace.put(declaration.name(), resolved);
            localOnly.put(declaration.name(), resolved);
        }
        return new TsonSchema(id, document.meta(), document.imports(), localOnly);
    }

    /** Stage 1 of {@link #resolveSchema(SchemaDocument)} -- every {@code !!import}'s own entries, in declaration order, merged as-is (never re-resolved against the importer). Mirrors {@code TsonSchemaLinker.mergeImports} exactly, including its collision rule, since this is the same concept discovered one stage earlier. */
    private Map<String, TypeDefinition> mergeImports(SchemaDocument document) {
        Map<String, TypeDefinition> merged = new LinkedHashMap<>();
        for (String importUri : document.imports()) {
            TsonSchema imported = coordinator.resolve(importUri).schema();
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
