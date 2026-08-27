package io.ltr8.tson;

import io.ltr8.tson.compiler.TsonSchemaSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link TsonSchemaSource} that fetches a schema document over HTTP, under a host allow-list and hard caps
 * on size and time. {@link TsonFileSchemaSource} is its local sibling, and the two share {@link
 * SchemaReference} for what a reference is allowed to be.
 *
 * <h2>Identity is not location</h2>
 *
 * <p>[TSON-DATA] §2.2.1 keeps the two apart ({@link SchemaReference}), and this class is built on that split.
 * The two questions are separate, and are configured separately:
 *
 * <ul>
 *   <li><b>Which schema names will this process load?</b> {@link Builder#allowHost} -- the security boundary.</li>
 *   <li><b>Where does it fetch them from?</b> {@code https://<host>} + path by default;
 *       {@link Builder#mapHost} points a host at another base for a mirror, an internal network, or a test,
 *       which is the only way to reach a non-default port, since an identity cannot carry one.</li>
 * </ul>
 *
 * <p>A mapped host does not rename anything: the loader still cross-checks that the fetched document's
 * embedded {@code !!id} is the identity that was asked for, so a mirror serving the wrong document fails
 * rather than substituting silently.
 *
 * <h2>The reference is attacker-controlled</h2>
 *
 * <p>A data document names its own schema (<code>!!schema:"https://…"</code>), so in a server the string
 * reaching {@link #fetch} came out of a request body. A source that fetches whatever it is handed is a
 * server-side request forgery primitive: it will read a cloud metadata endpoint, port-scan a private network,
 * or follow a redirect from a permitted host to one that isn't. Hence:
 *
 * <ul>
 *   <li><b>Deny by default.</b> No allowed host means nothing is fetched. A host is compared exactly -- there
 *       is no suffix or wildcard matching, because {@code .example.com} written as a suffix test also matches
 *       {@code evil-example.com}, which is how this control is usually defeated.</li>
 *   <li><b>The reference must be a legal identity</b> -- §2.2.1's own rule, enforced by {@link
 *       SchemaReference} before any connection is opened.</li>
 *   <li><b>Redirects are never followed.</b> A redirect is the allow-list's exit door: the check happened on
 *       the identity, and the second hop is a different URI. A 3xx is a fetch failure.</li>
 *   <li><b>Size and time are capped</b>, and size is enforced against bytes delivered, never against
 *       {@code Content-Length}, which the origin also controls.</li>
 *   <li><b>Policy is checked on every reference, including a cached one.</b> A hit skips the network, never
 *       the allow-list.</li>
 * </ul>
 *
 * <h2>Caching and threading</h2>
 *
 * <p>Cached by canonical identity, so a varied query string cannot force repeated outbound fetches, and
 * [TSON-SCHEMA] §10's immutability rule makes the entry permanently valid. The cache is bounded; once full it
 * stops accepting entries rather than evicting, since a schema set is small and fixed in practice.
 *
 * <p>This class is thread-safe. <b>That is not the same as it being safe to resolve a schema from a request
 * thread</b> -- resolution mutates the registry, which is not concurrent-safe, so a schema arriving at runtime
 * still needs external serialisation. {@link #preload} is the intended path: name the schemas at startup, on
 * one thread, and let request-time resolution find them already registered.
 */
public final class TsonHttpSchemaSource implements TsonSchemaSource, AutoCloseable {

    /** A schema document larger than this is refused. Generous for a schema; small enough not to be a memory lever. */
    public static final int DEFAULT_MAX_DOCUMENT_BYTES = 1 << 20;

    /** How long one fetch may take, end to end. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    /** How many schema documents may be held. */
    public static final int DEFAULT_MAX_CACHED_SCHEMAS = 128;

    /** [TSON-DATA] §7.1's media type, offered first and not insisted on -- a plain file server has no idea of it. */
    private static final String ACCEPT = "application/tson, */*;q=0.1";

    private final Map<String, URI> hosts;
    private final int maxDocumentBytes;
    private final Duration timeout;
    private final int maxCachedSchemas;
    private final boolean requireContentHashPin;
    private final HttpClient client;
    private final boolean ownsClient;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    private TsonHttpSchemaSource(Builder builder) {
        this.hosts = Map.copyOf(builder.hosts);
        this.maxDocumentBytes = builder.maxDocumentBytes;
        this.timeout = builder.timeout;
        this.maxCachedSchemas = builder.maxCachedSchemas;
        this.requireContentHashPin = builder.requireContentHashPin;
        this.ownsClient = builder.client == null;
        this.client = builder.client != null ? builder.client
                : HttpClient.newBuilder()
                        // The allow-list is checked on the identity; a redirect is a different URI, so
                        // following one would leave the allow-list behind. See the class note.
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(builder.timeout)
                        .build();
    }

    /** A source that fetches nothing until a host is allowed. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fetches {@code reference}'s schema document, or throws.
     *
     * @param reference the reference as written in a {@code !!schema}/{@code !!import}/{@code !!meta}
     *                  directive, scheme and {@code ?sha256=} pin included
     * @throws TsonSchemaFetchException if policy refuses it, or the host cannot supply it
     */
    @Override
    public String fetch(String reference) {
        // Policy first and always -- a cache hit skips the network, not the allow-list.
        Target target = permitted(reference);
        // Deliberately get-then-put rather than computeIfAbsent, which would hold a ConcurrentHashMap bin
        // lock for the whole of a network fetch -- blocking every other thread whose key lands in that bin,
        // and stalling a resize, for as long as the timeout allows. Two threads racing one identity fetch it
        // twice and store identical content, which costs a request and breaks nothing.
        //
        // (The loader is not re-entrant, so a recursive computeIfAbsent is not the hazard here: it fetches a
        // document, returns, and only then resolves and fetches its imports. Pinned by
        // TsonHttpSchemaSourceTest.fetchIsNeverReenteredByATransitiveImport, because a loader that became
        // re-entrant would make computeIfAbsent unsafe as well as slow.)
        String cached = cache.get(target.canonical());
        if (cached != null) {
            return cached;
        }
        String document = get(reference, target.location());
        if (cache.size() < maxCachedSchemas) {
            cache.put(target.canonical(), document);
        }
        return document;
    }

    /**
     * Fetches each reference now, so request-time resolution finds it already cached. Call during startup, on
     * one thread -- this is the path the threading note describes.
     *
     * @throws TsonSchemaFetchException on the first one that cannot be fetched, so a misconfigured deployment
     *                                  fails at startup rather than on its first request
     */
    public void preload(String... references) {
        for (String reference : references) {
            fetch(reference);
        }
    }

    /**
     * Whether this identity's document is already held, so resolving it will not touch the network. Answers
     * {@code false} for anything this source would refuse -- a question about the cache is not a request to
     * fetch.
     */
    public boolean isCached(String reference) {
        try {
            return cache.containsKey(permitted(reference).canonical());
        } catch (RuntimeException notFetchable) {
            return false;
        }
    }

    /** Releases the HTTP client, if this source created it. A caller-supplied client belongs to the caller. */
    @Override
    public void close() {
        if (ownsClient) {
            client.close();
        }
    }

    /** A permitted reference: what it names, and where this source will go to get it. */
    private record Target(String canonical, URI location) {
    }

    /** Where this source will go for {@code reference}, or a policy failure. */
    private Target permitted(String reference) {
        SchemaReference identity = SchemaReference.of(reference, requireContentHashPin);
        URI base = hosts.get(identity.host());
        if (base == null) {
            throw SchemaReference.notPermitted(reference, hosts.isEmpty()
                    ? "no host is allowed by this source"
                    : "host '" + identity.host() + "' is not one of " + hosts.keySet());
        }
        return new Target(identity.canonical(), base.resolve(identity.path()));
    }

    /** Opens {@code location}, enforcing the time and size caps, and decodes the body as UTF-8. */
    private String get(String reference, URI location) {
        HttpRequest request = HttpRequest.newBuilder(location)
                .GET()
                .timeout(timeout)
                .header("Accept", ACCEPT)
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                return switch (response.statusCode() / 100) {
                    case 2 -> new String(readCapped(reference, body), StandardCharsets.UTF_8);
                    // Not followed, by design -- see the class notes. Reported distinctly so the fix (point
                    // the reference, or the host mapping, at where the document actually is) is obvious.
                    case 3 -> throw transport(reference, "the host redirected (" + response.statusCode()
                            + "), and a redirect leaves the allow-list");
                    case 4 -> throw new TsonSchemaFetchException(reference,
                            TsonSchemaFetchException.Reason.NOT_FOUND,
                            "the host answered " + response.statusCode(), null);
                    default -> throw transport(reference, "the host answered " + response.statusCode());
                };
            }
        } catch (HttpTimeoutException e) {
            throw new TsonSchemaFetchException(reference, TsonSchemaFetchException.Reason.TIMEOUT,
                    "the host did not answer within " + timeout, e);
        } catch (IOException e) {
            throw transport(reference, "the host could not be reached: " + e, e);
        } catch (InterruptedException e) {
            // The flag belongs to whoever is unwinding this thread, not to this method.
            Thread.currentThread().interrupt();
            throw transport(reference, "interrupted while fetching", e);
        }
    }

    /**
     * Reads at most {@link #DEFAULT_MAX_DOCUMENT_BYTES}, or whatever the builder set. The cap is enforced
     * against the bytes actually delivered, never against {@code Content-Length} -- the host controls that too.
     */
    private byte[] readCapped(String reference, InputStream body) throws IOException {
        byte[] read = body.readNBytes(maxDocumentBytes + 1);
        if (read.length > maxDocumentBytes) {
            throw new TsonSchemaFetchException(reference, TsonSchemaFetchException.Reason.TOO_LARGE,
                    "a schema document may be at most " + maxDocumentBytes + " bytes", null);
        }
        return read;
    }

    private static TsonSchemaFetchException transport(String reference, String message) {
        return transport(reference, message, null);
    }

    private static TsonSchemaFetchException transport(String reference, String message, Throwable cause) {
        return new TsonSchemaFetchException(reference, TsonSchemaFetchException.Reason.TRANSPORT, message, cause);
    }

    /** Builds a {@link TsonHttpSchemaSource}. Every default is the safe one; nothing is fetched until a host is allowed. */
    public static final class Builder {

        private final Map<String, URI> hosts = new LinkedHashMap<>();
        private int maxDocumentBytes = DEFAULT_MAX_DOCUMENT_BYTES;
        private Duration timeout = DEFAULT_TIMEOUT;
        private int maxCachedSchemas = DEFAULT_MAX_CACHED_SCHEMAS;
        private boolean requireContentHashPin;
        private HttpClient client;

        private Builder() {
        }

        /**
         * Permits schemas identified by {@code host}, fetched over {@code https} from that same host. The host
         * is matched exactly: allowing {@code schemas.example.com} permits nothing on a subdomain and nothing
         * on a host that merely ends the same way.
         */
        public Builder allowHost(String host) {
            return mapHost(host, "https://" + host);
        }

        /**
         * Permits schemas identified by {@code host}, but fetches them from {@code base} instead -- a mirror,
         * an internal endpoint, or a test server. This is the only way to reach a non-default port, since
         * [TSON-DATA] §2.2.1 forbids one in an identifying URI.
         *
         * <p>It renames nothing: the identity stays {@code host} plus path, and the loader still cross-checks
         * the fetched document's embedded {@code !!id} against it, so a mirror serving the wrong document
         * fails rather than substituting silently.
         *
         * @param base an absolute {@code http}/{@code https} URI whose scheme, host and port are used; its
         *             path is a prefix the identity's path is resolved against
         */
        public Builder mapHost(String host, String base) {
            if (host == null || host.isBlank() || host.indexOf('/') >= 0) {
                throw new IllegalArgumentException("'" + host + "' is not a bare host name");
            }
            URI parsed;
            try {
                parsed = new URI(base);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("'" + base + "' is not a URI: " + e.getMessage(), e);
            }
            if (!parsed.isAbsolute() || parsed.getHost() == null) {
                throw new IllegalArgumentException("'" + base + "' is not an absolute URI with a host");
            }
            String scheme = parsed.getScheme().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) && !"http".equals(scheme)) {
                throw new IllegalArgumentException("'" + base + "' is not an http or https URI");
            }
            hosts.put(host.toLowerCase(Locale.ROOT), parsed);
            return this;
        }

        /** The largest schema document that will be read. Defaults to {@link #DEFAULT_MAX_DOCUMENT_BYTES}. */
        public Builder maxDocumentBytes(int maxDocumentBytes) {
            if (maxDocumentBytes <= 0) {
                throw new IllegalArgumentException("maxDocumentBytes must be positive");
            }
            this.maxDocumentBytes = maxDocumentBytes;
            return this;
        }

        /** How long one fetch may take. Defaults to {@link #DEFAULT_TIMEOUT}. */
        public Builder timeout(Duration timeout) {
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        /** How many documents may be cached. Defaults to {@link #DEFAULT_MAX_CACHED_SCHEMAS}. */
        public Builder maxCachedSchemas(int maxCachedSchemas) {
            if (maxCachedSchemas < 0) {
                throw new IllegalArgumentException("maxCachedSchemas must not be negative");
            }
            this.maxCachedSchemas = maxCachedSchemas;
            return this;
        }

        /**
         * Refuses any reference carrying no {@code ?sha256=} content-hash pin. Off by default, because a
         * self-describing document naming a plain URL is the ordinary case; on, it is the strongest control
         * available against a permitted host that is later compromised, since the loader then verifies every
         * fetched document against a hash the operator already published.
         */
        public Builder requireContentHashPin(boolean requireContentHashPin) {
            this.requireContentHashPin = requireContentHashPin;
            return this;
        }

        /**
         * Uses {@code client} instead of building one. The caller keeps ownership: {@link #close} will not
         * close it. A supplied client should not follow redirects -- see the class notes on why.
         */
        public Builder httpClient(HttpClient client) {
            this.client = client;
            return this;
        }

        public TsonHttpSchemaSource build() {
            return new TsonHttpSchemaSource(this);
        }
    }
}
