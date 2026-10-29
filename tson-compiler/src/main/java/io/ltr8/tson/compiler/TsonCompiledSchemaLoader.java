package io.ltr8.tson.compiler;

import io.ltr8.tson.base.policy.IdentifierPolicy;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.resolver.MetaKernelBootstrapResolver;
import io.ltr8.tson.schema.TsonLinkedSchema;

/**
 * On-demand resolution of a schema from its own URI -- a document's {@code !!import} target -- so a resolver reaching
 * one needn't have it pre-registered. Two methods, split by what the caller needs of the target:
 *
 * <ul>
 *   <li>{@link #resolveLinked} -- an {@code !!import} target (or a user schema being read): its
 *       <i>resolved</i> form, merged into the importer's namespace, or compiled per-mode by a {@code
 *       TsonCompiledSchemaRegistry}.
 *   <li>{@link #loadMeta} -- the <i>compiled</i> vocabulary of the schema that declared a constructor or an
 *       annotation type the importer applies: its {@code !enum}/{@code !integer} instances are read into {@code
 *       schema.meta} objects during the importer's own resolution.
 * </ul>
 *
 * <p>Not a plain registry lookup, because meta-kernel is never resolved from a document: it is pre-loaded
 * ([TSON-SCHEMA] §3.4), and an implementation answers a request for it directly, via {@link
 * MetaKernelBootstrapResolver#getMetaKernelSchema()}'s bootstrap.
 *
 * <p>Also the natural, single place to enforce policy over <i>what</i> gets resolved from <i>where</i>
 * (whitelisting/blacklisting hosts, disk-only resolution) -- see {@link SchemaSource}, the pluggable
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
     * Resolves {@code uri} to its compiled vocabulary -- the readers a schema importing it reads a construction
     * head or an annotation value through, where {@code uri} declared the constructor or the annotation type. Any
     * schema: one namespace per schema ([TSON-SCHEMA] §3.3.1) puts every schema's constructors in reach of the
     * schemas importing it.
     *
     * @throws RuntimeException whatever {@link #resolveLinked} may throw
     */
    TsonCompiledMetaSchema loadMeta(String uri);

    /**
     * {@link #loadMeta} for a schema already registered, named by its canonical identity ({@code
     * CanonicalIdentity.canonicalize}) -- the origin an imported entry records, which is the schema whose compiled
     * readers read an instance of it.
     *
     * @throws IllegalStateException if no schema is registered under {@code identity}
     */
    TsonCompiledMetaSchema vocabularyOf(String identity);

    /**
     * [TSON-DATA] §8.2's identifier policy the schemas this loader resolves are judged under. The resolver reads
     * a constructor payload under it, so an identifier-typed value there -- a key of an identifier-keyed map in a
     * governed schema's namespace value -- is judged as a name, as it is in a data document.
     */
    IdentifierPolicy identifierPolicy();
}
