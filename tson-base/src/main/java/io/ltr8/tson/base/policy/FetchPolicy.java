package io.ltr8.tson.base.policy;

/**
 * What this processor will admit and spend <b>obtaining a schema</b> -- the constraints every
 * {@code SchemaSource} applies, whatever it fetches from.
 *
 * <p>A schema reference comes out of a document, and in a server that means a request body
 * ([TSON-JSON] §10.4), so a source is a security boundary rather than a convenience. Three of its controls
 * are the same question whichever source answers it -- how large a document may be, how many may be held,
 * and whether a reference must carry a {@code ?sha256=} pin -- and this is that question asked once. The
 * defaults and the bounds checks live here rather than once per source, so two sources cannot disagree
 * about what a legal fetch policy is, and a deployment that moves a schema between them moves the
 * constraints with it.
 *
 * <p><b>What is deliberately not here.</b> Two things a source configures that this does not:
 *
 * <ul>
 *   <li><b>Where a schema is obtained from</b> -- {@code allowHost}/{@code mapHost}, the identity-to-location
 *       map. That is what <em>this</em> deployment can reach, not what any deployment will admit, and it is
 *       differently typed on each source (a base URI, a directory). [TSON-DATA] §2.2.1 keeps identity and
 *       location apart; so does this.</li>
 *   <li><b>A fetch timeout</b>, which stays {@code HttpSchemaSource}'s own. A directory has no timeout to
 *       state, and a component one implementation silently ignores is what makes a shared policy value
 *       untrustworthy -- a deployment reading it back could not tell which half applied.</li>
 * </ul>
 *
 * <p><b>And why it is {@link ProcessorPolicy}'s sibling rather than its component.</b> Both are constraints a
 * deployment states, which is why they share a package. But a {@code ProcessorPolicy} is threaded into every
 * reader and every stream, and none of them fetch anything: a fetch bound carried down the read path would
 * be a component every reader holds and no reader uses. They are stated in the same breath and consumed by
 * different subsystems, so they are two values.
 *
 * @param maxDocumentBytes      the largest schema document that will be read, enforced against bytes
 *                              actually delivered rather than against anything the origin declares
 * @param maxCachedSchemas      how many documents a source may hold. Zero disables caching; a source that is
 *                              full stops accepting entries rather than evicting, a schema set being small
 *                              and fixed in practice
 * @param requireContentHashPin whether a reference carrying no {@code ?sha256=} pin is refused. Off by
 *                              default, a self-describing document naming a plain URL being the ordinary
 *                              case; on, it is the strongest control available against a permitted origin
 *                              that is later compromised, since the loader then verifies every fetched
 *                              document against a hash the operator already published
 */
public record FetchPolicy(int maxDocumentBytes, int maxCachedSchemas, boolean requireContentHashPin) {

    /** Generous for a schema; small enough not to be a memory lever. */
    public static final int DEFAULT_MAX_DOCUMENT_BYTES = 1 << 20;

    /** How many schema documents a source may hold. */
    public static final int DEFAULT_MAX_CACHED_SCHEMAS = 128;

    public FetchPolicy {
        if (maxDocumentBytes <= 0) {
            throw new IllegalArgumentException("maxDocumentBytes must be positive");
        }
        if (maxCachedSchemas < 0) {
            throw new IllegalArgumentException("maxCachedSchemas must not be negative");
        }
    }

    /** What a source applies before a deployment says anything: the two caps, and no pin requirement. */
    public static FetchPolicy defaults() {
        return new FetchPolicy(DEFAULT_MAX_DOCUMENT_BYTES, DEFAULT_MAX_CACHED_SCHEMAS, false);
    }

    /**
     * This policy with one component replaced -- three methods rather than a builder, for
     * {@link ProcessorPolicy}'s reason: a policy is small, and naming the one component being changed at the
     * call site is what makes the change visible to whoever reads it next.
     */
    public FetchPolicy withMaxDocumentBytes(int maxDocumentBytes) {
        return new FetchPolicy(maxDocumentBytes, maxCachedSchemas, requireContentHashPin);
    }

    public FetchPolicy withMaxCachedSchemas(int maxCachedSchemas) {
        return new FetchPolicy(maxDocumentBytes, maxCachedSchemas, requireContentHashPin);
    }

    public FetchPolicy withRequireContentHashPin(boolean requireContentHashPin) {
        return new FetchPolicy(maxDocumentBytes, maxCachedSchemas, requireContentHashPin);
    }

    @Override
    public String toString() {
        return "at most " + maxDocumentBytes + " bytes per schema, at most " + maxCachedSchemas + " cached, "
                + (requireContentHashPin ? "content hash pin required" : "content hash pin optional");
    }
}
