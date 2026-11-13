package io.ltr8.tson.schema.meta;

import java.util.function.Consumer;

/**
 * The walk over every {@link TypeRef} a value of this model holds, at any depth of types written in place: the one
 * place that says which body shape carries which references.
 *
 * <p><b>A rewrite takes a name and gives a name back</b> ({@link Rewrite#atName}), at a type slot ({@link TypeExpr}: an
 * element, a map's value, a tuple position, a choice variant, a record field's type) and at a slot that holds a name
 * alone (a map's key, a reference's {@code of}, an alias's target, an application's head and arguments) alike. A type
 * written in place is walked into, each slot inside it rewritten the same way, and may come back as another type in
 * its stead ({@link Rewrite#atInPlace}). A chain is never walked: its parents are an open record's to substitute, and
 * its supertypes are names linking computes.
 *
 * <p><b>The switch over {@link Top} is exhaustive, with no default.</b> {@code Top} is sealed all the way down, so a
 * body shape added to the model fails to compile here until someone says which references it holds; a default arm
 * would skip them silently.
 */
public final class TypeSlots {

    private TypeSlots() {
    }

    /** What a walk does with each reference it meets, by the kind of slot holding it. */
    public interface Rewrite {

        /** A reference; a name comes back. */
        TypeRef atName(TypeRef reference);

        /** The body of a type written in place, met before its own slots are walked; the walk goes on in what returns. */
        default Type inPlace(Type body) {
            return body;
        }

        /**
         * A type written in place, met after its slots are walked, which may come back as another type in its stead:
         * how an application written in place closes where it stands.
         */
        default TypeExpr atInPlace(TypeInline inline) {
            return inline;
        }
    }

    /** What a type slot holds with {@code rewrite} applied: a name rewritten, a type written in place walked into. */
    public static TypeExpr map(TypeExpr type, Rewrite rewrite) {
        return switch (type) {
            case TypeRef reference -> rewrite.atName(reference);
            case TypeInline inline -> rewrite.atInPlace(inline.withOf((Type) mapBody(rewrite.inPlace(inline.of()),
                    rewrite)));
        };
    }

    /** Every reference {@code type} holds, at any depth of types written in place, each visited once, in order. */
    public static void forEach(TypeExpr type, Consumer<TypeRef> visit) {
        map(type, visitor(visit));
    }

    /** Every reference {@code body}'s slots hold, its chain left out; {@link #forEach} over each. */
    public static void forEachIn(Top body, Consumer<TypeRef> visit) {
        mapBody(body, visitor(visit));
    }

    /** Every application written in place in {@code type}, at any depth of types written in place. */
    public static void applications(TypeExpr type, Consumer<ApplyBody> visit) {
        map(type, new Rewrite() {
            @Override
            public TypeRef atName(TypeRef reference) {
                return reference;
            }

            @Override
            public Type inPlace(Type body) {
                if (body instanceof ApplyBody apply) {
                    visit.accept(apply);
                }
                return body;
            }
        });
    }

    private static Rewrite visitor(Consumer<TypeRef> visit) {
        return reference -> {
            visit.accept(reference);
            return reference;
        };
    }

    /** {@code body} with every reference its slots hold rewritten, its chain exactly as written. */
    public static Top mapBody(Top body, Rewrite rewrite) {
        return switch (body) {
            // `discriminators` is carried: it holds field names of this record, which no reference rewrite touches.
            case RecordBody record -> record.withFields(record.mapFields(field ->
                    field.withType(map(field.type(), rewrite))));
            case ArrayBody array -> new ArrayBody(array.chain(), map(array.elementType(), rewrite),
                    array.voidable(), array.ordered(), array.uniqueItems(), array.minItems(), array.maxItems());
            case MapBody map -> new MapBody(map.chain(), rewrite.atName(map.keyType()),
                    map(map.valueType(), rewrite), map.voidable(), map.ordered(), map.minItems(), map.maxItems());
            case NamespaceBody namespace -> new NamespaceBody(namespace.chain(),
                    rewrite.atName(namespace.keyType()), map(namespace.valueType(), rewrite),
                    namespace.ordered(), namespace.minItems(), namespace.maxItems());
            case TupleBody tuple -> new TupleBody(tuple.chain(), tuple.elements().stream()
                    .map(element -> new TupleElement(map(element.elementType(), rewrite), element.voidable()))
                    .toList());
            case ChoiceBody choice -> new ChoiceBody(choice.chain(),
                    choice.variants().stream().map(variant -> map(variant, rewrite)).toList(), choice.disjoint());
            case Alias alias -> new Alias(rewrite.atName(alias.target()));
            // `of` holds a name ([TSON-SCHEMA] §4.2); the index is carried, derived at link.
            case RefBody reference -> new RefBody(reference.chain(), rewrite.atName(reference.of()),
                    reference.index());
            // Every other atom's facets are values, never references; a declared type holds its chain alone.
            case Atom atom -> atom;
            case DeclaredType declared -> declared;
            // `on` names a field of the enclosing record, which no rewrite of type names touches.
            case DependentBody dependent -> dependent;
            case ApplyBody apply -> new ApplyBody(apply.chain(), rewrite.atName(apply.head()),
                    apply.arguments().stream().map(rewrite::atName).toList());
            // A value is data: a name it holds is judged at link against the closure.
            case ValueEntry value -> value;
        };
    }
}
