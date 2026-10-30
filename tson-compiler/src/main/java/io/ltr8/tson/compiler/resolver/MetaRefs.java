package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Type;

import java.util.function.UnaryOperator;

/**
 * The {@code schema.meta} reference walk: every {@link TypeRef} an entry holds, rewritten or merely visited.
 *
 * <p><b>One walk, three callers, and none of them is about templates.</b> {@code TemplateMaterialiser} closes
 * an application through it,
 * {@code SyntheticMerge} renames onto a merged entry (§8.2), and {@code TemplateRegularity} uses it as a
 * visitor by returning each reference unchanged. Which body shape carries which references is a fact about
 * the value model, not about any of those three, so it is stated once here.
 *
 * <p><b>Visiting is rewriting with the identity function</b>, deliberately: a reader that walked bodies
 * separately would be a second list of shapes to keep in step, and the one that fell behind would silently
 * skip a reference rather than fail.
 */
final class MetaRefs {

    private MetaRefs() {
    }

    /**
     * Every {@link TypeRef} an entry's value holds, mapped -- its chain included ({@link TypeChain}): the parents
     * and the names of the supertypes and subtypes, which a rename reaches like any other reference.
     */
    static Top mapRefs(Top definition, UnaryOperator<TypeRef> map) {
        return mapBodyRefs(definition, map, true);
    }

    /**
     * The same, leaving the chain exactly as written.
     *
     * <p>{@code TemplateMaterialiser} is the caller, and a composition operand is why: an application there
     * denotes no entry -- its fields are absorbed by value -- so {@code parents} keeps it as written until {@code
     * SchemaResolver} folds the chain, and closing it would mint the entry that path exists to avoid.
     *
     * <p><b>Skipping the call is the point, not discarding its result.</b> Closing an application publishes
     * the entry as a side effect, so a walk that maps this channel has already minted whatever the caller
     * then does with the rewritten reference.
     */
    static Top mapRefsKeepingChain(Top definition, UnaryOperator<TypeRef> map) {
        return mapBodyRefs(definition, map, false);
    }

    /**
     * Every {@link TypeRef} one body holds, mapped -- <b>the only place that knows what each body shape
     * carries</b>, so a shape added to {@code schema.meta} needs remembering here and nowhere else.
     */
    static Top mapBodyRefs(Top body, UnaryOperator<TypeRef> map) {
        return mapBodyRefs(body, map, true);
    }

    private static Top mapBodyRefs(Top body, UnaryOperator<TypeRef> map, boolean mapChain) {
        Top mapped = switch (body) {
            // An open record maps the field types that name none of its parameters, which mean the same thing in
            // every application and so are closed already: the base names the entry each member names. A type
            // naming a parameter, at any depth, waits for substitution to supply the argument. Its chain maps
            // below, a record template being IS-A its parents as any record is.
            case RecordBody record when OpenEntry.isOpen(record) -> record.withFields(record.mapFields(field ->
                    OpenEntry.parameterIn(field.type(), record.parameterNames()).isPresent()
                            ? field : field.withType(map.apply(field.type()))));
            // `discriminators` is carried, not mapped: it holds field *names* of this record, which no
            // reference rewrite touches. Dropping it here would lose a family's selector list on every
            // synthetic rename and every materialisation rewrite -- a body that arrived sealed would come
            // back with nothing to dispatch on.
            case RecordBody record -> record.withFields(record.mapFields(field -> field.withType(map.apply(field.type()))));
            case ArrayBody array -> new ArrayBody(array.chain(), map.apply(array.elementType()),
                    array.voidable(), array.ordered(), array.uniqueItems(), array.minItems(), array.maxItems());
            case MapBody mapBody -> new MapBody(mapBody.chain(),
                    map.apply(mapBody.keyType()), map.apply(mapBody.valueType()),
                    mapBody.voidable(), mapBody.ordered(), mapBody.minItems(), mapBody.maxItems());
            case TupleBody tuple -> new TupleBody(tuple.chain(), tuple.elements().stream()
                    .map(element -> new TupleElement(map.apply(element.elementType()), element.voidable())).toList());
            case ChoiceBody choice -> new ChoiceBody(choice.chain(),
                    choice.variants().stream().map(map).toList(), choice.disjoint());
            // An alias's target maps like any other reference, arguments and all -- which is what lets a
            // closed alias follow its target onto the entry materialisation minted for it, and a partial
            // application keep the arguments it binds.
            case Alias alias -> new Alias(map.apply(alias.target()));
            // A held body maps nothing: its references are tokens that have not been resolved against
            // anything yet, and rewriting one would be rewriting a name whose meaning is not settled until
            // substitution supplies the arguments.
            // A kind holds none either: `of` names a constructor, which no rewrite of this schema's entries
            // touches, and its members are derived at link, after every rewrite.
            default -> body; // an atom body holds no type references
        };
        return mapChain && mapped instanceof Type type
                ? TypeChain.withChain(mapped, mapChain(type.chain(), map)) : mapped;
    }

    /** Every reference {@code chain} holds, mapped -- a name in it as the bare reference it is. */
    private static TypeChain mapChain(TypeChain chain, UnaryOperator<TypeRef> map) {
        UnaryOperator<String> name = written -> map.apply(TypeRef.of(written)).name();
        return new TypeChain(chain.parents().stream().map(map).toList(),
                chain.supertypes().stream().map(name).toList(), chain.subtypes().stream().map(name).toList());
    }
}
