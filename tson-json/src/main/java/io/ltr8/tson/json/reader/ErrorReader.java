package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
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
 * <p>Today this is where every container and sum constructor lands, [TSON-JSON] §6-§8 being unbuilt. It stays
 * afterwards, for [TSON-SCHEMA] §2.2.2's extension point: a meta-layer constructor this library has never
 * seen has no factory to dispatch to.
 */
public final class ErrorReader implements JsonTypeReader<Object> {

    private final String name;
    private final RuntimeException cause;

    public ErrorReader(String name, RuntimeException cause) {
        this.name = name;
        this.cause = cause;
    }

    @Override
    public Object read(JsonReadContext ctx) {
        ctx.report(Diagnostic.Code.NOT_IMPLEMENTED, "'" + name + "' has no usable compiled reader -- the schema "
                + "itself compiled fine, but nothing here can read a JSON value against this type: "
                + cause.getMessage(), "a type this encoding can read", "");
        EventSkip.nextValue(ctx);
        return null;
    }
}
