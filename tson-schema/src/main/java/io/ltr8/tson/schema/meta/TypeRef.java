package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Annotations;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A reference: the value of the kernel's {@code reference} atom ([TSON-SCHEMA] §4.2), the object every
 * reference-typed position delivers, in a tree read and a bound one alike. {@code name} is the qualified name, its
 * {@link #segments} joined by {@code .}; {@code arguments}, when present, is the argument list, so {@code result<T>}
 * is {@code TypeRef("result", [Ref(T)])} (SPEC-FEEDBACK.md #12).
 *
 * <p><b>A reference is data grammar</b> ([TSON-DATA] §7.4): {@code geo.address<T>} is a qualified name, an adjacent
 * {@code <}, arguments and {@code >}, written unquoted wherever a value stands, and a reference without arguments is
 * one token. {@link #text} recreates the canonical text: no whitespace, one space between arguments, so {@code
 * map<text integer>}. Identity is the parsed form and never the spelling, so {@code result< T >} and {@code
 * result<T>} are one reference.
 *
 * <p><b>{@link #referent} is what the reference names</b> in the closure it was read under: the value of the entry a
 * name or an application denotes, an application resolved level by level by structural identity, so in {@code
 * foo<bar<text>>} the argument names the entry {@code bar<text>} closed to and the whole the entry {@code foo}'s
 * application closed to. Its arguments are references in turn. A reference read where a closure is known carries
 * it ({@link #closure}), attached by the kind's parser from the reference type's linked index ({@code
 * ReferenceBody.closure}); one built by hand, read inside a schema document being resolved, or read at a position
 * naming values carries {@link Closure#NONE}, and names nothing.
 *
 * <p>An argument-carrying reference survives only where an application is still open: a record template's
 * {@code chain.parents} and field types, held text, and the {@code @application} annotation. A closed application
 * lifts to an entry and is named bare where it stands (§5.3).
 *
 * <p>{@code arguments} normalises {@code null} to empty: absent and empty are one state, and there is no spelling of
 * an empty argument list.
 *
 * <p><b>{@code annotations} carries the annotations written on the reference itself</b>: [TSON-SCHEMA] §8.3 attaches
 * {@code @alias:name} to the type value when a use site is flattened past an alias entry, so
 * {@code type: @alias:field_name token} records both where the reference points and what the author wrote. Excluded
 * from {@link #equals}, as {@code closure} is: both are about where a reference came from, and §8.2 keys identity on
 * where it points.
 */
public record TypeRef(String name, List<TypeArgument> arguments, Annotations annotations, Closure closure) {

    /** Where a reference finds what it names: the closure it was read under. */
    @FunctionalInterface
    public interface Closure {

        /** No closure: nothing is named. */
        Closure NONE = reference -> Optional.empty();

        /** What {@code reference} names here, or empty where it names nothing this closure holds. */
        Optional<Object> referentOf(TypeRef reference);
    }

    public TypeRef {
        Objects.requireNonNull(name, "name");
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        annotations = annotations == null ? Annotations.empty() : annotations;
        closure = closure == null ? Closure.NONE : closure;
    }

    /** A reference with annotations and no closure. */
    public TypeRef(String name, List<TypeArgument> arguments, Annotations annotations) {
        this(name, arguments, annotations, Closure.NONE);
    }

    /** Same as the canonical constructor with no annotations and no closure -- every caller that has neither. */
    public TypeRef(String name, List<TypeArgument> arguments) {
        this(name, arguments, Annotations.empty(), Closure.NONE);
    }

    /** A reference with no arguments, e.g. a plain field type like {@code integer}. */
    public static TypeRef of(String name) {
        return new TypeRef(name, null);
    }

    /** A copy of this reference with {@code annotations} replaced -- every other component unchanged. */
    public TypeRef withAnnotations(Annotations annotations) {
        return new TypeRef(name, arguments, annotations, closure);
    }

    /** A copy of this reference that finds what it names in {@code closure}, each reference argument as well. */
    public TypeRef withClosure(Closure closure) {
        List<TypeArgument> carried = arguments.stream().map(argument -> argument instanceof TypeArgument.Ref ref
                ? (TypeArgument) new TypeArgument.Ref(ref.ref().withClosure(closure)) : argument).toList();
        return new TypeRef(name, carried, annotations, closure);
    }

    /** The qualified name's segments: {@code geo.address} is {@code [geo, address]}. */
    public List<String> segments() {
        return List.of(name.split("\\.", -1));
    }

    /** What this reference names in the closure it was read under, or empty where it carries none. */
    public Optional<Object> referent() {
        return closure.referentOf(this);
    }

    /**
     * The canonical text: the head, then, where there are arguments, {@code <}, each argument's {@link
     * TypeArgument#text} separated by one space, and {@code >}. What resolved output writes, unquoted.
     */
    public String text() {
        if (arguments.isEmpty()) {
            return name;
        }
        StringBuilder out = new StringBuilder(name).append('<');
        for (int i = 0; i < arguments.size(); i++) {
            out.append(i == 0 ? "" : " ").append(arguments.get(i).text());
        }
        return out.append('>').toString();
    }

    /**
     * {@code text} as a quoted token inside a reference's argument list: {@code "} and {@code \} escaped, and a
     * control character as {@code \}{@code uXXXX}, so the argument reads back as the one token it is.
     */
    static String quoted(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format("\\u%04X", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Excludes {@code annotations} and {@code closure} -- neither changes a reference's identity. */
    @Override
    public boolean equals(Object o) {
        return o instanceof TypeRef other
                && Objects.equals(name, other.name)
                && Objects.equals(arguments, other.arguments);
    }

    /** Excludes {@code annotations} and {@code closure} -- see {@link #equals}. */
    @Override
    public int hashCode() {
        return Objects.hash(name, arguments);
    }

    /** The name, arguments and annotations, as the record would write them; the closure is no part of the value. */
    @Override
    public String toString() {
        return "TypeRef[name=" + name + ", arguments=" + arguments + ", annotations=" + annotations + "]";
    }
}
