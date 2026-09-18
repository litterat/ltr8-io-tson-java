package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.diagnostics.RecordDiagnostics;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.FamilySelectors;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

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
 * reference flattening of both"). A family whose base is a template has minted entries for every member, and
 * §8.2 makes a minted name non-normative -- so an alias is the only name a document has for one.
 */
final class DispatchFactories {

    private DispatchFactories() {
    }

    /** {@code concrete} for a record with no subtypes, a dispatcher for every other record-family position. */
    static ValueReaderFactory over(ValueReaderFactory concrete) {
        return (name, definition, context) -> {
            RecordBody body = (RecordBody) definition.body();
            return switch (body.extension()) {
                // Linking refuses anything composed onto a FINAL record, so it has no subtype a tag could name.
                case FINAL -> concrete.create(name, definition, context);
                case OPEN -> definition.subtypes().isEmpty()
                        ? concrete.create(name, definition, context)
                        : new DispatchTagReader(name, displayName(name, definition, context),
                                context.admitting(List.of(name)), context.admitting(definition.subtypes()),
                                concrete.create(name, definition, context), context,
                                context.locationOf(name, definition), rules(name, definition, body, context));
                case ABSTRACT, SEALED -> family(name, displayName(name, definition, context), definition,
                        body.extension(), context, rules(name, definition, body, context));
            };
        };
    }

    private static String displayName(String name, TypeDefinition definition, ValueReaderContext context) {
        return EntryDisplayName.of(name, definition, context.schema().entries());
    }

    private static RecordDiagnostics rules(String name, TypeDefinition definition, RecordBody body,
                                           ValueReaderContext context) {
        return new RecordDiagnostics(displayName(name, definition, context),
                String.join(" | ", body.fields().stream().map(RecordField::name).toList()));
    }

    /**
     * An entry that declares type parameters. A <b>family base</b> -- a template whose held body carries
     * {@code extension} ({@code SPEC-FEEDBACK.md} #13) -- is a type by the only test that matters, a value
     * standing at it being a value of one of its instantiations, so it dispatches exactly as a closed base
     * does: ABSTRACT on {@code $type}, SEALED on the discriminators {@code template.discriminators} states on
     * the entry, which this module reads without ever parsing the held body's text. Reported under the
     * template's own name, the one the author wrote; a family base has no field list of its own, so the
     * closure rules carry none.
     *
     * <p>Any other template is not a type until it is applied ([TSON-SCHEMA] §5.10), and its body is held
     * unsubstituted text no reader could read, so it reaches {@link OpenTemplateReader} unexamined.
     */
    static final ValueReaderFactory TEMPLATE = (name, definition, context) -> {
        TemplateBody held = (TemplateBody) definition.body();
        return held.extension().isEmpty()
                ? new OpenTemplateReader(name, definition.parameters(), context.locationOf(name, definition))
                : family(name, name, definition, held.extension().get(), context, new RecordDiagnostics(name, ""));
    };

    /** An ABSTRACT or SEALED base, closed or a template: the family's dispatcher, with no concrete reading. */
    private static JsonTypeReader<?> family(String name, String displayName, TypeDefinition definition,
                                            RecordExtensionType extension, ValueReaderContext context,
                                            RecordDiagnostics rules) {
        JsonSchemaLocation location = context.locationOf(name, definition);
        Set<String> selfNames = context.admitting(List.of(name));
        return extension == RecordExtensionType.SEALED
                // The sealed reader takes its subtypes raw: it maps each member's pins to that member, so an
                // alias is not a second member. Where it compares a written tag it admits aliases (`deeper`).
                ? new DispatchMemberReader(selfNames, displayName,
                        FamilySelectors.of(definition, context.schema().entries()),
                        Set.copyOf(definition.subtypes()), context, location, rules)
                : new DispatchTagReader(name, displayName, selfNames, context.admitting(definition.subtypes()),
                        null, context, location, rules);
    }
}
