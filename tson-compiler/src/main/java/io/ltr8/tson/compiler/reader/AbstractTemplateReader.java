package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.schema.meta.FamilySelectors;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.List;
import java.util.Set;

/**
 * The reader a <b>family base</b> compiles to -- a template whose held body carries {@code extension}
 * ({@code SPEC-FEEDBACK.md} #13). A value here is a value of one of the template's instantiations, chosen by
 * a tag (ABSTRACT) or by reading the discriminator fields (SEALED), so the reader dispatches and never reads
 * a record of its own.
 *
 * <p><b>The peer of {@link OpenTemplateReader}, and the reason that one is now the narrower case.</b> "A
 * template is not a type" is a rule about <em>elimination</em>: a position typed {@code arr} would have to
 * read a value against an element type nothing has fixed, leaving a reader to infer arguments from the
 * payload -- ill-defined, since an empty array determines nothing and two instantiations accept one document.
 * None of that reaches a marked template: every member is a closed entry with its arguments already fixed, so
 * the existential is eliminated by dispatch. {@link OpenTemplateReader} keeps every template that has no such
 * dispatch -- a container, a constructor application, a reference template.
 *
 * <p><b>The template itself is the base, and nothing is minted for it.</b> So the name a diagnostic prints,
 * the name a binding map is keyed on, and the entry {@code subtypes} hangs off are all the one the author
 * wrote -- which is what lets {@code pet} bind to a host sealed type directly.
 *
 * <p><b>Both dispatchers are the ones a closed base uses</b> ({@link RecordDispatch}), so a family whose base
 * is a template and one whose base is a hand-written record read identically and report identically. The
 * selectors come from {@code FamilySelectors} either way -- a closed base declares them, and a template's are
 * recovered from its members at the base's own declared type -- which is the same derivation {@code
 * RecordExtension} checks the family against, since a check and a dispatch that disagreed about the selectors
 * would admit a family no reader could place.
 *
 * <p>{@link Subsumption.Applied} because the dispatcher it holds decides §7.2 at this position itself. The
 * marker changes nothing today -- {@code Subsumption.guard} runs past the template branch and never sees one
 * -- and is stated so that a later route to this reader cannot acquire a second dispatcher in front of it.
 */
public final class AbstractTemplateReader implements TsonTypeReader<Object>, Subsumption.Applied {

    private final TsonTypeReader<Object> dispatcher;

    @SuppressWarnings("unchecked")
    public AbstractTemplateReader(String name, TypeDefinition definition, ValueReaderContext context,
                                   TsonTypeReaderResolver readers) {
        TemplateBody held = (TemplateBody) definition.body();
        RecordExtensionType extension = held.extension().orElseThrow(() -> new IllegalStateException(
                "'" + name + "' is not a family base: only a template carrying `extension` dispatches, and "
                        + "OpenTemplateReader is what every other template compiles to (§5.10)"));
        // Reported under the template's own name, never the application its `source` records: a marked
        // template keeps the `source` of whatever it composed, and EntryDisplayName renders that in
        // preference to a name it takes for derived. The author wrote this one, which is the whole point of
        // the template being the base -- naming `pet_base<…>` would name something the schema never declares.
        Set<String> selfNames = Subsumption.admitting(List.of(name), context.namesMeaning());

        this.dispatcher = (TsonTypeReader<Object>) (extension == RecordExtensionType.SEALED
                // The selectors are the base's, at the types the base declares -- the one set known before a
                // member is selected, which is what the pins are compared in. Named on the entry and typed
                // from any member, and handed to the same dispatcher a closed base uses: one dispatch, two
                // sources for its inputs.
                ? new RecordMemberDispatchReader(selfNames, name,
                        FamilySelectors.of(definition, context.schema().entries()),
                        Set.copyOf(definition.subtypes()), context, readers)
                : new RecordTagDispatchReader(selfNames, name,
                        Subsumption.admitting(definition.subtypes(), context.namesMeaning()), readers));
    }

    @Override
    public Object read(TsonReadContext ctx) {
        return dispatcher.read(ctx);
    }
}
