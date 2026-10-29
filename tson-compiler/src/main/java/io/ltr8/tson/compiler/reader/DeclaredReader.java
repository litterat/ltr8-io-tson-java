package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.compiler.stream.EventSkip;
import io.ltr8.tson.schema.meta.DeclaredType;

import java.util.Optional;
import java.util.Set;

/**
 * meta.tn's {@code declared_type} constructor, whose instance is core's {@code declared}: the open sum over the
 * governing closure, where the value names its own type by its tag and is read in full against it ([TSON-SCHEMA]
 * §7.8).
 *
 * <p><b>The tag is the whole of what the value says about its type.</b> A value carrying no type-ref names no
 * type, and an open position has nothing to infer one from, so it is a validation error in every mode. A tag
 * naming no type of the closure is an unresolved reference, {@code UNKNOWN_TYPE}.
 *
 * <p><b>Fixed at compile time.</b> A {@code declared} entry belongs to exactly one schema, so "the governing
 * closure" is that schema's -- its own declarations and its imports -- known when this is built and resolved
 * through the same {@link TsonTypeReaderResolver} every other dispatch uses. The selected type's reader builds
 * the value, so every mode hands back what it built.
 */
final class DeclaredReader implements TsonTypeReader<Object> {

    /** One factory for every mode: the reader selects and builds nothing of its own. */
    static final ValueReaderFactory FACTORY = (name, definition, context) -> {
        if (!(definition instanceof DeclaredType)) {
            throw new IllegalArgumentException("'" + name + "' is not declared_type-shaped: " + definition);
        }
        return new DeclaredReader(context.displayName(name, definition), context.schema().entries().keySet(),
                context.readers());
    };

    private final String displayName;
    private final Set<String> closure;
    private final TsonTypeReaderResolver readers;

    private DeclaredReader(String displayName, Set<String> closure, TsonTypeReaderResolver readers) {
        this.displayName = displayName;
        this.closure = closure;
        this.readers = readers;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        Optional<String> typeRef = EventSkip.typeRefAhead(ctx);
        if (typeRef.isEmpty()) {
            // VALIDATION_ERROR, not UNKNOWN_TYPE_REF: nothing here failed to resolve, so §8.1's resolver
            // category would be the wrong verdict -- the position promises no type for an untagged value.
            return abandon(ctx, Diagnostic.Code.VALIDATION_ERROR,
                    "'" + displayName + "' takes a value that names its own type, so it requires an explicit "
                            + "type annotation (!typeName)",
                    "a type annotation naming the value's own type", "no type annotation");
        }
        String name = typeRef.get();
        if (!closure.contains(name)) {
            return abandon(ctx, Diagnostic.Code.UNKNOWN_TYPE,
                    "'" + name + "' is not a type this schema declares or imports, and '" + displayName
                            + "' resolves a value's own type name there (§2.2.3)",
                    "a type declared by the governing schema", name);
        }
        return readers.resolve(name).read(ctx);
    }

    /** Reports, discards the whole value (framing included -- nothing has consumed it), and yields nothing. */
    private static Object abandon(TsonReadContext ctx, Diagnostic.Code code, String message, String expected,
            String actual) {
        ctx.report(code, message, expected, actual);
        EventSkip.dataValue(ctx);
        return null;
    }
}
