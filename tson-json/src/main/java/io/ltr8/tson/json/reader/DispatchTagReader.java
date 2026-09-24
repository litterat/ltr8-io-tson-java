package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.diagnostics.RecordDiagnostics;
import io.ltr8.tson.base.diagnostics.RecordExtensionDiagnostics;
import io.ltr8.tson.base.diagnostics.Refusal;
import io.ltr8.tson.base.diagnostics.SubsumptionDiagnostics;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A record-family position placed by {@code $type} alone: [TSON-JSON] §6.1.5's subsumption reading. It
 * selects a reader and builds nothing, so one class serves every mode.
 *
 * <p>Two positions take it, told apart by whether an untagged value has somewhere to go:
 * <ul>
 *   <li><b>An OPEN record with subtypes</b> -- untagged, the value is the record itself and goes to its
 *       own concrete reader ({@link #untagged}); a tag restating the record goes there too, and one naming a
 *       subtype goes to that subtype.</li>
 *   <li><b>An ABSTRACT base</b>, closed or a family-base template -- there are no direct instances, so the
 *       tag is REQUIRED and the base itself is not admissible. The failure lands before the object's shape
 *       is consulted, which is the same refusal TSON text gives a missing {@code !variant}.</li>
 * </ul>
 *
 * <p><b>Every name it admits was resolved to a reader when the schema compiled.</b> A base's {@code
 * subtypes} is its whole family, not only its children, so a tag naming a type any number of levels down
 * reaches that type's reader in one step, and every alias meaning one of them ([TSON-SCHEMA] §7.2's "after
 * reference flattening of both") reaches the same reader.
 *
 * <p><b>It decides from the leading members and reads no further</b> ({@link ReservedMembers#lead}): §3.3
 * puts {@code $type} first, after a {@code $schema} where one is present, so the selector is known before any
 * of the object's own members, and the reader it selects reads the object once, from the start.
 */
final class DispatchTagReader implements JsonTypeReader<Object>, ExactReader {

    private final String name;
    private final String displayName;

    /** Every written name that means this base: its own, and every alias whose chain ends at it. */
    private final Set<String> selfNames;

    /** Every written name this position admits, to where a value tagged with it goes. */
    private final Map<String, Route> routes;

    /** Where an untagged value goes -- the record's own concrete reader, or null where the tag is REQUIRED. */
    private final JsonTypeReader<?> untagged;

    /** The subtype names, for the {@code expected} of a refusal. */
    private final Set<String> subtypes;

    private final JsonSchemaLocation schemaLocation;
    private final RecordDiagnostics rules;
    private final RecordExtensionDiagnostics extension;
    private final SubsumptionDiagnostics subsumption;

    /**
     * @param untagged the OPEN record's own concrete reader, or null for an ABSTRACT base
     * @param subtypes every name of the family below the base, aliases included
     */
    DispatchTagReader(String name, String displayName, Set<String> selfNames, Set<String> subtypes,
                            JsonTypeReader<?> untagged, ValueReaderContext context,
                            JsonSchemaLocation schemaLocation, RecordDiagnostics rules) {
        this.name = name;
        this.displayName = displayName;
        this.selfNames = Set.copyOf(selfNames);
        this.subtypes = Set.copyOf(subtypes);
        this.untagged = untagged;
        this.schemaLocation = schemaLocation;
        this.rules = rules;
        this.extension = new RecordExtensionDiagnostics(displayName, String.join(" | ", subtypes));
        this.subsumption = new SubsumptionDiagnostics(displayName);
        Map<String, Route> table = new LinkedHashMap<>();
        if (untagged != null) {
            // A tag restating the record reads it exactly as an untagged value does; wrapped, its `$value` is
            // read here again, since the value inside may name a subtype of its own.
            Route self = new Route(this, untagged);
            selfNames.forEach(written -> table.put(written, self));
        }
        for (String subtype : subtypes) {
            table.putIfAbsent(subtype, Route.to(context.readers().resolve(subtype)));
        }
        this.routes = Map.copyOf(table);
    }

    /** The OPEN record's own concrete reader, or null for an ABSTRACT base. */
    JsonTypeReader<?> untagged() {
        return untagged;
    }

    /** Where a value tagged {@code written} goes from here, or null where this position does not admit it. */
    Route route(String written) {
        return routes.get(written);
    }

    @Override
    public Object read(JsonReadContext ctx) {
        ctx = ctx.inRecord(schemaLocation);
        if (!(ctx.peek() instanceof JsonEvent.ObjectStart)) {
            if (untagged != null) {
                return untagged.read(ctx);
            }
            JsonEvent found = ctx.next();
            ctx.report(rules.notARecord(JsonAtoms.describe(found)));
            EventSkip.value(ctx, found);
            return null;
        }
        return dispatch(ctx, ReservedMembers.lead(ctx));
    }

    /** Reached by a tag naming this base from an enclosing position: placed again, from the leading members. */
    @Override
    public Object readExact(JsonReadContext ctx, JsonTypeReader<?> wrapped) {
        return read(ctx);
    }

    private Object dispatch(JsonReadContext ctx, ReservedMembers.Lead lead) {
        if (untagged != null && (lead.type() == null || !lead.wrapper() && selfNames.contains(lead.type()))) {
            // Untagged, an inline restatement, or leading reserved members naming no type: all of it is the
            // record's own reader's to judge, on exactly the terms a record without subtypes judges it. A
            // restating wrapper takes its route instead, whose `$value` may name a subtype of its own.
            return untagged instanceof ExactReader exact ? exact.readExact(ctx, this) : untagged.read(ctx);
        }
        if (Tags.refusesScope(ctx, lead, displayName, Tags.RECORD)) {
            return null;
        }
        if (lead.type() == null) {
            // Before the members: §6.1.5 is explicit that nothing about the object's shape is consulted, so
            // an abstract position with no tag fails whatever it holds.
            return refuse(ctx, extension.tagRequired(ReservedMembers.TYPE));
        }
        Route route = routes.get(lead.type());
        if (route == null) {
            // Located at the value and not at `/$type`, though the member is right there. §9.4 holds both
            // encodings to one pointer for a rule, and TSON's tag is an annotation with no pointer step of its
            // own -- so a rule they share can only be located where they both have a location.
            if (!NameHygiene.refuses(ctx, lead.type())) {
                ctx.report(untagged != null ? subsumption.notAdmissible(lead.type(), admissible())
                        // The base itself is not admissible here, which is the whole of what ABSTRACT means --
                        // where a concrete position would take a tag naming it as a redundant restatement.
                        : selfNames.contains(lead.type()) ? extension.tagNamesTheBase(ReservedMembers.TYPE)
                        : extension.notASubtype(ReservedMembers.TYPE, lead.type()));
            }
            EventSkip.nextValue(ctx);
            return null;
        }
        return route.read(ctx, lead);
    }

    /** What a {@code $type} may name at an OPEN position, for a diagnostic's machine-readable {@code expected}. */
    private String admissible() {
        return name + " | " + String.join(" | ", subtypes);
    }

    private static Object refuse(JsonReadContext ctx, Refusal refusal) {
        ctx.report(refusal);
        EventSkip.nextValue(ctx);
        return null;
    }
}
