package io.ltr8.tson.json.reader;

import io.ltr8.bind.DataClass;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.JsonCompiledSchema;
import io.ltr8.tson.schema.ExternAdmission;
import io.ltr8.tson.schema.ExternTarget;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.Extern;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The position an extern types ([TSON-SCHEMA] §5.13): the boundary between the scope that holds a value and the
 * foreign schema's, which the value's body is read in (§7.9; [TSON-JSON] §8.6). It selects how the value crosses and
 * builds nothing, so one class serves every mode.
 *
 * <p>The {@code $type} leading the object, or its wrapper, is the enclosing scope's: it is judged here, against this
 * namespace, before the value crosses -- a name of this scope reaching the target, a subtype or a variant of it, by
 * identity ({@link io.ltr8.tson.schema.ExternAdmission}). A foreign reader reads the rest as that entry: the inline
 * form's object without its {@code $type}, the wrapper's {@code $value} as it stands. The wrapper's own members are
 * apparatus of the enclosing scope and are judged here, and so is a {@code $type} leading its {@code $value}, refused
 * before the value crosses ([TSON-JSON] §3.3): the value's one type name is the boundary's.
 *
 * <p><b>An extern of a map is a map-typed position</b> ([TSON-JSON] §3.3): its object's members are keys, a leading
 * {@code $type} among them, so no annotation object is recognised and the value always crosses as written. A
 * dispatcher that matched its tag hands it a wrapper, whose {@code $value} crosses.
 */
public final class ExternReader implements JsonTypeReader<Object>, ExactReader {

    /**
     * How a value crosses through one extern: the foreign entry's reader, which reads the value untagged as that
     * entry; the scope it is read in; whether the entry is abstract with no selectors, where a value must name a
     * subtype of it; whether it reads an object as a record, where the annotation members may stand inline; and
     * whether it is map-typed, where a wrapper's {@code $value} holds keys (§3.3).
     */
    record Crossing(JsonTypeReader<?> reader, ForeignScope scope, boolean namesASubtype, boolean record,
                    boolean keyed) {
    }

    /**
     * Where a compile obtains the compiled schema a foreign value is read in, in its own mode: the schema an extern
     * names, or the one declaring an entry a tag reaches at an extern position.
     */
    public interface ForeignSchemas {

        JsonCompiledSchema compiled(TsonLinkedSchema linked);

        /** The schema of canonical identity {@code identity}, compiled; empty where this compile cannot obtain it. */
        Optional<JsonCompiledSchema> compiled(String identity);
    }

    private final String name;
    private final JsonSchemaLocation location;
    private final Crossing own;
    private final Map<String, Crossing> admitted;
    private final String narrower;
    private final String admissible;
    private final boolean mapTyped;
    private final ScopeCrossing crossing;
    private final TsonLinkedSchema outer;

    /**
     * The boundary at a position typed by the extern {@code name}: every name of {@code context}'s namespace that
     * reaches, past its alias chain and its externs, the entry {@code name} lands on or one it admits, each with the
     * foreign reader it crosses through, obtained from {@code foreign}.
     */
    public static JsonTypeReader<?> of(String name, ValueReaderContext context, ForeignSchemas foreign,
                                       ScopeCrossing crossing) {
        TsonLinkedSchema linked = context.linked();
        ExternAdmission admission = ExternAdmission.of(linked, name);
        ExternTarget.Landing landing = admission.landing();
        Crossing own = crossing(linked, name, landing, foreign);
        Map<String, Crossing> admitted = new LinkedHashMap<>();
        List<String> narrower = new ArrayList<>();
        Map<String, Crossing> byEntry = new HashMap<>(Map.of(name, own));
        admission.admitted().forEach((written, reach) -> {
            String terminal = reach.terminal();
            if (reach.through().isPresent()) {
                admitted.put(written, byEntry.computeIfAbsent(terminal,
                        extern -> crossing(linked, extern, reach.through().get(), foreign)));
            } else {
                // A name of this scope that is no extern: the target itself says no more than the position does,
                // and anything narrower is read where the position's foreign schema reads it.
                admitted.put(written, reach.same() ? own : byEntry.computeIfAbsent(terminal,
                        entry -> imported(linked, name, landing, reach, foreign)));
            }
            // What a message offers is a tag that is read: an abstract record naming no selectors would refuse in turn.
            if (!reach.same() && !ExternAdmission.namesASubtype(reach.reached())) {
                narrower.add(written);
            }
        });
        Top target = landing.definition();
        return new ExternReader(name, context, own, admitted, narrower, TagMembers.keyed(target), crossing);
    }

