package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.ValueParser;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;

/**
 * The reader a bootstrap compile ({@code TsonSchemaCompiler.compileBootstrap}) gives a meta-kernel entry not yet
 * read, under {@code SPEC-FEEDBACK.md #4}'s order: reading a value against a type waits on that type being ready, and
 * a held value orders nothing.
 *
 * <ul>
 *   <li><b>A read of a schema document's value</b> ({@link TsonReadContext#readsSchemaDocument()}) that reaches it is
 *       a wait: it throws {@link NotReady} naming {@link #entry}, and {@code MetaKernelBootstrapResolver} makes that
 *       entry ready and reads the value again against a compile that holds it.</li>
 *   <li><b>Any other read is a held value's</b>, a field's default or pin decoded once every reader exists, and binds
 *       as written: what base type resolution gives the token ([TSON-DATA] §4), which the field's own bridge then
 *       carries to its component. The value is judged against its type when the whole kernel links.</li>
 * </ul>
 *
 * <p>Nothing else compiles one: an ordinary compile has no pending entries, and refuses a name with no entry.
 */
public record PendingTypeReader(String entry) implements TsonTypeReader<Object> {

    @Override
    public Object read(TsonReadContext ctx) {
        if (ctx.readsSchemaDocument()) {
            throw new NotReady(entry);
        }
        TsonEvent event = ctx.next();
        if (!(event instanceof TokenEvent token)) {
            throw new IllegalStateException("'" + entry + "': a held value is a bare token, found " + event);
        }
        return ValueParser.INSTANCE.read(new TokenValue(token.text(), token.form()));
    }

    /**
     * A read reached {@link #entry()}, which is not ready yet. A signal to the bootstrap that compiled the reader,
     * which catches it; no stack trace, since it is control flow rather than a failure.
     */
    public static final class NotReady extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String entry;

        NotReady(String entry) {
            super("'" + entry + "' is not ready", null, false, false);
            this.entry = entry;
        }

        /** The entry the read waits on. */
        public String entry() {
            return entry;
        }
    }
}
