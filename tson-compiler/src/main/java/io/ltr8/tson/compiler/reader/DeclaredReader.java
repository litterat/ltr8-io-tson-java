package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.compiler.stream.EventSkip;
import io.ltr8.tson.schema.meta.DeclaredType;
import io.ltr8.tson.schema.meta.NamespaceBody;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The kernel's {@code declared_type} constructor, whose instance is its {@code declared}: the open sum over the
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
 * the value, so every mode hands back what it built; in bind mode a value of a namespace type is delivered as the
 * {@code Namespace} it is ({@link NamespaceInstanceReader}), as at every position whose tag names its type.
 *
 * <p>The kernel's {@code schema} map holds its entries at {@code declared}: each entry's value names its type by its
 * tag, a constructor for a type entry and an ordinary type for a value entry.
 */
final class DeclaredReader implements TsonTypeReader<Object> {

    /** One factory for every mode: the reader selects and builds nothing of its own. */
    static final ValueReaderFactory FACTORY = (name, definition, context) -> {
        if (!(definition instanceof DeclaredType)) {
            throw new IllegalArgumentException("'" + name + "' is not declared_type-shaped: " + definition);
        }
        Set<String> namespaces = new HashSet<>();
        context.schema().entries().forEach((entry, value) -> {
            if (value instanceof NamespaceBody) {
                namespaces.add(entry);
            }
        });
        return new DeclaredReader(context.displayName(name, definition), context.schema().entries().keySet(),
                namespaces, context.readers());
    };

    private final String displayName;
    private final Set<String> closure;
    private final Set<String> namespaces;
    private final TsonTypeReaderResolver readers;

    private DeclaredReader(String displayName, Set<String> closure, Set<String> namespaces,
                           TsonTypeReaderResolver readers) {
        this.displayName = displayName;
        this.closure = closure;
        this.namespaces = Set.copyOf(namespaces);
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
        Object read = readers.resolve(name).read(ctx);
        return namespaces.contains(name) ? NamespaceInstanceReader.instance(name, read) : read;
    }

    /** Reports, discards the whole value (framing included -- nothing has consumed it), and yields nothing. */
    private static Object abandon(TsonReadContext ctx, Diagnostic.Code code, String message, String expected,
            String actual) {
        ctx.report(code, message, expected, actual);
        EventSkip.dataValue(ctx);
        return null;
    }
}
