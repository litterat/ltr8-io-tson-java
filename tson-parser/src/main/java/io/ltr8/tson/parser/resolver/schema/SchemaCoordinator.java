package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.resolver.schema.compiled.TsonCompiledSchema;

/**
 * Given a schema's own URI (a document's {@code !!meta} target, or one of its {@code !!import}
 * entries), returns its *compiled* form -- fetching, resolving, registering, and compiling it if
 * it doesn't already exist, rather than requiring every schema a resolver might need to already be
 * pre-registered by some other code path.
 *
 * <p><b>Why this exists, not just a raw {@code TsonCompiledRegistry} reference.</b> {@code
 * TsonSchemaResolver} used to hold a {@code TsonCompiledRegistry} directly and simply looked things up
 * in it, throwing if they weren't there yet -- fine as a first cut, but wrong once meta-kernel's own
 * bootstrap enters the picture: resolving meta-kernel's own document means resolving *its* {@code
 * !!meta}, which names itself. A plain "look it up, fail if missing" registry has no way to close
 * that loop -- it would need meta-kernel to already be registered before it can register meta-kernel.
 * A coordinator can recognize the request as the one genuinely circular case in the whole series
 * (Part 2 §1.5) and answer it directly, via {@link MetaKernelBootstrapResolver#parse()}'s own hand-written
 * bootstrap, instead of trying (and failing) to resolve it the ordinary way.
 *
 * <p>This is also the natural, single place to enforce policy over *what* gets resolved from
 * *where* -- the user's own framing: "we can control whitelists or blacklists for resolution... we
 * don't allow HTTP requests and just load from disk, or only HTTP requests to certain hosts." See
 * {@link TsonSchemaSource}, the pluggable hook {@link DefaultSchemaCoordinator} defers to for exactly
 * this, once a request isn't already cached and isn't the meta-kernel bootstrap case.
 */
public interface SchemaCoordinator {

    /**
     * @throws RuntimeException if {@code uri} can't be resolved -- not cached, not the meta-kernel
     *                          bootstrap case, and either not fetchable or invalid once fetched (a
     *                          malformed {@code !!id}, an unresolvable reference, ...). The specific
     *                          type depends on where resolution failed -- {@code
     *                          TsonSchemaValidationException}, an {@code IllegalStateException} from
     *                          {@link TsonSchemaSource#registeredOnly()}, or whatever a caller-supplied
     *                          {@link TsonSchemaSource} itself throws.
     */
    TsonCompiledSchema resolve(String uri);
}
