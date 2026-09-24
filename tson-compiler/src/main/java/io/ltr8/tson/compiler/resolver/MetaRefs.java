package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The {@code schema.meta} reference walk: every {@link TypeRef} an entry holds, rewritten or merely visited.
 *
 * <p><b>One walk, four callers, and none of them is about templates.</b> {@code TemplateMaterialiser} closes
 * an application through it,
 * {@code SyntheticMerge} renames onto a merged entry (§8.2), and {@code TemplateRegularity} uses it as a
 * visitor by returning each reference unchanged. Which body shape carries which references is a fact about
 * the value model, not about any of those four, so it is stated once here.
 *
 * <p><b>Visiting is rewriting with the identity function</b>, deliberately: a reader that walked bodies
 * separately would be a second list of shapes to keep in step, and the one that fell behind would silently
 * skip a reference rather than fail.
 */
final class MetaRefs {

    private MetaRefs() {
    }

    /**
     * Every {@link TypeRef} a definition holds, mapped -- {@code source}, and whatever its body carries.
     *
     * <p>{@code type_definition.supertypes} is a name list rather than a type-ref channel and is not
     * covered: it is the derived transitive index, computed once every parent is a type, so a name there
     * denotes a declared or an <em>instantiation</em> entry and never a synthetic one. The body's own
     * {@code record.supertypes} <em>is</em> a reference channel and is mapped with the rest.
     */
    static TypeDefinition mapRefs(TypeDefinition definition, UnaryOperator<TypeRef> map) {
        return mapRefs(definition, map, true, true);
    }

    /**
     * The same, leaving a {@link RecordBody}'s {@code supertypes} exactly as written.
     *
     * <p>{@code TemplateMaterialiser} is the caller, and a composition operand is why: an application there
     * denotes no entry -- its fields are absorbed by value -- so the channel keeps it as the record of what
     * was applied, and closing it would mint the entry that path exists to avoid.
     *
     * <p><b>Skipping the call is the point, not discarding its result.</b> Closing an application publishes
     * the entry as a side effect, so a walk that maps this channel has already minted whatever the caller
     * then does with the rewritten reference.
     */
    static TypeDefinition mapRefsKeepingRecordSupertypes(TypeDefinition definition,
            UnaryOperator<TypeRef> map) {
        return mapRefs(definition, map, false, false);
    }

    /**
     * {@code source} is <b>provenance, not a reference to flatten</b>, and this walk leaves it alone.
     *
     * <p>§8.2 draws that line itself -- "what is canonicalised is identity, not provenance" -- and the
     * omission only started to matter once a declaration could own an instantiation's entry (§8.2).
     * {@code TemplateMaterialiser}'s pass walks the <em>declared</em> entries, minted ones living in the map
     * it returns, so before that a declared entry's {@code source} was always a bare
     * name -- a constructor, a refinement source -- where closing is a no-op. A declaration that is its own
     * instantiation records the application there, and mapping it would close that application to the entry
     * it denotes, which is the declaration: {@code bx}'s {@code source} became {@code bx}, arguments and all
     * stripped, and every index derived from those arguments emptied.
     */
    private static TypeDefinition mapRefs(TypeDefinition definition, UnaryOperator<TypeRef> map,
            boolean mapRecordSupertypes, boolean mapSource) {
        Optional<TypeRef> source = mapSource ? definition.source().map(map) : definition.source();
        return new TypeDefinition(source, definition.kind(),
                definition.supertypes(), definition.subtypes(),
                mapBodyRefs(definition.body(), map, mapRecordSupertypes), definition.position(),
                definition.annotations());
    }

    /**
     * Every {@link TypeRef} one body holds, mapped -- <b>the only place that knows what each body shape
     * carries</b>, so a shape added to {@code schema.meta} needs remembering here and nowhere else.
     */
    static Top mapBodyRefs(Top body, UnaryOperator<TypeRef> map) {
        return mapBodyRefs(body, map, true);
    }

    private static Top mapBodyRefs(Top body, UnaryOperator<TypeRef> map, boolean mapRecordSupertypes) {
        return switch (body) {
            // `discriminators` is carried, not mapped: it holds field *names* of this record, which no
            // reference rewrite touches. Dropping it here would lose a family's selector list on every
            // synthetic rename and every materialisation rewrite -- a body that arrived sealed would come
            // back with nothing to dispatch on.
            case RecordBody record -> new RecordBody(mapRecordSupertypes
                    ? record.supertypes().stream().map(map).toList() : record.supertypes(),
                    record.fields().stream().map(field -> field.withType(map.apply(field.type()))).toList(),
                    record.groups(), record.extension(), record.discriminators());
            case ArrayBody array -> new ArrayBody(map.apply(array.elementType()), array.state(),
                    array.unordered(), array.uniqueItems(), array.minItems(), array.maxItems());
            case MapBody mapBody -> new MapBody(map.apply(mapBody.keyType()), map.apply(mapBody.valueType()),
                    mapBody.state(), mapBody.minItems(), mapBody.maxItems());
            case TupleBody tuple -> new TupleBody(tuple.elements().stream()
                    .map(element -> new TupleElement(map.apply(element.elementType()), element.state())).toList());
            case ChoiceBody choice -> new ChoiceBody(choice.variants().stream().map(map).toList());
            // An alias's target maps like any other reference, arguments and all -- which is what lets a
            // closed alias follow its own `source` onto the entry materialisation minted for it, and a
            // partial application keep the arguments it binds.
            case Reference reference -> new Reference(map.apply(reference.target()));
            // A held body maps nothing: its references are tokens that have not been resolved against
            // anything yet, and rewriting one would be rewriting a name whose meaning is not settled until
            // substitution supplies the arguments.
            default -> body; // an atom body holds no type references
        };
    }
}