    private static Crossing crossing(TsonLinkedSchema linked, String extern, ExternTarget.Landing landing,
                                     ForeignSchemas foreign) {
        ExternTarget target = linked.externs().get(extern);
        return new Crossing(foreign.compiled(target.foreign()).get(target.type()),
                new ForeignScope(extern, linked.declaringIdentity(extern), target.schema()),
                ExternAdmission.namesASubtype(landing.definition()), readsARecord(landing.definition()),
                TagMembers.keyed(landing.definition()));
    }

    /**
     * The crossing at the extern {@code position} for an admitted name of this namespace that is no extern and reaches
     * a subtype or a variant of its target: read in the position's foreign schema, or in the namespace its target lands
     * in, where either holds that entry, else in the schema declaring it ([TSON-SCHEMA] §7.9).
     */
    private static Crossing imported(TsonLinkedSchema linked, String position, ExternTarget.Landing landing,
                                     ExternAdmission.Admitted reach, ForeignSchemas foreign) {
        String entry = reach.entry();
        String declaredBy = reach.declaringSchema();
        String through = linked.declaringIdentity(position);
        boolean namesASubtype = ExternAdmission.namesASubtype(reach.reached());
        boolean record = readsARecord(reach.reached());
        boolean keyed = TagMembers.keyed(reach.reached());
        for (TsonLinkedSchema in : List.of(linked.externs().get(position).foreign(), landing.in())) {
            if (in.schema().entries().containsKey(entry) && in.declaringIdentity(entry).equals(declaredBy)) {
                return new Crossing(foreign.compiled(in).get(entry), new ForeignScope(position, through,
                        in.identity()), namesASubtype, record, keyed);
            }
        }
        JsonTypeReader<?> reader = foreign.compiled(declaredBy).<JsonTypeReader<?>>map(compiled -> compiled.get(entry))
                .orElseGet(() -> ctx -> {
                    ctx.report(Diagnostic.Code.NOT_IMPLEMENTED, "'" + entry + "' reaches '" + reach.identity()
                            + "', which a compile with no registry cannot read in its own schema's scope "
                            + "([TSON-SCHEMA] §7.9)", "a schema this compile can obtain", entry);
                    EventSkip.nextValue(ctx);
                    return null;
                });
        return new Crossing(reader, new ForeignScope(position, through, declaredBy), namesASubtype, record, keyed);
    }

    /** Whether a value of {@code definition} is a record, whose annotation members may stand inline (§3.3). */
    private static boolean readsARecord(Top definition) {
        return definition instanceof RecordBody record && !record.isOpen();
    }

    private ExternReader(String name, ValueReaderContext context, Crossing own, Map<String, Crossing> admitted,
                         List<String> narrower, boolean mapTyped, ScopeCrossing crossing) {
        this(name, context.locationOf(name), own, Map.copyOf(admitted), String.join(" | ", admitted.keySet()),
                String.join(" | ", narrower), mapTyped, crossing, context.linked());
    }

    private ExternReader(String name, JsonSchemaLocation location, Crossing own, Map<String, Crossing> admitted,
                         String admissible, String narrower, boolean mapTyped, ScopeCrossing crossing,
                         TsonLinkedSchema outer) {
        this.name = name;
        this.location = location;
        this.own = own;
        this.admitted = admitted;
        this.admissible = admissible;
        this.narrower = narrower;
        this.mapTyped = mapTyped;
        this.crossing = crossing;
        this.outer = outer;
    }

    /**
     * This boundary with the target's own reader bound to {@code target} ({@link BindTargets#to}), so a component
     * declaring something more specific than the target's natural reading receives it. A name reaching a subtype or a
     * variant crosses to that entry's reader as it is, as a dispatcher's routes are.
     */
    JsonTypeReader<?> boundTo(DataClass target, String what, String where, List<String> mismatches) {
        Crossing bound = new Crossing(BindTargets.to(own.reader(), target, what, where, mismatches), own.scope(),
                own.namesASubtype(), own.record(), own.keyed());
        Map<String, Crossing> rebound = new LinkedHashMap<>();
        admitted.forEach((written, through) -> rebound.put(written, through == own ? bound : through));
        return new ExternReader(name, location, bound, Map.copyOf(rebound), admissible, narrower, mapTyped, crossing,
                outer);
    }

