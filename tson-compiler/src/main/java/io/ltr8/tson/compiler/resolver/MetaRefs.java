package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.DeclaredType;
import io.ltr8.tson.schema.meta.DependentBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.NamespaceBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.ReferenceBody;

import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Type;
import io.ltr8.tson.schema.meta.ValueEntry;


/**
 * The {@code schema.meta} reference walk: every {@link TypeRef} an entry holds that a rewrite may move.
 *
 * <p><b>The walk is the value model's.</b> {@code TemplateMaterialiser} closes an application through it; which body
 * shape carries which references is a fact about the value model, not about the caller, so it is stated here.
 *
 * <p><b>Two kinds of slot.</b> A type slot ({@link TypeExpr}: an element, a map's value, a tuple position, a choice
 * variant, a record field's type) holds a name or a type written in place, so a rewrite there may put a construction
 * in the name's stead ({@link Rewrite#atSlot}); a slot that holds a name alone (a map's key, a reference's {@code of},
 * an alias's target, a parent) takes a name back ({@link Rewrite#atName}). A type written in place is walked into,
 * each slot inside it rewritten the same way, and stays in place under its constructor.
 *
 * <p><b>The switch over {@link Top} is exhaustive, with no default.</b> {@code Top} is sealed all the way down, so a
 * body shape added to {@code schema.meta} fails to compile here until someone says which references it holds; a
 * default arm would have skipped its references silently. The one grouped arm is {@link Atom} after {@link
 * ReferenceBody}: an atom's facets are values, and the reference is the one atom that names an entry.
 */
final class MetaRefs {

    private MetaRefs() {
    }

    /** What a walk does with each reference it meets, by the kind of slot holding it. */
    interface Rewrite {

        /** A reference at a slot that holds a name alone; a name comes back. */
        TypeRef atName(TypeRef reference);

        /** A reference at a type slot, which may take a type written in place in the name's stead. */
        default TypeExpr atSlot(TypeRef reference) {
            return atName(reference);
        }

    }

    /**
     * Every {@link TypeRef} an entry's value holds, mapped, its chain left exactly as written: a parent an open record
     * writes is substituted when the record closes, and {@code SchemaResolver} folds a closed declaration's applied
     * parents into edges ({@code TemplateMaterialiser}, the caller).
     */
    static Top mapRefs(Top definition, Rewrite map) {
        return mapBodyRefs(definition, map);
    }

    /** What a type slot holds with {@code map} applied: a name rewritten, a type written in place walked into. */
    private static TypeExpr mapSlot(TypeExpr type, Rewrite map) {
        return switch (type) {
            case TypeRef reference -> map.atSlot(reference);
            case TypeInline inline -> mapInline(inline, map);
        };
    }

    /** A type written in place with each slot inside it mapped; it stays in place, its constructor and no chain. */
    private static TypeInline mapInline(TypeInline inline, Rewrite map) {
        return inline.withOf((Type) mapBodyRefs(inline.of(), map));
    }

    private static Top mapBodyRefs(Top body, Rewrite map) {
        return switch (body) {
            // `discriminators` is carried, not mapped: it holds field *names* of this record, which no
            // reference rewrite touches; dropping it would leave a sealed body nothing to dispatch on. An open
            // record maps the field types that name none of its parameters, which mean the same thing in every
            // application and so are closed already: the base names the entry each member names. A type naming a
            // parameter at any depth waits for substitution to supply the argument.
            case RecordBody record -> record.withFields(record.mapFields(field ->
                    OpenEntry.parameterIn(field.type()).isPresent() ? field
                            : field.withType(mapSlot(field.type(), map))));
            case ArrayBody array -> new ArrayBody(array.chain(), mapSlot(array.elementType(), map),
                    array.voidable(), array.ordered(), array.uniqueItems(), array.minItems(), array.maxItems());
            case MapBody mapBody -> new MapBody(mapBody.chain(),
                    map.atName(mapBody.keyType()), mapSlot(mapBody.valueType(), map),
                    mapBody.voidable(), mapBody.ordered(), mapBody.minItems(), mapBody.maxItems());
            case NamespaceBody namespace -> new NamespaceBody(namespace.chain(),
                    map.atName(namespace.keyType()), mapSlot(namespace.valueType(), map),
                    namespace.ordered(), namespace.minItems(), namespace.maxItems());
            case TupleBody tuple -> new TupleBody(tuple.chain(), tuple.elements().stream()
                    .map(element -> new TupleElement(mapSlot(element.elementType(), map), element.voidable()))
                    .toList());
            case ChoiceBody choice -> new ChoiceBody(choice.chain(),
                    choice.variants().stream().map(variant -> mapSlot(variant, map)).toList(), choice.disjoint());
            // An alias's target is a name, and maps as one: an application there is refused, to be declared.
            case Alias alias -> new Alias(map.atName(alias.target()));
            // `of` holds a name (§4.2), and maps as one: an application there is refused, to be declared. The index
            // is carried, derived at link.
            case ReferenceBody reference -> new ReferenceBody(reference.chain(), map.atName(reference.of()),
                    reference.index());
            // Every other atom's facets are values, never references; a declared type holds its chain alone.
            case Atom atom -> atom;
            case DeclaredType declared -> declared;
            // `on` names a field of the enclosing record, which no rewrite of type names touches.
            case DependentBody dependent -> dependent;

            // A value is data: a name it holds is judged at link against the closure (`ValuesByName`), and
            // data writes no sugar form, so none names an entry a rewrite here could move.
            case ValueEntry value -> value;
        };
    }
}
