package io.ltr8.tson.compiler;

import io.ltr8.tson.base.io.ByteSink;
import io.ltr8.tson.compiler.config.ResolverBindContext;
import io.ltr8.tson.atom.VocabularyAtoms;
import io.ltr8.annotation.Transparent;
import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassAnnotated;
import io.ltr8.annotation.Annotation;
import io.ltr8.annotation.Annotations;
import io.ltr8.tson.tree.TsonValue;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassElement;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassTuple;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.tson.compiler.writer.DataClassObjectWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The write-side counterpart to {@link TsonObjectReader} -- given a Java object and its {@link
 * DataClass} descriptor from {@code tson-bind}, writes it as TSON text. See {@link
 * TsonObjectReader}'s own Javadoc for the read/write split and why this pair lives in {@code
 * tson-compiler}.
 */
public final class TsonObjectWriter {

    private final DataBindContext context;

    /** The engine this facade puts a document around -- see {@link DataClassObjectWriter}. */
    private final DataClassObjectWriter engine;

    /** The document header this writer emits, if any -- see {@link #describing}. */
    private final TsonDocumentHeader header;

    /**
     * The root value's own type-ref, when {@link #describing} supplied one. Not part of {@link
     * TsonDocumentHeader}: §2.2 is explicit that header directives are properties of the <em>document</em>
     * and the root value's type annotation is not one of them, however adjacent the two look on the wire.
     */
    private final Optional<String> rootTypeName;

    public TsonObjectWriter(DataBindContext context) {
        this(context, TsonDocumentHeader.NONE, Optional.empty());
    }

    public TsonObjectWriter() {
        this(ResolverBindContext.defaultContext());
    }

    private TsonObjectWriter(DataBindContext context, TsonDocumentHeader header, Optional<String> rootTypeName) {
        this.context = context;
        this.engine = new DataClassObjectWriter(context);
        this.header = header;
        this.rootTypeName = rootTypeName;
    }

    /**
     * A writer whose documents are <b>self-describing</b>: {@code !!schema:"<schemaUri>"} in the header and
     * {@code !rootTypeName} on the root value, which together are everything a reader needs to resolve the
     * schema, pick the type and validate -- nothing out of band. The mirror of {@link
     * TsonObjectReader#withSchema}/{@code readAs}, which is what reads such a document back.
     *
     * <p><b>Both, not just the directive.</b> A bound object carries neither fact: the schemaless writer
     * emits a type-ref only where the value would not read back without one (see {@link #toTson}), so a
     * record writes bare. A {@code !!schema} on its own therefore produces a document whose reader answers
     * "data declares a !!schema but has no root type-ref to select a type" -- half self-describing is not
     * self-describing, so this method takes both and there is no one-argument form to get it half right.
     *
     * <p><b>Derivation, not a setter, and off by default.</b> Emitting a directive by default would change
     * every document this library has ever produced ({@code tson validate --output tson} included), so the
     * plain writer keeps writing a bare value and a caller opts in per writer, exactly as the readers derive.
     *
     * <p>Both strings are the caller's to supply. Deriving them from a compiled schema the bind registry
     * already holds is the shape that would spare a caller naming what it already knows, and it needs the
     * schema-aware writer this library does not have yet ({@code BACKLOG.md}); nothing here forecloses it.
     *
     * @param schemaUri    the governing schema's identity, written as the {@code !!schema} argument
     * @param rootTypeName the schema's own name for the root value's type, written as its type-ref. A value
     *                     that writes a type-ref of its own -- a vocabulary host type, a union member -- is
     *                     refused rather than written twice; see {@link TsonDataEmitter#typeRef}
     */
    public TsonObjectWriter describing(String schemaUri, String rootTypeName) {
        return new TsonObjectWriter(context, header.describing(schemaUri), Optional.of(rootTypeName));
    }

    /**
     * A writer that names {@code documentId} in an {@code !!id} directive -- the document's own identity,
     * emitted first when {@link #describing} is also in force (§2.2 fixes the order).
     */
    public TsonObjectWriter identifiedBy(String documentId) {
        return new TsonObjectWriter(context, header.identifiedBy(documentId), rootTypeName);
    }

    // ── Entry point ──────────────────────────────────────────────────────

    /**
     * Writes {@code document} back as TSON, header and root type-ref included -- the round trip
     * {@link TsonObjectReader#readDocument} exists for, in one call.
     *
     * <p><b>This is the call site {@link #describing} is awkward at.</b> Naming a schema here needs the root
     * type as well, because a bound object carries neither and the binder cannot invert a class to a name --
     * so writing a document back by hand means restating what the read had just worked out. A document
     * carries both, and supplies them.
     *
     * <p><b>The document's own facts win over this writer's</b>, component by component and only where it
     * has them, so reproducing a document reproduces it while a writer configured for something the document
     * does not state still contributes it.
     *
     * @throws TsonWriteException if {@code document} names a schema but no root type -- the pair is what
     *                            makes a document self-describing, and a reader given only the directive
     *                            has a schema and no way to pick a type from it
     */
    public String toTson(TsonObjectDocument<?> document) {
        return forDocument(document).toTson(document.value());
    }

