package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.stream.EventSkip;

/**
 * A stand-in {@link TsonTypeReader} for an entry no reader could be built for -- a constructor with no
 * {@link ValueReaderFactory} (one declared by a meta-layer schema this library has never seen), a factory
 * that rejected the entry, or a type with no bound class. Substituting one lets the schema as a whole still
 * compile; only actually {@link #read}ing a value against this specific entry fails, and only then. A
 * caller who never reads this entry never sees the failure at all.
 *
 * <p><b>Reports rather than throws, and the code is what says it is a gap.</b> {@link
 * Diagnostic.Code#NOT_IMPLEMENTED} means <i>this could not be checked</i> where every other read code means
 * <i>this is wrong</i>, so a gap can ride in the report without being mistaken for a verdict on the
 * document. Throwing instead would take the rest of the read with it: a document with one unreadable field
 * and several ordinary mistakes would report none of them, and a multi-document {@code tson validate} would
 * lose its whole envelope.
 *
 * <p><b>The same rule {@code SchemaFailure} applies one layer out.</b> A schema that fails to compile
 * during a read arrives as a {@code NOT_IMPLEMENTED} diagnostic too, so a gap travels by code wherever it
 * is found. In fail-fast mode nothing is lost: {@code report} raises {@code ReadException}, which carries
 * this same {@link Diagnostic} and its code, so a caller that needs the distinction asks {@code
 * e.diagnostic().code()} rather than matching on an exception type.
 *
 * <p>{@link OpenTemplateReader} is the structural twin -- a whole entry that refuses every value -- and the
 * two behave identically: report before consuming, so the position names the value the author wrote, then
 * skip it so the stream stays in step with its siblings.
 */
final public class ErrorReader implements TsonTypeReader<Object> {

    private final String name;
    private final RuntimeException cause;

    public ErrorReader(String name, RuntimeException cause) {
        this.name = name;
        this.cause = cause;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>A cause that already says what it is passes through unchanged, and still throws.</b> A {@link
     * MissingBindingException} is a misconfiguration -- a type the caller never mapped -- and neither
     * reporting it as this library's gap nor collecting it as a problem with the document would be true of
     * it. It is the reading application's own wiring, so it reaches that application as itself, in every
     * mode; wrapped in "no usable compiled reader" it would read as this library's gap.
     */
    @Override
    public Object read(TsonReadContext ctx) {
        if (cause instanceof MissingBindingException missing) {
            throw missing;
        }
        // Reported before anything is consumed, so the data position names the value that could not be read
        // rather than whatever the cursor drifted to while skipping it.
        ctx.report(Diagnostic.Code.NOT_IMPLEMENTED, "'" + name + "' has no usable compiled reader -- "
                + "the schema itself compiled fine, but nothing can read a value against this type: "
                + cause.getMessage(), "a type this library can read", "");
        EventSkip.dataValue(ctx);
        return null;
    }
}
