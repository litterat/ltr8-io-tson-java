package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.ForeignSchemas;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.compiler.stream.SchemaRef;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.meta.ScopeKind;
import io.ltr8.tson.schema.meta.Scoped;
import io.ltr8.tson.tree.TsonScopedValue;
import io.ltr8.tson.tree.TsonValue;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * meta.tn's {@code scoped} constructor: the open sum, where the value names its own type and the instance
 * names the namespaces that name may be resolved in ([TSON-SCHEMA] §7.8). One reader for every instance --
 * core's {@code declared}, {@code extern} and {@code dynamic}, and every narrowing {@code extern_of} or
 * {@code extern_type} materialises -- because what separates them is two constraint values and not a shape.
 *
 * <p><b>The value's own shape picks the cell.</b> A value carrying a nested {@code !!schema} is EXTERN; a
 * value carrying a type-ref alone is LOCAL; a value carrying neither names no type and is a validation
 * error, there being nothing at an open position for a type to be inferred from. A cell the instance's
 * {@link Scoped#scope} does not hold refuses the value it would have taken -- so {@code declared} refuses a
 * pushed scope and {@code extern} requires one, both from this one reader.
 *
 * <p><b>LOCAL is fixed at compile time and EXTERN is not.</b> A {@code scoped} entry belongs to exactly one
 * schema, so "the governing namespace" is that schema's, known when this is built and resolved through the
 * same {@link TsonTypeReaderResolver} every other dispatch uses. Which foreign schema an EXTERN value names
 * is the document's choice, so it is looked up as the value arrives, through {@link ForeignSchemas} -- the
 * ordinary loader, so a schema nothing would supply is one of the five {@code SCHEMA_*} codes and never a
 * verdict on the document.
 *
 * <p><b>The scope pops by returning.</b> There is no scope stack: the reader for the foreign type is the
 * foreign schema's own compiled reader, wired to that schema's entries, so everything below the pushed value
 * resolves there by construction and everything after it resolves here again.
 *
 * <p><b>Tree mode keeps the push and bind mode cannot.</b> {@code scoped} is where a document says which
 * schema a value belongs to, and a tree that dropped that could not be written back -- so tree mode wraps an
 * EXTERN value in a {@link TsonScopedValue}. A bound object has nowhere to carry a URI, and inventing
 * somewhere would be a change to what a consumer's own class means, so bind mode hands the object back as it
 * is. That is the same asymmetry {@code TsonAbsent} already makes for [TSON-DATA] §2.9.
 */
final class ScopedReader implements TsonTypeReader<Object> {

    private final String displayName;
    private final Scoped body;
    private final Set<String> localNames;
    private final TsonTypeReaderResolver local;
    private final ForeignSchemas foreign;

    /** {@code schemas}, keyed by canonical identity ([TSON-DATA] §2.2.1) -- an empty list means "any type". */
    private final Map<String, List<String>> admittedSchemas;

    /** How a read result carries the scope it was read in: tree mode wraps, bind mode hands it straight back. */
    private final BiFunction<String, Object, Object> inScope;

    private ScopedReader(String displayName, Scoped body, Set<String> localNames, TsonTypeReaderResolver local,
            ForeignSchemas foreign, BiFunction<String, Object, Object> inScope) {
        this.displayName = displayName;
        this.body = body;
        this.localNames = localNames;
        this.local = local;
        this.foreign = foreign;
        this.inScope = inScope;
        this.admittedSchemas = canonicalKeys(body);
    }

    /** Tree mode: an EXTERN value comes back wrapped in the scope the document pushed onto it. */
    static final ValueReaderFactory TREE = factory((uri, value) ->
            value == null ? null : new TsonScopedValue(uri, (TsonValue) value));

    /** Bind mode: the bound object, unwrapped -- see this class's own note on why. */
    static final ValueReaderFactory BIND = factory((uri, value) -> value);

    private static ValueReaderFactory factory(BiFunction<String, Object, Object> inScope) {
        return (name, definition, context) -> {
            if (!(definition.body() instanceof Scoped scoped)) {
                throw new IllegalArgumentException("'" + name + "' is not scoped-shaped: " + definition.body());
            }
            return new ScopedReader(EntryDisplayName.of(name, definition), scoped,
                    context.schema().entries().keySet(), context.readers(), context.foreign(), inScope);
        };
    }

    private static Map<String, List<String>> canonicalKeys(Scoped body) {
        Map<String, List<String>> byIdentity = new LinkedHashMap<>();
        body.schemas().ifPresent(schemas -> schemas.forEach((uri, types) ->
                byIdentity.put(TsonCanonicalIdentity.canonicalize(uri.toString()), types)));
        return byIdentity;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        return ctx.peek() instanceof SchemaRef ref ? readExtern(ctx, ref) : readLocal(ctx);
    }

    /**
     * A value naming a type in the governing namespace -- no directive, so the type-ref is the whole of what
     * the value says about itself, and it resolves exactly where any other type-ref in this schema does.
     */
    private Object readLocal(TsonReadContext ctx) {
        if (!body.admits(ScopeKind.LOCAL)) {
            return abandon(ctx, Diagnostic.Code.VALIDATION_ERROR,
                    "'" + displayName + "' takes a value from a foreign schema, so the value must open a scope "
                            + "with its own '!!schema' naming the schema its type comes from (§7.8)",
                    "a value prefixed by '!!schema'", "no '!!schema'");
        }
        Optional<String> typeRef = EventSkip.typeRefAhead(ctx);
        if (typeRef.isEmpty()) {
            return missingTypeRef(ctx);
        }
        String name = typeRef.get();
        if (!localNames.contains(name)) {
            return abandon(ctx, Diagnostic.Code.UNKNOWN_TYPE,
                    "'" + name + "' is not a type this schema declares or imports, and '" + displayName
                            + "' resolves a value's own type name there (§2.2.3)",
                    "a type declared by the governing schema", name);
        }
        return local.resolve(name).read(ctx);
    }

    /**
     * §7.8's scope push: the directive names the schema, the value's own type-ref names the type within it,
     * and the foreign schema's compiled reader validates the value in full.
     */
    private Object readExtern(TsonReadContext ctx, SchemaRef ref) {
        ctx.next(); // the directive, whose scope is this value and nothing after it
        if (!body.admits(ScopeKind.EXTERN)) {
            return abandon(ctx, Diagnostic.Code.VALIDATION_ERROR,
                    "'" + displayName + "' takes a type this schema declares or imports, so a value here cannot "
                            + "open a scope onto '" + ref.uri() + "' (§7.8)",
                    "a value carrying no '!!schema'", ref.uri());
        }
        String identity = TsonCanonicalIdentity.canonicalize(ref.uri());
        // Absent `schemas` is "any foreign schema"; present, it is a closed set, matched by canonical
        // identity so a pinned key and an unpinned directive are one schema (§2.2.1). The pin itself is the
        // loader's to verify, on the reference as written, exactly as for any other schema reference.
        if (!admittedSchemas.isEmpty() && !admittedSchemas.containsKey(identity)) {
            return abandon(ctx, Diagnostic.Code.VALIDATION_ERROR,
                    "'" + displayName + "' admits values from " + admittedSchemas.keySet() + ", and '"
                            + ref.uri() + "' is not one of them",
                    "one of " + admittedSchemas.keySet(), ref.uri());
        }
        Optional<String> typeRef = EventSkip.typeRefAhead(ctx);
        if (typeRef.isEmpty()) {
            return missingTypeRef(ctx);
        }
        String name = typeRef.get();
        List<String> admittedTypes = admittedSchemas.get(identity);
        if (admittedTypes != null && !admittedTypes.isEmpty() && !admittedTypes.contains(name)) {
            return abandon(ctx, Diagnostic.Code.VALIDATION_ERROR,
                    "'" + displayName + "' admits " + admittedTypes + " from '" + ref.uri() + "', and '" + name
                            + "' is not one of them",
                    "one of " + admittedTypes, name);
        }
        TsonCompiledSchema compiled = foreign.get(ref.uri(), ctx);
        if (compiled == null) {
            // Reported by the lookup, with the code that says whose problem it is. The value is skipped so
            // the containing record or array reads on, and only this value goes without a verdict.
            EventSkip.dataValue(ctx);
            return null;
        }
        TsonTypeReader<?> reader = compiled.find(name).orElse(null);
        if (reader == null) {
            return abandon(ctx, Diagnostic.Code.UNKNOWN_TYPE, compiled.unknownTypeMessage(name),
                    compiled.declaredTypeNames(), name);
        }
        // No location threading: the foreign reader offers its own declaration on entry like every other
        // reader, so a diagnostic from inside the pushed value already names the schema that judged it.
        return inScope.apply(ref.uri(), reader.read(ctx));
    }

    /** §7.8's "the discriminant is required": an open position has nothing to infer a type from. */
    private Object missingTypeRef(TsonReadContext ctx) {
        // VALIDATION_ERROR, not UNKNOWN_TYPE_REF: nothing here failed to resolve, so §8.1's resolver category
        // would be the wrong verdict. §7.8 states this one outright -- "a scoped value that opens a schema
        // scope but names no type is a validation error" -- and the same holds for the LOCAL cell, an open
        // position having nothing else to discriminate on either way.
        return abandon(ctx, Diagnostic.Code.VALIDATION_ERROR,
                "'" + displayName + "' is a scoped type -- the value names its own type, so it requires an "
                        + "explicit type annotation (!typeName)",
                "a type annotation naming the value's own type", "no type annotation");
    }

    /** Reports, discards the whole value (framing included -- nothing has consumed it), and yields nothing. */
    private Object abandon(TsonReadContext ctx, Diagnostic.Code code, String message, String expected,
            String actual) {
        ctx.report(code, message, expected, actual);
        EventSkip.dataValue(ctx);
        return null;
    }
}
