package io.ltr8.tson.parser.resolver;

import io.ltr8.tson.parser.compiler.TsonCompiledRegistry;

/**
 * Where a {@link TsonCompiledSchemaLoader} gets a schema document's own raw source text from, for a
 * URI that isn't already registered/compiled and isn't meta-kernel's own pre-loaded bootstrap case --
 * the extension point for enforcing policy over what gets fetched from where (e.g. whitelisting/
 * blacklisting hosts, or disk-only resolution). A caller wanting a specific policy implements this
 * interface (e.g. checking {@code uri} against an allowed-host list before ever opening a
 * connection, or refusing any {@code http(s)} scheme outright and only reading from a local
 * classpath/filesystem location) and hands it to {@link
 * DefaultTsonCompiledSchemaLoader#DefaultTsonCompiledSchemaLoader(TsonCompiledRegistry,
 * TsonSchemaSource)}.
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

    /** Never fetches anything -- every call throws {@link IllegalStateException} naming {@code uri}. */
    static TsonSchemaSource registeredOnly() {
        return uri -> {
            throw new IllegalStateException("'" + uri + "' is not registered, and this loader has no "
                    + "fetch capability configured to load it from anywhere");
        };
    }
}
