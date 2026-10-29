package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.schema.meta.DeclaredType;
import io.ltr8.tson.schema.meta.EntryDisplayName;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * meta.tn's {@code declared_type} constructor, whose instance is core's {@code declared}: the open sum over the
 * governing closure, where the value names its own type and is read in full against it ([TSON-JSON] §8.5,
 * [TSON-SCHEMA] §7.8). It selects a reader and builds nothing, so one class serves every mode.
 *
 * <p><b>The value is an annotation object led by {@code $type}</b> ({@link TagMembers#lead}): inline when the
 * named type reads the value as a record, wrapper otherwise. Any other value -- a bare scalar, an array, an object
 * with no annotation member, one with no {@code $type} -- names no type, and is a validation error in every mode,
 * the position promising no type for it to be read as. A {@code $type} naming no type of the closure is an
 * unresolved reference, {@code UNKNOWN_TYPE}; a leading {@code $schema} is refused as at every value position
 * ({@link Tags}).
 *
 * <p><b>Wired at compile.</b> A {@code declared} entry belongs to one schema, so every name its governing
 * closure holds is resolved to a {@link Route} when the schema compiles, and every mode hands back what the
 * selected reader built.
 */
final class DispatchDeclaredReader implements JsonTypeReader<Object> {

    /** One factory for every mode: the reader builds nothing of its own. */
    static final ValueReaderFactory FACTORY = (name, definition, context) -> {
        if (!(definition instanceof DeclaredType)) {
            throw new IllegalArgumentException("'" + name + "' is not declared_type-shaped: " + definition);
        }
        return new DispatchDeclaredReader(EntryDisplayName.of(name, context.schema()), context,
                context.locationOf(name));
    };

    private static final String UNTYPED_EXPECTED = "an annotation object naming the value's type";

    /** How this position describes itself in the {@code $schema} refusal. */
    private static final String WHAT = "is a declared type, whose values name a type of the governing schema";

    private final String displayName;
    private final JsonSchemaLocation schemaLocation;

    /** Every name the governing closure holds, to where a value tagged with it goes. */
    private final Map<String, Route> closure;

    private DispatchDeclaredReader(String displayName, ValueReaderContext context, JsonSchemaLocation schemaLocation) {
        this.displayName = displayName;
        this.schemaLocation = schemaLocation;
        Map<String, Route> routes = new LinkedHashMap<>();
        for (String entry : context.schema().entries().keySet()) {
            routes.put(entry, Route.to(context.readers().resolve(entry)));
        }
        this.closure = Map.copyOf(routes);
    }

    @Override
    public Object read(JsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        if (!(ctx.peek() instanceof JsonEvent.ObjectStart)) {
            return abandon(ctx, Diagnostic.Code.VALIDATION_ERROR, untyped(), UNTYPED_EXPECTED,
                    JsonAtoms.describe(ctx.peek()));
        }
        TagMembers.Lead lead = TagMembers.lead(ctx);
        if (!lead.present()) {
            return abandon(ctx, Diagnostic.Code.VALIDATION_ERROR, untyped(), UNTYPED_EXPECTED,
                    "an object naming no type");
        }
        if (Tags.refusesScope(ctx, lead, displayName, WHAT)) {
            return null;
        }
        if (lead.type() == null) {
            return abandon(ctx, Diagnostic.Code.VALIDATION_ERROR,
                    "'" + displayName + "' is a declared type -- the value names its own type, so its annotation "
                            + "object requires a '$type' (§8.5)", "a '$type' naming the value's own type",
                    "no $type");
        }
        Route route = closure.get(lead.type());
        if (route == null) {
            if (!NameHygiene.refuses(ctx, lead.type())) {
                ctx.report(Diagnostic.Code.UNKNOWN_TYPE, "'" + lead.type() + "' is not a type this schema declares "
                        + "or imports, and '" + displayName + "' resolves a value's own type name there (§8.5)",
                        "a type declared by the governing schema", lead.type());
            }
            EventSkip.nextValue(ctx);
            return null;
        }
        return route.read(ctx, lead);
    }

    /**
     * The refusal of a value that is not an annotation object, whatever it is instead: §8.5 makes it a validation
     * error in every mode, and there is no permissive reading of it.
     */
    private String untyped() {
        return "'" + displayName + "' is a declared type -- the value names its own type, so it must be an "
                + "annotation object leading with '$type' (§8.5)";
    }

    /** Reports, discards the whole value, and yields nothing. */
    private static Object abandon(JsonReadContext ctx, Diagnostic.Code code, String message, String expected,
                                  String actual) {
        ctx.report(code, message, expected, actual);
        EventSkip.nextValue(ctx);
        return null;
    }
}
