package io.ltr8.tson;

import io.ltr8.tson.compiler.TsonSchemaFetchException;
import io.ltr8.tson.compiler.TsonSchemaSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link TsonSchemaSource} that reads a schema document from a directory on disk, under a host allow-list
 * and a hard cap on size. {@link TsonHttpSchemaSource} is its remote sibling, and the two share {@link
 * SchemaReference} for what a reference is allowed to be -- so an identity means the same thing whichever of
 * them serves it, which is what lets a deployment move a schema between them without renaming it.
 *
 * <h2>Identity is not location</h2>
 *
 * <p>[TSON-DATA] §2.2.1 makes a reference's identity its host plus path and says a consumer "MAY fetch by
 * whichever scheme its policy allows" -- so a schema named {@code https://schemas.example.com/order-1.tn} may
 * legitimately be served from a directory, and this class is that policy. {@link Builder#mapHost} is the whole
 * configuration: the identity's host selects a directory, and its path is resolved beneath it.
 *
 * <p>The loader still cross-checks the document's embedded {@code !!id} against the identity that was asked
 * for, so a file in the wrong place fails rather than being substituted silently.
 *
 * <h2>The reference is attacker-controlled</h2>
 *
 * <p>The same warning its remote sibling carries, against a different primitive. A data document names its own
 * schema, so in a server the string reaching {@link #fetch} came out of a request body -- and a source that
 * resolves whatever path it is handed is an arbitrary-file-read primitive rather than an SSRF one. Hence:
 *
 * <ul>
 *   <li><b>Deny by default.</b> No mapped host means nothing is read, and a host is compared exactly.</li>
 *   <li><b>The resolved file must be inside its directory</b>, checked after the path is made real --
 *       {@link Path#toRealPath} resolves {@code ..} and follows every symlink, so one check covers traversal
 *       and symlink escape together. Checking the unresolved path instead passes a symlink that points
 *       anywhere, which is the usual way this control is defeated.</li>
 *   <li><b>Only a regular file is read.</b> A directory, device or socket is refused rather than opened.</li>
 *   <li><b>Size is capped</b>, against bytes actually read.</li>
 *   <li><b>Policy is checked on every reference, including a cached one.</b></li>
 * </ul>
 *
 * <p>The traversal check is what {@code ..} costs, and it costs nothing legitimate: §2.2.1's identities are
 * absolute URIs whose paths do not contain {@code ..} in the first place.
 *
 * <h2>Caching and threading</h2>
 *
 * <p>Cached by canonical identity, bounded the same way, and permanently valid under [TSON-SCHEMA] §10's
 * immutability rule -- <b>which is the rule this relies on, not the filesystem</b>: a file edited in place
 * after it has been read is not seen, and under §10 editing it was already the mistake. There is no staleness
 * check, deliberately, since a source that re-stats every reference would make identity mean "whatever is
 * there now".
 *
 * <p>This class is thread-safe, with the same caveat its sibling carries: resolution itself is not, so a
 * schema arriving at runtime still needs external serialisation. {@link #preload} is the intended path.
 */
public final class TsonFileSchemaSource implements TsonSchemaSource {

    /** A schema document larger than this is refused. */
    public static final int DEFAULT_MAX_DOCUMENT_BYTES = 1 << 20;

    /** How many schema documents may be held. */
    public static final int DEFAULT_MAX_CACHED_SCHEMAS = 128;

    private final Map<String, Path> hosts;
    private final int maxDocumentBytes;
    private final int maxCachedSchemas;
    private final boolean requireContentHashPin;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    private TsonFileSchemaSource(Builder builder) {
        this.hosts = Map.copyOf(builder.hosts);
        this.maxDocumentBytes = builder.maxDocumentBytes;
        this.maxCachedSchemas = builder.maxCachedSchemas;
        this.requireContentHashPin = builder.requireContentHashPin;
    }

    /** A source that reads nothing until a host is mapped to a directory. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Reads {@code reference}'s schema document, or throws.
     *
     * @param reference the reference as written in a {@code !!schema}/{@code !!import}/{@code !!meta}
     *                  directive, scheme and {@code ?sha256=} pin included
     * @throws TsonSchemaFetchException if policy refuses it, or no readable file backs it
     */
    @Override
    public String fetch(String reference) {
        // Policy first and always -- a cache hit skips the disk, not the allow-list.
        Permitted permitted = policy(reference);
        String cached = cache.get(permitted.canonical());
        if (cached != null) {
            return cached;
        }
        String document = read(reference, locate(reference, permitted));
        if (cache.size() < maxCachedSchemas) {
            cache.put(permitted.canonical(), document);
        }
        return document;
    }

    /**
     * Reads each reference now, so request-time resolution finds it already cached. Call during startup, on
     * one thread.
     *
     * @throws TsonSchemaFetchException on the first one that cannot be read, so a misconfigured deployment
     *                                  fails at startup rather than on its first request
     */
    public void preload(String... references) {
        for (String reference : references) {
            fetch(reference);
        }
    }

    /**
     * Whether this identity's document is already held. Answers {@code false} for anything this source would
     * refuse -- a question about the cache is not a request to read.
     */
    public boolean isCached(String reference) {
        try {
            return cache.containsKey(policy(reference).canonical());
        } catch (RuntimeException notReadable) {
            return false;
        }
    }

    /** A permitted reference: what it names, and where under this source it would be looked for. */
    private record Permitted(String canonical, Path directory, String relative) {
    }

    /**
     * {@code reference} as a permitted identity, or a policy failure. <b>Touches no filesystem</b>, which is
     * what lets {@link #isCached} answer from the cache alone -- a cached document stays cached when the file
     * behind it is moved or removed, [TSON-SCHEMA] §10's immutability rule having already settled that its
     * content cannot change under the same identity.
     */
    private Permitted policy(String reference) {
        SchemaReference identity = SchemaReference.of(reference, requireContentHashPin);
        Path directory = hosts.get(identity.host());
        if (directory == null) {
            throw SchemaReference.notPermitted(reference, hosts.isEmpty()
                    ? "no host is mapped to a directory by this source"
                    : "host '" + identity.host() + "' is not one of " + hosts.keySet());
        }
        String relative = identity.path().startsWith("/") ? identity.path().substring(1) : identity.path();
        if (relative.isEmpty()) {
            throw SchemaReference.notPermitted(reference, "names no path under '" + directory + "'");
        }
        return new Permitted(identity.canonical(), directory, relative);
    }

    /**
     * The real file behind a permitted reference.
     *
     * <p><b>Containment is checked on the real path, and that ordering is the control.</b>
     * {@code directory.resolve(path)} alone yields something that still contains {@code ..} and still points
     * through any symlink on the way; {@link Path#toRealPath} settles both, and only then is "is this
     * inside?" a question worth asking. A missing file has no real path, which is how
     * {@link NoSuchFileException} separates "not here" from "not allowed" without ever revealing whether an
     * out-of-tree file exists.
     */
    private Path locate(String reference, Permitted permitted) {
        Path real;
        Path root;
        try {
            root = permitted.directory().toRealPath();
            real = root.resolve(permitted.relative()).toRealPath();
        } catch (NoSuchFileException e) {
            throw new TsonSchemaFetchException(reference, TsonSchemaFetchException.Reason.NOT_FOUND,
                    "no file backs it under '" + permitted.directory() + "'", e);
        } catch (IOException e) {
            throw new TsonSchemaFetchException(reference, TsonSchemaFetchException.Reason.TRANSPORT,
                    "could not be resolved under '" + permitted.directory() + "': " + e, e);
        }
        if (!real.startsWith(root)) {
            throw SchemaReference.notPermitted(reference, "resolves to '" + real + "', which is outside '"
                    + root + "' -- a schema path may not escape the directory its host is mapped to");
        }
        if (!Files.isRegularFile(real)) {
            throw SchemaReference.notPermitted(reference, "resolves to '" + real + "', which is not a regular file");
        }
        return real;
    }

    /** Reads {@code file}, enforcing the size cap against bytes read, and decodes it as UTF-8. */
    private String read(String reference, Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(maxDocumentBytes + 1);
            if (bytes.length > maxDocumentBytes) {
                throw new TsonSchemaFetchException(reference, TsonSchemaFetchException.Reason.TOO_LARGE,
                        "a schema document may be at most " + maxDocumentBytes + " bytes", null);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            // Between the containment check and the open -- rare, and still "not here" rather than a fault.
            throw new TsonSchemaFetchException(reference, TsonSchemaFetchException.Reason.NOT_FOUND,
                    "no file backs it at '" + file + "'", e);
        } catch (IOException e) {
            throw new TsonSchemaFetchException(reference, TsonSchemaFetchException.Reason.TRANSPORT,
                    "could not be read from '" + file + "': " + e, e);
        }
    }

    /** Builds a {@link TsonFileSchemaSource}. Every default is the safe one; nothing is read until a host is mapped. */
    public static final class Builder {

        private final Map<String, Path> hosts = new LinkedHashMap<>();
        private int maxDocumentBytes = DEFAULT_MAX_DOCUMENT_BYTES;
        private int maxCachedSchemas = DEFAULT_MAX_CACHED_SCHEMAS;
        private boolean requireContentHashPin;

        private Builder() {
        }

        /**
         * Serves schemas identified by {@code host} from {@code directory}, the identity's path resolving
         * beneath it. The host is matched exactly, and nothing outside {@code directory} is ever read.
         *
         * <p>There is no {@code allowHost} counterpart, unlike the HTTP source: a host name says where to
         * fetch from over HTTPS, and says nothing at all about where a file lives.
         *
         * @throws IllegalArgumentException if {@code host} is not a bare host name, or {@code directory} is
         *                                  not an existing directory -- a mapping that cannot be satisfied is
         *                                  a startup mistake, and saying so at build time beats a
         *                                  {@code NOT_FOUND} per reference later
         */
        public Builder mapHost(String host, Path directory) {
            if (host == null || host.isBlank() || host.indexOf('/') >= 0) {
                throw new IllegalArgumentException("'" + host + "' is not a bare host name");
            }
            if (directory == null || !Files.isDirectory(directory)) {
                throw new IllegalArgumentException("'" + directory + "' is not an existing directory");
            }
            hosts.put(host.toLowerCase(Locale.ROOT), directory);
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

        /** How many documents may be cached. Defaults to {@link #DEFAULT_MAX_CACHED_SCHEMAS}. */
        public Builder maxCachedSchemas(int maxCachedSchemas) {
            if (maxCachedSchemas < 0) {
                throw new IllegalArgumentException("maxCachedSchemas must not be negative");
            }
            this.maxCachedSchemas = maxCachedSchemas;
            return this;
        }

        /**
         * Refuses any reference carrying no {@code ?sha256=} content-hash pin. Off by default. It buys less
         * here than it does over HTTP -- a local directory is not a compromised origin -- but it is the same
         * control, and a deployment that pins everywhere should be able to say so uniformly.
         */
        public Builder requireContentHashPin(boolean requireContentHashPin) {
            this.requireContentHashPin = requireContentHashPin;
            return this;
        }

        public TsonFileSchemaSource build() {
            return new TsonFileSchemaSource(this);
        }
    }
}
