package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Annotations;

import java.util.List;
import java.util.Optional;

/**
 * A type reference, what the kernel's {@code reference} atom reads to: the object every reference-typed position
 * delivers, in a tree read and a bound one alike ([TSON-SCHEMA] §4.2). It is a name, a {@link NameRef}; inside an open
 * record it may also be a {@link ParamRef}, one of the record's parameters, which only the record itself makes. {@code
 * name} is the qualified name, its {@link #segments} joined by {@code .}.
 *
 * <p><b>A reference holds a name and nothing else.</b> An application, {@code box<T>}, is no reference: it is the
 * construction {@link ApplyBody} written in place, and an argument list is no part of a value ([TSON-DATA] §7.2.5).
 *
 * <p><b>{@link #referent} is what the reference names</b> in the closure it was read under: the value of the entry its
 * name names. A reference read where a closure is known carries it ({@link #closure}), attached by the kind's parser
 * from the reference type's linked index ({@code ReferenceBody.closure}). One read in data at a position naming values
 * ({@code meta.ref<order>}) names the value its name reached in the governing schema ({@code ReferenceBody.Values}).
 * One built by hand, or read inside a schema document being resolved, carries {@link Closure#NONE}, and names nothing.
 *
 * <p><b>{@code annotations} carries the annotations written on the reference itself</b>: [TSON-SCHEMA] §8.3 attaches
 * {@code @alias:name} to the type value when a use site is flattened past an alias entry, so
 * {@code type: @alias:meta.field_name token} records both where the reference points and what the author wrote.
 * <b>Identity</b> is the variant and the name: {@code annotations} and {@code closure} are excluded from {@code
 * equals}, as both are about where a reference came from, and §8.2 keys identity on where it points.
 */
public sealed interface TypeRef extends TypeExpr permits NameRef, ParamRef {

    /** Where a reference finds what it names: the closure it was read under. */
    @FunctionalInterface
    interface Closure {

        /** No closure: nothing is named. */
        Closure NONE = reference -> Optional.empty();

        /** What {@code reference} names here, or empty where it names nothing this closure holds. */
        Optional<Object> referentOf(TypeRef reference);
    }

    /** The annotations written on the reference itself; no part of its identity. */
    Annotations annotations();

    /** The qualified name. */
    String name();

    /** The closure the reference was read under, {@link Closure#NONE} where none is known; no part of its identity. */
    Closure closure();

    /** A copy of this reference with {@code annotations} replaced -- every other component unchanged. */
    TypeRef withAnnotations(Annotations annotations);

    /** A copy of this reference that finds what it names in {@code closure}. */
    TypeRef withClosure(Closure closure);

    /** The name {@code name}, unannotated and naming nothing. */
    static TypeRef of(String name) {
        return new NameRef(name, Annotations.empty(), Closure.NONE);
    }

    /** The name {@code name}, read under {@code closure}. */
    static TypeRef of(String name, Annotations annotations, Closure closure) {
        return new NameRef(name, annotations, closure);
    }

    /** The qualified name's segments: {@code geo.address} is {@code [geo, address]}. */
    default List<String> segments() {
        return List.of(name().split("\\.", -1));
    }

    /** What this reference names in the closure it was read under, or empty where it carries none. */
    default Optional<Object> referent() {
        return closure().referentOf(this);
    }

    /** The canonical text: the name. What resolved output writes, unquoted. */
    @Override
    default String text() {
        return name();
    }
}
