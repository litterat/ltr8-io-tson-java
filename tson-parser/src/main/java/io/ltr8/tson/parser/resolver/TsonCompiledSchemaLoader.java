package io.ltr8.tson.parser.resolver;

import io.ltr8.tson.parser.compiler.TsonCompiledMetaSchema;

/**
 * Given a schema's own URI (a document's {@code !!meta} target, or one of its {@code !!import}
 * entries), returns its *compiled* form -- fetching, resolving, registering, and compiling it if
 * it doesn't already exist, rather than requiring every schema a resolver might need to already be
 * pre-registered by some other code path.
 *
 * <p>Not just a raw {@code TsonCompiledRegistry} lookup, because a plain "look it up, fail if
 * missing" registry has no way to bootstrap meta-kernel's own document: resolving it means
 * resolving *its own* {@code !!meta}, which names itself, so a registry-only lookup would need
 * meta-kernel already registered before it could ever register meta-kernel. An implementation can
 * recognize that request as the one genuinely circular case in the whole series (Part 2 §1.5) and
 * answer it directly, via {@link MetaKernelBootstrapResolver#getMetaKernelSchema()}'s own
 * hand-written bootstrap, instead of trying (and failing) to resolve it the ordinary way.
 *
 * <p>Also the natural, single place to enforce policy over *what* gets resolved from *where* (e.g.
 * whitelisting/blacklisting hosts, or disk-only resolution) -- see {@link TsonSchemaSource}, the
 * pluggable hook {@link DefaultTsonCompiledSchemaLoader} defers to for exactly this, once a request
 * isn't already cached and isn't the meta-kernel bootstrap case.
 */
public interface TsonCompiledSchemaLoader {

    /**
     * @throws RuntimeException if {@code uri} can't be resolved -- not cached, not the meta-kernel
     *                          bootstrap case, and either not fetchable or invalid once fetched (a
     *                          malformed {@code !!id}, an unresolvable reference, ...). The specific
     *                          type depends on where resolution failed -- {@code
     *                          TsonSchemaValidationException}, an {@code IllegalStateException} from
     *                          {@link TsonSchemaSource#registeredOnly()}, or whatever a caller-supplied
     *                          {@link TsonSchemaSource} itself throws.
     */
    TsonCompiledMetaSchema load(String uri);
}
