package io.ltr8.tson.compiler;

import io.ltr8.tson.base.io.ByteSink;
import io.ltr8.tson.compiler.writer.TreeValueWriter;
import io.ltr8.bind.DataBindException;
import io.ltr8.tson.tree.*;
import io.ltr8.tson.tree.TsonDocument;
import io.ltr8.tson.tree.TsonValue;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writes an immutable {@link TsonValue} tree back to TSON text -- the write-side counterpart to {@link
 * TsonTreeReader}, and the tree analogue of {@link TsonObjectWriter} (which writes a Java object graph).
 * Drives {@link TsonDataEmitter} for all grammar-level formatting (delimiters, separators, escaping), the
 * same relationship {@link TsonObjectWriter} has with it.
 *
 * <p><b>Closer to lossless than {@link TsonObjectWriter}</b>, because the tree already carries each node's
 * own type-ref: a {@code TsonAtom(42, "int32")} writes back as {@code !int32 42}, so the integer width
 * survives a read/edit/write round trip (the object writer drops it, having no way to recover it from a bound
 * {@code long}). A JDK-backed vocabulary host type (uuid/uri/date/binary/...) is written with its type-ref
 * and its atom's own {@code write} form, preferring the node's own captured type-ref (e.g. {@code base64url})
 * over the reverse-map default; a plain number/boolean/string is written bare unless the node kept a type-ref.
 *
 * <p><b>Round trip is value-preserving, not byte-identical:</b> a vocabulary value is emitted quoted ({@code
 * !uuid "..."}) whichever way it was written in the source, and a node re-read from the result yields an
 * equal tree. {@link TsonMissing} is a navigation artifact, not a value, so writing one is a programming
 * error ({@link IllegalArgumentException}).
 *
 * <p><b>Wire annotations are re-emitted</b> (§3.1), ahead of each value's type-ref and in the order the tree
 * holds them, repeats included -- so a schemalessly read tree survives a read/write/read round trip with its
 * metadata intact, not just its values. An annotation's own value is written as an ordinary node, so a nested
 * one ({@code @a:@b:val target}) needs no special handling. Only the <i>schemaless</i> reader captures
 * annotations in the first place, so a schema-driven tree still writes back without them -- nothing is lost
 * here, there was nothing on the node to write.
 */
public final class TsonTreeWriter {

    /**
     * The engine this facade puts a document around -- see {@link TreeValueWriter}. Stateless past its atom
     * table, so one instance serves every writer.
     */
    private static final TreeValueWriter ENGINE = new TreeValueWriter();

    /** The document header this writer emits, if any -- see {@link #describing}. */
    private final TsonDocumentHeader header;

    public TsonTreeWriter() {
        this(TsonDocumentHeader.NONE);
    }

    private TsonTreeWriter(TsonDocumentHeader header) {
        this.header = header;
    }

    /**
     * A writer whose documents are <b>self-describing</b>: {@code !!schema:"<schemaUri>"} in the header,
     * over a root value that already names its own type. The mirror of {@link TsonTreeReader#withSchema},
     * which is what reads such a document back.
     *
     * <p><b>One argument, where {@link TsonObjectWriter#describing} needs two</b>, because a tree already
     * carries what a bound object cannot: a schema-driven read records each node's type, so the root's own
     * {@code !typeName} is written back with it. A root that has no type-ref -- a hand-built node, or one
     * from a schemaless read of an untagged document -- would make a document declaring a schema and then
     * giving a reader no type to select, so writing one is refused rather than half-done.
     *
     * <p><b>Derivation, not a setter, and off by default.</b> Emitting a directive by default would change
     * every document this library has ever produced ({@code tson validate --output tson} included), so the
     * plain writer keeps writing a bare value and a caller opts in per writer, exactly as the readers derive.
     */
    public TsonTreeWriter describing(String schemaUri) {
        return new TsonTreeWriter(header.describing(schemaUri));
    }

    /**
     * A writer that names {@code documentId} in an {@code !!id} directive -- the document's own identity,
     * emitted first when {@link #describing} is also in force (§2.2 fixes the order).
     */
    public TsonTreeWriter identifiedBy(String documentId) {
        return new TsonTreeWriter(header.identifiedBy(documentId));
    }

