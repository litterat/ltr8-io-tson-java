package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.DeclaredType;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.NamespaceBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.ReferenceBody;

import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Type;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.SiblingRef;

import java.util.function.UnaryOperator;

/**
 * The {@code schema.meta} reference walk: every {@link TypeRef} an entry holds that a rewrite may move.
 *
 * <p><b>One walk, two callers.</b> {@code TemplateMaterialiser} closes an application through it, and {@code
 * SyntheticMerge} renames onto a merged entry (§8.2). Which body shape carries which references is a fact about the
 * value model, not about either caller, so it is stated once here.
 *
 * <p><b>The switch over {@link Top} is exhaustive, with no default.</b> {@code Top} is sealed all the way down, so a
 * body shape added to {@code schema.meta} fails to compile here until someone says which references it holds; a
 * default arm would have skipped its references silently. The one grouped arm is {@link Atom} after {@link
 * ReferenceBody}: an atom's facets are values, and the reference is the one atom that names an entry.
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

    /** A field's type with {@code map} applied; a sibling names a field, which no rewrite of type names touches. */
    private static Reference mapField(Reference type, UnaryOperator<TypeRef> map) {
        return switch (type) {
            case TypeRef reference -> map.apply(reference);
            case SiblingRef sibling -> sibling;
        };
    }

    private static Top mapBodyRefs(Top body, UnaryOperator<TypeRef> map, boolean mapChain) {
        Top mapped = switch (body) {
            // `discriminators` is carried, not mapped: it holds field *names* of this record, which no
            // reference rewrite touches. Dropping it here would lose a family's selector list on every
            // synthetic rename and every materialisation rewrite -- a body that arrived sealed would come
            // back with nothing to dispatch on. An open record maps the field types that name none of its
            // parameters, which mean the same thing in every application and so are closed already: the base
            // names the entry each member names. A type naming a parameter at any depth waits for substitution to
            // supply the argument. Its chain maps below, an open record being IS-A its parents as any record is.
            case RecordBody record -> record.withFields(record.mapFields(field ->
                    OpenEntry.parameterIn(field.type()).isPresent() ? field
                            : field.withType(mapField(field.type(), map))));
            case ArrayBody array -> new ArrayBody(array.chain(), map.apply(array.elementName()),
                    array.voidable(), array.ordered(), array.uniqueItems(), array.minItems(), array.maxItems());
            case MapBody mapBody -> new MapBody(mapBody.chain(),
                    map.apply(mapBody.keyType()), map.apply(mapBody.valueType()),
                    mapBody.voidable(), mapBody.ordered(), mapBody.minItems(), mapBody.maxItems());
            case NamespaceBody namespace -> new NamespaceBody(namespace.chain(),
                    map.apply(namespace.keyType()), map.apply(namespace.valueType()),
                    namespace.ordered(), namespace.minItems(), namespace.maxItems());
            case TupleBody tuple -> new TupleBody(tuple.chain(), tuple.elements().stream()
                    .map(element -> new TupleElement(map.apply(element.elementType()), element.voidable())).toList());
            case ChoiceBody choice -> new ChoiceBody(choice.chain(),
                    choice.variants().stream().map(map).toList(), choice.disjoint());
            // An alias's target maps like any other reference, arguments and all -- which is what lets a
            // closed alias follow its target onto the entry materialisation minted for it.
            case Alias alias -> new Alias(map.apply(alias.target()));
            // `of` is a type reference like any other, arguments and all: `!meta.reference_type { of: box<text> }`
            // closes its application here as `meta.ref<box<text>>` closes its argument before substituting it,
            // so either way `of` names the entry the application denotes, which the merge renames like any other
            // reference (§4.2). The index is carried, derived at link.
            case ReferenceBody reference -> new ReferenceBody(reference.chain(), map.apply(reference.of()),
                    reference.allowSibling(), reference.index());
            // Every other atom's facets are values, never references; a declared type holds its chain alone.
            case Atom atom -> atom;
            case DeclaredType declared -> declared;

            // A value is data: a name it holds is judged at link against the closure (`ValuesByName`), and
            // data writes no sugar form, so none names a synthetic a rewrite here could move.
            case ValueEntry value -> value;
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
