package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;

/**
 * The reader an entry gets when building its real one failed -- the schema still compiles, and reading a
 * value against this one type reports {@code NOT_IMPLEMENTED} and skips the value.
 *
 * <p><b>A gap costs that value a verdict and nothing else's.</b> Throwing out of the compile would take every
 * other entry's reader with it; reporting per value keeps the rest of the document judged, and the
 * <em>code</em> rather than the channel is what keeps a gap apart from an author error -- {@code
 * Code.verdict()} is false for this one, so nothing downstream can mistake it for a verdict on the document.
 *
 * <p>This is where a constructor this encoding has no reader for lands -- {@code scoped}, [TSON-JSON] §8.5
 * being unbuilt -- and where [TSON-SCHEMA] §2.2.2's extension point lands for good: a meta-layer constructor
 * this library has never seen has no factory to dispatch to.
 */
public final class ErrorReader implements JsonTypeReader<Object> {

    private final String name;
    private final RuntimeException cause;

    public ErrorReader(String name, RuntimeException cause) {
        this.name = name;
        this.cause = cause;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>A missing binding passes through as itself, and throws.</b> A type the caller never mapped to a class
     * is the reading application's own wiring -- neither this library's gap nor a problem with the document --
     * so it reaches that application as itself in every mode, as {@code tson-compiler}'s bind mode does.
     */
    @Override
    public Object read(JsonReadContext ctx) {
        if (cause instanceof MissingBindingException missing) {
            throw missing;
        }
        ctx.report(Diagnostic.Code.NOT_IMPLEMENTED, "'" + name + "' has no usable compiled reader -- the schema "
                + "itself compiled fine, but nothing here can read a JSON value against this type: "
                + cause.getMessage(), "a type this encoding can read", "");
        EventSkip.nextValue(ctx);
        return null;
    }
}
