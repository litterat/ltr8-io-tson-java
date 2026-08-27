package io.ltr8.tson.compiler;

/**
 * Where a {@link TsonCompiledSchemaLoader} gets a schema document's own raw source text from, for a
 * URI that isn't already registered/compiled and isn't meta-kernel's own pre-loaded bootstrap case --
 * the extension point for enforcing policy over what gets fetched from where (e.g. whitelisting/
 * blacklisting hosts, or disk-only resolution). A caller wanting a specific policy implements this
 * interface (e.g. checking {@code uri} against an allowed-host list before ever opening a
 * connection, or refusing any {@code http(s)} scheme outright and only reading from a local
 * classpath/filesystem location) and hands it to a {@code TsonCompiledMetaRegistry}'s own
 * {@code (TsonSchemaRegistry, DataBindContext, TsonSchemaSource)} constructor. Two implementations
 * ship: {@code TsonHttpSchemaSource} and {@code TsonFileSchemaSource}, both in the {@code tson}
 * module, both denying by default.
 *
 * <p><b>{@link #registeredOnly()} is the default -- nothing is ever fetched.</b> Mirrors {@code
 * TsonSchemaRegistry}'s own no-arg-constructor default ("resolves an import only if it's already
 * registered -- nothing is ever fetched") and {@code TsonSchemaLoader}'s own precedent: a fetching
 * source is a policy decision, and a library that guesses one has made it for every deployment that
 * did not ask.
 *
 * <p><b>{@link TsonSchemaFetchException} is the contract.</b> A source signals "cannot supply this"
 * with that and nothing else, so a read catching a failure to obtain a schema can tell an unfetchable
 * schema from a broken invariant by type. Anything else a source throws is a fault in that source, and
 * is treated as one -- {@code SchemaFailure} rethrows it rather than reporting the document as invalid.
 */
@FunctionalInterface
public interface TsonSchemaSource {

    /**
     * Returns {@code uri}'s own raw schema-document source text.
     *
     * @throws TsonSchemaFetchException if {@code uri} can't be fetched -- not found, not permitted by
     *                                  whatever policy this implementation enforces, unreachable, or
     *                                  anything else that leaves this source without the document.
     *                                  {@code TsonSchemaFetchException.Reason} carries which, since
     *                                  a caller's mistake and an operator's want telling apart. This
     *                                  is the only exception the contract permits for that: throwing
     *                                  another type says a fault in this source, not a schema it
     *                                  cannot serve.
     */
    String fetch(String uri);

    /**
     * Never fetches anything -- every call throws naming {@code uri}.
     *
     * <p>{@link TsonSchemaFetchException.Reason#NOT_PERMITTED} rather than {@code NOT_FOUND}: nothing
     * was looked for. A loader with no fetch capability configured refuses every reference it does not
     * already hold, whether or not anything anywhere could have served it, and no retry changes that.
     */
    static TsonSchemaSource registeredOnly() {
        return uri -> {
            throw new TsonSchemaFetchException(uri, TsonSchemaFetchException.Reason.NOT_PERMITTED,
                    "it is not registered, and this loader has no fetch capability configured to load it "
                            + "from anywhere", null);
        };
    }
}
