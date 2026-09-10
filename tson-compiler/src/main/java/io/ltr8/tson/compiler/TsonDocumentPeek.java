package io.ltr8.tson.compiler;

import io.ltr8.tson.base.io.ByteSource;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ParseException;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.compiler.lexer.LexException;
import io.ltr8.tson.compiler.stream.DocumentStart;

import java.io.InputStream;
import java.util.Objects;

/**
 * A document's header, read, <b>with the rest of the document still to come on the same stream</b> --
 * [TSON-DATA] §7.1's "at most two directives of lookahead and no value parsing, so streams, previews, and
 * content sniffers can classify a document from its opening bytes".
 *
 * <p><b>Nothing is rewound, because nothing is re-read.</b> The header is the stream's first event
 * ({@code DocumentStart}), so this holds the live stream positioned just past it and a reader continues
 * from there: {@code TsonObjectReader#read(TsonDocumentPeek, Class)} and its tree counterpart take one
 * exactly as they take a {@code String} or an {@code InputStream}. That is what makes this usable on a
 * source that cannot be read twice -- an HTTP request body, a socket, a pipe -- where the routing decision
 * needs the header before the read that consumes the body.
 *
 * <pre>{@code
 * TsonDocumentPeek peek = tson.begin(request.getInputStream());
 * Tson version = peek.header().schema().filter(V2::names).isPresent() ? v2 : v1;
 * Invoice invoice = version.objectReader().read(peek, Invoice.class);
 * }</pre>
 *
 * <p><b>Which reader continues is the caller's choice, and that is the point</b> -- one schema version per
 * bind context, so v1 and v2 are two readers rather than one reader reconfigured. What may <em>not</em>
 * differ is the lexical half of the policy: the token policy and §9.1's limits were applied to the tokens
 * this header is made of, so a reader handed a peek must agree with the one that opened it. It is refused
 * rather than ignored.
 *
 * <p><b>Every entry here builds a source that acquires nothing</b> -- a {@code String} or an {@code
 * InputStream}, whose {@code ByteSource.close()} is a no-op -- which is what lets a peek hold its stream
 * across the gap between the header and the read without owning a resource nobody will release. An entry
 * taking a {@code ByteSource} would inherit that obligation, so it would also have to say who closes it.
 *
 * <p><b>Total in the document's own content.</b> A document whose <em>value</em> is malformed still yields
 * its header, and a malformed <em>header</em> yields {@link TsonDocumentHeader#NONE} rather than throwing --
 * a peek's one job is not to answer with a schema the document does not name. The failure is kept, not
 * discarded: the read that follows reports it through that reader's own receiver, so a caller who peeks and
 * then reads gets exactly the diagnostic a caller who only read would have. An {@code UncheckedIOException}
 * is not caught -- the source itself failed, which is no verdict on the document.
 */
public final class TsonDocumentPeek {

    private final TsonDataStream stream;
    private final ProcessorPolicy policy;
    private final TsonDocumentHeader header;

    /** What went wrong reading the header, replayed by the read that follows, or {@code null}. */
    private final RuntimeException failure;

    private final DocumentStart start;

    private TsonDocumentPeek(TsonDataStream stream, ProcessorPolicy policy, TsonDocumentHeader header,
                             DocumentStart start, RuntimeException failure) {
        this.stream = stream;
        this.policy = policy;
        this.header = header;
        this.start = start;
        this.failure = failure;
    }

    /** The header of {@code source}, under this processor's default policy. */
    public static TsonDocumentPeek of(String source) {
        return of(source, ProcessorPolicy.defaults());
    }

    /**
     * The header of {@code source}, under this processor's default policy. {@code source} is not closed
     * here, and must not be read directly afterwards -- it is positioned mid-document, and this peek is
     * what carries the rest.
     */
    public static TsonDocumentPeek of(InputStream source) {
        return of(source, ProcessorPolicy.defaults());
    }

    /** {@link #of(InputStream)} under {@code policy} -- what {@code Tson#begin} supplies from its config. */
    public static TsonDocumentPeek of(InputStream source, ProcessorPolicy policy) {
        return of(new TsonDataStream(ByteSource.of(source), policy, DiagnosticsReceiver.throwing()), policy);
    }

    /** {@link #of(String)} under {@code policy}. */
    public static TsonDocumentPeek of(String source, ProcessorPolicy policy) {
        return of(new TsonDataStream(ByteSource.of(source), policy, DiagnosticsReceiver.throwing()), policy);
    }

    private static TsonDocumentPeek of(TsonDataStream stream, ProcessorPolicy policy) {
        try {
            DocumentStart start = (DocumentStart) stream.next();
            return new TsonDocumentPeek(stream, policy,
                    new TsonDocumentHeader(start.id(), start.schema(), start.meta()), start, null);
        } catch (ParseException | LexException e) {
            return new TsonDocumentPeek(stream, policy, TsonDocumentHeader.NONE, null, e);
        }
    }

    /** What the document declares ahead of its value ([TSON-DATA] §2.2). */
    public TsonDocumentHeader header() {
        return Objects.requireNonNull(header);
    }

    /** Whether this is a <em>schema</em> document -- [TSON-SCHEMA] §12.1's {@code !!meta} decides. */
    public boolean isSchemaDocument() {
        return header.isSchemaDocument();
    }

    // ── For the readers that continue from here ──────────────────────────

    TsonDataStream stream() {
        return stream;
    }

    /** The policy the header was read under -- a reader continuing here must share it. */
    ProcessorPolicy policy() {
        return policy;
    }

    /** The header event, or {@code null} when the header did not read -- then {@link #failure} says why. */
    DocumentStart start() {
        return start;
    }

    RuntimeException failure() {
        return failure;
    }
}
