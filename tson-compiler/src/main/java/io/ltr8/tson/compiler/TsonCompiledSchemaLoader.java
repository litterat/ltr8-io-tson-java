package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.resolver.MetaKernelBootstrapResolver;
import io.ltr8.tson.schema.TsonLinkedSchema;

/**
 * On-demand resolution of a schema from its own URI -- a document's {@code !!import} or {@code !!meta}
 * target -- so a resolver reaching one needn't have it pre-registered. Two methods, split by what the
 * caller needs of the target:
 *
 * <ul>
 *   <li>{@link #resolveLinked} -- an {@code !!import} target (or a user schema being read): its
 *       <i>resolved</i> form is all that's needed (merged into the importer's type-name namespace, or
 *       compiled per-mode by a {@code TsonCompiledSchemaRegistry}). Never compiled by the loader itself.
 *   <li>{@link #loadMeta} -- a {@code !!meta} target: a governing meta-schema, which must be
 *       <i>compiled</i> (its {@code !enum}/{@code !integer} instances are read into {@code schema.meta}
 *       objects during the governed schema's own resolution).
 * </ul>
 *
 * <p>Not a plain registry lookup, because meta-kernel's own document names <i>itself</i> as its {@code
 * !!meta} (Part 2 §1.5's one deliberate circularity): a "look it up, fail if missing" registry would need
 * meta-kernel already registered before it could register meta-kernel. An implementation recognizes that
 * request and answers it directly, via {@link MetaKernelBootstrapResolver#getMetaKernelSchema()}'s own
 * hand-written bootstrap, instead of trying (and failing) to resolve it the ordinary way.
 *
 * <p>Also the natural, single place to enforce policy over <i>what</i> gets resolved from <i>where</i>
 * (whitelisting/blacklisting hosts, disk-only resolution) -- see {@link TsonSchemaSource}, the pluggable
 * hook the implementation ({@code TsonCompiledMetaRegistry}) defers to for exactly this, once a request
 * isn't already cached and isn't the meta-kernel bootstrap case.
 */
public interface TsonCompiledSchemaLoader {

    /**
     * Resolves {@code uri} to its linked form -- fetching/resolving/linking/registering it if it isn't
     * already, but never compiling it. For an {@code !!import} target (whose resolved entries the importer
     * merges) or a user schema a {@code TsonCompiledSchemaRegistry} then compiles in its own mode.
     *
     * @throws RuntimeException if {@code uri} can't be resolved -- not cached and either not fetchable or
     *                          invalid once fetched (a malformed {@code !!id}, an unresolvable reference, a
     *                          content-hash mismatch, ...); the specific type depends on where it failed.
     */
    TsonLinkedSchema resolveLinked(String uri);

    /**
     * Resolves {@code uri} to its compiled governing meta-schema -- for a document's own {@code !!meta}
     * target, which must be a meta-layer schema (its own {@code !!meta} is meta-kernel).
     *
     * @throws io.ltr8.tson.schema.TsonSchemaValidationException if {@code uri} resolves but isn't a
     *                               meta-layer schema, so cannot govern another -- an authoring error, like
     *                               every other verdict on a schema's own soundness (in addition to whatever
     *                               {@link #resolveLinked} may throw)
     */
    TsonCompiledMetaSchema loadMeta(String uri);
}
