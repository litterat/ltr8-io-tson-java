package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Annotations;

import java.util.List;
import java.util.Objects;

/**
 * A reference, parsed: the value of the kernel's {@code reference} atom ([TSON-SCHEMA] §4.2) wherever a slot holds
 * one, and the host shape every reference-typed slot delivers into. {@code name} is the head, segments joined by
 * {@code .}; {@code arguments}, when present, is the argument list, so {@code result<T>} is
 * {@code TypeRef("result", [Ref(T)])} (SPEC-FEEDBACK.md #12).
 *
 * <p><b>The text is the value.</b> A reference is read from a token by {@code io.ltr8.tson.atom.ReferenceGrammar} and
 * written as {@link #text}: no whitespace, arguments separated by one space, so {@code map<text integer>}; one without
 * arguments is one unquoted token, and one with arguments is written quoted wherever it stands in data
 * ({@code target: "result<T>"}), {@code <} being reserved there. Identity is the parsed form and never the spelling,
 * so {@code result< T >} and {@code result<T>} are one reference.
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
 * from {@link #equals}: it is metadata about where a reference came from, and §8.2 keys identity on where it points.
 */
public record TypeRef(String name, List<TypeArgument> arguments, Annotations annotations) {

    public TypeRef {
        Objects.requireNonNull(name, "name");
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        annotations = annotations == null ? Annotations.empty() : annotations;
    }

    /** Same as the canonical constructor with no annotations -- every caller that has none to carry. */
    public TypeRef(String name, List<TypeArgument> arguments) {
        this(name, arguments, Annotations.empty());
    }

    /** A reference with no arguments, e.g. a plain field type like {@code integer}. */
    public static TypeRef of(String name) {
        return new TypeRef(name, null);
    }

    /** A copy of this reference with {@code annotations} replaced -- every other component unchanged. */
    public TypeRef withAnnotations(Annotations annotations) {
        return new TypeRef(name, arguments, annotations);
    }

    /**
     * The canonical text: the head, then, where there are arguments, {@code <}, each argument's {@link
     * TypeArgument#text} separated by one space, and {@code >}. What resolved output writes, quoted where it carries
     * arguments.
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

    /** Excludes {@code annotations} -- metadata does not change a reference's identity, as on {@code RecordField}. */
    @Override
    public boolean equals(Object o) {
        return o instanceof TypeRef other
                && Objects.equals(name, other.name)
                && Objects.equals(arguments, other.arguments);
    }

    /** Excludes {@code annotations} -- see {@link #equals}. */
    @Override
    public int hashCode() {
        return Objects.hash(name, arguments);
    }
}
