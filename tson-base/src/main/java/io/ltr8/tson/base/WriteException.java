package io.ltr8.tson.base;

/**
 * A value that cannot be written in the encoding it was handed to -- one fact, stated by every encoding, so
 * one exception rather than one per encoding.
 *
 * <p>The write-side peer of {@link ReadException}, and here for {@link ParseException}'s reason: a failure
 * whose subject is the <em>processor</em> rather than any one document format belongs to the vocabulary both
 * encodings share. What raises it differs, and that is each encoding's own -- the JSON writers refuse a
 * choice with no discriminator ([TSON-JSON] §8 gives untagged dispatch no schemaless form) and a map whose
 * key type a member name cannot spell; the TSON writers refuse a second type annotation on one value. Both
 * refuse a host value their encoding has no way to emit.
 *
 * <p><b>Distinct from {@code DataBindException}</b>, which says a <em>class</em> could not be taken apart.
 * This says the value came apart correctly and the encoding has nowhere to put it, which is a different
 * remedy: one is fixed in the class, the other by choosing a different encoding or a different shape. Where
 * a write fails for that other reason -- a bridge that will not unwrap -- the binding failure is wrapped
 * and preserved as the cause, so one unchecked type covers every way a write can fail and the whole
 * read/write stack throws only unchecked exceptions.
 */
public final class WriteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WriteException(String message) {
        super(message);
    }

    public WriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
