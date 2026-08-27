package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonMissingBindingException;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;

/**
 * A stand-in {@link TsonTypeReader} for a constructor with no compiled reader implemented yet --
 * this package's own copy of {@code reader.ErrorReader}. Registering one directly (see {@link
 * ValueReaderFactoryRegistry}'s own trailing, clearly-marked block of these) lets a schema that
 * merely *declares* one of these constructors still compile successfully; only actually {@link
 * #read}ing a value against this specific entry fails, and only then. A caller who never reads this
 * entry never sees the failure at all.
 *
 * <p><b>Reports rather than throws, and the code is what says it is a gap.</b> {@link
 * Diagnostic.Code#NOT_IMPLEMENTED} means <i>this could not be checked</i> where every other read code means
 * <i>this is wrong</i>, so a gap can ride in the report without being mistaken for a verdict on the
 * document. Throwing instead took the rest of the read with it: one unreadable field and a document with
 * several ordinary mistakes reported none of them, and in a multi-document {@code tson validate} the whole
 * envelope was lost -- the exact failure the schema pipeline gave up throwing gaps to avoid.
 *
 * <p><b>This is {@link ErrorReader}'s own neighbour's rule, applied here too.</b> A schema that fails to
 * compile during a read already arrives as a {@code NOT_IMPLEMENTED} diagnostic ({@code SchemaFailure}),
 * so a gap found one layer further in was the only one still travelling by channel. In fail-fast mode
 * nothing is lost: {@code report} raises {@code TsonReadException}, which carries this same {@link
 * Diagnostic} and its code, so a caller that needs the distinction asks {@code
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
     * TsonMissingBindingException} is a misconfiguration -- a type the caller never mapped -- and neither
     * reporting it as this library's gap nor collecting it as a problem with the document would be true of
     * it. It is the reading application's own wiring, so it reaches that application as itself, in every
     * mode; wrapping it in "no usable compiled reader" is what once sent a downstream service's missing
     * configuration out as a 501.
     */
    @Override
    public Object read(TsonReadContext ctx) {
        if (cause instanceof TsonMissingBindingException missing) {
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
