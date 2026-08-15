package io.ltr8.tson.compiler;

/**
 * Where a {@link TsonCompiledSchemaLoader} gets a schema document's own raw source text from, for a
 * URI that isn't already registered/compiled and isn't meta-kernel's own pre-loaded bootstrap case --
 * the extension point for enforcing policy over what gets fetched from where (e.g. whitelisting/
 * blacklisting hosts, or disk-only resolution). A caller wanting a specific policy implements this
 * interface (e.g. checking {@code uri} against an allowed-host list before ever opening a
 * connection, or refusing any {@code http(s)} scheme outright and only reading from a local
 * classpath/filesystem location) and hands it to a {@code TsonCompiledMetaRegistry}'s own
 * {@code (TsonSchemaRegistry, DataBindContext, TsonSchemaSource)} constructor.
 *
 * <p><b>{@link #registeredOnly()} is the default -- nothing is ever fetched.</b> Mirrors {@code
 * TsonSchemaRegistry}'s own no-arg-constructor default ("resolves an import only if it's already
 * registered -- nothing is ever fetched") and {@code TsonSchemaLoader}'s own precedent for the same
 * reason: a real disk/HTTP-backed {@code TsonSchemaSource} is deliberately not built yet (a separate,
 * later task -- see this project's own task list) rather than guessing at a policy shape nobody
 * asked for.
 */
@FunctionalInterface
public interface TsonSchemaSource {

    /**
     * Returns {@code uri}'s own raw schema-document source text.
     *
     * @throws RuntimeException if {@code uri} can't be fetched -- not found, not permitted by
     *                          whatever policy this implementation enforces, or any other reason;
     *                          this interface doesn't mandate a specific exception type, since the
     *                          right one depends on the implementation's own failure modes (a
     *                          disk-backed source might throw for a missing file, an HTTP-backed one
     *                          for a disallowed host or a network error).
     */
    String fetch(String uri);

    /**
     * Never fetches anything -- every call throws naming {@code uri}.
     *
     * <p>A {@link io.ltr8.tson.schema.TsonSchemaValidationException} rather than an {@code
     * IllegalStateException}: a schema referencing an identity nothing can supply is a resolution failure
     * ([TSON-DATA] §8.1 puts "unresolved type names, schema resolution failures" in the resolver-error
     * category), not a broken invariant. The distinction is what lets a caller report a mistyped {@code
     * !!import} as a problem with the document instead of as a fault in this library -- the CLI's exit 1
     * against its exit 70.
     */
    static TsonSchemaSource registeredOnly() {
        return uri -> {
            throw new io.ltr8.tson.schema.TsonSchemaValidationException("'" + uri + "' is not registered, "
                    + "and this loader has no fetch capability configured to load it from anywhere");
        };
    }
}