    /** {@link #toTson(TsonObjectDocument)} into a stream -- UTF-8, flushed and not closed. */
    public void write(TsonObjectDocument<?> document, OutputStream out) {
        forDocument(document).write(document.value(), out);
    }

    /** {@link #toTson(TsonObjectDocument)} into any {@link Appendable}. */
    public void write(TsonObjectDocument<?> document, Appendable out) {
        forDocument(document).write(document.value(), out);
    }

    /** This writer with {@code document}'s own directives and root type applied over its own. */
    private TsonObjectWriter forDocument(TsonObjectDocument<?> document) {
        TsonObjectWriter writer = this;
        if (document.schema().isPresent()) {
            String type = document.rootType().orElseThrow(() -> new TsonWriteException(
                    "this document names the schema \"" + document.schema().get() + "\" and no root type, so "
                    + "there is nothing to write as the root's type-ref and a reader would have no way to pick "
                    + "a type from that schema -- a document read against a schema carries both, so this one "
                    + "was assembled by hand; supply the type, or drop the schema and write a bare value", null));
            writer = writer.describing(document.schema().get(), type);
        }
        if (document.id().isPresent()) {
            writer = writer.identifiedBy(document.id().get());
        }
        return writer;
    }

    /**
     * Writes {@code value} as TSON text -- mainly useful as a debugging tool (inspect what a bound
     * object actually contains) rather than a guaranteed-lossless serializer. Emits a {@code
     * !typeName} type-ref only where one is actually needed for the value to read back correctly:
     * exactly the built-in vocabulary's JDK-backed host types (uuid/uri/ipv4/ipv6/date/time/
     * datetime/binary/rational/complex/duration), none of which round-trip through default value
     * resolution (§4) on their own. Everything default resolution *does* already recover -- plain
     * numbers, booleans, strings, {@code null} -- is written bare, which means the integer family's
     * exact width is **not** preserved: a field bound from {@code !uint8 42} writes back as plain
     * {@code 42}, indistinguishable from a field that was never {@code !uint8}-typed at all. That's
     * not a bug to fix -- a schemaless writer has no way to know the width was ever there in the
     * first place, the same reason a schemaless reader has no way to reject an out-of-range value
     * without the annotation.
     *
     * <p>A record whose bound class declares an {@code Annotations} component gets its wire annotations
     * (§3.1) written back ahead of the value. An annotation's own value round-trips in whatever form the
     * read produced -- a bound object writes like any other value, and one kept structurally (its name
     * resolved to no declared type) writes through the tree writer.
     */
    public String toTson(Object value) {
        StringBuilder text = new StringBuilder();
        write(value, text);
        return text.toString();
    }

    /**
     * Writes {@code value} as TSON <b>into {@code out} as it goes</b>, so a large or open-ended document
     * never exists as a {@code String} -- the write-side counterpart to {@link TsonObjectReader} taking an {@code
     * InputStream}. The bytes are UTF-8 ([TSON-DATA] §9.1), the stream is <b>flushed and not closed</b>
     * (the caller owns it -- an HTTP response body is the case this exists for), and buffering is the
     * encoder's own.
     *
     * <p>{@link #toTson} is this method over a {@link StringBuilder}, kept for the callers that do want the
     * whole document in hand.
     */
    public void write(Object value, OutputStream out) {
        try (ByteSink sink = ByteSink.of(out)) {
            write(value, sink);
        }
    }

    /**
     * Writes {@code value} into {@code sink} -- the general byte target, which {@link #write(Object,
     * OutputStream)} adapts to. {@code ByteSink.of} also covers a {@code ByteBuffer} and a channel.
     *
     * <p>UTF-8 is encoded by this library ({@code Utf8Sink}), not by an {@code OutputStreamWriter}: the
     * block is the sink's to size, and an unpaired surrogate is refused rather than written as {@code ?}.
     * The sink is flushed and not closed.
     */
    public void write(Object value, ByteSink sink) {
        TsonDataEmitter emitter = new TsonDataEmitter(sink);
        header.emit(emitter);
        rootTypeName.ifPresent(emitter::typeRef);
        engine.write(value, emitter);
        emitter.flush();
    }

    /**
     * As {@link #write(Object, OutputStream)}, into any {@link Appendable} -- a caller who already holds a
     * {@code Writer}, or is assembling a larger text around this document. An {@link IOException} from
     * {@code out} surfaces as an {@link UncheckedIOException}; see {@link TsonDataEmitter}.
     */
    public void write(Object value, Appendable out) {
        TsonDataEmitter writer = new TsonDataEmitter(out);
        header.emit(writer);
        rootTypeName.ifPresent(writer::typeRef);
        engine.write(value, writer);
    }
}

