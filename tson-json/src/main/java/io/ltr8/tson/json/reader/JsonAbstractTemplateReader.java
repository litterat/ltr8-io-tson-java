package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.diagnostics.RecordDiagnostics;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.tree.JsonValue;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.List;
import java.util.Optional;

/**
 * The reader a <b>family base</b> compiles to in this encoding -- a template whose held body carries {@code
 * extension} ({@code SPEC-FEEDBACK.md} #13). A value here is a value of one of the template's
 * instantiations, selected by {@code $type}, so the reader dispatches and never reads a record of its own.
 *
 * <p><b>The peer of {@code tson-compiler}'s {@code AbstractTemplateReader}, and deliberately the same
 * reading.</b> [TSON-JSON] §9.4 holds both stacks to one verdict for one rule, so a family whose base is a
 * template reads here exactly as it does in TSON text: the tag is required, naming the base selects nothing,
 * and a name outside the family is not admissible. What differs is the tag's spelling -- {@code $type}
 * against {@code !name} -- which {@code RecordExtensionDiagnostics} spends only in {@code actual}.
 *
 * <p><b>ABSTRACT only, and the reason is a module boundary rather than a design choice.</b> A SEALED family
 * is selected by reading its discriminator fields, and for a template those live in the held body's text --
 * which {@code tson-compiler}'s {@code HeldBody} parses. This module deliberately depends on the schema
 * pipeline's <em>output</em> and never on its engine (see {@code module-info}), so it cannot reach that
 * derivation, and duplicating the walk here would put two opinions about which fields select a family on
 * either side of a module wall, where §9.4 makes drift a specification failure rather than untidiness. So a
 * SEALED template base still reaches {@link OpenTemplateReader} in this encoding, and the divergence is
 * stated here rather than hidden: it closes when the discriminator names are stated structurally on the
 * {@code template} constructor, where both stacks read them without parsing anything.
 */
public final class JsonAbstractTemplateReader implements JsonTypeReader<JsonValue> {

    private final JsonTypeReader<JsonValue> dispatcher;

    /**
     * The reader for {@code definition}, or empty where this encoding cannot dispatch it -- a template with
     * no {@code extension} at all, or a SEALED one, both of which the caller sends to {@link
     * OpenTemplateReader}.
     */
    public static Optional<JsonTypeReader<?>> of(String name, TypeDefinition definition,
                                                  ValueReaderContext context) {
        if (!(definition.body() instanceof TemplateBody held)
                || held.extension().orElse(null) != RecordExtensionType.ABSTRACT) {
            return Optional.empty();
        }
        return Optional.of(new JsonAbstractTemplateReader(name, definition, context));
    }

    private JsonAbstractTemplateReader(String name, TypeDefinition definition, ValueReaderContext context) {
        // Reported under the template's own name -- the one the author wrote, which is the whole point of the
        // template being the base rather than an entry derived from it. A family base has no field list of
        // its own, so the closure rules carry none: nothing here decodes a member.
        this.dispatcher = new TreeRecordAbstractReader(
                context.admitting(List.of(name)), name, context.admitting(definition.subtypes()),
                context.readers(), context.locationOf(name, definition),
                new RecordDiagnostics(name, ""));
    }

    @Override
    public JsonValue read(JsonReadContext ctx) {
        return dispatcher.read(ctx);
    }
}
