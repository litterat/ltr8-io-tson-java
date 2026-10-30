package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.diagnostics.RecordDiagnostics;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.FamilySelectors;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Top;

import java.util.List;
import java.util.Set;

/**
 * Which reader a record-family position gets, decided once from its extension fact ([TSON-SCHEMA] §5.2) --
 * [TSON-JSON] §6.1.5's readings as separate readers rather than branches taken on every value.
 *
 * <p><b>A decorator over a mode's concrete record factory</b>, because only the concrete reading differs by
 * mode. Every other reading places the value and hands it on, building nothing, so the dispatchers are the
 * same in every mode:
 * <ul>
 *   <li>A record with <b>no subtypes</b> -- FINAL by construction, or OPEN in a loaded schema that cannot grow
 *       -- is the concrete reader and nothing else.</li>
 *   <li><b>OPEN with subtypes</b> is a {@link DispatchTagReader} in front of the concrete reader, which
 *       takes the untagged value.</li>
 *   <li><b>ABSTRACT</b> is a {@link DispatchTagReader} with no concrete reader behind it.</li>
 *   <li><b>SEALED</b> is a {@link DispatchMemberReader}, which selects on the discriminator members.</li>
 * </ul>
 *
 * <p>Both dispatchers compare a written tag against flattened names ([TSON-SCHEMA] §7.2 compares "after
 * alias flattening of both"). A family whose base is a template has minted entries for every member, and
 * §8.2 makes a minted name non-normative -- so an alias is the only name a document has for one.
 */
final class DispatchFactories {

    private DispatchFactories() {
    }

    /** {@code concrete} for a record with no subtypes, a dispatcher for every other record-family position. */
    static ValueReaderFactory over(ValueReaderFactory concrete) {
        return (name, definition, context) -> {
            RecordBody body = (RecordBody) definition;
            return switch (body.extension()) {
                // Linking refuses anything composed onto a FINAL record, so it has no subtype a tag could name.
                case FINAL -> concrete.create(name, definition, context);
                case OPEN -> context.subtypesOf(name, definition).isEmpty()
                        ? concrete.create(name, definition, context)
                        : new DispatchTagReader(name, displayName(name, definition, context),
                                context.admitting(List.of(name)), context.admitting(context.subtypesOf(name, definition)),
                                context.instantiableSubtypesOf(name, definition), concrete.create(name, definition, context),
                                context,
                                context.locationOf(name), rules(name, definition, body, context));
                case ABSTRACT -> family(name, displayName(name, definition, context), definition,
                        context, rules(name, definition, body, context));
            };
        };
    }

    private static String displayName(String name, Top definition, ValueReaderContext context) {
        return EntryDisplayName.of(name, context.schema());
    }

    private static RecordDiagnostics rules(String name, Top definition, RecordBody body,
                                           ValueReaderContext context) {
        return new RecordDiagnostics(displayName(name, definition, context),
                String.join(" | ", body.fields().keySet()));
    }

    /**
     * An entry that declares type parameters. A <b>family base</b> -- a record template (§5.10) -- is a type by
     * the only test that matters, a value standing at it being a value of one of its instantiations, so it
     * dispatches exactly as a closed base does: ABSTRACT on {@code $type}, SEALED on the discriminators the open
     * record states. Reported under the
     * template's own name, the one the author wrote; a family base has no field list of its own, so the
     * closure rules carry none.
     *
     * <p>Any other template is not a type until it is applied ([TSON-SCHEMA] §5.10), and its body is held
     * unsubstituted text no reader could read, so it reaches {@link OpenTemplateReader} unexamined.
     */
    static final ValueReaderFactory TEMPLATE = (name, definition, context) -> {
        return OpenEntry.isFamilyBase(definition)
                ? family(name, name, definition, context, new RecordDiagnostics(name, ""))
                : new OpenTemplateReader(name, OpenEntry.parameterNames(definition), context.locationOf(name));
    };

    /**
     * An ABSTRACT base, closed or a template: the family's dispatcher, with no concrete reading. Which
     * dispatcher is the body's own statement -- selectors named, or none and the tag decides.
     */
    private static JsonTypeReader<?> family(String name, String displayName, Top definition,
                                            ValueReaderContext context, RecordDiagnostics rules) {
        JsonSchemaLocation location = context.locationOf(name);
        Set<String> selfNames = context.admitting(List.of(name));
        return FamilySelectors.dispatchesOnMembers(definition)
                // The sealed reader takes its subtypes raw: it maps each member's pins to that member, so an
                // alias is not a second member. Where it compares a written tag it admits aliases (`deeper`).
                ? new DispatchMemberReader(selfNames, displayName,
                        FamilySelectors.of(definition),
                        Set.copyOf(context.subtypesOf(name, definition)), context, location, rules)
                : new DispatchTagReader(name, displayName, selfNames, context.admitting(context.subtypesOf(name,
                        definition)), context.instantiableSubtypesOf(name, definition),
                        null, context, location, rules);
    }
}
