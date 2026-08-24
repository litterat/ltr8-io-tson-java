package io.ltr8.tson.compiler;

import java.io.InputStream;

/**
 * A peeked header plus the document it came off, whole -- the one-shot-stream answer to {@link
 * TsonDocumentHeader#peek(InputStream)}, whose source is left wherever the lexer stopped.
 *
 * <p>An HTTP request body, a socket, a pipe: read once, no rewind, and yet the routing decision needs the
 * header before the read that consumes it. {@link TsonDocumentHeader#peekResumable} answers by recording
 * every byte the peek pulled and handing back {@link #document()} -- those bytes followed by the rest of the
 * source, so the document reads from its very first byte, header directives included. What is buffered is
 * what the lexer pulled to reach the end of the header (its decoder reads ahead in chunks), never the
 * document.
 *
 * <p>{@link #document()} is the only stream to use afterwards; the source is positioned mid-document and
 * reading it directly would skip whatever the peek buffered. Closing {@link #document()} closes the source
 * with it.
 *
 * @param header   what the document declares ahead of its value
 * @param document the same document from its first byte, ready to hand to a reader
 */
public record TsonDocumentPeek(TsonDocumentHeader header, InputStream document) {
}