    /**
     * Writes {@code document} back as TSON, header and all -- the round trip {@link
     * TsonTreeReader#readDocument} exists for, in one call.
     *
     * <p><b>The document's own header wins over this writer's</b>, component by component, and only where it
     * has one: a {@code TsonDocument} carrying a {@code !!schema} is written with that schema whatever
     * {@link #describing} was set to, and one carrying none keeps whatever this writer already had. Reading
     * a document and writing it back therefore reproduces it, which is the point, while a writer configured
     * for something the document does not state still contributes it.
     *
     * <p>Equivalent to {@code describing(...)}/{@code identifiedBy(...)} applied by hand and then
     * {@link #toTson(TsonValue)} -- which is four lines and two {@link java.util.Optional}s at every call
     * site, and gets the precedence question wrong about as often as not.
     */
    public String toTson(TsonDocument document) {
        return forDocument(document).toTson(document.root());
    }

    /** {@link #toTson(TsonDocument)} into a stream -- UTF-8, flushed and not closed. */
    public void write(TsonDocument document, OutputStream out) {
        forDocument(document).write(document.root(), out);
    }

    /** {@link #toTson(TsonDocument)} into any {@link Appendable}. */
    public void write(TsonDocument document, Appendable out) {
        forDocument(document).write(document.root(), out);
    }

    /** This writer with {@code document}'s own directives applied over its own. */
    private TsonTreeWriter forDocument(TsonDocument document) {
        TsonTreeWriter writer = this;
        if (document.schema().isPresent()) {
            writer = writer.describing(document.schema().get());
        }
        if (document.id().isPresent()) {
            writer = writer.identifiedBy(document.id().get());
        }
        return writer;
    }

    /** Writes {@code node} as TSON text -- {@link #write(TsonValue, Appendable)} into a fresh buffer. */
    public String toTson(TsonValue node) {
        StringBuilder text = new StringBuilder();
        write(node, text);
        return text.toString();
    }

    /**
     * Writes {@code node} as TSON <b>into {@code out} as it goes</b>, so a large document never exists as a
     * {@code String} -- the write-side counterpart to {@link TsonTreeReader} taking an {@code InputStream}.
     * The bytes are UTF-8 ([TSON-DATA] §9.1), the stream is <b>flushed and not closed</b> (the caller owns
     * it), and buffering is the encoder's own.
     *
     * <p>The tree itself is of course already in memory -- that is what a tree is. What this saves is the
     * <em>second</em> copy: the rendered document, which for a large tree is the bigger of the two.
     */
    public void write(TsonValue node, OutputStream out) {
        write(node, ByteSink.of(out));
    }

    /**
     * Writes {@code node} into {@code sink} -- the general byte target, which {@link #write(TsonValue,
     * OutputStream)} adapts to. {@code ByteSink.of} also covers a {@code ByteBuffer} and a channel, so a
     * further target costs no method here.
     *
     * <p>UTF-8 is encoded by this library ({@code Utf8Sink}), not by an {@code OutputStreamWriter}: the
     * block is the sink's to size, and an unpaired surrogate is refused rather than written as {@code ?}.
     * The sink is flushed and not closed.
     */
    public void write(TsonValue node, ByteSink sink) {
        if (header.schema().isPresent() && node.typeRef().isEmpty()) {
            throw new TsonWriteException("a document declaring !!schema \"" + header.schema().get()
                    + "\" needs a root type-ref to select a type, and this root node carries none -- read"
                    + " the tree against its schema (which records each node's type) or set one on the"
                    + " root before writing", null);
        }
        try {
            TsonDataEmitter emitter = new TsonDataEmitter(sink);
            header.emit(emitter);
            ENGINE.write(node, emitter);
            emitter.flush();
        } catch (DataBindException e) {
            throw new TsonWriteException("cannot write TsonValue as TSON: " + e.getMessage(), e);
        }
    }

    /**
     * As {@link #write(TsonValue, OutputStream)}, into any {@link Appendable}. An {@link IOException} from
     * {@code out} surfaces as an {@link UncheckedIOException}; see {@link TsonDataEmitter}.
     */
    public void write(TsonValue node, Appendable out) {
        try {
            if (header.schema().isPresent() && node.typeRef().isEmpty()) {
                throw new TsonWriteException("a document declaring !!schema \"" + header.schema().get()
                        + "\" needs a root type-ref to select a type, and this root node carries none -- read"
                        + " the tree against its schema (which records each node's type) or set one on the"
                        + " root before writing", null);
            }
            TsonDataEmitter emitter = new TsonDataEmitter(out);
            header.emit(emitter);
            ENGINE.write(node, emitter);
        } catch (DataBindException e) {
            throw new TsonWriteException("cannot write TsonValue as TSON: " + e.getMessage(), e);
        }
    }
}
