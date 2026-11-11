package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Annotations;

import java.util.List;
import java.util.Optional;

/**
 * A type reference: a {@link Reference} that names a type, the object every reference-typed position but a record
 * field's type delivers, in a tree read and a bound one alike ([TSON-SCHEMA] §4.2). It is a {@link NameRef} or an
 * {@link ApplicationRef}, and {@link #of(String, List, Annotations, Closure)} decides which; inside an open record it
 * may also be a {@link ParamRef}, one of the record's parameters, which only the record itself makes. {@code name} is
 * the qualified name, its {@link #segments} joined by {@code .}; {@code arguments} is the argument list, empty for a
 * name, so
 * {@code result<T>} is the application {@code result} with arguments {@code [T]} ([TSON-SCHEMA] §4.2). An argument is
 * itself a reference, a name or an application, and never a literal token ([TSON-SCHEMA] §5.10).
 *
 * <p><b>A reference is data grammar</b> ([TSON-DATA] §7.4): {@code geo.address<T>} is a qualified name, an adjacent
 * {@code <}, arguments and {@code >}, written unquoted wherever a value stands, and a reference without arguments is
 * one token. {@link #text} recreates the canonical text: no whitespace, one space between arguments, so {@code
 * map<text integer>}. Identity is the parsed form and never the spelling, so {@code result< T >} and {@code
 * result<T>} are one reference.
 *
 * <p><b>{@link #referent} is what the reference names</b> in the closure it was read under: the value of the entry its
 * name names. Data holds a reference as a name ([TSON-SCHEMA] §4.2), so an application names nothing there. A
 * reference read where a closure is known carries it ({@link #closure}), attached by the kind's parser from the
 * reference type's linked index ({@code ReferenceBody.closure}). One read in data at a position naming values
 * ({@code meta.ref<order>}) names the value its name reached in the governing schema ({@code ReferenceBody.Values}).
 * One built by hand, or read inside a schema document being resolved, carries {@link Closure#NONE}, and names nothing.
 *
 * <p>An argument-carrying reference survives only where an application is still open: an open record's
 * {@code chain.parents} and field types, and the {@code @meta.application} annotation. A closed application is
 * the record or construction it denotes, under a declaration's name or held in place at a type slot
 * ({@link TypeInline}).
 *
 * <p><b>{@code annotations} carries the annotations written on the reference itself</b>: [TSON-SCHEMA] §8.3 attaches
 * {@code @alias:name} to the type value when a use site is flattened past an alias entry, so
 * {@code type: @alias:meta.field_name token} records both where the reference points and what the author wrote.
 * <b>Identity</b> is the variant, the name and the arguments: {@code annotations} and {@code closure} are excluded
 * from {@code equals}, as both are about where a reference came from, and §8.2 keys identity on where it points.
 */
public sealed interface TypeRef extends Reference, TypeExpr permits NameRef, ApplicationRef, ParamRef {

    /** Where a reference finds what it names: the closure it was read under. */
    @FunctionalInterface
    interface Closure {

        /** No closure: nothing is named. */
        Closure NONE = reference -> Optional.empty();

        /** What {@code reference} names here, or empty where it names nothing this closure holds. */
        Optional<Object> referentOf(TypeRef reference);
    }

    /** The qualified name. */
    String name();

    /** The argument list: empty for a {@link NameRef}, never empty for an {@link ApplicationRef}. */
    List<TypeRef> arguments();

    /** The closure the reference was read under, {@link Closure#NONE} where none is known; no part of its identity. */
    Closure closure();

    @Override
    TypeRef withAnnotations(Annotations annotations);

    /** A copy of this reference that finds what it names in {@code closure}, each reference argument as well. */
    TypeRef withClosure(Closure closure);

    /** A reference with no arguments, e.g. a plain field type like {@code integer}. */
    static TypeRef of(String name) {
        return new NameRef(name, Annotations.empty(), Closure.NONE);
    }

    /**
     * The reference {@code name} with {@code arguments}, read under {@code closure}: a {@link NameRef} where {@code
     * arguments} is empty, an {@link ApplicationRef} otherwise. {@code null} arguments are no arguments: absent and
     * empty are one state, and there is no spelling of an empty argument list.
     */
    static TypeRef of(String name, List<TypeRef> arguments, Annotations annotations, Closure closure) {
        return arguments == null || arguments.isEmpty()
                ? new NameRef(name, annotations, closure)
                : new ApplicationRef(name, arguments, annotations, closure);
    }

    /** The reference {@code name} with {@code arguments}, naming nothing. */
    static TypeRef of(String name, List<TypeRef> arguments, Annotations annotations) {
        return of(name, arguments, annotations, Closure.NONE);
    }

    /** The reference {@code name} with {@code arguments}, unannotated and naming nothing. */
    static TypeRef of(String name, List<TypeRef> arguments) {
        return of(name, arguments, Annotations.empty(), Closure.NONE);
    }

    /** The qualified name's segments: {@code geo.address} is {@code [geo, address]}. */
    default List<String> segments() {
        return List.of(name().split("\\.", -1));
    }

    /** What this reference names in the closure it was read under, or empty where it carries none. */
    default Optional<Object> referent() {
        return closure().referentOf(this);
    }

    /**
     * The canonical text: the head, then, where there are arguments, {@code <}, each argument's {@link
     * #text} separated by one space, and {@code >}. What resolved output writes, unquoted.
     */
    @Override
    default String text() {
        List<TypeRef> arguments = arguments();
        if (arguments.isEmpty()) {
            return name();
        }
        StringBuilder out = new StringBuilder(name()).append('<');
        for (int i = 0; i < arguments.size(); i++) {
            out.append(i == 0 ? "" : " ").append(arguments.get(i).text());
        }
        return out.append('>').toString();
    }
}
