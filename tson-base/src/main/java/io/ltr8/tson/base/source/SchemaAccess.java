package io.ltr8.tson.base.source;

import io.ltr8.tson.base.policy.FetchPolicy;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Where this deployment may obtain a schema, and under what constraints -- one value collecting the
 * {@link SchemaSource} and the {@link FetchPolicy} that governs it.
 *
 * <p>[TSON-SCHEMA] §10 makes a schema URL a logical identifier resolved through a <em>schema library</em>,
 * with fetching "a permitted but opt-in way to populate the library". This is that opt-in, stated as a
 * value: the library itself is the registry, and this is how it comes to hold anything. Hence the name --
 * {@code SchemaLibrary} would claim to be the store rather than the access to it, and a {@code *Config}
 * suffix would name the mechanism where every other value here names the question it answers
 * ({@link FetchPolicy}, {@code ProcessorPolicy}, {@code SchemaReference}).
 *
 * <p><b>Why a value at all.</b> Two facts always travel together and are meaningless apart: a source with no
 * policy has unstated bounds, and a policy with no source governs nothing. Handing them separately to
 * everything that needs schemas means every such caller reassembles the pair, and one that reassembles it
 * wrongly is a deployment whose bounds silently do not apply. {@code Tson} is the caller today; the JSON
 * encoding's schema-directed decode is the next, and it takes this rather than growing its own copy of the
 * host list and the caps.
 *
 * <p><b>Deny by default</b>, like everything else here: {@link #registeredOnly()} is what an unconfigured
 * deployment gets, and it fetches nothing at all.
 *
 * <p><b>The three ways to name a source are mutually exclusive</b>, and the builder refuses a combination
 * rather than picking one. {@link Builder#httpSchemas} and {@link Builder#fileSchemas} each build one
 * source, and {@link Builder#source} holds one, so admitting two would mean silently dropping one; a
 * deployment that genuinely needs both writes the composition itself and passes the result to
 * {@code source}, where the order they are tried in is stated rather than assumed. A {@link
 * Builder#fetchPolicy} beside {@code source} is refused on the same principle -- a source built elsewhere
 * carries its own policy, set on its own builder, so one stated here could not reach it.
 */
public final class SchemaAccess {

    private final SchemaSource source;
    private final FetchPolicy fetchPolicy;

    private SchemaAccess(SchemaSource source, FetchPolicy fetchPolicy) {
        this.source = source;
        this.fetchPolicy = fetchPolicy;
    }

    /** Nothing is fetchable: only schemas already registered resolve. What an unconfigured deployment gets. */
    public static SchemaAccess registeredOnly() {
        return new SchemaAccess(SchemaSource.registeredOnly(), FetchPolicy.defaults());
    }

    /**
     * Access through {@code source}, which carries its own policy.
     *
     * <p>{@link #fetchPolicy()} reports {@link FetchPolicy#defaults()} here and is not applied to anything:
     * a source built elsewhere was configured at its own builder, and this has no way to reach inside it.
     * Use {@link #builder()} with {@link Builder#httpSchemas}/{@link Builder#fileSchemas} for a policy that
     * governs the source it is stated beside.
     */
    public static SchemaAccess of(SchemaSource source) {
        return new SchemaAccess(Objects.requireNonNull(source, "source"), FetchPolicy.defaults());
    }

    /**
     * Access to schemas identified by any of {@code hosts}, fetched over {@code https} from that same host,
     * under {@link FetchPolicy#defaults()} -- the one-call form of {@link Builder#httpSchemas}. Use
     * {@link #builder()} to state a policy, map a host elsewhere, or name more than one kind of source.
     */
    public static SchemaAccess httpSchemas(String... hosts) {
        return builder().httpSchemas(hosts).build();
    }

    /**
     * Access to schemas identified by {@code host}, served from {@code directory}, under
     * {@link FetchPolicy#defaults()} -- the one-call form of {@link Builder#fileSchemas}. Use
     * {@link #builder()} to state a policy or map more than one host.
     */
    public static SchemaAccess fileSchemas(String host, Path directory) {
        return builder().fileSchemas(host, directory).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Where a schema is obtained -- already governed by {@link #fetchPolicy()} where this built the source. */
    public SchemaSource source() {
        return source;
    }

    /** What obtaining one will admit and spend. */
    public FetchPolicy fetchPolicy() {
        return fetchPolicy;
    }

    @Override
    public String toString() {
        return source + " (" + fetchPolicy + ")";
    }

    /** Builds a {@link SchemaAccess}. Every default is the safe one; nothing is fetchable until a host is named. */
    public static final class Builder {

        private SchemaSource source;
        private HttpSchemaSource.Builder httpSchemas;
        private FileSchemaSource.Builder fileSchemas;
        private FetchPolicy fetchPolicy = FetchPolicy.defaults();
        private boolean fetchPolicyStated;

        private Builder() {
        }

        /**
         * Fetches schemas identified by any of {@code hosts} over {@code https} from that same host -- the
         * short form of {@link HttpSchemaSource}, which is where the policy is documented. Repeatable: each
         * call adds hosts.
         *
         * <p><b>Deny by default is the property worth knowing.</b> A host not named here is not fetched, and
         * a host is matched exactly -- naming {@code example.com} permits nothing on a subdomain. In a server
         * the reference comes out of a request body, so this list is a security boundary rather than a
         * convenience.
         *
         * <p>Reach for {@link HttpSchemaSource#builder()} and {@link #source} instead when you need a mirror
         * or a non-default port ({@code mapHost}), a different timeout, or your own
         * {@link java.net.http.HttpClient}. The caps and the {@code ?sha256=} pin requirement are
         * {@link #fetchPolicy}'s and reach a source built here.
         */
        public Builder httpSchemas(String... hosts) {
            rejectMixed("httpSchemas");
            if (httpSchemas == null) {
                httpSchemas = HttpSchemaSource.builder();
            }
            for (String host : hosts) {
                httpSchemas.allowHost(host);
            }
            return this;
        }

        /**
         * Serves schemas identified by {@code host} from {@code directory} -- the short form of
         * {@link FileSchemaSource}, which is where the policy is documented. Repeatable: each call maps
         * another host.
         *
         * <p>Nothing outside {@code directory} is ever read, symlinks included, and no host but the ones
         * named here is served at all. [TSON-DATA] §2.2.1 is what makes this legitimate rather than a hack:
         * an identity names a document independently of where it is stored, so
         * {@code https://schemas.example.com/order-1.tn} may perfectly well live in a directory.
         */
        public Builder fileSchemas(String host, Path directory) {
            rejectMixed("fileSchemas");
            if (fileSchemas == null) {
                fileSchemas = FileSchemaSource.builder();
            }
            fileSchemas.mapHost(host, directory);
            return this;
        }

        /**
         * A source of your own -- the general seam, and the one thing the short forms cannot express: a
         * composition of both, or an implementation this library does not ship.
         *
         * <p>It carries its own {@link FetchPolicy}, stated at its own builder, so {@link #fetchPolicy} may
         * not be named beside it.
         */
        public Builder source(SchemaSource source) {
            if (httpSchemas != null || fileSchemas != null) {
                throw new IllegalStateException("supply either source or httpSchemas/fileSchemas, not both "
                        + "-- the short forms build a source, so passing one as well would silently discard it");
            }
            if (fetchPolicyStated) {
                throw new IllegalStateException("supply either source or fetchPolicy, not both -- a source you "
                        + "build yourself carries its own policy, set on its own builder, so the one stated "
                        + "here could not reach it");
            }
            this.source = Objects.requireNonNull(source, "source");
            return this;
        }

        /**
         * What the source built here will admit and spend obtaining a schema. Defaults to
         * {@link FetchPolicy#defaults()}.
         *
         * <p>It governs a source this builder makes, which is why it cannot be named beside {@link #source}.
         */
        public Builder fetchPolicy(FetchPolicy fetchPolicy) {
            if (source != null) {
                throw new IllegalStateException("supply either source or fetchPolicy, not both -- a source you "
                        + "build yourself carries its own policy, set on its own builder, so the one stated "
                        + "here could not reach it");
            }
            this.fetchPolicy = Objects.requireNonNull(fetchPolicy, "fetchPolicy");
            this.fetchPolicyStated = true;
            return this;
        }

        public SchemaAccess build() {
            if (source != null) {
                return new SchemaAccess(source, FetchPolicy.defaults());
            }
            SchemaSource built = httpSchemas != null ? httpSchemas.fetchPolicy(fetchPolicy).build()
                    : fileSchemas != null ? fileSchemas.fetchPolicy(fetchPolicy).build()
                    : SchemaSource.registeredOnly();
            return new SchemaAccess(built, fetchPolicy);
        }

        /**
         * The two short forms build one source each, and {@link #source} holds one, so mixing them would mean
         * silently dropping one.
         */
        private void rejectMixed(String called) {
            if (source != null) {
                throw new IllegalStateException("supply either source or " + called + ", not both");
            }
            if ("httpSchemas".equals(called) ? fileSchemas != null : httpSchemas != null) {
                throw new IllegalStateException("supply either httpSchemas or fileSchemas, not both -- for a "
                        + "deployment needing each for different hosts, compose the two sources yourself and "
                        + "pass the result to source, so which one is tried first is stated rather than "
                        + "assumed");
            }
        }
    }
}