    @Override
    public Object read(JsonReadContext ctx) {
        ctx = ctx.underDeclaration(location);
        if (!mapTyped && ctx.peek() instanceof JsonEvent.ObjectStart) {
            TagMembers.Lead lead = TagMembers.lead(ctx);
            if (lead.present()) {
                return tagged(ctx, lead);
            }
        }
        if (own.namesASubtype()) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "'" + name + "' takes a value that names its type -- "
                    + abstractNamed(name), narrower.isEmpty() ? "no tag this scope can write" : narrower,
                    "(no tag)");
            EventSkip.nextValue(ctx);
            return null;
        }
        return crossing.read(ctx, own.scope(), own.reader(), false);
    }

    /** Reached by a dispatcher that matched this extern's name: the whole annotation object, its tag judged again. */
    @Override
    public Object readExact(JsonReadContext ctx, JsonTypeReader<?> wrapped) {
        return mapTyped ? TagMembers.readWrapped(ctx, this, true) : read(ctx);
    }

    @Override
    public boolean takesWrapper() {
        return true;
    }

    private Object tagged(JsonReadContext ctx, TagMembers.Lead lead) {
        if (Tags.refusesScope(ctx, lead, name, "is " + describe() + ", whose value is read in the scope its schema "
                + "states and never one the data names (§8.6)")) {
            return null;
        }
        if (lead.type() == null) {
            if (lead.typed()) {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH, "this object leads with this encoding's annotation members "
                        + "but no '$type' naming a type (§3.3)", "a '$type' member holding a type name", "no $type");
            } else {
                ctx.field(TagMembers.VALUE).report(Diagnostic.Code.UNRECOGNIZED_FIELD, "'$value' belongs to an "
                                + "annotation object in wrapper form, which leads with '$type' naming the value's type "
                                + "(§3.3)", "'$value' straight after a leading '$type'", TagMembers.VALUE);
            }
            EventSkip.nextValue(ctx);
            return null;
        }
        String tag = lead.type();
        Crossing through = admitted.get(tag);
        if (through == null) {
            // At the value and not at `/$type`, as every tag refusal here is: §9.4 holds both encodings to one
            // pointer for a rule they share, and TSON's tag has no pointer step of its own.
            if (!NameHygiene.refuses(ctx, tag)) {
                boolean unresolved = !outer.schema().entries().containsKey(tag);
                ctx.report(unresolved ? Diagnostic.Code.UNKNOWN_TYPE_REF : Diagnostic.Code.TYPE_MISMATCH,
                        refusal(tag, unresolved), admissible, tag);
            }
            EventSkip.nextValue(ctx);
            return null;
        }
        if (through.namesASubtype()) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "a tag naming '" + tag + "' selects nothing -- "
                    + abstractNamed(tag), narrower.isEmpty() ? "no tag this scope can write" : narrower, tag);
            EventSkip.nextValue(ctx);
            return null;
        }
        if (lead.wrapper() || !through.record()) {
            return TagMembers.readWrapped(ctx, inner -> crossing.read(inner, through.scope(), through.reader(),
                    false), through.keyed());
        }
        return crossing.read(ctx, through.scope(), through.reader(), true);
    }

    /** A tag this boundary does not admit: unresolved in this scope, or reaching a type the target does not admit. */
    private String refusal(String tag, boolean unresolved) {
        String admits = "a tag is admitted where it reaches the target, a subtype of it, or a variant of it ("
                + admissible + ") ([TSON-SCHEMA] §7.9)";
        return unresolved
                ? "'" + tag + "' names no type of the enclosing scope, and a tag at a '" + name + "' position, which "
                        + "is " + describe() + ", is read there: the foreign schema's own names are not, and " + admits
                : "'" + tag + "' is not admissible at a '" + name + "' position, which is " + describe()
                        + ": the tag is read in the enclosing scope, where '" + tag + "' reaches another type, and "
                        + admits;
    }

    private String abstractNamed(String written) {
        return "'" + written + "' reaches an abstract record naming no discriminators, which has no direct "
                + "instances, so a value here names a subtype of it, by a name of the enclosing scope, "
                + (narrower.isEmpty() ? "and this scope has none" : "one of (" + narrower + ")")
                + " ([TSON-SCHEMA] §7.2, §7.9)";
    }

    private String describe() {
        return outer.schema().entries().get(name) instanceof Extern extern
                ? "an extern of '" + extern.type() + "' in '" + extern.schema() + "'"
                : "an extern";
    }
}
