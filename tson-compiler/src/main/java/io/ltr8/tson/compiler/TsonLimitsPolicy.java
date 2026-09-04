package io.ltr8.tson.compiler;

/**
 * The resource limits this processor applies to a document it reads -- [TSON-DATA] §9.1's denial-of-service
 * bounds, as a value a caller can read <em>before</em> writing a document rather than only discover by having
 * one refused.
 *
 * <p><b>A limit refusal is not a verdict on the document.</b> The document may be well-formed, valid, and
 * accepted in full by the next processor along; what happened is that this deployment declined to spend the
 * resources. That is why the refusal carries {@link Diagnostic.Code#LIMIT_EXCEEDED}, whose {@link
 * Diagnostic.Code#verdict()} is {@code false}, and never one of the codes that assert something about the
 * document's conformance.
 *
 * <p><b>Why the limits are a value and not a constant.</b> The argument is {@link
 * TsonUnicodeProcessorPolicy}'s, and for the same reason: the bound is the reading deployment's own choice,
 * so the same bytes may be accepted by one server and refused by another, and that divergence is
 * unexplainable unless the configuration can be stated. A sender that can read the limits writes a document
 * that fits; one that cannot learns them one round trip too late. It is reported by the same surfaces --
 * {@code Tson.limitsPolicy()}, {@link TsonTreeReader#limitsPolicy()}, {@link
 * TsonObjectReader#limitsPolicy()}, and {@code tson policy} on the command line.
 *
 * <p><b>Only nesting depth is bounded so far.</b> §9.1 names four other limits (token length, document size,
 * numeric-literal length, decoded binary size) and omits the ones that bound <em>shape</em> rather than size
 * -- elements per container, fields per record, annotations per value, values per document, foreign schemas
 * loaded per document. {@code SPEC-FEEDBACK.md} #33 asks §9.1 for the whole set with defaults; this record is
 * where each lands as it is built, which is why it is a record with one component rather than an {@code int}
 * threaded through the readers.
 *
 * @param maxDepth the deepest a container may nest before the read is refused -- see {@link #maxDepth()}
 */
public record TsonLimitsPolicy(int maxDepth) {

    /**
     * The default nesting depth, 64.
     *
     * <p><b>Chosen as the lowest depth in common use rather than the most generous.</b> A default is only
     * worth having if a document written against it travels, and a document that fits the tightest common
     * limit fits every processor above it; the reverse choice makes "a conforming document" a property of
     * whoever received it. 64 is what {@code System.Text.Json} bounds at, against 128 for {@code serde_json}
     * and 1000 for Jackson, and it is what this library's one other depth guard ({@code
     * TemplateMaterialiser}'s template-closing depth) already picked independently.
     *
     * <p>It is far above what the documents this format is for actually reach -- structured output validated
     * against a schema nests in single digits -- so raising it is a deliberate act rather than the ordinary
     * case.
     */
    public static final int DEFAULT_MAX_DEPTH = 64;

    public TsonLimitsPolicy {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be at least 1, not " + maxDepth);
        }
    }

    /** The limits a processor applies when a caller has stated none. */
    public static TsonLimitsPolicy defaults() {
        return new TsonLimitsPolicy(DEFAULT_MAX_DEPTH);
    }

    /**
     * These limits with the nesting depth replaced.
     *
     * <p><b>There is no unlimited.</b> The bound this enforces is what keeps a deeply nested document from
     * exhausting the Java stack while a reader descends it, and a {@link StackOverflowError} is an {@link
     * Error}: it passes through every {@code catch (RuntimeException)} in the reader stack, so the failure it
     * replaces is not a diagnostic at all. A depth set above what the host stack carries reintroduces exactly
     * that, which is a choice a caller may make and not one this offers a name for.
     */
    public TsonLimitsPolicy withMaxDepth(int depth) {
        return new TsonLimitsPolicy(depth);
    }

    /**
     * The deepest a record, map, array or tuple may nest before the read is refused, counted at the cursor as
     * containers open -- so the refusal happens before any reader descends into the value, which is the point
     * of counting it in the token stream rather than in the readers that recurse.
     *
     * <p>A schema document is counted the same way, its own schema map being the outermost container: §9.1 is
     * Part 1 and speaks of documents, but a schema is untrusted input too wherever one is fetched or reached
     * through {@code !!import}.
     */
    @Override
    public int maxDepth() {
        return maxDepth;
    }

    @Override
    public String toString() {
        return "max depth " + maxDepth;
    }
}
