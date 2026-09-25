package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonCompiledSchema;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.ScopeKind;
import io.ltr8.tson.schema.meta.Scoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * meta.tn's {@code scoped} constructor: the open sum, where the value names its own type and the instance names
 * the namespaces that name may be drawn from ([TSON-JSON] §8.5, [TSON-SCHEMA] §7.8). One reader for every
 * instance -- core's {@code declared}, {@code extern} and {@code dynamic}, and every narrowing {@code extern_of}
 * or {@code extern_type} materialises -- because what separates them is two constraint values and not a shape.
 * It selects a reader and builds nothing, so one class serves every mode.
 *
 * <p><b>The leading members pick the cell</b> ({@link ReservedMembers#lead}). An annotation object leading with
 * {@code $schema} is EXTERN; one leading with {@code $type} alone is LOCAL; any other value -- a bare scalar,
 * an array, an object with no reserved member -- names no type, and is a validation error in every mode, the
 * position promising no type for it to be read as. A cell the instance's {@code scope} does not hold refuses the
 * value it would have taken.
 *
 * <p><b>LOCAL is wired at compile and EXTERN is not.</b> A {@code scoped} entry belongs to one schema, so every
 * name its governing namespace holds is resolved to a {@link Route} when the schema compiles. Which foreign
 * schema an EXTERN value names is the document's choice, so it is looked up as the value arrives, through
 * {@link ForeignSchemas} -- the compiling registry's own lookup, so a schema nothing would supply is one of the
 * five {@code SCHEMA_*} codes and never a verdict.
 *
 * <p><b>Opening the scope consumes {@code $schema}</b> ({@link JsonReadContext#consumeLeadingMember}). What is
 * left is an annotation object led by {@code $type} in the foreign namespace, which the foreign type's reader
 * reads as it reads any tagged value of its own; everything below it resolves in that schema by construction,
 * and the scope pops by returning. A {@code $schema} anywhere inside the value is refused by the reader that
 * meets it, exactly as at any position that is not scoped.
 *
 * <p><b>Every mode hands back what the selected reader built</b>, as a tagged value at a record or choice
 * position does: a JSON tree holds the annotated value without the apparatus that selected its type.
 */
final class DispatchScopedReader implements JsonTypeReader<Object> {

    /** One factory for every mode: the reader builds nothing of its own. */
    static final ValueReaderFactory FACTORY = (name, definition, context) -> {
        if (!(definition.body() instanceof Scoped scoped)) {
            throw new IllegalArgumentException("'" + name + "' is not scoped-shaped: " + definition.body());
        }
        return new DispatchScopedReader(EntryDisplayName.of(name, definition, context.schema().entries()), scoped,
                context, context.locationOf(name, definition));
    };

    private static final String UNTYPED_EXPECTED = "an annotation object naming the value's type";

    private final String displayName;
    private final Scoped body;
    private final JsonSchemaLocation schemaLocation;
    private final ForeignSchemas foreign;

    /** Every name the governing namespace holds, to where a LOCAL value tagged with it goes. */
    private final Map<String, Route> local;

    /** {@code schemas}, keyed by canonical identity ([TSON-DATA] §2.2.1) -- an empty list means "any type". */
    private final Map<String, List<String>> admittedSchemas;

    private DispatchScopedReader(String displayName, Scoped body, ValueReaderContext context,
                                 JsonSchemaLocation schemaLocation) {
        this.displayName = displayName;
        this.body = body;
        this.schemaLocation = schemaLocation;
        this.foreign = context.foreign();
        Map<String, Route> routes = new LinkedHashMap<>();
        if (body.admits(ScopeKind.LOCAL)) {
            for (String entry : context.schema().entries().keySet()) {
                routes.put(entry, Route.to(context.readers().resolve(entry)));
            }
        }
        this.local = Map.copyOf(routes);
        Map<String, List<String>> byIdentity = new LinkedHashMap<>();
        body.schemas().ifPresent(schemas -> schemas.forEach((uri, types) ->
                byIdentity.put(CanonicalIdentity.canonicalize(uri.toString()), types)));
        this.admittedSchemas = Map.copyOf(byIdentity);
    }

    @Override
    public Object read(JsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        if (!(ctx.peek() instanceof JsonEvent.ObjectStart)) {
            return abandon(ctx, Diagnostic.Code.VALIDATION_ERROR, untyped(), UNTYPED_EXPECTED,
                    JsonAtoms.describe(ctx.peek()));
        }
        ReservedMembers.Lead lead = ReservedMembers.lead(ctx);
        if (!lead.present()) {
            return abandon(ctx, Diagnostic.Code.VALIDATION_ERROR, untyped(), UNTYPED_EXPECTED,
                    "an object naming no type");
        }
        return lead.schema() ? readExtern(ctx, lead) : readLocal(ctx, lead);
    }

    /** A value naming a type in the governing namespace: {@code $type} alone, resolved as at any other position. */
    private Object readLocal(JsonReadContext ctx, ReservedMembers.Lead lead) {
        if (!body.admits(ScopeKind.LOCAL)) {
            return abandon(ctx, Diagnostic.Code.VALIDATION_ERROR,
                    "'" + displayName + "' takes a value from a foreign schema, so the value must lead with "
                            + "'$schema' naming the schema its type comes from (§8.5)",
                    "an annotation object leading with '$schema'", "no $schema");
        }
        if (lead.type() == null) {
            return missingType(ctx);
        }
        Route route = local.get(lead.type());
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
     * §8.5's EXTERN cell: {@code $schema} names the schema, {@code $type} names the type within it, and the
     * foreign schema's compiled reader validates the value in full.
     */
    private Object readExtern(JsonReadContext ctx, ReservedMembers.Lead lead) {
        String uri = lead.schemaRef();
        if (!body.admits(ScopeKind.EXTERN)) {
            return abandon(ctx, Diagnostic.Code.VALIDATION_ERROR,
                    "'" + displayName + "' takes a type this schema declares or imports, so a value here cannot "
                            + "open a scope" + (uri == null ? "" : " onto '" + uri + "'") + " (§8.5)",
                    "an annotation object carrying no '$schema'", ReservedMembers.SCHEMA);
        }
        if (uri == null) {
            ctx.field(ReservedMembers.SCHEMA).report(Diagnostic.Code.TYPE_MISMATCH,
                    "'$schema' holds a schema reference, which is a string (§3.2)", "a string", "not a string");
            EventSkip.nextValue(ctx);
            return null;
        }
        String identity = CanonicalIdentity.canonicalize(uri);
        // Absent `schemas` is "any foreign schema"; present, it is a closed set, matched by canonical identity so
        // a pinned key and an unpinned reference are one schema ([TSON-DATA] §2.2.1).
        if (!admittedSchemas.isEmpty() && !admittedSchemas.containsKey(identity)) {
            return abandon(ctx, Diagnostic.Code.VALIDATION_ERROR,
                    "'" + displayName + "' admits values from " + admittedSchemas.keySet() + ", and '" + uri
                            + "' is not one of them", "one of " + admittedSchemas.keySet(), uri);
        }
        if (lead.type() == null) {
            return missingType(ctx);
        }
        List<String> admittedTypes = admittedSchemas.get(identity);
        if (admittedTypes != null && !admittedTypes.isEmpty() && !admittedTypes.contains(lead.type())) {
            return abandon(ctx, Diagnostic.Code.VALIDATION_ERROR,
                    "'" + displayName + "' admits " + admittedTypes + " from '" + uri + "', and '" + lead.type()
                            + "' is not one of them", "one of " + admittedTypes, lead.type());
        }
        JsonCompiledSchema compiled = foreign.get(uri, ctx);
        if (compiled == null) {
            // Reported by the lookup, with the code that says whose problem it is. Only this value goes without a
            // verdict; the containing record or array reads on.
            EventSkip.nextValue(ctx);
            return null;
        }
        JsonTypeReader<?> reader = compiled.find(lead.type()).orElse(null);
        if (reader == null) {
            return abandon(ctx, Diagnostic.Code.UNKNOWN_TYPE, compiled.unknownTypeMessage(lead.type()),
                    compiled.declaredTypeNames(), lead.type());
        }
        JsonReadContext.consumeLeadingMember(ctx);
        // No location threading: the foreign reader offers its own declaration on entry like every other reader,
        // so a diagnostic from inside the pushed value names the schema that judged it.
        return Route.to(reader).read(ctx, lead.withoutSchema());
    }

    /** §8.5: a value at a scoped position names its own type, and an annotation object with no `$type` does not. */
    private Object missingType(JsonReadContext ctx) {
        return abandon(ctx, Diagnostic.Code.VALIDATION_ERROR,
                "'" + displayName + "' is a scoped type -- the value names its own type, so its annotation object "
                        + "requires a '$type' (§8.5)", "a '$type' naming the value's own type", "no $type");
    }

    /**
     * The refusal of a value that is not an annotation object, whatever it is instead: §8.5 makes it a validation
     * error in every mode, and there is no permissive reading of it.
     */
    private String untyped() {
        return "'" + displayName + "' is a scoped type -- the value names its own type, so it must be an annotation "
                + "object leading with '$type' (§8.5)";
    }

    /** Reports, discards the whole value, and yields nothing. */
    private static Object abandon(JsonReadContext ctx, Diagnostic.Code code, String message, String expected,
                                  String actual) {
        ctx.report(code, message, expected, actual);
        EventSkip.nextValue(ctx);
        return null;
    }
}
