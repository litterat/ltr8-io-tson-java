package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonSchemaValidationException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
 * <b>Returning {@code null} is not a way to say it either</b>, and is refused where the loader calls a
 * source, naming this rule: a {@code null} carries no {@link TsonSchemaFetchException.Reason}, so a
 * deployment that refuses a reference and a host that did not answer would arrive indistinguishable.
 *
 * <p><b>{@link #ofMap} exists because a map is the natural first source and spells a miss the wrong
 * way.</b> {@code schemaSource(schemas::get)} compiles, serves every identity in the map, and returns
 * {@code null} for the rest -- and since the identity comes from the document, and in a server that
 * means a request body, any caller can reach it. That form is a contract violation the type system
 * cannot catch, so the fix is to make the correct thing shorter than the wrong one.
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

    /**
     * Serves schema documents a caller already holds, keyed by identity -- what {@code schemas::get} means
     * and does not do.
     *
     * <p><b>It matches by canonical identity, not by the string a document happened to write.</b> Each key is
     * canonicalized once here and every lookup likewise ([TSON-DATA] §2.2.1: scheme and query stripped), so a
     * reference carrying a {@code ?sha256=} pin finds the entry registered without one, and {@code http://}
     * and {@code https://} spellings of one identity are one entry. A raw map lookup matches none of those,
     * which is the second half of the same trap -- it fails only for the documents that pin, which are the
     * ones a deployment that cares about integrity writes.
     *
     * <p><b>A miss is {@link TsonSchemaFetchException.Reason#NOT_FOUND}</b>, where {@link #registeredOnly}'s
     * is {@code NOT_PERMITTED}: this source has somewhere to look and looked, and the answer is that this
     * deployment does not publish that schema. Neither is retryable, but they are different sentences to put
     * in front of whoever sent the document.
     *
     * <p>The map is copied, so what this serves cannot change under a registry that has already read from it.
     *
     * @param schemas identity to schema-document source text; keys must be legal identities (§2.2.1), and two
     *                that canonicalize alike are refused rather than silently collapsed
     * @throws TsonSchemaValidationException if a key is not a legal canonical identity
     * @throws IllegalArgumentException      if two keys name one identity, or a document is {@code null}
     */
    static TsonSchemaSource ofMap(Map<String, String> schemas) {
        Map<String, String> byIdentity = new LinkedHashMap<>();
        schemas.forEach((reference, document) -> {
            Objects.requireNonNull(document, () -> "no schema document for '" + reference + "'");
            String identity = TsonCanonicalIdentity.canonicalize(reference);
            String existing = byIdentity.put(identity, document);
            if (existing != null && !existing.equals(document)) {
                throw new IllegalArgumentException("two schemas were supplied for the identity '" + identity
                        + "' -- a reference's scheme and ?sha256= pin are not part of its identity (§2.2.1), so "
                        + "keys differing only in those name one schema and cannot both be served");
            }
        });
        Map<String, String> served = Map.copyOf(byIdentity);
        return uri -> {
            String identity;
            try {
                identity = TsonCanonicalIdentity.canonicalize(uri);
            } catch (TsonSchemaValidationException e) {
                // Refused rather than reported as a miss: nothing was looked for, because there is no
                // identity to look for. Wrapped so this source still fails the one way the contract permits.
                throw new TsonSchemaFetchException(uri, TsonSchemaFetchException.Reason.NOT_PERMITTED,
                        "it is not a legal schema identity: " + e.getMessage(), e);
            }
            String document = served.get(identity);
            if (document == null) {
                throw new TsonSchemaFetchException(uri, TsonSchemaFetchException.Reason.NOT_FOUND,
                        "this deployment publishes no schema with that identity", null);
            }
            return document;
        };
    }
}
