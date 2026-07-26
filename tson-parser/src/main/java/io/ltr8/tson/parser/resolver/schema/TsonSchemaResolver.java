package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.ast.schema.SchemaMap;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonCompiledSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves a whole {@link SchemaDocument} into a {@link TsonSchema} -- the document-level half of
 * what used to be one class (see "Split from {@code DefinitionResolver}" below): header-directive
 * validation ({@code !!id}/{@code !!import}), deriving the structure namespace from this resolver's
 * own {@link SchemaCoordinator}, merging {@code !!import} entries into the type-name namespace, and
 * handing each local declaration off to an internal {@link DefinitionResolver} for the actual,
 * per-declaration resolution work (Part 2 §4, §8's {@code type_definition} shape).
 *
 * <p><b>Split from {@code DefinitionResolver}</b> (2026-07-27, on the user's own explicit
 * direction: "remove the SchemaCoordinator out of TsonSchemaResolver all together... rename
 * TsonSchemaResolver to DefinitionResolver... create a new TsonSchemaResolver which requires a
 * SchemaCoordinator"). The old, single class's own {@link SchemaCoordinator} field was consulted by
 * exactly two methods, {@code compiledMetaSchema} and {@code mergeImports}, and both of those were
 * only ever reached from that class's own {@code resolveSchema(SchemaDocument)} -- a real, concrete
 * sign that "resolve one declaration" and "resolve a whole document, given a way to fetch its
 * governing meta-schema and imports" were two different jobs living in one file. This class holds
 * the {@link SchemaCoordinator} and performs the validation/import-merging; {@link
 * DefinitionResolver} (now internal, package-private, no {@code Tson} prefix) never references
 * {@code SchemaCoordinator} at all -- it does still have its own batch {@code
 * resolveSchema(SchemaDocument, TsonCompiledSchema)} convenience (looping every declaration, no
 * validation, no import-merging), since that one needs no coordinator either, only an
 * already-derived structure namespace; see its own Javadoc for why that keeps it off this class.
 *
 * <p><b>A {@link SchemaCoordinator} is required now, not optional</b> -- the old class's own no-arg
 * constructor (and the "coordinator may be {@code null}" branch every coordinator-touching method
 * used to carry) is gone. A document-level resolution that can't validate its own {@code !!id} or
 * reach its own {@code !!meta} isn't a degraded version of this job, it's a different one --
 * per-declaration resolution against a hand-built namespace, which is exactly what {@link
 * DefinitionResolver} is for directly (see its own Javadoc, and {@code DefinitionResolverTest}/
 * {@code PositionalFormTest}, which construct one directly for isolated-declaration cases that used
 * to route through this class's own coordinator-less constructor).
 */
public final class TsonSchemaResolver {

    private final SchemaCoordinator coordinator;
    private final DefinitionResolver definitionResolver = new DefinitionResolver();

    /**
     * @param coordinator consulted by {@link #resolveSchema(SchemaDocument)} to resolve a document's
     *                     own {@code !!meta}/{@code !!import} targets -- fetching, resolving,
     *                     registering, and compiling each as needed rather than requiring them to
     *                     already exist somewhere. Required -- see this class's own "A
     *                     SchemaCoordinator is required now" note.
     *
     *                     <p>Deliberately a {@link SchemaCoordinator}, not a bare {@code
     *                     TsonCompiledRegistry} reference (an earlier version of this constructor
     *                     took one directly) -- a plain "look it up, throw if missing" registry has
     *                     no way to bootstrap meta-kernel's own document: resolving it means
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
     * made because {@code DefinitionResolver#bindAtomInstance} genuinely needs the higher layer's
     * own compiled output, not just its resolved one, to bind a constructor-application/refinement
     * value against.
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
     * SchemaCoordinator} and its entries merged in, *before* any local declaration is resolved
     * (exactly the pre-seeding {@code MetaTn1Parser} used to do by hand for meta-kernel
     * specifically, now generalized). This is genuinely required, not cosmetic: unlike the structure
     * namespace (consulted only for constructor-application targets), an import's own entries feed
     * the *type-name* namespace -- the same {@code resolved} map {@link DefinitionResolver}'s own
     * composition/refinement/atom-refinement resolution looks a supertype/refinement-source straight
     * up in, with no fallback of any kind. meta.tn1's own {@code date_type => ~atom &
     * atom_specification & {...}}, composing with two meta-kernel entries it only has via its own
     * {@code !!import}, would fail to resolve at all without this. Collision handling mirrors {@code
     * TsonSchemaLinker.mergeImports}'s own established rule exactly, not a new one: a name declared by
     * more than one import, or by an import *and* a local declaration, is a {@link
     * TsonSchemaValidationException} -- checked as each import is merged in, and
     * again as each local declaration is about to be resolved, so a collision is caught at the
     * earliest point either side of it becomes known, before any further resolution work is spent.
     * <b>Merged entries keep their home namespace</b>, same as {@code TsonSchemaLinker}'s own note on
     * this: an imported entry is copied in exactly as its own schema resolved it, never re-resolved
     * or re-materialized against the importer. The result's own {@link TsonSchema#entries()} is
     * local-only, same as {@code MetaTn1Parser}'s own convention -- imported entries are visible
     * *during* resolution but are never part of what this method itself returns; a caller that wants
     * the merged whole gets it from {@code TsonSchemaRegistry.register}'s own eventual output instead,
     * same as always.
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
        Map<String, TypeDefinition> localOnly = new LinkedHashMap<>();
        for (SchemaMap.Declaration declaration : document.body().declarations().values()) {
            if (namespace.containsKey(declaration.name())) {
                throw new TsonSchemaValidationException("'" + declaration.name()
                        + "' collides with an entry of the same name brought in by !!import");
            }
            TypeDefinition resolved = definitionResolver.resolve(declaration, namespace, metaParser);
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
