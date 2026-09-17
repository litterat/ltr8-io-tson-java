package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.diagnostics.RecordDiagnostics;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.tree.JsonValue;
import io.ltr8.tson.schema.meta.FamilySelectors;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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
 * <p><b>Both readings, on the same terms a closed base gets them.</b> ABSTRACT dispatches on {@code $type};
 * SEALED reads the discriminator fields, which {@code template.discriminators} now states structurally on
 * the entry ({@code FamilySelectors}). That is what closed the one place these two encodings knowingly
 * differed: the selector names used to live only in the held body's <em>text</em>, which {@code
 * tson-compiler}'s {@code HeldBody} parses and this module -- depending on the schema pipeline's output and
 * never on its engine -- could not reach. Stating them on the entry means neither stack parses anything, and
 * §1.3's promise that a resolved-output consumer needs no template support holds literally.
 */
public final class TreeTemplateAbstractReader implements JsonTypeReader<JsonValue> {

    private final JsonTypeReader<JsonValue> dispatcher;

    /**
     * The reader for {@code definition}, or empty where it is no family base at all -- a template carrying no
     * {@code extension}, which the caller sends to {@link OpenTemplateReader}.
     */
    public static Optional<JsonTypeReader<?>> of(String name, TypeDefinition definition,
                                                  ValueReaderContext context) {
        if (!(definition.body() instanceof TemplateBody held) || held.extension().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TreeTemplateAbstractReader(name, definition, held.extension().get(), context));
    }

    private TreeTemplateAbstractReader(String name, TypeDefinition definition,
                                        RecordExtensionType extension, ValueReaderContext context) {
        // Reported under the template's own name -- the one the author wrote, which is the whole point of the
        // template being the base rather than an entry derived from it. A family base has no field list of
        // its own, so the closure rules carry none: nothing here decodes a member.
        RecordDiagnostics rules = new RecordDiagnostics(name, "");
        this.dispatcher = extension == RecordExtensionType.SEALED
                ? new TreeRecordSealedReader(context.admitting(List.of(name)), name,
                        FamilySelectors.of(definition, context.schema().entries()),
                        Set.copyOf(definition.subtypes()), context,
                        context.locationOf(name, definition), rules)
                : new TreeRecordAbstractReader(
                        context.admitting(List.of(name)), name, context.admitting(definition.subtypes()),
                        context.readers(), context.locationOf(name, definition), rules);
    }

    @Override
    public JsonValue read(JsonReadContext ctx) {
        return dispatcher.read(ctx);
    }
}
